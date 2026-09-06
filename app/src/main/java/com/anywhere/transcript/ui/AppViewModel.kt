package com.anywhere.transcript.ui

import android.app.ActivityManager
import android.app.Application
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.core.content.ContextCompat
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.anywhere.transcript.TranscriberApp
import com.anywhere.transcript.data.AppSettings
import com.anywhere.transcript.data.DeviceTier
import com.anywhere.transcript.data.ModelCatalog
import com.anywhere.transcript.data.ModelInfo
import com.anywhere.transcript.data.ModelStatus
import com.anywhere.transcript.data.db.HistoryEntry
import com.anywhere.transcript.engine.BackendDevice
import com.anywhere.transcript.engine.WhisperEngine
import com.anywhere.transcript.service.TranscriptionService
import com.anywhere.transcript.transcription.TranscriptionBus
import com.anywhere.transcript.transcription.TranscriptionUiState
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.Dispatchers

class AppViewModel(app: android.app.Application) : AndroidViewModel(app) {

    private val graph: TranscriberApp get() = getApplication()

    val uiState: StateFlow<TranscriptionUiState> = TranscriptionBus.state

    val settings: StateFlow<AppSettings> = graph.settingsRepo.settings
        .stateIn(viewModelScope, SharingStarted.Eagerly, AppSettings())

    val history: Flow<List<HistoryEntry>> = graph.db.historyDao().observeAll()

    val modelStates: StateFlow<Map<String, com.anywhere.transcript.data.ModelDownloadState>> =
        graph.modelRepo.states

    val customModels: StateFlow<List<ModelInfo>> = graph.customRepo.entries
        .map { list -> list.map { it.toModelInfo() } }
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    private val _customModelError = MutableStateFlow<String?>(null)
    val customModelError: StateFlow<String?> = _customModelError.asStateFlow()

    private val autoTier = MutableStateFlow(detectTier())

    val effectiveTier: StateFlow<DeviceTier> =
        combine(settings, autoTier) { s, auto -> DeviceTier.fromNameOrNull(s.tierOverride) ?: auto }
            .stateIn(viewModelScope, SharingStarted.Eagerly, autoTier.value)

    val recommendedModel: StateFlow<ModelInfo> =
        effectiveTier.map { ModelCatalog.recommendedFor(it) }
            .stateIn(viewModelScope, SharingStarted.Eagerly, ModelCatalog.recommendedFor(autoTier.value))

    /** First-run onboarding: shows until dismissed, or once any model exists. */
    val needsOnboarding: StateFlow<Boolean> =
        combine(settings, modelStates) { s, states ->
            !s.onboardingDone && states.values.none { it.status == ModelStatus.DOWNLOADED }
        }.stateIn(viewModelScope, SharingStarted.Eagerly, true)

    /** Marks onboarding done; [modelId] is the picked model (null = skip). */
    fun finishOnboarding(modelId: String?) {
        viewModelScope.launch {
            if (modelId != null) {
                selectModel(modelId)
                if (modelId.startsWith("qnn-turbo")) graph.settingsRepo.setBackendPref("qnn")
            }
            graph.settingsRepo.setOnboardingDone(true)
        }
    }

    val engineInfo: String by lazy { runCatching { WhisperEngine.systemInfo() }.getOrElse { "unavailable" } }
    val backends: List<BackendDevice> by lazy { runCatching { WhisperEngine.backends() }.getOrElse { emptyList() } }

    // ---- transcription ----------------------------------------------------------

    fun requestTranscribe(uri: Uri, displayName: String, backendOverride: String? = null) {
        val model = graph.modelRepo.selectedOrDefault(settings.value, effectiveTier.value)
        val usesWhisper = backendOverride == null || backendOverride == "auto"
        if (usesWhisper && model == null) {
            // Nothing usable on the whisper.cpp engine: fail visibly instead of
            // starting a service that would sit stuck in IDLE forever.
            TranscriptionBus.update {
                it.copy(
                    phase = com.anywhere.transcript.transcription.JobPhase.ERROR,
                    fileName = displayName,
                    error = "No usable model. Download one from the Models tab first" +
                        " (or the QNN Turbo package for this chip).",
                    modelMissing = true,
                )
            }
            return
        }
        val intent = Intent(getApplication(), TranscriptionService::class.java).apply {
            action = TranscriptionService.ACTION_START
            data = uri
            putExtra(TranscriptionService.EXTRA_NAME, displayName)
            if (backendOverride != null) putExtra(TranscriptionService.EXTRA_BACKEND, backendOverride)
        }
        ContextCompat.startForegroundService(getApplication(), intent)
    }

