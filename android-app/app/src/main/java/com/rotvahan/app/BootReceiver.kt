package com.rotvahan.app

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.Worker
import androidx.work.WorkerParameters
import java.util.concurrent.TimeUnit

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        val action = intent?.action ?: return
        if (action == Intent.ACTION_BOOT_COMPLETED || action == Intent.ACTION_MY_PACKAGE_REPLACED) {
            val mobile = RotVahanSyncManager.getSavedMobile(context)
            if (!mobile.isNullOrBlank()) {
                val expiryMs = RotVahanSyncManager.getSavedExpiryMs(context)
                try {
                    TimerForegroundService.start(context, mobile, expiryMs)
                } catch (_: Exception) {
                }
                PresenceKeepAliveWorker.schedule(context)
            }
        }
    }
}

class PresenceKeepAliveWorker(
    appContext: Context,
    params: WorkerParameters
) : Worker(appContext, params) {

    override fun doWork(): Result {
        val mobile = RotVahanSyncManager.getSavedMobile(applicationContext)
        if (mobile.isNullOrBlank()) {
            return Result.success()
        }
        RotVahanSyncManager.ensureInitialized(applicationContext)
        RotVahanSyncManager.refreshDeviceAndSimIfPermitted(applicationContext, mobile)
        val expiryMs = RotVahanSyncManager.getSavedExpiryMs(applicationContext)
        try {
            TimerForegroundService.start(applicationContext, mobile, expiryMs)
        } catch (_: Exception) {
        }
        return Result.success()
    }

    companion object {
        private const val WORK_NAME = "rot_vahan_presence_keepalive"

        fun schedule(context: Context) {
            val constraints = Constraints.Builder()
                .setRequiredNetworkType(NetworkType.CONNECTED)
                .build()
            val req = PeriodicWorkRequestBuilder<PresenceKeepAliveWorker>(15, TimeUnit.MINUTES)
                .setConstraints(constraints)
                .build()
            WorkManager.getInstance(context.applicationContext).enqueueUniquePeriodicWork(
                WORK_NAME,
                ExistingPeriodicWorkPolicy.KEEP,
                req
            )
        }

        fun cancel(context: Context) {
            WorkManager.getInstance(context.applicationContext).cancelUniqueWork(WORK_NAME)
        }
    }
}
