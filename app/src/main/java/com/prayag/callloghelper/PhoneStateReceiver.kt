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
 * activity is required. We trigger a log export on every IDLE broadcast, since
 * all calls (missed, answered, outgoing) end with IDLE. This makes the receiver
 * completely stateless, which avoids the process-death bug where previousState
 * would reset to IDLE between broadcasts, causing the transition check to fail.
 *
 * CallLogManager deduplicates and only queries from the last known sync timestamp,
 * so triggering on every IDLE is safe and lightweight.
 *
 * goAsync() is used so the system doesn't reclaim the process before our
 * background thread finishes writing the file.
 */
class PhoneStateReceiver : BroadcastReceiver() {

    companion object {
        private const val TAG = "callloghelper"
    }

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != TelephonyManager.ACTION_PHONE_STATE_CHANGED) return

        val stateStr = intent.getStringExtra(TelephonyManager.EXTRA_STATE) ?: return

        Log.d(TAG, "PhoneStateReceiver: state=$stateStr")

        if (stateStr != TelephonyManager.EXTRA_STATE_IDLE) return

        Log.d(TAG, "PhoneStateReceiver: IDLE detected — scheduling log export")

        // Keep the BroadcastReceiver alive while we do async work.
        val pendingResult = goAsync()

        Thread {
            try {
                // Delay: the call log ContentProvider may take a moment to persist
                // the new record after the call ends.
                Thread.sleep(3_000)
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