    fun cancel() {
        TranscriptionBus.requestCancel()
    }

    fun dismissResult() {
        TranscriptionBus.reset()
    }

    // ---- models -----------------------------------------------------------------

    fun download(model: ModelInfo) = graph.modelRepo.download(model)
    fun cancelDownload(modelId: String) = graph.modelRepo.cancel(modelId)
    fun deleteModel(modelId: String) = graph.modelRepo.delete(modelId)
    fun diskUsageBytes(): Long = graph.modelRepo.diskUsageBytes()
    fun isDownloaded(modelId: String): Boolean = graph.modelRepo.isDownloaded(modelId)
    fun rescanModels() = graph.modelRepo.rescan()

    fun selectModel(modelId: String?) {
        viewModelScope.launch { graph.settingsRepo.setModelId(modelId) }
    }

    fun selectedModel(): ModelInfo? =
        graph.modelRepo.selectedOrDefault(settings.value, effectiveTier.value)

    // ---- custom models -----------------------------------------------------------

    fun addCustomModel(url: String) {
        viewModelScope.launch {
            val added = graph.customRepo.addUrl(url)
            _customModelError.value = if (added == null) {
                "Enter a valid https://…/model.bin URL"
            } else {
                download(added.toModelInfo())
                null
            }
        }
    }

    fun clearCustomModelError() {
        _customModelError.value = null
    }

    fun importModel(uri: Uri) {
        viewModelScope.launch {
            val app = getApplication<Application>()
            val name = (com.anywhere.transcript.ui.components.displayName(app, uri) ?: "imported")
                .replace(Regex("[^A-Za-z0-9._ -]"), "_")

            // QNN context-binary package (AI Hub zip): extract to files/qnn/<arch>
            if (name.endsWith(".zip", true)) {
                importQnnZip(uri, name)
                return@launch
            }

            val fileName = if (name.endsWith(".bin", true)) name else "$name.bin"
            try {
                val dst = graph.modelRepo.modelFile("import-tmp").parentFile!!.resolve(fileName)
                app.contentResolver.openInputStream(uri)?.use { input ->
                    dst.outputStream().use { input.copyTo(it) }
                }
                if (dst.length() > 0) {
                    graph.customRepo.addImported(fileName)
                } else {
                    dst.delete()
                    _customModelError.value = "Could not read the selected file"
                }
            } catch (t: Throwable) {
                _customModelError.value = "Import failed: ${t.message ?: "unknown error"}"
            }
        }
    }

    /**
     * Imports an AI Hub QNN zip: sniffs the arch, extracts the 4 payload
     * files into files/qnn/<arch>, and marks the matching catalog entry
     * downloaded. Wrong-arch packages are rejected with a clear message.
     */
    private suspend fun importQnnZip(uri: Uri, name: String) {
        val app = getApplication<Application>()
        withContext(Dispatchers.IO) {
            try {
                val stream = app.contentResolver.openInputStream(uri)
                    ?: run {
                        _customModelError.value = "Could not read the selected file"
                        return@withContext
                    }
                val sniffed = stream.use { com.anywhere.transcript.data.QnnImport.sniffArch(it) }
                    ?: com.anywhere.transcript.data.QnnImport.archFromSocKey(name)
                val deviceArch = com.anywhere.transcript.data.Hexagon.deviceArch()

                val arch = when {
                    sniffed != null -> sniffed
                    deviceArch != null -> deviceArch
                    else -> {
                        _customModelError.value = "Could not tell which chip this QNN package is for."
                        return@withContext
                    }
                }

                if (deviceArch != null && arch != deviceArch) {
                    _customModelError.value = "This package is for Hexagon $arch — this device is $deviceArch. " +
                        "Context binaries are chip-locked."
                    return@withContext
                }

                // extract (re-open: the sniff consumed the stream)
                app.contentResolver.openInputStream(uri)?.use { input ->
                    val written = com.anywhere.transcript.data.QnnImport.extract(
                        input,
                        com.anywhere.transcript.engine.QnnWhisperEngine.modelDir(app, arch),
                    ) ?: run {
                        _customModelError.value = "Could not read the QNN package zip."
                        return@use
                    }
                    if (written.containsAll(com.anywhere.transcript.data.QnnImport.REQUIRED)) {
                        graph.modelRepo.rescan()
                        _customModelError.value = null
                    } else {
                        _customModelError.value = "Package incomplete — expected encoder/decoder .onnx + " +
                            "qairt context binaries (found: ${written.joinToString()})."
                    }
                }
            } catch (t: Throwable) {
                _customModelError.value = "QNN import failed: ${t.message ?: "unknown error"}"
            }
        }
    }

