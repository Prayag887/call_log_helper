// MainActivity.kt
package com.prayag.callloghelper

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.PowerManager
import android.provider.Settings
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.app.ActivityCompat
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import java.util.concurrent.TimeUnit

class MainActivity : ComponentActivity() {

    companion object {
        const val TAG = "callloghelper"
        private const val STEP_NONE                = 0
        private const val STEP_STORAGE             = 1
        private const val STEP_RUNTIME_PERMISSIONS = 2
        private const val STEP_BATTERY_OPT         = 3
        private const val STEP_DONE                = 4
    }

    // ─────────────────────────────────────────────────────────────
    // Step 1: MANAGE_EXTERNAL_STORAGE settings screen (Android 11+)
    // Using a result launcher so we know exactly when the user returns —
    // avoids calling initApp() from onResume() and triggering double prompts.
    // ─────────────────────────────────────────────────────────────
    private val storageSettingsLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
            // User has returned from the "All files access" settings screen.
            // Proceed to the next step regardless of what they chose;
            // checkStoragePermission() will re-evaluate and redirect if needed.
            proceedFromStep(STEP_STORAGE)
        }

    // ─────────────────────────────────────────────────────────────
    // Step 2: Runtime permissions (READ_CALL_LOG, READ_PHONE_STATE, POST_NOTIFICATIONS)
    // ─────────────────────────────────────────────────────────────
    private val runtimePermissionsLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { results ->
            val allGranted = results.values.all { it }
            if (allGranted) {
                Log.d(TAG, "Runtime permissions granted")
            } else {
                Log.e(TAG, "Some permissions denied: $results")
            }
            // Always move forward; missing permissions are silently handled downstream.
            proceedFromStep(STEP_RUNTIME_PERMISSIONS)
        }

    // ─────────────────────────────────────────────────────────────
    // Step 3: Battery optimization exemption settings screen
    // ─────────────────────────────────────────────────────────────
    private val batteryOptLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
            // User returned from the battery optimization settings screen.
            proceedFromStep(STEP_BATTERY_OPT)
        }

    // Track which step currently has a UI shown so we never launch two at once.
    private var currentStep = STEP_NONE

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Only start the flow on fresh creation, not on config change.
        if (savedInstanceState == null) {
            startPermissionFlow()
        }
    }

    // ─────────────────────────────────────────────────────────────
    // Permission flow — called sequentially, one step at a time.
    // Each launcher callback calls proceedFromStep() to advance.
    // ─────────────────────────────────────────────────────────────

    private fun startPermissionFlow() {
        currentStep = STEP_NONE
        proceedFromStep(STEP_NONE)
    }

    /**
     * Called after [fromStep] completes. Evaluates which step is needed next
     * and launches it, or calls [onAllPermissionsReady] if everything is done.
     */
    private fun proceedFromStep(fromStep: Int) {
        // ── Step 1: All-files access (Android 11+) ──────────────────────────
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && !Environment.isExternalStorageManager()) {
            if (currentStep == STEP_STORAGE) return  // already waiting for result
            Log.d(TAG, "→ Requesting MANAGE_EXTERNAL_STORAGE")
            currentStep = STEP_STORAGE
            storageSettingsLauncher.launch(
                Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION).apply {
                    data = Uri.fromParts("package", packageName, null)
                }
            )
            return
        }

        // ── Step 2: Runtime permissions ─────────────────────────────────────
        val runtimePerms = buildList {
            add(Manifest.permission.READ_CALL_LOG)
            add(Manifest.permission.READ_PHONE_STATE)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                add(Manifest.permission.POST_NOTIFICATIONS)
            }
        }
        val missing = runtimePerms.filter {
            ActivityCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }
        if (missing.isNotEmpty()) {
            if (currentStep == STEP_RUNTIME_PERMISSIONS) return  // already waiting for result
            Log.d(TAG, "→ Requesting runtime permissions: $missing")
            currentStep = STEP_RUNTIME_PERMISSIONS
            runtimePermissionsLauncher.launch(missing.toTypedArray())
            return
        }

        // ── Step 3: Battery optimization exemption ───────────────────────────
        val pm = getSystemService(POWER_SERVICE) as PowerManager
        if (!pm.isIgnoringBatteryOptimizations(packageName)) {
            if (currentStep == STEP_BATTERY_OPT) return  // already waiting for result
            Log.d(TAG, "→ Requesting battery optimization exemption")
            currentStep = STEP_BATTERY_OPT
            batteryOptLauncher.launch(
                Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                    data = Uri.fromParts("package", packageName, null)
                }
            )
            return
        }

        // ── All steps done ───────────────────────────────────────────────────
        currentStep = STEP_DONE
        onAllPermissionsReady()
    }

    private fun onAllPermissionsReady() {
        Log.d(TAG, "All permissions ready — exporting logs and starting service")

        // Write once immediately on setup
        Thread {
            CallLogManager.exportCallLogsToFile(applicationContext).also {
                Log.d(TAG, "Initial export: ${it?.absolutePath}")
            }
        }.start()

        // Start the persistent foreground service
        val intent = Intent(this, CallStateService::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(intent)
        } else {
            startService(intent)
        }
        Log.d(TAG, "CallStateService start requested")

        // WorkManager periodic fallback (15 min safety net)
        val workRequest = PeriodicWorkRequestBuilder<CallLogWorker>(15, TimeUnit.MINUTES).build()
        WorkManager.getInstance(this).enqueueUniquePeriodicWork(
            "calllog_sync",
            ExistingPeriodicWorkPolicy.KEEP,
            workRequest
        )
        Log.d(TAG, "Periodic fallback worker scheduled")
    }

}