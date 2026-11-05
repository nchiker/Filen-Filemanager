package app.filenfiles.ui

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.filled.Share
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
    var retry by remember { mutableIntStateOf(0) }
    LaunchedEffect(id, retry) {
        state = Viewer.Downloading(0, 0)
        state = try {
            val item = env.app.router.stat(id) ?: error("File not found")
            title = item.name
            val f = env.app.router.materialize(item) { d, t -> state = Viewer.Downloading(d, t) }
            Viewer.Ready(item, f)
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
        AppBar(title, onBack = { env.nav.popBackStack() }, actions = {
            if (ready != null) {
                IconButton({ OpenWith.open(ctx, ready.file) }) { Icon(Icons.AutoMirrored.Filled.OpenInNew, "Open with") }
                IconButton({ OpenWith.share(ctx, ready.file) }) { Icon(Icons.Filled.Share, "Share") }
            }
        })
        when (val s = state) {
            is Viewer.Downloading -> Column(Modifier.fillMaxSize().padding(32.dp), Arrangement.Center, Alignment.CenterHorizontally) {
                if (s.total > 0) LinearProgressIndicator(progress = { (s.done.toFloat() / s.total).coerceIn(0f, 1f) }, modifier = Modifier.fillMaxWidth())
                else CircularProgressIndicator()
                Spacer(Modifier.height(12.dp)); Text("Preparing file…")
            }
            is Viewer.Failed -> Column(Modifier.fillMaxSize().padding(24.dp), Arrangement.Center, Alignment.CenterHorizontally) {
                Text(s.message, color = MaterialTheme.colorScheme.error)
                Spacer(Modifier.height(12.dp)); OutlinedButton({ retry++ }) { Text("Retry") }
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

private fun isBuiltIn(mime: String?) = mime?.startsWith("image/") == true || mime == "application/pdf"
