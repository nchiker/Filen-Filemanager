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
import java.util.concurrent.TimeUnit

/**
 * Keeps the offline copies in line with the pins (see PinManager): a one-time run right after the user pins or unpins,
 * and a periodic run (unmetered network, battery not low) for changes made elsewhere. The state file records what has
 * arrived, so a stopped run simply continues next time.
 */
class PinSyncWorker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {
    override suspend fun getForegroundInfo(): ForegroundInfo {
        val nm = applicationContext.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CHANNEL, "Offline files", NotificationManager.IMPORTANCE_LOW))
        val n = NotificationCompat.Builder(applicationContext, CHANNEL).setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle("Filen Files").setContentText("Syncing offline files…").setOngoing(true).setOnlyAlertOnce(true).build()
        return ForegroundInfo(ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
    }

    override suspend fun doWork(): Result {
        val app = applicationContext as FilesApp
        withTimeoutOrNull(30_000) { app.session.state.first { it !is FilenSession.State.Unknown } }
        if (app.session.state.value !is FilenSession.State.LoggedIn) return Result.success()
        runCatching { setForeground(getForegroundInfo()) } // refused when started from the background: still runs, just less protected
        return try {
            if (app.filen.sync() > 0 && runAttemptCount < 3) Result.retry() else Result.success()
        } catch (e: kotlinx.coroutines.CancellationException) { throw e
        } catch (e: Exception) { if (runAttemptCount < 3) Result.retry() else Result.success() }
    }

    companion object {
        private const val CHANNEL = "offline"
        private const val ID = 45

        /** Right now (the user just pinned/unpinned or tapped Sync now). */
        fun syncNow(ctx: Context) {
            val req = OneTimeWorkRequestBuilder<PinSyncWorker>()
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .build()
            // REPLACE would cancel a running download; APPEND_OR_REPLACE runs a second pass afterwards so the latest pins are seen.
            WorkManager.getInstance(ctx).enqueueUniqueWork("pins-sync-now", ExistingWorkPolicy.APPEND_OR_REPLACE, req)
        }

        /** Every 12 hours on an unmetered network; safe to call at every start. */
        fun schedule(ctx: Context) {
            val req = PeriodicWorkRequestBuilder<PinSyncWorker>(12, TimeUnit.HOURS)
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.UNMETERED).setRequiresBatteryNotLow(true).build())
                .build()
            WorkManager.getInstance(ctx).enqueueUniquePeriodicWork("pins-sync-periodic", ExistingPeriodicWorkPolicy.KEEP, req)
        }

        fun cancelAll(ctx: Context) {
            WorkManager.getInstance(ctx).cancelUniqueWork("pins-sync-now"); WorkManager.getInstance(ctx).cancelUniqueWork("pins-sync-periodic")
        }
    }
}
