package app.filenfiles.ui

import android.webkit.MimeTypeMap
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.NoteAdd
import androidx.compose.material.icons.filled.Unarchive
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.filenfiles.OpenWith
import app.filenfiles.core.FileItem
import app.filenfiles.core.ItemId
import app.filenfiles.core.MaterializedFile
import app.filenfiles.core.Names
import app.filenfiles.core.Origin
import app.filenfiles.core.ZipEdit
import app.filenfiles.filen.LocalCache
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.util.zip.ZipFile

private typealias ZEntry = ZipEdit.Entry
private typealias ZRow = ZipEdit.Row

private sealed interface ZState {
    data class Downloading(val done: Long, val total: Long) : ZState
    data class Ready(val item: FileItem, val zipPath: String, val entries: List<ZEntry>) : ZState
    data class Failed(val message: String) : ZState
}

private fun mimeFor(name: String): String? =
    MimeTypeMap.getSingleton().getMimeTypeFromExtension(name.substringAfterLast('.', "").lowercase())

/** Unpacks one entry into the open-files cache (kept for reuse) and returns it as a local file. */
private suspend fun extractEntry(ctx: android.content.Context, zipId: ItemId, zipPath: String, entry: ZRow): MaterializedFile = withContext(Dispatchers.IO) {
    val rel = Names.safeEntryPath(entry.path) ?: throw IOException("This entry has an unsafe path.")
    val base = File(LocalCache.dir(ctx, LocalCache.OPEN), "zip-${(zipId.value.hashCode().toLong() and 0xffffffffL).toString(16)}-${File(zipPath).length()}")
    val target = File(base, rel)
    if (!target.canonicalPath.startsWith(base.canonicalPath + File.separator)) throw IOException("This entry has an unsafe path.")
    if (!target.isFile) {
        target.parentFile?.mkdirs()
        LocalCache.requireSpace(ctx, entry.size)
        val tmp = File(target.path + ".part")
        try {
            ZipFile(zipPath).use { zf ->
                val e = zf.getEntry(entry.path) ?: throw IOException("The entry is no longer in the zip.")
                try { zf.getInputStream(e).use { i -> tmp.outputStream().buffered(1 shl 16).use { o -> i.copyTo(o) } } }
                catch (ex: java.util.zip.ZipException) { throw IOException("Couldn't read “${entry.name}”: the zip is damaged or password protected (not supported).") }
            }
            if (!tmp.renameTo(target)) throw IOException("Couldn't unpack “${entry.name}”.")
        } finally { tmp.delete() }
    }
    MaterializedFile(target.absolutePath, target.name, mimeFor(target.name))
}

/**
 * Browses the inside of a .zip like a folder (the way Google Files does): tap a folder to go in, tap a file to unpack
 * just that file and open it. "Extract all" reuses the normal extraction flow; "Add files" rewrites the zip in the background.
 */
