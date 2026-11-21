package com.rotvahan.app

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import kotlinx.coroutines.*
import java.util.Locale

class TimerForegroundService : Service() {

    companion object {
        const val CHANNEL_ID = "rot_vahan_timer_channel"
        const val NOTIFICATION_ID = 1001
        const val COMPLETION_NOTIFICATION_ID = 1002
        const val EXTRA_MOBILE = "extra_mobile"
        const val EXTRA_EXPIRY_MS = "extra_expiry_ms"

        fun start(context: Context, mobile: String, expiryMs: Long) {
            val intent = Intent(context, TimerForegroundService::class.java).apply {
                putExtra(EXTRA_MOBILE, mobile)
                putExtra(EXTRA_EXPIRY_MS, expiryMs)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, TimerForegroundService::class.java))
        }
    }

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var countdownJob: Job? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        RotVahanSyncManager.ensureInitialized(applicationContext)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        RotVahanSyncManager.ensureInitialized(applicationContext)

        val mobile = intent?.getStringExtra(EXTRA_MOBILE)
            ?: RotVahanSyncManager.getSavedMobile(applicationContext)
        val passedExpiry = intent?.getLongExtra(EXTRA_EXPIRY_MS, 0L) ?: 0L
        val savedExpiry = RotVahanSyncManager.getSavedExpiryMs(applicationContext)
        val initialExpiry = if (passedExpiry > 0L) passedExpiry else savedExpiry

        val initialRemaining = RotVahanSyncManager.computeRemainingSeconds(initialExpiry)
        val initialNotification = buildProgressNotification(initialRemaining)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ServiceCompat.startForeground(
                this,
                NOTIFICATION_ID,
                initialNotification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
            )
        } else {
            startForeground(NOTIFICATION_ID, initialNotification)
        }

        if (mobile.isNullOrBlank() || initialExpiry <= 0L) {
            stopServiceCleanly()
            return START_NOT_STICKY
        }

        startBackgroundCountdown(mobile, initialExpiry)
        return START_REDELIVER_INTENT
    }

    private fun startBackgroundCountdown(mobile: String, fallbackExpiryMs: Long) {
        countdownJob?.cancel()
        countdownJob = serviceScope.launch {
            val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

            while (isActive) {
                val activeExpiry = RotVahanSyncManager.userData.value?.timerExpiryTime
                    ?.takeIf { it > 0L }
                    ?: fallbackExpiryMs
                val remainingSecs = RotVahanSyncManager.computeRemainingSeconds(activeExpiry)

                if (remainingSecs <= 0) {
                    RotVahanSyncManager.markTimerExpired(mobile)
                    showCompletionNotification(nm)
                    stopServiceCleanly()
                    break
                }

                try {
                    nm.notify(NOTIFICATION_ID, buildProgressNotification(remainingSecs))
                } catch (_: SecurityException) {
                    // Notification permission denied on Android 13+
                }
                delay(1000L)
            }
        }
    }

    private fun buildProgressNotification(remainingSeconds: Int): Notification {
        val mins = remainingSeconds / 60
        val secs = remainingSeconds % 60
        val formatted = String.format(Locale.US, "%02d:%02d", mins, secs)
        val onlineText = if (RotVahanSyncManager.isOnline.value) "Online" else "Offline"

        val openAppIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            openAppIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_lock_idle_alarm)
            .setContentTitle("ROT Vahan · $onlineText")
            .setContentText("Session timer active: $formatted remaining")
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setContentIntent(pendingIntent)
            .build()
    }

    private fun showCompletionNotification(nm: NotificationManager) {
        val openAppIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val pendingIntent = PendingIntent.getActivity(
            this,
            1,
            openAppIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val doneNotification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_lock_idle_alarm)
            .setContentTitle("ROT Vahan")
            .setContentText("2-minute session completed.")
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setContentIntent(pendingIntent)
            .build()

        try {
            nm.notify(COMPLETION_NOTIFICATION_ID, doneNotification)
        } catch (_: SecurityException) {
            // Notification permission denied by user on Android 13+
        }
    }

    private fun stopServiceCleanly() {
        countdownJob?.cancel()
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "ROT Vahan Session Timer",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Shows the active 2-minute ROT Vahan background timer."
                setShowBadge(false)
            }
            val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            nm.createNotificationChannel(channel)
        }
    }

    override fun onDestroy() {
        countdownJob?.cancel()
        serviceScope.cancel()
        super.onDestroy()
    }
}
