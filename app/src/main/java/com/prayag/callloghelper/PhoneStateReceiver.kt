package com.prayag.callloghelper

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.telephony.TelephonyManager
import android.util.Log

/**
 * Receives android.intent.action.PHONE_STATE broadcasts from the system.
 *
 * This fires even if the app has never been opened — no foreground service or
 * activity is required. When a call ends (state transitions to IDLE after being
 * OFFHOOK or RINGING), we export the call logs immediately on a background thread.
 *
 * goAsync() is used so the system doesn't reclaim the process before our
 * background thread finishes writing the file.
 */
class PhoneStateReceiver : BroadcastReceiver() {

    companion object {
        private const val TAG = "callloghelper"

        // Persist previous state across multiple broadcasts within the same process.
        // Receivers are stateless objects (re-instantiated each time), so we use
        // a companion object field — good enough because the OS keeps the process
        // alive long enough between consecutive call-state broadcasts.
        @Volatile
        private var previousState = TelephonyManager.CALL_STATE_IDLE
    }

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != TelephonyManager.ACTION_PHONE_STATE_CHANGED) return

        val stateStr = intent.getStringExtra(TelephonyManager.EXTRA_STATE) ?: return
        val newState = when (stateStr) {
            TelephonyManager.EXTRA_STATE_IDLE    -> TelephonyManager.CALL_STATE_IDLE
            TelephonyManager.EXTRA_STATE_RINGING -> TelephonyManager.CALL_STATE_RINGING
            TelephonyManager.EXTRA_STATE_OFFHOOK -> TelephonyManager.CALL_STATE_OFFHOOK
            else -> return
        }

        Log.d(TAG, "PhoneStateReceiver: state=$stateStr (prev=$previousState)")

        val callJustEnded =
            (previousState == TelephonyManager.CALL_STATE_OFFHOOK ||
             previousState == TelephonyManager.CALL_STATE_RINGING) &&
            newState == TelephonyManager.CALL_STATE_IDLE

        previousState = newState

        if (!callJustEnded) return

        Log.d(TAG, "PhoneStateReceiver: call ended — scheduling log export")

        // Keep the BroadcastReceiver alive while we do async work.
        val pendingResult = goAsync()

        Thread {
            try {
                // Small delay: the call log ContentProvider may take a moment
                // to persist the new record after the call ends.
                Thread.sleep(2_000)
                val file = CallLogManager.exportCallLogsToFile(context.applicationContext)
                Log.d(TAG, "PhoneStateReceiver: logs written → ${file?.absolutePath}")
            } catch (e: Exception) {
                Log.e(TAG, "PhoneStateReceiver: error writing logs", e)
            } finally {
                pendingResult.finish()
            }
        }.start()
    }
}
