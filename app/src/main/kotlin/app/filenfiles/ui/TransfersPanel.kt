package app.filenfiles.ui

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.filenfiles.transfer.TransferManager.State

/** Upload progress / failures, shown above the bottom bar on every screen. */
@Composable
fun TransfersPanel(env: AppEnv, padForNavBar: Boolean) {
    val m = env.app.transfers
    val all by m.items.collectAsState()
    if (all.isEmpty()) return
    val active = all.filter { it.active }
    val failed = all.filter { it.state == State.FAILED }
    val done = all.count { it.state == State.DONE }
    Surface(tonalElevation = 3.dp, modifier = Modifier.fillMaxWidth().then(if (padForNavBar) Modifier.navigationBarsPadding() else Modifier)) {
        Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
            if (active.isNotEmpty()) {
                val cur = active.firstOrNull { it.state == State.UPLOADING || it.state == State.WAITING_NETWORK || it.state == State.PREPARING } ?: active.first()
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        val position = all.indexOf(cur).coerceAtLeast(0) + 1
                        Text(
                            when (cur.state) {
                                State.WAITING_NETWORK -> "Waiting for network…"
                                State.PREPARING -> "Preparing ${cur.name}"
                                else -> "${cur.verb} $position of ${all.size}"
                            } + (if (cur.attempt > 1) " (attempt ${cur.attempt})" else ""),
                            style = MaterialTheme.typography.labelLarge,
                        )
                        Text(cur.name, style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                    TextButton({ m.cancelAll() }) { Text("Cancel") }
                }
                if (cur.state == State.UPLOADING && cur.size > 0)
                    LinearProgressIndicator(progress = { (cur.done.toFloat() / cur.size).coerceIn(0f, 1f) }, modifier = Modifier.fillMaxWidth())
                else LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            } else if (failed.isEmpty() && done > 0) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(if (done == 1) "Transfer complete" else "$done transfers complete", Modifier.weight(1f), style = MaterialTheme.typography.labelLarge)
                    IconButton({ m.clearFinished() }) { Icon(Icons.Filled.Close, "Dismiss") }
                }
            }
            failed.forEach { t ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("Couldn't finish ${t.name}", style = MaterialTheme.typography.labelLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(t.error ?: "Upload failed", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error, maxLines = 2)
                    }
                    IconButton({ m.retry(t.id) }) { Icon(Icons.Filled.Refresh, "Retry") }
                    IconButton({ m.dismiss(t.id) }) { Icon(Icons.Filled.Close, "Dismiss") }
                }
            }
        }
    }
}
