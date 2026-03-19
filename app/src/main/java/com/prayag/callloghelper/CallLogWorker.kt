package com.prayag.callloghelper

import android.content.Context
import androidx.work.Worker
import androidx.work.WorkerParameters

class CallLogWorker(
    context: Context,
    workerParams: WorkerParameters
) : Worker(context, workerParams) {
    override fun doWork(): Result {
        CallLogManager.exportCallLogsToFile(applicationContext)
        return Result.success()
    }
}