package app.filenfiles.ui

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import app.filenfiles.core.FileItem
import app.filenfiles.core.Origin

enum class Batch { COPY, MOVE, DELETE, STAR, SAVE, SHARE_WITH, COMPRESS }

/** Replaces the screen's top bar while items are selected. Actions that don't apply to the whole selection are hidden. */
@Composable
fun SelectionBar(env: AppEnv, modifier: Modifier = Modifier, onBatch: (Batch, List<FileItem>) -> Unit) {
    val sel = env.actions.selection
    val items = sel.items.values.toList()
    val router = env.app.router
    val canModify = items.all { router.modifier(it) != null }
    val canShare = items.all { router.sharing()?.canShare(it) == true }
    val canSave = items.any { it.origin != Origin.LOCAL }
    var more by remember { mutableStateOf(false) }
    Surface(modifier.fillMaxWidth(), tonalElevation = 6.dp, color = MaterialTheme.colorScheme.secondaryContainer) {
        Row(Modifier.statusBarsPadding().height(56.dp).padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton({ sel.clear() }) { Icon(Icons.Filled.Close, "Clear selection") }
            Text("${items.size} selected", Modifier.weight(1f), style = MaterialTheme.typography.titleMedium, maxLines = 1, softWrap = false)
            IconButton({ onBatch(Batch.COPY, items) }) { Icon(Icons.Filled.ContentCopy, "Copy to…") }
            if (canModify) IconButton({ onBatch(Batch.MOVE, items) }) { Icon(Icons.Filled.DriveFileMove, "Move to…") }
            if (canModify) IconButton({ onBatch(Batch.DELETE, items) }) { Icon(Icons.Filled.Delete, "Delete") }
            Box {
                IconButton({ more = true }) { Icon(Icons.Filled.MoreVert, "More") }
                DropdownMenu(more, { more = false }) {
                    if (sel.visible.size > items.size) DropdownMenuItem({ Text("Select all") }, { more = false; sel.selectAll() })
                    DropdownMenuItem({ Text("Add to or remove from favorites") }, { more = false; onBatch(Batch.STAR, items) })
                    DropdownMenuItem({ Text("Compress to zip…") }, { more = false; onBatch(Batch.COMPRESS, items) })
                    if (canSave) DropdownMenuItem({ Text("Save to device") }, { more = false; onBatch(Batch.SAVE, items) })
                    if (canShare) DropdownMenuItem({ Text("Share with a Filen contact…") }, { more = false; onBatch(Batch.SHARE_WITH, items) })
                }
            }
        }
    }
}
