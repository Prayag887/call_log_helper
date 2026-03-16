package com.prayag.callloghelper

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.app.ActivityCompat

class MainActivity : ComponentActivity() {

    companion object {
        const val TAG = "CallLogHelper"
    }

    // Register the permission launcher BEFORE onCreate
    private val requestPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { isGranted ->
            if (isGranted) {
                Log.d(TAG, "Permission granted by user")
                val logs = CallLogManager.getNewCallLogs(this)
                Log.d(TAG, "Initial call logs: $logs")
            } else {
                Log.e(TAG, "Permission denied")
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.READ_CALL_LOG)
            != PackageManager.PERMISSION_GRANTED
        ) {
            // Launch the permission request
            requestPermissionLauncher.launch(Manifest.permission.READ_CALL_LOG)
        } else {
            Log.d(TAG, "Permission already granted")
            val logs = CallLogManager.getNewCallLogs(this)
            Log.d(TAG, "Initial call logs: $logs")
        }
    }
}