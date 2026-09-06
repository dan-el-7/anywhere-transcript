package com.anywhere.transcript.data

import android.content.Context
import com.anywhere.transcript.engine.QnnWhisperEngine
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

enum class ModelStatus { NOT_DOWNLOADED, DOWNLOADING, DOWNLOADED, FAILED }

/** HTTP status error, distinguishable from transport-level IOExceptions. */
private class HttpError(val code: Int) : IOException("HTTP $code")

data class ModelDownloadState(
    val modelId: String,
    val status: ModelStatus = ModelStatus.NOT_DOWNLOADED,
    val downloadedBytes: Long = 0,
    val totalBytes: Long = 0,
    val error: String? = null,
)

/** Downloads/keeps/deletes ggml models from Hugging Face, with HTTP resume support. */
class ModelRepository(
    context: Context,
    private val scope: CoroutineScope,
    private val customModels: CustomModelsRepository? = null,
) {

    private val appContext = context.applicationContext

    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .build()

    private val dir: File
        get() = File(appContext.filesDir, "models").apply { mkdirs() }

    fun modelFile(modelId: String): File {
        ModelCatalog.byId[modelId]?.let { return File(dir, it.file) }
        customModels?.fileFor(modelId)?.let { return File(dir, it) }
        return File(dir, "$modelId.bin")
    }

    private val _states = MutableStateFlow<Map<String, ModelDownloadState>>(emptyMap())
    val states: StateFlow<Map<String, ModelDownloadState>> = _states.asStateFlow()

    private val jobs = ConcurrentHashMap<String, Job>()

    /** Set by the app to raise the download foreground service. */
    var onDownloadStarted: (() -> Unit)? = null

    init {
        seedFromDisk()
    }

    /** Re-checks disk state (models can appear via adb push or external copy). */
    fun rescan() {
        seedFromDisk()
    }

    private fun seedFromDisk() {
        // Never clobber in-flight/failed states: the Models screen rescans on
        // every entry and a wholesale reset would hide a running download.
        _states.update { cur ->
            val out = cur.toMutableMap()
            ModelCatalog.all
                .filter { isDownloaded(it.id) }
                .forEach { m ->
                    val st = cur[m.id]
                    if (st == null || st.status != ModelStatus.DOWNLOADING) {
                        out[m.id] = ModelDownloadState(m.id, ModelStatus.DOWNLOADED, m.sizeBytes, m.sizeBytes)
                    }
                }
            out
        }
    }

    fun isDownloaded(modelId: String): Boolean {
        if (ModelCatalog.isQnnPackage(modelId)) {
            // the zip is deleted after extraction; the extracted per-arch dir is
            // the real state (v79's files must not mark v73 downloaded)
            return QnnWhisperEngine.modelsReady(appContext, archOf(modelId))
        }
        val f = modelFile(modelId)
        return f.exists() && f.length() > 1_000_000
    }

    private fun archOf(modelId: String): String = modelId.removePrefix("qnn-turbo-")

    fun diskUsageBytes(): Long = dir.walkBottomUp().filter { it.isFile }.sumOf { it.length() }

    fun downloadedModels(): List<ModelInfo> = ModelCatalog.all.filter { isDownloaded(it.id) }

    /** The model to use: manual selection if downloaded, otherwise the tier recommendation. */
    fun selectedOrDefault(settings: AppSettings, tier: DeviceTier): ModelInfo {
        settings.modelId?.let { id ->
            ModelCatalog.byId[id]?.let { if (isDownloaded(it.id)) return it }
        }
        return ModelCatalog.recommendedFor(tier)
    }

    fun download(model: ModelInfo) {
        if (jobs.containsKey(model.id) || isDownloaded(model.id)) return
        // synchronous so the download FGS (and any UI that binds immediately)
        // observes the in-flight state on its very first collect
        setState(model.id) { it.copy(status = ModelStatus.DOWNLOADING, error = null) }
        val job = scope.launch(Dispatchers.IO) {
            try {
                val dst = modelFile(model.id)
                val part = File(dir, dst.name + ".part")
                var startBytes = if (part.exists()) part.length() else 0L

                // Resume loop: on a dropped connection (screen-off Wi-Fi doze,
                // flaky mobile data) keep resuming from the .part instead of
                // failing or — worse — renaming a truncated file as complete.
                var attempts = 0
                while (isActive) {
                    try {
                        val reqBuilder = Request.Builder()
                            .url(model.url)
                            .header("User-Agent", "AnywhereTranscript/1.0")
                        if (startBytes > 0) reqBuilder.header("Range", "bytes=$startBytes-")

                        client.newCall(reqBuilder.build()).execute().use { resp ->
                            if (!resp.isSuccessful) throw HttpError(resp.code)
                            val body = resp.body ?: throw IOException("Empty response body")
                            val rangeOk = resp.code == 206 && startBytes > 0
                            if (!rangeOk && startBytes > 0) {
                                // server ignored the range request: restart cleanly
                                part.delete()
                                part.createNewFile()
                            }
                            val offset = if (rangeOk) startBytes else 0L
                            val total = if (body.contentLength() > 0) body.contentLength() + offset else -1L

                            setState(model.id) {
                                it.copy(
                                    downloadedBytes = offset,
                                    totalBytes = if (total > 0) total else it.totalBytes,
                                )
                            }

                            val buf = ByteArray(256 * 1024)
                            var copied = offset
                            FileOutputStream(part, rangeOk && offset > 0).use { out ->
                                body.byteStream().use { input ->
                                    while (true) {
                                        val r = input.read(buf)
                                        if (r < 0) break
                                        out.write(buf, 0, r)
                                        copied += r
                                        if (total > 0 && (copied / (512 * 1024)) != ((copied - r) / (512 * 1024))) {
                                            setState(model.id) { s -> s.copy(downloadedBytes = copied, totalBytes = total) }
                                        }
                                        if (!isActive) throw CancellationException()
                                    }
                                }
                            }
                            startBytes = copied
                            if (total > 0 && copied < total) {
                                throw IOException("Connection closed at $copied / $total")
                            }
                        }

                        if (!part.renameTo(dst)) {
                            part.copyTo(dst, overwrite = true)
                            part.delete()
                        }
                        if (ModelCatalog.isQnnPackage(model.id)) {
                            extractQnnPackage(dst, archOf(model.id))
                            dst.delete() // 2GB zip no longer needed once extracted
                        }
                        setState(model.id) {
                            it.copy(
                                status = ModelStatus.DOWNLOADED,
                                downloadedBytes = dst.length(),
                                totalBytes = dst.length(),
                            )
                        }
                        return@launch
                    } catch (ce: CancellationException) {
                        throw ce
                    } catch (io: IOException) {
                        // client errors won't heal with retries (408/429 might)
                        if (io is HttpError && io.code in 400..499 && io.code != 408 && io.code != 429) throw io
                        attempts++
                        if (attempts >= 5) throw io
                        startBytes = part.length()
                        setState(model.id) { s -> s.copy(downloadedBytes = startBytes) }
                        delay(attempts * 2_000L) // 2s, 4s, 6s, 8s backoff
                    }
                }
            } catch (ce: CancellationException) {
                setState(model.id) { it.copy(status = ModelStatus.NOT_DOWNLOADED) }
            } catch (t: Throwable) {
                setState(model.id) {
                    it.copy(status = ModelStatus.FAILED, error = t.message ?: "Download failed")
                }
            } finally {
                jobs.remove(model.id)
            }
        }
        jobs[model.id] = job
        onDownloadStarted?.invoke()
    }

    fun cancel(modelId: String) {
        jobs[modelId]?.cancel()
        // explicit cancel: drop the partial so abandoned attempts don't pin GBs
        val dst = modelFile(modelId)
        File(dir, dst.name + ".part").delete()
    }

    fun delete(modelId: String) {
        cancel(modelId)
        val dst = modelFile(modelId)
        dst.delete()
        File(dir, dst.name + ".part").delete()
        if (ModelCatalog.isQnnPackage(modelId)) {
            QnnWhisperEngine.deleteExtracted(appContext, archOf(modelId))
        }
        setState(modelId) {
            it.copy(status = ModelStatus.NOT_DOWNLOADED, downloadedBytes = 0, totalBytes = 0, error = null)
        }
    }

    /** Extracts encoder/decoder ONNX + qairt context binaries into files/qnn/<arch>. */
    private fun extractQnnPackage(zipFile: File, arch: String) {
        val outDir = QnnWhisperEngine.modelDir(appContext, arch).apply { mkdirs() }
        java.util.zip.ZipInputStream(zipFile.inputStream().buffered()).use { zis ->
            while (true) {
                val entry = zis.nextEntry ?: break
                val name = entry.name.substringAfterLast('/')
                if (name in setOf("encoder.onnx", "encoder_qairt_context.bin", "decoder.onnx", "decoder_qairt_context.bin")) {
                    File(outDir, name).outputStream().use { zis.copyTo(it) }
                }
                zis.closeEntry()
            }
        }
    }

    private fun setState(id: String, f: (ModelDownloadState) -> ModelDownloadState) {
        _states.update { cur ->
            val old = cur[id] ?: ModelDownloadState(id)
            cur + (id to f(old))
        }
    }
}
