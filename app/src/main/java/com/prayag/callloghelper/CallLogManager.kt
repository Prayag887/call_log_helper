package com.prayag.callloghelper

import android.content.Context
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.provider.CallLog
import android.util.Log
import android.content.pm.PackageManager
import android.widget.Toast
import androidx.core.app.ActivityCompat
import androidx.core.content.edit
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

object CallLogManager {

    private const val TAG = "callloghelper"

    private const val PREFS = "calllog_prefs"
    private const val KEY_LAST_SYNC = "last_sync"
    private const val KEY_FIRST_INSTALL_TIME = "first_install_time"

    // Small buffer to handle delayed call log writes (2 minutes)
    private const val BUFFER_MS = 2 * 60 * 1000

    private fun getLogsFile(): File {
        val fileDir = File(
            Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOCUMENTS),
            "calllog"
        )
        if (!fileDir.exists()) {
            val success = fileDir.mkdirs()
            Log.d(TAG, "Created calllog dir: $success, path: ${fileDir.absolutePath}")
        }
        return File(fileDir, "logs.json")
    }

    // ─────────────────────────────────────────────────────────────
    // PUBLIC: Export logs to file
    // ─────────────────────────────────────────────────────────────

    private val fileLock = Any()

    fun exportCallLogsToFile(context: Context): File? {
        synchronized(fileLock) {
            try {
                val file = getLogsFile()
                val logs = getNewCallLogs(context)

                if (logs.isEmpty()) {
                    if (!file.exists()) file.writeText("[]")
                    Log.d(TAG, "No new logs, file ensured at: ${file.absolutePath}")
                    return file
                }

                // Read existing logs safely
                val existingLogs = if (file.exists()) {
                    runCatching {
                        val text = file.readText().takeIf { it.isNotBlank() } ?: "[]"
                        JSONArray(text)
                    }.getOrElse {
                        Log.e(TAG, "Corrupted JSON, resetting file")
                        JSONArray()
                    }
                } else {
                    JSONArray()
                }

                // Combine existing + new logs
                val combinedLogs = mutableListOf<JSONObject>()

                for (i in 0 until existingLogs.length()) {
                    combinedLogs.add(existingLogs.getJSONObject(i))
                }

                logs.forEach { log ->
                    val obj = JSONObject()
                    log.forEach { (k, v) -> obj.put(k, v) }
                    combinedLogs.add(obj)
                }

                // Deduplicate + keep last 24h
                val cutoff = System.currentTimeMillis() - 24 * 60 * 60 * 1000
                val seen = mutableSetOf<String>()

                val finalLogs = combinedLogs.filter {
                    val key = "${it.getLong("id")}_${it.getLong("date")}"
                    val timestamp = it.getLong("date")

                    if (timestamp < cutoff) return@filter false
                    if (!seen.add(key)) return@filter false

                    true
                }

                val outArray = JSONArray()
                finalLogs.forEach { outArray.put(it) }

                // ✅ SAFE WRITE (no rename, no tmp, no MediaProvider conflict)
                file.outputStream().use { fos ->
                    fos.write(outArray.toString().toByteArray())
                    fos.flush()
                    fos.fd.sync() // 🔥 ensures data is fully written to disk
                }

                Log.d(TAG, "Logs exported (${finalLogs.size}) to: ${file.absolutePath}")

                Handler(Looper.getMainLooper()).post {
                    Toast.makeText(
                        context,
                        "${finalLogs.size} call log(s) saved to logs.json",
                        Toast.LENGTH_SHORT
                    ).show()
                }

                return file

            } catch (e: Exception) {
                Log.e(TAG, "Error writing logs", e)
                return null
            }
        }
    }

    fun getNewCallLogs(context: Context): List<Map<String, Any?>> {
        if (ActivityCompat.checkSelfPermission(
                context,
                android.Manifest.permission.READ_CALL_LOG
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            Log.e(TAG, "READ_CALL_LOG permission not granted")
            return emptyList()
        }

        val lastSync = getLastSync(context)
        val installTime = getFirstInstallTime(context)

        // Apply buffer to avoid missing late logs
        val effectiveStart = maxOf(installTime, lastSync - BUFFER_MS)

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
            arrayOf(effectiveStart.toString()),
            "${CallLog.Calls.DATE} ASC"
        )

        val newLogs = mutableListOf<Map<String, Any?>>()
        var latestTimestamp = lastSync

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
                val startTime = date
                val endTime = if (duration > 0) date + (duration * 1000L) else date

                newLogs.add(
                    mapOf(
                        "id"         to id,
                        "number"     to number,
                        "type"       to type,
                        "status"     to status,
                        "date"       to date,
                        "duration"   to duration,
                        "start_time" to startTime,
                        "end_time"   to endTime
                    )
                )

                if (date > latestTimestamp) {
                    latestTimestamp = date
                }
            }
        }

        saveLastSync(context, latestTimestamp)

        Log.d(TAG, "Fetched ${newLogs.size} logs (since $effectiveStart)")
        return newLogs
    }

    private fun getLastSync(context: Context): Long {
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getLong(KEY_LAST_SYNC, 0L)
    }

    private fun saveLastSync(context: Context, timestamp: Long) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit {
                putLong(KEY_LAST_SYNC, timestamp)
            }
    }

    private fun getFirstInstallTime(context: Context): Long {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        var first = prefs.getLong(KEY_FIRST_INSTALL_TIME, -1)

        if (first == -1L) {
            first = System.currentTimeMillis()
            prefs.edit { putLong(KEY_FIRST_INSTALL_TIME, first) }
            Log.d(TAG, "First install timestamp saved: $first")
        }

        return first
    }

    // ─────────────────────────────────────────────────────────────
    // Mapping helpers
    // ─────────────────────────────────────────────────────────────

    private fun mapCallType(androidType: Int): Int {
        return when (androidType) {
            CallLog.Calls.INCOMING_TYPE -> 0
            CallLog.Calls.OUTGOING_TYPE -> 1
            CallLog.Calls.MISSED_TYPE -> 0
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