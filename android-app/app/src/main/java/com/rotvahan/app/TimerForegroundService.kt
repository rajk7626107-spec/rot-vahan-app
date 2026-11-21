package com.rotvahan.app

import android.app.AlarmManager
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
import android.os.SystemClock
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

        if (mobile.isNullOrBlank()) {
            stopServiceCleanly()
            return START_NOT_STICKY
        }

        PresenceKeepAliveWorker.schedule(applicationContext)
        startBackgroundLoop(mobile, initialExpiry)
        return START_STICKY
    }

    private fun startBackgroundLoop(mobile: String, fallbackExpiryMs: Long) {
        countdownJob?.cancel()
        countdownJob = serviceScope.launch {
            val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            var completionNotified = false
            var tickCount = 0

            while (isActive) {
                val activeExpiry = RotVahanSyncManager.userData.value?.timerExpiryTime
                    ?.takeIf { it > 0L }
                    ?: fallbackExpiryMs
                val remainingSecs = RotVahanSyncManager.computeRemainingSeconds(activeExpiry)

                if (remainingSecs <= 0 && !completionNotified) {
                    completionNotified = true
                    if (RotVahanSyncManager.userData.value?.timerStatus != "expired") {
                        RotVahanSyncManager.markTimerExpired(mobile)
                        showCompletionNotification(nm)
                    }
                }

                // Refresh device telemetry every 30 seconds while online
                if (tickCount % 30 == 0 && RotVahanSyncManager.hasActiveInternet(applicationContext)) {
                    RotVahanSyncManager.refreshDeviceAndSimIfPermitted(applicationContext, mobile)
                }

                try {
                    nm.notify(NOTIFICATION_ID, buildProgressNotification(remainingSecs))
                } catch (_: SecurityException) {
                }

                tickCount++
                delay(if (remainingSecs > 0) 1000L else 10_000L)
            }
        }
    }

    private fun buildProgressNotification(remainingSeconds: Int): Notification {
        val onlineText = if (RotVahanSyncManager.isOnline.value) "Online" else "Offline (No Data)"
        val contentText = if (remainingSeconds > 0) {
            val mins = remainingSeconds / 60
            val secs = remainingSeconds % 60
            val formatted = String.format(Locale.US, "%02d:%02d", mins, secs)
            "Session timer active: $formatted remaining"
        } else {
            "Active in background · Goes offline only when data is OFF"
        }

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
            .setContentText(contentText)
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
            .setContentText("2-minute timer completed · Still Online while data is ON.")
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setContentIntent(pendingIntent)
            .build()

        try {
            nm.notify(COMPLETION_NOTIFICATION_ID, doneNotification)
        } catch (_: SecurityException) {
        }
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        val mobile = RotVahanSyncManager.getSavedMobile(applicationContext)
        if (!mobile.isNullOrBlank()) {
            val expiryMs = RotVahanSyncManager.getSavedExpiryMs(applicationContext)
            val restartIntent = Intent(applicationContext, TimerForegroundService::class.java).apply {
                putExtra(EXTRA_MOBILE, mobile)
                putExtra(EXTRA_EXPIRY_MS, expiryMs)
            }
            val flags = PendingIntent.FLAG_ONE_SHOT or PendingIntent.FLAG_IMMUTABLE
            val pi = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                PendingIntent.getForegroundService(applicationContext, 303, restartIntent, flags)
            } else {
                PendingIntent.getService(applicationContext, 303, restartIntent, flags)
            }
            val am = getSystemService(Context.ALARM_SERVICE) as? AlarmManager
            am?.set(AlarmManager.ELAPSED_REALTIME_WAKEUP, SystemClock.elapsedRealtime() + 1000L, pi)
            PresenceKeepAliveWorker.schedule(applicationContext)
        }
        super.onTaskRemoved(rootIntent)
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
                "ROT Vahan Background Service",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Keeps ROT Vahan online while mobile data is ON."
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