    fun removeCustomModel(modelId: String) {
        viewModelScope.launch {
            graph.modelRepo.delete(modelId)
            graph.customRepo.remove(modelId)
        }
    }

    // ---- history ----------------------------------------------------------------

    fun deleteHistory(id: Long) {
        viewModelScope.launch { graph.db.historyDao().deleteById(id) }
    }

    fun clearHistory() {
        viewModelScope.launch { graph.db.historyDao().clearAll() }
    }

    // ---- recording (live transcription) ---------------------------------------

    private val _recordState = MutableStateFlow(com.anywhere.transcript.transcription.RecordUiState())
    val recordState: StateFlow<com.anywhere.transcript.transcription.RecordUiState> = _recordState.asStateFlow()

    /** One-shot navigation request from screens without a nav callback. */
    val goToModels = MutableStateFlow(false)

    private val recordSession by lazy {
        com.anywhere.transcript.transcription.LiveRecordingSession(
            getApplication(),
            viewModelScope,
            graph.settingsRepo,
            graph.modelRepo,
        ).also { session ->
            viewModelScope.launch { session.state.collect { _recordState.value = it } }
            session.onTakeFinished = { file ->
                // Run the final pass in-process (no FGS restart): stopping a
                // take can race a DONE-state service teardown, and
                // startForegroundService at that instant crashes with
                // ForegroundServiceDidNotStartInTimeException. The user is
                // in-app watching the Record tab; the bus state drives the UI.
                viewModelScope.launch(Dispatchers.IO) {
                    graph.coordinator.start(
                        android.net.Uri.fromFile(file),
                        "Recording ${java.text.DateFormat.getTimeInstance(java.text.DateFormat.SHORT).format(java.util.Date())}",
                    )
                }
            }
        }
    }

    fun startRecording() {
        recordSession.reset()
        recordSession.start()
    }

    fun stopRecording() {
        recordSession.stop()
    }

    // ---- settings ---------------------------------------------------------------

    fun setTierOverride(v: String) = viewModelScope.launch { graph.settingsRepo.setTierOverride(v) }
    fun setBackendPref(v: String) = viewModelScope.launch { graph.settingsRepo.setBackendPref(v) }
    fun setLanguage(v: String) = viewModelScope.launch { graph.settingsRepo.setLanguage(v) }
    fun setTranslate(v: Boolean) = viewModelScope.launch { graph.settingsRepo.setTranslate(v) }
    fun setDynamicColor(v: Boolean) = viewModelScope.launch { graph.settingsRepo.setDynamicColor(v) }
    fun setThemeMode(v: String) = viewModelScope.launch { graph.settingsRepo.setThemeMode(v) }
    fun setHideIncompatibleModels(v: Boolean) = viewModelScope.launch { graph.settingsRepo.setHideIncompatibleModels(v) }

    // ---- platform ---------------------------------------------------------------

    fun copyToClipboard(text: String) {
        val cm = getApplication<Application>().getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        cm.setPrimaryClip(ClipData.newPlainText("Transcript", text))
    }

    fun shareText(text: String) {
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_TEXT, text)
        }
        getApplication<Application>().startActivity(
            Intent.createChooser(intent, null).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        )
    }

    private fun detectTier(): DeviceTier {
        val am = getApplication<Application>().getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val info = ActivityManager.MemoryInfo()
        am.getMemoryInfo(info)
        return DeviceTier.detect(info.totalMem)
    }
}
