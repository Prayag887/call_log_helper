package com.prayag.callloghelper

import android.database.MatrixCursor

class LogsCursor(private val logs: List<Map<String, Any?>>) : MatrixCursor(
    arrayOf("id", "number", "type", "status", "date", "duration")
) {
    init {
        for (log in logs) {
            addRow(
                arrayOf(
                    log["id"],
                    log["number"],
                    log["type"],
                    log["status"],
                    log["date"],
                    log["duration"]
                )
            )
        }
    }
}