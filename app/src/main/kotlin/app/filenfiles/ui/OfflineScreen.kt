package app.filenfiles.ui

import android.text.format.Formatter
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.filenfiles.core.FileItem
import app.filenfiles.core.ItemId
import app.filenfiles.core.Origin
import app.filenfiles.transfer.PinSyncWorker
import kotlinx.coroutines.launch

/**
 * Everything that is on the phone for offline use. Built only from the pin store, so it works with no network
 * (and is the way in after a cold start offline, since folder listings are only kept in memory).
 */
@Composable
fun OfflineScreen(env: AppEnv) {
    val pins = env.app.router.pinStorage()
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val rev by (pins?.revision ?: remember { kotlinx.coroutines.flow.MutableStateFlow(0) }).collectAsState()
    val syncing by (pins?.syncing ?: remember { kotlinx.coroutines.flow.MutableStateFlow(false) }).collectAsState()
    val files = remember(rev) { pins?.offline().orEmpty().sortedBy { it.name.lowercase() } }
    val folders = remember(rev) { pins?.pinnedFolders().orEmpty() }
    val total = files.sumOf { it.size }

    Column(Modifier.fillMaxSize()) {
        AppBar("Available offline", onBack = { env.nav.popBackStack() }, actions = {
            TextButton({ PinSyncWorker.syncNow(ctx) }, enabled = !syncing) { Text(if (syncing) "Syncing…" else "Sync now") }
        })
        if (syncing) LinearProgressIndicator(Modifier.fillMaxWidth())
        if (pins == null || (files.isEmpty() && folders.isEmpty())) {
            EmptyState("Nothing is available offline yet. Use “Make available offline” on a file or folder.")
            return@Column
        }
        Text("${files.size} ${if (files.size == 1) "file" else "files"} · ${Formatter.formatFileSize(ctx, total)} on this phone",
            Modifier.padding(horizontal = 16.dp, vertical = 8.dp), style = MaterialTheme.typography.bodySmall)
        pins.lastError?.let { Text("Some files could not be downloaded: $it", Modifier.padding(horizontal = 16.dp), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
        LazyColumn(Modifier.fillMaxSize()) {
            if (folders.isNotEmpty()) item { SectionTitle("Pinned folders") }
            items(folders, key = { "d" + it.uuid }) { f ->
                ListItem(
                    leadingContent = { Icon(Icons.Filled.Folder, null) },
                    headlineContent = { Text(f.name, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                    supportingContent = { Text("Stays in step with Filen") },
                    trailingContent = {
                        IconButton({
                            scope.launch {
                                pins.unpin(listOf(FileItem(ItemId("filen:${f.uuid}"), f.name, true, 0, 0, null, Origin.FILEN_PERSONAL)))
                                PinSyncWorker.syncNow(ctx)
                            }
                        }) { Icon(Icons.Filled.Close, "Stop keeping offline") }
                    },
                )
            }
            if (files.isNotEmpty()) item { SectionTitle("Files") }
            items(files, key = { "f" + it.key }) { f ->
                val item = FileItem(ItemId("filen:${f.version}"), f.name, false, f.size, f.modified, f.mime, Origin.FILEN_PERSONAL)
                ListItem(
                    leadingContent = { ItemThumb(item, 44.dp) },
                    headlineContent = { Text(f.name, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                    supportingContent = { Text(Formatter.formatFileSize(ctx, f.size)) },
                    modifier = Modifier.clickable { env.actions.open(item) },
                )
            }
        }
    }
}
