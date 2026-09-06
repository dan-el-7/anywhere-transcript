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
import com.anywhere.transcript.service.TranscriptionService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

class TranscriberApp : Application() {

    val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    val db: AppDatabase by lazy { AppDatabase.build(this) }
    val settingsRepo: SettingsRepository by lazy { SettingsRepository(this) }
    val customRepo: CustomModelsRepository by lazy { CustomModelsRepository(this, appScope) }
    val modelRepo: ModelRepository by lazy { ModelRepository(this, appScope, customRepo) }

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        // Extract native libs to disk so the DSP loader can find the skel,
        // then point ADSP_LIBRARY_PATH at them before any engine use.
        runCatching {
            WhisperEngine.setDspLibraryPath(applicationInfo.nativeLibraryDir)
        }
    }

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            TranscriptionService.CHANNEL_ID,
            getString(R.string.notif_channel_name),
            NotificationManager.IMPORTANCE_LOW,
        ).apply {
            description = getString(R.string.notif_channel_desc)
        }
        val manager = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        manager.createNotificationChannel(channel)
    }
}
