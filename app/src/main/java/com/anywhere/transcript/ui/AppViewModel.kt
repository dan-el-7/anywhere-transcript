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
            val name = (com.anywhere.transcript.ui.components.displayName(app, uri) ?: "imported.bin")
                .replace(Regex("[^A-Za-z0-9._ -]"), "_")
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