@Composable
fun ArchiveScreen(env: AppEnv, id: ItemId) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var state by remember(id) { mutableStateOf<ZState>(ZState.Downloading(0, 0)) }
    var retry by remember { mutableIntStateOf(0) }
    var cwd by rememberSaveable(id.value) { mutableStateOf("") }
    var preview by remember { mutableStateOf<MaterializedFile?>(null) }
    var opening by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(id, retry) {
        state = ZState.Downloading(0, 0)
        state = try {
            val found = env.app.router.stat(id) ?: error("The zip was not found")
            val path = if (found.origin == Origin.LOCAL) found.id.key
                else env.app.router.materialize(found) { d, t -> state = ZState.Downloading(d, t) }.path
            val entries = withContext(Dispatchers.IO) { ZipEdit.entries(File(path)) }
            ZState.Ready(found, path, entries)
        } catch (e: CancellationException) { throw e }
        catch (e: java.util.zip.ZipException) { ZState.Failed("This file isn't a readable zip (it may be damaged or password protected).") }
        catch (e: Throwable) { ZState.Failed(e.friendly()) }
    }
    val ready = state as? ZState.Ready
    val item = ready?.item
    val canAdd = item != null && env.app.router.modifier(item) != null && env.app.router.parentOf(item.id) != null
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        if (uris.isNotEmpty() && item != null) {
            env.app.transfers.enqueueAddToZip(item, uris, cwd)
            scope.launch { env.snackbar.showSnackbar("Adding ${if (uris.size == 1) "1 file" else "${uris.size} files"} to “${item.name}”…") }
            // The zip is being rewritten (a new file replaces it), so this view would go stale: go back to its folder.
            env.nav.popBackStack()
        }
    }
    val up = { cwd = cwd.dropLast(1).substringBeforeLast('/', "").let { if (it.isEmpty()) "" else "$it/" } }
    BackHandler(enabled = preview != null || cwd.isNotEmpty()) { if (preview != null) preview = null else up() }

    val shown = preview
    if (shown != null) {
        Column(Modifier.fillMaxSize()) {
            AppBar(shown.name, onBack = { preview = null }, actions = {
                IconButton({ OpenWith.open(ctx, shown) }) { Icon(Icons.AutoMirrored.Filled.OpenInNew, "Open with") }
            })
            if (shown.mime == "application/pdf") PdfViewer(shown.path) else ImageViewer(shown.path)
        }
        return
    }
    Column(Modifier.fillMaxSize()) {
        AppBar(item?.name ?: "Zip", onBack = { if (cwd.isNotEmpty()) up() else env.nav.popBackStack() }, actions = {
            if (canAdd) IconButton({ picker.launch(arrayOf("*/*")) }) { Icon(Icons.Filled.NoteAdd, "Add files to this zip") }
            if (item != null) IconButton({ env.actions.extract(item) }) { Icon(Icons.Filled.Unarchive, "Extract all") }
        })
        when (val s = state) {
            is ZState.Downloading -> Column(Modifier.fillMaxSize().padding(32.dp), Arrangement.Center, Alignment.CenterHorizontally) {
                if (s.total > 0) LinearProgressIndicator(progress = { (s.done.toFloat() / s.total).coerceIn(0f, 1f) }, modifier = Modifier.fillMaxWidth())
                else CircularProgressIndicator()
                Spacer(Modifier.height(12.dp)); Text("Opening zip…")
            }
            is ZState.Failed -> Column(Modifier.fillMaxSize().padding(24.dp), Arrangement.Center, Alignment.CenterHorizontally) {
                Text(s.message, color = MaterialTheme.colorScheme.error)
                Spacer(Modifier.height(12.dp)); OutlinedButton({ retry++ }) { Text("Retry") }
            }
            is ZState.Ready -> {
                // Path inside the archive, each part tappable.
                val parts = cwd.trimEnd('/').split('/').filter { it.isNotEmpty() }
                Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    TextButton({ cwd = "" }, contentPadding = PaddingValues(horizontal = 6.dp)) {
                        Text(s.item.name, fontWeight = if (parts.isEmpty()) FontWeight.Bold else FontWeight.Normal, maxLines = 1)
                    }
                    parts.forEachIndexed { i, p ->
                        Icon(Icons.Filled.ChevronRight, null, Modifier.size(18.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                        TextButton({ cwd = parts.take(i + 1).joinToString("/") + "/" }, contentPadding = PaddingValues(horizontal = 6.dp)) {
                            Text(p, fontWeight = if (i == parts.lastIndex) FontWeight.Bold else FontWeight.Normal, maxLines = 1)
                        }
                    }
                }
                if (opening != null) LinearProgressIndicator(Modifier.fillMaxWidth())
                val rows = remember(s, cwd) { ZipEdit.children(s.entries, cwd) }
                if (rows.isEmpty()) EmptyState(if (cwd.isEmpty()) "This zip is empty" else "This folder is empty")
                else LazyColumn(Modifier.fillMaxSize()) {
                    items(rows, key = { it.path }) { r ->
                        val fi = FileItem(ItemId("zip:${r.path}"), r.name, r.isDir, r.size, if (r.time > 0) r.time else 0, if (r.isDir) null else mimeFor(r.name), s.item.origin)
                        ListItem(
                            modifier = Modifier.clickable(enabled = opening == null) {
                                if (r.isDir) cwd = r.path
                                else {
                                    opening = r.name
                                    scope.launch {
                                        try {
                                            val f = extractEntry(ctx, id, s.zipPath, r)
                                            if (isBuiltIn(f.mime)) preview = f else OpenWith.open(ctx, f)
                                        } catch (e: CancellationException) { throw e }
                                        catch (e: Throwable) { Toast.makeText(ctx, e.friendly(), Toast.LENGTH_LONG).show() }
                                        finally { opening = null }
                                    }
                                }
                            },
                            leadingContent = { Icon(iconFor(fi), null, tint = iconTint(fi)) },
                            headlineContent = { Text(r.name, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                            supportingContent = if (r.isDir) null else ({ subtitle(fi, ctx).takeIf { it.isNotEmpty() }?.let { Text(it, maxLines = 1) } }),
                        )
                    }
                }
            }
        }
    }
}
