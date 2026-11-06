package app.filenfiles.backup

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.pm.ServiceInfo
import androidx.core.app.NotificationCompat
import androidx.work.CoroutineWorker
import androidx.work.ForegroundInfo
import androidx.work.WorkerParameters
import app.filenfiles.FilesApp

class BackupWorker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {
    override suspend fun doWork(): Result {
        val app = applicationContext as FilesApp
        val nm = applicationContext.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CHANNEL, "Camera backup", NotificationManager.IMPORTANCE_LOW))
        fun info(text: String, done: Int, total: Int) = ForegroundInfo(
            ID,
            NotificationCompat.Builder(applicationContext, CHANNEL).setSmallIcon(android.R.drawable.stat_sys_upload)
                .setContentTitle("Backing up photos").setContentText(text).setOngoing(true).setOnlyAlertOnce(true)
                .apply { if (total > 0) setProgress(total, done, false) else setProgress(0, 0, true) }.build(),
            ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC,
        )
        var started = false
        return when (CameraBackup.run(app) { done, total ->
            // Only become a foreground job once there is actual work, so an idle check doesn't flash a notification.
            runCatching { setForeground(info("$done of $total", done, total)) }
            started = true
        }) {
            CameraBackup.Outcome.DONE -> Result.success()
            CameraBackup.Outcome.RETRY -> Result.retry()
        }.also { if (started) app.prefs.backupLastRun = System.currentTimeMillis() }
    }

    private companion object { const val CHANNEL = "backup"; const val ID = 43 }
}
