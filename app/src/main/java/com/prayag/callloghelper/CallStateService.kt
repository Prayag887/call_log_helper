// CallStateService.kt
package com.prayag.callloghelper

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.os.Build
import android.os.IBinder
import android.telephony.PhoneStateListener
import android.telephony.TelephonyCallback
import android.telephony.TelephonyManager
import android.util.Log
import androidx.annotation.RequiresApi
import androidx.core.app.NotificationCompat

class CallStateService : Service() {

    companion object {
        const val TAG = "callloghelper"
        private const val CHANNEL_ID = "calllog_service_channel"
        private const val NOTIFICATION_ID = 1001
    }

    private lateinit var telephonyManager: TelephonyManager

    // For API < 31
    private var legacyListener: PhoneStateListener? = null

    // For API >= 31
    private var modernCallback: TelephonyCallback? = null

    // Track previous state to detect call-end transitions
    private var previousState = TelephonyManager.CALL_STATE_IDLE

    override fun onCreate() {
        super.onCreate()
        telephonyManager = getSystemService(TELEPHONY_SERVICE) as TelephonyManager
        startForeground(NOTIFICATION_ID, buildNotification())
        registerPhoneStateListener()
        Log.d(TAG, "CallStateService started")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // Restart if killed by system
        return START_STICKY
    }

    override fun onDestroy() {
        super.onDestroy()
        unregisterPhoneStateListener()
        Log.d(TAG, "CallStateService destroyed")
    }

    override fun onBind(intent: Intent?): IBinder? = null

    // ─────────────────────────────────────────────────────────────
    // Phone state registration
    // ─────────────────────────────────────────────────────────────

    private fun registerPhoneStateListener() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            registerModernCallback()
        } else {
            registerLegacyListener()
        }
    }

    private fun unregisterPhoneStateListener() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            modernCallback?.let { telephonyManager.unregisterTelephonyCallback(it) }
        } else {
            @Suppress("DEPRECATION")
            legacyListener?.let { telephonyManager.listen(it, PhoneStateListener.LISTEN_NONE) }
        }
    }

    @RequiresApi(Build.VERSION_CODES.S)
    private fun registerModernCallback() {
        val callback = object : TelephonyCallback(), TelephonyCallback.CallStateListener {
            override fun onCallStateChanged(state: Int) {
                handleStateChange(state)
            }
        }
        telephonyManager.registerTelephonyCallback(mainExecutor, callback)
        modernCallback = callback
    }

    @Suppress("DEPRECATION")
    private fun registerLegacyListener() {
        val listener = object : PhoneStateListener() {
            override fun onCallStateChanged(state: Int, phoneNumber: String?) {
                handleStateChange(state)
            }
        }
        telephonyManager.listen(listener, PhoneStateListener.LISTEN_CALL_STATE)
        legacyListener = listener
    }

    // ─────────────────────────────────────────────────────────────
    // State change handler
    // ─────────────────────────────────────────────────────────────

    private fun handleStateChange(newState: Int) {
        val stateLabel = when (newState) {
            TelephonyManager.CALL_STATE_IDLE -> "IDLE"
            TelephonyManager.CALL_STATE_RINGING -> "RINGING"
            TelephonyManager.CALL_STATE_OFFHOOK -> "OFFHOOK"
            else -> "UNKNOWN"
        }
        Log.d(TAG, "Call state changed: $stateLabel (prev: $previousState)")

        // A call just ended: was active/ringing, now idle
        val callJustEnded = (previousState == TelephonyManager.CALL_STATE_OFFHOOK ||
                previousState == TelephonyManager.CALL_STATE_RINGING) &&
                newState == TelephonyManager.CALL_STATE_IDLE

        if (callJustEnded) {
            Log.d(TAG, "Call ended — exporting logs")
            // Small delay: call log provider may take a moment to persist the record
            Thread {
                Thread.sleep(2000)
                val file = CallLogManager.exportCallLogsToFile(applicationContext)
                Log.d(TAG, "Logs written after call end: ${file?.absolutePath}")
            }.start()
        }

        previousState = newState
    }

    // ─────────────────────────────────────────────────────────────
    // Foreground notification
    // ─────────────────────────────────────────────────────────────

    private fun buildNotification(): Notification {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Call Log Service",
                NotificationManager.IMPORTANCE_MIN   // silent, no sound
            ).apply {
                description = "Monitors call state to export logs"
                setShowBadge(false)
            }
            getSystemService(NotificationManager::class.java)
                .createNotificationChannel(channel)
        }

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Call Log Helper")
            .setContentText("Monitoring calls in background")
            .setSmallIcon(android.R.drawable.sym_call_incoming)
            .setPriority(NotificationCompat.PRIORITY_MIN)
            .setOngoing(true)
            .build()
    }
}