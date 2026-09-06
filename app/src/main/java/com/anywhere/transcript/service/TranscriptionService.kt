package com.anywhere.transcript.service

import android.app.Notification
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.Uri
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.anywhere.transcript.R
import com.anywhere.transcript.TranscriberApp
import com.anywhere.transcript.transcription.JobPhase
import com.anywhere.transcript.transcription.TranscriptionBus
import com.anywhere.transcript.transcription.TranscriptionCoordinator
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * Foreground service so long transcriptions survive backgrounding.
 * State flows through TranscriptionBus; the UI subscribes to the same bus.
 */
class TranscriptionService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var coordinator: TranscriptionCoordinator? = null
    private var collector: Job? = null
    private var foregroundStarted = false
    private var lastNotifiedProgress = -1

    private lateinit var builder: NotificationCompat.Builder

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        val app = application as TranscriberApp
        coordinator = TranscriptionCoordinator(
            applicationContext,
            scope,
            app.settingsRepo,
            app.modelRepo,
            app.db.historyDao(),
        )
        builder = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_wave)
            .setOnlyAlertOnce(true)
            .setOngoing(true)
            .setSilent(true)
            .addAction(0, getString(R.string.notif_cancel), cancelPendingIntent())

        collector = scope.launch {
            TranscriptionBus.state.collect { st ->
                when (st.phase) {
                    JobPhase.IDLE -> Unit
                    JobPhase.PREPARING, JobPhase.DECODING, JobPhase.TRANSCRIBING ->
                        updateNotification(st.fileName, st.progress)
                    JobPhase.DONE, JobPhase.ERROR, JobPhase.CANCELLED -> {
                        stopForeground(STOP_FOREGROUND_REMOVE)
                        stopSelf()
                    }
                }
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_CANCEL -> {
                TranscriptionBus.requestCancel()
                return START_NOT_STICKY
            }
            ACTION_START -> {
                val uri: Uri = intent.data ?: run {
                    stopSelf()
                    return START_NOT_STICKY
                }
                val name = intent.getStringExtra(EXTRA_NAME) ?: "audio"
                startForegroundCompat()
                coordinator?.start(uri, name)
            }
            else -> {
                stopSelf()
                return START_NOT_STICKY
            }
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        collector?.cancel()
        scope.cancel()
        super.onDestroy()
    }

    private fun startForegroundCompat() {
        if (foregroundStarted) return
        val notification = buildNotification()
        // Some OEM Android 15 builds reject the androidx ServiceCompat path with
        // "type none", so call the framework directly and fall back across types.
        val attempts = when {
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.VANILLA_ICE_CREAM -> listOf(
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROCESSING,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC,
            )
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q -> listOf(
                ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC,
            )
            else -> listOf(0)
        }
        var lastError: Exception? = null
        for (type in attempts) {
            try {
                if (type == 0) {
                    startForeground(NOTIFICATION_ID, notification)
                } else {
                    startForeground(NOTIFICATION_ID, notification, type)
                }
                foregroundStarted = true
                return
            } catch (e: Exception) {
                lastError = e
            }
        }
        throw lastError ?: IllegalStateException("startForeground failed")
    }

    private fun buildNotification(): Notification =
        builder
            .setContentTitle(getString(R.string.notif_title))
            .setContentText("")
            .setProgress(0, 0, true)
            .build()

    private fun updateNotification(fileName: String, progress: Float) {
        if (!foregroundStarted) return
        val pct = (progress * 100).toInt().coerceIn(0, 100)
        if (pct == lastNotifiedProgress && progress >= 0f) return
        lastNotifiedProgress = pct
        val indeterminate = progress < 0f
        val text = if (indeterminate) fileName else "$fileName · $pct%"
        val notification = builder
            .setContentTitle(getString(R.string.notif_title))
            .setContentText(text)
            .setProgress(100, pct, indeterminate)
            .build()
        try {
            androidx.core.app.NotificationManagerCompat.from(this).notify(NOTIFICATION_ID, notification)
        } catch (_: SecurityException) {
            // POST_NOTIFICATIONS not granted; the service keeps running regardless
        }
    }

    private fun cancelPendingIntent() = android.app.PendingIntent.getService(
        this,
        0,
        Intent(this, TranscriptionService::class.java).setAction(ACTION_CANCEL),
        android.app.PendingIntent.FLAG_IMMUTABLE or android.app.PendingIntent.FLAG_UPDATE_CURRENT,
    )

    companion object {
        const val CHANNEL_ID = "transcription"
        const val NOTIFICATION_ID = 1
        const val ACTION_START = "com.anywhere.transcript.action.START"
        const val ACTION_CANCEL = "com.anywhere.transcript.action.CANCEL"
        const val EXTRA_NAME = "extra_name"
    }
}
