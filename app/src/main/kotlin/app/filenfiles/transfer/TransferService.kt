package app.filenfiles.transfer

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder
import androidx.core.app.NotificationCompat
import app.filenfiles.FilesApp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch

/** Keeps the process alive (and the user informed) while uploads are pending. */
class TransferService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CHANNEL, "Transfers", NotificationManager.IMPORTANCE_LOW))
        startForeground(ID, build("Preparing…", 0, 0), ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        val manager = (application as FilesApp).transfers
        scope.launch {
            manager.items.collect { list ->
                val active = list.filter { it.active }
                if (active.isEmpty()) { stopSelf(); return@collect }
                val cur = active.firstOrNull { it.state == TransferManager.State.UPLOADING } ?: active.first()
                val text = when (cur.state) {
                    TransferManager.State.WAITING_NETWORK -> "Waiting for network… ${cur.name}"
                    else -> "Uploading ${cur.name}" + if (active.size > 1) " (+${active.size - 1} more)" else ""
                }
                nm.notify(ID, build(text, cur.done, cur.size))
            }
        }
        return START_NOT_STICKY // a killed process loses its in-memory queue; don't restart into an empty one
    }

    private fun build(text: String, done: Long, total: Long): Notification {
        val b = NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(android.R.drawable.stat_sys_upload).setContentTitle("Filen Files")
            .setContentText(text).setOngoing(true).setOnlyAlertOnce(true)
        if (total > 0) b.setProgress(100, (done * 100 / total).toInt().coerceIn(0, 100), false) else b.setProgress(0, 0, true)
        return b.build()
    }

    override fun onDestroy() { scope.cancel(); super.onDestroy() }

    private companion object { const val CHANNEL = "transfers"; const val ID = 42 }
}
