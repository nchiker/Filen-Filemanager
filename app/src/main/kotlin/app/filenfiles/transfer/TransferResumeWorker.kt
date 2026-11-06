package app.filenfiles.transfer

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.pm.ServiceInfo
import androidx.core.app.NotificationCompat
import androidx.work.*
import app.filenfiles.FilesApp
import app.filenfiles.filen.FilenSession
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Safety net for uploads that other apps hand over through the documents provider: when Android refuses to start the
 * foreground service from the background, this expedited job runs the persisted queue until it is empty. If the system
 * stops it early (quota, timeout) WorkManager runs it again; the queue itself is persisted, so nothing is lost.
 */
class TransferResumeWorker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {
    override suspend fun getForegroundInfo(): ForegroundInfo {
        val nm = applicationContext.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CHANNEL, "Transfers", NotificationManager.IMPORTANCE_LOW))
        val n = NotificationCompat.Builder(applicationContext, CHANNEL).setSmallIcon(android.R.drawable.stat_sys_upload)
            .setContentTitle("Filen Files").setContentText("Finishing uploads…").setOngoing(true).setOnlyAlertOnce(true).build()
        return ForegroundInfo(ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
    }

    override suspend fun doWork(): Result {
        val app = applicationContext as FilesApp
        withTimeoutOrNull(30_000) { app.session.state.first { it !is FilenSession.State.Unknown } }
        if (app.session.state.value !is FilenSession.State.LoggedIn) return Result.success() // signed out: nothing to do
        app.transfers.resume()
        app.transfers.items.first { list -> list.none { it.active } } // suspends until everything finished or failed
        return Result.success()
    }

    companion object {
        private const val CHANNEL = "transfers"
        private const val ID = 44

        fun enqueue(ctx: Context) {
            val req = OneTimeWorkRequestBuilder<TransferResumeWorker>()
                .setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .build()
            // KEEP: while one is running or queued another start attempt must not pile up more.
            WorkManager.getInstance(ctx).enqueueUniqueWork("transfers-resume", ExistingWorkPolicy.KEEP, req)
        }
    }
}
