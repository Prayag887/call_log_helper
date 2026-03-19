// MainActivity.kt
package com.prayag.callloghelper

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.Settings
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.app.ActivityCompat
import androidx.work.*
import java.util.concurrent.TimeUnit

class MainActivity : ComponentActivity() {

    companion object {
        const val TAG = "callloghelper"
    }

    // Permissions needed upfront
    private val permissions = buildList {
        add(Manifest.permission.READ_CALL_LOG)
        add(Manifest.permission.READ_PHONE_STATE)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            add(Manifest.permission.POST_NOTIFICATIONS) // foreground service notification
        }
    }.toTypedArray()

    private val requestPermissionsLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { results ->
            val allGranted = results.values.all { it }
            if (allGranted) {
                Log.d(TAG, "All permissions granted")
                onPermissionsReady()
            } else {
                Log.e(TAG, "Some permissions denied: $results")
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        initApp()
    }

    override fun onResume() {
        super.onResume()
        initApp()
    }

    private fun initApp() {
        // Step 1: MANAGE_EXTERNAL_STORAGE for Android 11+
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && !Environment.isExternalStorageManager()) {
            Log.d(TAG, "Requesting MANAGE_EXTERNAL_STORAGE")
            startActivity(
                Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION).apply {
                    data = Uri.fromParts("package", packageName, null)
                }
            )
            return
        }

        // Step 2: Runtime permissions
        val missing = permissions.filter {
            ActivityCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }
        if (missing.isNotEmpty()) {
            requestPermissionsLauncher.launch(missing.toTypedArray())
            return
        }

        onPermissionsReady()
    }

    private fun onPermissionsReady() {
        // Write once immediately
        CallLogManager.exportCallLogsToFile(this).also {
            Log.d(TAG, "Initial export: ${it?.absolutePath}")
        }

        // Start the persistent foreground service
        startCallStateService()

        // Keep WorkManager as a safety-net fallback (e.g. service restart lag)
        schedulePeriodicWork()
    }

    private fun startCallStateService() {
        val intent = Intent(this, CallStateService::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(intent)
        } else {
            startService(intent)
        }
        Log.d(TAG, "CallStateService start requested")
    }

    private fun schedulePeriodicWork() {
        val workRequest = PeriodicWorkRequestBuilder<CallLogWorker>(15, TimeUnit.MINUTES)
            .setConstraints(
                Constraints.Builder()
                    .setRequiresBatteryNotLow(true)
                    .build()
            )
            .build()

        WorkManager.getInstance(this).enqueueUniquePeriodicWork(
            "calllog_sync",
            ExistingPeriodicWorkPolicy.KEEP,
            workRequest
        )
        Log.d(TAG, "Periodic fallback worker scheduled")
    }
}