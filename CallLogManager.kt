package com.prayag.callloghelper

import android.content.Context
import android.provider.CallLog
import android.util.Log
import android.content.pm.PackageManager
import androidx.core.app.ActivityCompat

object CallLogManager {

    private const val TAG = "CallLogManager"

    // Store the last timestamp read to avoid duplicates
    private var lastReadTimestamp: Long = System.currentTimeMillis()

    fun getNewCallLogs(context: Context): List<Map<String, Any?>> {
        if (ActivityCompat.checkSelfPermission(context, android.Manifest.permission.READ_CALL_LOG)
            != PackageManager.PERMISSION_GRANTED
        ) {
            Log.e(TAG, "READ_CALL_LOG permission not granted")
            return emptyList()
        }

        val projection = arrayOf(
            CallLog.Calls._ID,
            CallLog.Calls.NUMBER,
            CallLog.Calls.TYPE,
            CallLog.Calls.DATE,
            CallLog.Calls.DURATION,
            CallLog.Calls.NEW
        )

        val cursor = context.contentResolver.query(
            CallLog.Calls.CONTENT_URI,
            projection,
            "${CallLog.Calls.DATE} > ?",
            arrayOf(lastReadTimestamp.toString()),
            "${CallLog.Calls.DATE} ASC"
        )

        val newLogs = mutableListOf<Map<String, Any?>>()

        cursor?.use {
            while (it.moveToNext()) {
                val id = it.getLong(0)
                val number = it.getString(1)
                val androidType = it.getInt(2)
                val date = it.getLong(3)
                val duration = it.getLong(4)
                val newFlag = it.getInt(5)

                val type = mapCallType(androidType)
                val status = mapCallStatus(androidType, duration, newFlag)

                newLogs.add(
                    mapOf(
                        "id" to id,
                        "number" to number,
                        "type" to type,
                        "status" to status,
                        "date" to date,
                        "duration" to duration
                    )
                )

                if (date > lastReadTimestamp) lastReadTimestamp = date
            }
        }

        Log.d(TAG, "Fetched ${newLogs.size} new call logs with type/status")
        return newLogs
    }

    private fun mapCallType(androidType: Int): Int {
        return when (androidType) {
            CallLog.Calls.INCOMING_TYPE -> 0 // INCOMING
            CallLog.Calls.OUTGOING_TYPE -> 1 // OUTGOING
            CallLog.Calls.MISSED_TYPE -> 0   // treat as incoming, status = MISSED
            else -> 0
        }
    }

    private fun mapCallStatus(callType: Int, duration: Long, newFlag: Int): Int {
        return when {
            duration > 0 -> 4 // ANSWERED
            callType == CallLog.Calls.OUTGOING_TYPE && duration == 0L -> 2 // FAILED
            callType == CallLog.Calls.MISSED_TYPE -> 8 // MISSED
            else -> 9 // UNKNOWN
        }
    }
}