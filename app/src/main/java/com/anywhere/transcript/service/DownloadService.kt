package com.anywhere.transcript.service

import android.app.Notification
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.wifi.WifiManager
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import com.anywhere.transcript.MainActivity
import com.anywhere.transcript.R
import com.anywhere.transcript.TranscriberApp
import com.anywhere.transcript.data.ModelStatus
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * Foreground service so model downloads (multi-GB) survive backgrounding and
 * screen-off: holds the dataSync foreground contract plus wifi/CPU wake locks,
 * and mirrors download progress into a notification with a cancel action.
 * The downloads themselves run in ModelRepository's application scope.
 */
class DownloadService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var collector: Job? = null
    private var foregroundStarted = false
    private var lastNotifiedProgress = -1

    private var wifiLock: WifiManager.WifiLock? = null
    private var wakeLock: PowerManager.WakeLock? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        val builder = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_download)
            .setOnlyAlertOnce(true)
            .setOngoing(true)
            .setSilent(true)
            .setContentIntent(mainPendingIntent())
            .addAction(0, getString(R.string.notif_cancel), cancelPendingIntent())

        val repo = (application as TranscriberApp).modelRepo

        collector = scope.launch {
            repo.states.collect { states ->
                val active = states.values.filter { it.status == ModelStatus.DOWNLOADING }
                if (active.isEmpty()) {
                    releaseLocks()
                    if (foregroundStarted) {
                        stopForeground(STOP_FOREGROUND_REMOVE)
                        stopSelf()
                    }
                    return@collect
                }
                acquireLocks()
                startForegroundCompat()

                // single active download is the norm; show its progress verbatim
                val dl = active.first()
                val label = com.anywhere.transcript.data.ModelCatalog.byId[dl.modelId]?.label
                    ?: repo.modelFile(dl.modelId).name
                val pct = if (dl.totalBytes > 0)
                    ((dl.downloadedBytes * 100) / dl.totalBytes).toInt().coerceIn(0, 100) else -1
                if (pct != lastNotifiedProgress) {
                    lastNotifiedProgress = pct
                    val text = if (pct >= 0) "$label · $pct%" else label
                    val n: Notification = builder
                        .setContentTitle(getString(R.string.notif_dl_title))
                        .setContentText(text)
                        .setProgress(100, pct.coerceAtLeast(0), pct < 0)
                        .build()
                    notify(n)
                }
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // startForegroundService contract: foreground must be established even if
        // the state collector hasn't seen a download yet.
        startForegroundCompat()
        if (intent?.action == ACTION_CANCEL) {
            val repo = (application as TranscriberApp).modelRepo
            repo.states.value.values
                .filter { it.status == ModelStatus.DOWNLOADING }
                .forEach { repo.cancel(it.modelId) }
            // state collector stops the service once no downloads remain
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        collector?.cancel()
        scope.cancel()
        releaseLocks()
        super.onDestroy()
    }

    private fun acquireLocks() {
        if (wifiLock == null) {
            val wm = getSystemService(WIFI_SERVICE) as WifiManager
            @Suppress("DEPRECATION")
            wifiLock = wm.createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "at:download")
                .apply { setReferenceCounted(false) }
        }
        if (wakeLock == null) {
            val pm = getSystemService(POWER_SERVICE) as PowerManager
            wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "at:download")
                .apply { setReferenceCounted(false) }
        }
        // WifiLock has no timed acquire; bounded instead by the service lifetime
        // (released as soon as no downloads remain) and stopSelf on cancel.
        wifiLock?.acquire()
        wakeLock?.acquire(3 * 60 * 60 * 1000L)
    }

    private fun releaseLocks() {
        runCatching { wifiLock?.release() }
        runCatching { wakeLock?.release() }
    }

    private fun notify(n: Notification) {
        try {
            androidx.core.app.NotificationManagerCompat.from(this).notify(NOTIFICATION_ID, n)
        } catch (_: SecurityException) {
            // POST_NOTIFICATIONS not granted; the service keeps running regardless
        }
    }

    private fun startForegroundCompat() {
        if (foregroundStarted) return
        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_download)
            .setContentTitle(getString(R.string.notif_dl_title))
            .setProgress(0, 0, true)
            .setOngoing(true)
            .setSilent(true)
            .build()
        // Same OEM fallback chain as TranscriptionService ("type none" on some
        // Android 15 builds via the androidx path).
        val attempts = when {
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.VANILLA_ICE_CREAM -> listOf(
                ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROCESSING,
            )
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q -> listOf(
                ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC,
            )
            else -> listOf(0)
        }
        var lastError: Exception? = null
        for (type in attempts) {
            try {
                if (type == 0) startForeground(NOTIFICATION_ID, notification)
                else startForeground(NOTIFICATION_ID, notification, type)
                foregroundStarted = true
                return
            } catch (e: Exception) {
                lastError = e
            }
        }
        throw lastError ?: IllegalStateException("startForeground failed")
    }

    private fun mainPendingIntent() = PendingIntent.getActivity(
        this, 0,
        Intent(this, MainActivity::class.java),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    private fun cancelPendingIntent() = PendingIntent.getService(
        this, 0,
        Intent(this, DownloadService::class.java).setAction(ACTION_CANCEL),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    companion object {
        const val CHANNEL_ID = "downloads"
        const val NOTIFICATION_ID = 2
        const val ACTION_CANCEL = "com.anywhere.transcript.action.CANCEL_DOWNLOAD"

        /** Starts the foreground service; safe to call on every download. */
        fun start(context: android.content.Context) {
            val i = Intent(context, DownloadService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(i)
            } else {
                context.startService(i)
            }
        }
    }
}
