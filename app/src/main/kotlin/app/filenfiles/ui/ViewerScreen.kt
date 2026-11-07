package app.filenfiles.ui

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.DriveFileMove
import androidx.compose.material.icons.filled.Share
import androidx.compose.foundation.clickable
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import app.filenfiles.OpenWith
import app.filenfiles.core.FileItem
import app.filenfiles.core.ItemId
import app.filenfiles.core.MaterializedFile
import kotlinx.coroutines.CancellationException

private sealed interface Viewer {
    data class Downloading(val done: Long, val total: Long) : Viewer
    data class Ready(val item: FileItem, val file: MaterializedFile) : Viewer
    data class Failed(val message: String) : Viewer
}

/** Streams the file (download+decrypt for Filen) and shows it in a built-in viewer, or hands it to Open With. */
@Composable
fun ViewerScreen(env: AppEnv, id: ItemId) {
    val ctx = LocalContext.current
    var state by remember(id) { mutableStateOf<Viewer>(Viewer.Downloading(0, 0)) }
    var title by remember { mutableStateOf("") }
    // Known as soon as the file is looked up, so copy/move/delete work while it is still downloading.
    var item by remember(id) { mutableStateOf<FileItem?>(null) }
    var retry by remember { mutableIntStateOf(0) }
    LaunchedEffect(id, retry) {
        state = Viewer.Downloading(0, 0)
        state = try {
            val found = env.app.router.stat(id) ?: error("File not found")
            title = found.name; item = found
            val f = env.app.router.materialize(found) { d, t -> state = Viewer.Downloading(d, t) }
            Viewer.Ready(found, f)
        } catch (e: CancellationException) { throw e } catch (e: Throwable) { Viewer.Failed(e.friendly()) }
    }
    val ready = state as? Viewer.Ready
    // Types without a built-in viewer go straight to Android's Open With chooser.
    var launchedExternal by remember(id) { mutableStateOf(false) }
    LaunchedEffect(ready) {
        if (ready != null && !isBuiltIn(ready.file.mime) && !launchedExternal) {
            launchedExternal = true; OpenWith.open(ctx, ready.file)
        }
    }
    Column(Modifier.fillMaxSize()) {
        AppBar(title, onBack = { env.nav.popBackStack() })
        Box(Modifier.weight(1f).fillMaxWidth()) { ViewerBody(state, ctx) { retry++ } }
        item?.let { f ->
            val close = { env.nav.popBackStack(); Unit }
            val canModify = env.actions.canModify(f)
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            Row(Modifier.fillMaxWidth().navigationBarsPadding().padding(vertical = 4.dp)) {
                ViewerTool(Icons.Filled.ContentCopy, "Copy", Modifier.weight(1f)) { env.actions.run(Op.Copy(listOf(f))) }
                if (canModify) ViewerTool(Icons.Filled.DriveFileMove, "Move", Modifier.weight(1f)) { env.actions.run(Op.Move(listOf(f), close)) }
                if (canModify) ViewerTool(Icons.Filled.Delete, "Delete", Modifier.weight(1f)) { env.actions.run(Op.Delete(listOf(f), close)) }
                ViewerTool(Icons.Filled.Share, "Share", Modifier.weight(1f), enabled = ready != null) { ready?.let { OpenWith.share(ctx, it.file) } }
                ViewerTool(Icons.AutoMirrored.Filled.OpenInNew, "Open with", Modifier.weight(1f), enabled = ready != null) { ready?.let { OpenWith.open(ctx, it.file) } }
            }
        }
    }
}

/** Stack-style tool button: icon over a small label. */
@Composable
private fun ViewerTool(icon: ImageVector, label: String, modifier: Modifier, enabled: Boolean = true, onClick: () -> Unit) {
    val tint = if (enabled) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.outline
    Column(
        modifier.clickable(enabled = enabled, onClick = onClick).padding(vertical = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(icon, null, tint = tint, modifier = Modifier.size(24.dp))
        Spacer(Modifier.height(4.dp))
        Text(label, style = MaterialTheme.typography.labelSmall, color = if (enabled) MaterialTheme.colorScheme.onSurface else tint, maxLines = 1, textAlign = TextAlign.Center)
    }
}

@Composable
private fun ViewerBody(state: Viewer, ctx: android.content.Context, onRetry: () -> Unit) {
    Column(Modifier.fillMaxSize()) {
        when (val s = state) {
            is Viewer.Downloading -> Column(Modifier.fillMaxSize().padding(32.dp), Arrangement.Center, Alignment.CenterHorizontally) {
                if (s.total > 0) LinearProgressIndicator(progress = { (s.done.toFloat() / s.total).coerceIn(0f, 1f) }, modifier = Modifier.fillMaxWidth())
                else CircularProgressIndicator()
                Spacer(Modifier.height(12.dp)); Text("Preparing file…")
            }
            is Viewer.Failed -> Column(Modifier.fillMaxSize().padding(24.dp), Arrangement.Center, Alignment.CenterHorizontally) {
                Text(s.message, color = MaterialTheme.colorScheme.error)
                Spacer(Modifier.height(12.dp)); OutlinedButton(onRetry) { Text("Retry") }
            }
            is Viewer.Ready -> when {
                s.file.mime?.startsWith("image/") == true -> ImageViewer(s.file.path)
                s.file.mime == "application/pdf" -> PdfViewer(s.file.path)
                else -> Column(Modifier.fillMaxSize().padding(24.dp), Arrangement.Center, Alignment.CenterHorizontally) {
                    Text("Opened in another app")
                    Spacer(Modifier.height(12.dp)); Button({ OpenWith.open(ctx, s.file) }) { Text("Open with…") }
                }
            }
        }
    }
}

fun isBuiltIn(mime: String?) = mime?.startsWith("image/") == true || mime == "application/pdf"
