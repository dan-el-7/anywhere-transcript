package com.anywhere.transcript

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.os.Build
import com.anywhere.transcript.data.CustomModelsRepository
import com.anywhere.transcript.data.ModelRepository
import com.anywhere.transcript.engine.WhisperEngine
import com.anywhere.transcript.data.SettingsRepository
import com.anywhere.transcript.data.db.AppDatabase
import com.anywhere.transcript.service.DownloadService
import com.anywhere.transcript.service.TranscriptionService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

class TranscriberApp : Application() {

    val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    val db: AppDatabase by lazy { AppDatabase.build(this) }
    val settingsRepo: SettingsRepository by lazy { SettingsRepository(this) }
    val customRepo: CustomModelsRepository by lazy { CustomModelsRepository(this, appScope) }

    /**
     * Shared transcription coordinator (model routing, windowed decode,
     * history insert). The FGS uses its own instance (same process-wide bus);
     * this one lets in-app callers (Record tab final pass) run jobs without
     * a service start.
     */
    val coordinator: com.anywhere.transcript.transcription.TranscriptionCoordinator by lazy {
        com.anywhere.transcript.transcription.TranscriptionCoordinator(
            this,
            appScope,
            settingsRepo,
            modelRepo,
            db.historyDao(),
        )
    }
    val modelRepo: ModelRepository by lazy {
        ModelRepository(this, appScope, customRepo).apply {
            onDownloadStarted = { DownloadService.start(this@TranscriberApp) }
        }
    }

    override fun onCreate() {
        super.onCreate()
        createNotificationChannels()
        // No DSP loader setup: raw Hexagon dispatch was removed (HTP runs
        // precompiled graphs only; the NPU is used exclusively via the QNN
        // engine's context binaries). The QNN path needs no ADSP_LIBRARY_PATH.
        // OpenCL is opt-in (crash-safe default off); kept in sync by SettingsRepository
        runCatching {
            val flags = getSharedPreferences("engine_flags", MODE_PRIVATE)
            WhisperEngine.setOpenclEnabled(flags.getBoolean("opencl_enabled", false))
        }
    }

    private fun createNotificationChannels() {
        val manager = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        manager.createNotificationChannel(
            NotificationChannel(
                TranscriptionService.CHANNEL_ID,
                getString(R.string.notif_channel_name),
                NotificationManager.IMPORTANCE_LOW,
            ).apply { description = getString(R.string.notif_channel_desc) },
        )
        manager.createNotificationChannel(
            NotificationChannel(
                DownloadService.CHANNEL_ID,
                getString(R.string.notif_dl_channel_name),
                NotificationManager.IMPORTANCE_LOW,
            ).apply { description = getString(R.string.notif_dl_channel_desc) },
        )
    }
}
