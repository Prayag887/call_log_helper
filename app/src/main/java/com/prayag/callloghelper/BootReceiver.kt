package com.prayag.callloghelper

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import java.util.concurrent.TimeUnit

class BootReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED ||
            intent.action == "android.intent.action.QUICKBOOT_POWERON"
        ) {
            Log.d("callloghelper", "Device booted — starting CallStateService")

            // Start the persistent foreground service
            val serviceIntent = Intent(context, CallStateService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(serviceIntent)
            } else {
                context.startService(serviceIntent)
            }

            // Also ensure the WorkManager periodic fallback is scheduled.
            // This covers the case where the app has never been opened after
            // installation — MainActivity normally schedules this, but on
            // first boot after install it may not have run yet.
            schedulePeriodicWork(context)
        }
    }

    private fun schedulePeriodicWork(context: Context) {
        val workRequest = PeriodicWorkRequestBuilder<CallLogWorker>(15, TimeUnit.MINUTES)
            .build()

        WorkManager.getInstance(context).enqueueUniquePeriodicWork(
            "calllog_sync",
            ExistingPeriodicWorkPolicy.KEEP,
            workRequest
        )
        Log.d("callloghelper", "BootReceiver: periodic fallback worker ensured")
    }
}