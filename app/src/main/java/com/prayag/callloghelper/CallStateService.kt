// CallStateService.kt
package com.prayag.callloghelper

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
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
        // Delay after call ends before reading the log — provider needs time to persist
        private const val POST_CALL_DELAY_MS = 2_000L
    }

    private lateinit var telephonyManager: TelephonyManager
    private val mainHandler = Handler(Looper.getMainLooper())

    private var legacyListener: PhoneStateListener? = null
    private var modernCallback: TelephonyCallback? = null

    private var previousState = TelephonyManager.CALL_STATE_IDLE

    override fun onCreate() {
        super.onCreate()
        telephonyManager = getSystemService(TELEPHONY_SERVICE) as TelephonyManager
        startForeground(NOTIFICATION_ID, buildNotification())
        registerPhoneStateListener()
        Log.d(TAG, "CallStateService started")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        return START_STICKY
    }

    override fun onDestroy() {
        super.onDestroy()
        mainHandler.removeCallbacksAndMessages(null)
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
    // State change handler — writes log on every meaningful transition
    // ─────────────────────────────────────────────────────────────

    private fun handleStateChange(newState: Int) {
        val stateLabel = when (newState) {
            TelephonyManager.CALL_STATE_IDLE     -> "IDLE"
            TelephonyManager.CALL_STATE_RINGING  -> "RINGING"
            TelephonyManager.CALL_STATE_OFFHOOK  -> "OFFHOOK"
            else                                 -> "UNKNOWN"
        }
        Log.d(TAG, "Call state: $stateLabel (prev: $previousState)")

        when {
            // Incoming call is ringing — log it immediately so missed calls are captured
            // even if the user never picks up
            newState == TelephonyManager.CALL_STATE_RINGING -> {
                Log.d(TAG, "Incoming call ringing — writing log")
                exportAsync(delayMs = 0)
            }

            // Call was answered (incoming or outgoing)
            newState == TelephonyManager.CALL_STATE_OFFHOOK &&
                    previousState != TelephonyManager.CALL_STATE_OFFHOOK -> {
                Log.d(TAG, "Call answered/started — writing log")
                exportAsync(delayMs = 0)
            }

            // Call just ended — delay slightly so the provider can persist the record
            newState == TelephonyManager.CALL_STATE_IDLE &&
                    (previousState == TelephonyManager.CALL_STATE_OFFHOOK ||
                            previousState == TelephonyManager.CALL_STATE_RINGING) -> {
                Log.d(TAG, "Call ended — writing log after ${POST_CALL_DELAY_MS}ms delay")
                exportAsync(delayMs = POST_CALL_DELAY_MS)
            }
        }

        previousState = newState
    }

    /**
     * Runs [CallLogManager.exportCallLogsToFile] on a background thread after [delayMs].
     * Uses Handler so the delay is cancellable and doesn't leak threads.
     */
    private fun exportAsync(delayMs: Long) {
        mainHandler.postDelayed({
            Thread {
                val file = CallLogManager.exportCallLogsToFile(applicationContext)
                Log.d(TAG, "Export complete: ${file?.absolutePath}")
            }.start()
        }, delayMs)
    }

    // ─────────────────────────────────────────────────────────────
    // Foreground notification
    // ─────────────────────────────────────────────────────────────

    private fun buildNotification(): Notification {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Call Log Service",
                NotificationManager.IMPORTANCE_MIN
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