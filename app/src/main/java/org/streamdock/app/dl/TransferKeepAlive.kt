package org.streamdock.app.dl

import android.content.Context
import androidx.work.*
import java.util.concurrent.TimeUnit

/** If Android kills the process while downloads are unfinished, this periodic check brings them back
 *  (the queue lives on disk, so nothing is lost). Minimum interval Android allows is 15 minutes. */
class TransferKeepAlive(context: Context, params: WorkerParameters) : Worker(context, params) {
    override fun doWork(): Result {
        val ctx = applicationContext
        DownloadCenter.init(ctx)
        if (DownloadCenter.hasActive()) TransferService.start(ctx)
        return Result.success()
    }
    companion object {
        fun schedule(context: Context) {
            val request = PeriodicWorkRequestBuilder<TransferKeepAlive>(15, TimeUnit.MINUTES)
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .build()
            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                "download-keepalive", ExistingPeriodicWorkPolicy.KEEP, request)
        }
    }
}
