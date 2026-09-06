package com.anywhere.transcript.data

import android.content.Context
import com.anywhere.transcript.engine.QnnWhisperEngine
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
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

    init {
        val seeded = ModelCatalog.all
            .filter { isDownloaded(it.id) }
            .associate { it.id to ModelDownloadState(it.id, ModelStatus.DOWNLOADED, it.sizeBytes, it.sizeBytes) }
        _states.value = seeded
    }

    fun isDownloaded(modelId: String): Boolean {
        if (ModelCatalog.isQnnPackage(modelId)) {
            // the zip is deleted after extraction; extracted files are the real state
            return QnnWhisperEngine.modelsReady(appContext)
        }
        val f = modelFile(modelId)
        return f.exists() && f.length() > 1_000_000
    }

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
        val job = scope.launch {
            setState(model.id) { it.copy(status = ModelStatus.DOWNLOADING, error = null) }
            try {
                val dst = modelFile(model.id)
                val part = File(dir, dst.name + ".part")
                var startBytes = if (part.exists()) part.length() else 0L

                while (isActive) {
                    val reqBuilder = Request.Builder()
                        .url(model.url)
                        .header("User-Agent", "AnywhereTranscript/1.0")
                    if (startBytes > 0) reqBuilder.header("Range", "bytes=$startBytes-")

                    client.newCall(reqBuilder.build()).execute().use { resp ->
                        if (!resp.isSuccessful) throw IOException("HTTP ${resp.code}")
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
                        // fall through: body ended; if size looks complete we finish below
                    }

                    if (!part.renameTo(dst)) {
                        part.copyTo(dst, overwrite = true)
                        part.delete()
                    }
                    if (ModelCatalog.isQnnPackage(model.id)) {
                        extractQnnPackage(dst)
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
    }

    fun cancel(modelId: String) {
        jobs[modelId]?.cancel()
    }

    fun delete(modelId: String) {
        cancel(modelId)
        val dst = modelFile(modelId)
        dst.delete()
        File(dir, dst.name + ".part").delete()
        if (ModelCatalog.isQnnPackage(modelId)) {
            QnnWhisperEngine.deleteExtracted(appContext)
        }
        setState(modelId) {
            it.copy(status = ModelStatus.NOT_DOWNLOADED, downloadedBytes = 0, totalBytes = 0, error = null)
        }
    }

    /** Extracts encoder/decoder ONNX + qairt context binaries into files/qnn. */
    private fun extractQnnPackage(zipFile: File) {
        val outDir = File(appContext.getExternalFilesDir(null), "qnn").apply { mkdirs() }
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
