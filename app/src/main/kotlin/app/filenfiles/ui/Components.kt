package app.filenfiles.ui

import android.text.format.DateUtils
import android.text.format.Formatter
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.InsertDriveFile
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.filenfiles.core.FileItem
import app.filenfiles.core.Origin
import app.filenfiles.core.Permission
import kotlinx.coroutines.CancellationException
import uniffi.filen_sdk_rs.FilenSdkException

// ---------- loading ----------

sealed interface Load<out T> {
    data object Loading : Load<Nothing>
    data class Ok<T>(val value: T) : Load<T>
    data class Fail(val message: String) : Load<Nothing>
}

fun Throwable.friendly(): String = when (this) {
    is FilenSdkException -> runCatching { message() }.getOrNull() ?: "Filen error"
    is SecurityException -> "Permission needed: grant “All files access” to browse this folder."
    else -> message ?: javaClass.simpleName
}

@Composable
fun <T> loadState(vararg keys: Any?, refresh: Int = 0, block: suspend () -> T): Load<T> {
    var state by remember(*keys, refresh) { mutableStateOf<Load<T>>(Load.Loading) }
    LaunchedEffect(*keys, refresh) {
        state = try { Load.Ok(block()) } catch (e: CancellationException) { throw e } catch (e: Throwable) { Load.Fail(e.friendly()) }
    }
    return state
}

@Composable
fun <T> LoadHost(state: Load<T>, onRetry: (() -> Unit)? = null, content: @Composable (T) -> Unit) {
    when (state) {
        Load.Loading -> Box(Modifier.fillMaxSize(), Alignment.Center) { CircularProgressIndicator() }
        is Load.Fail -> Column(Modifier.fillMaxSize().padding(24.dp), Arrangement.Center, Alignment.CenterHorizontally) {
            Text(state.message, color = MaterialTheme.colorScheme.error)
            if (onRetry != null) { Spacer(Modifier.height(12.dp)); OutlinedButton(onRetry) { Text("Retry") } }
        }
        is Load.Ok -> content(state.value)
    }
}

// ---------- formatting / icons ----------

fun formatDate(ms: Long): String =
    if (ms <= 0) "" else DateUtils.getRelativeTimeSpanString(ms, System.currentTimeMillis(), DateUtils.MINUTE_IN_MILLIS, DateUtils.FORMAT_ABBREV_RELATIVE).toString()

fun iconFor(item: FileItem): ImageVector = when {
    item.isDirectory -> when (item.origin) {
        Origin.FILEN_SHARED_WITH_ME, Origin.FILEN_SHARED_BY_ME -> Icons.Filled.FolderShared
        else -> Icons.Filled.Folder
    }
    item.mime?.startsWith("image/") == true -> Icons.Filled.Image
    item.mime?.startsWith("video/") == true -> Icons.Filled.VideoFile
    item.mime?.startsWith("audio/") == true -> Icons.Filled.AudioFile
    item.mime == "application/pdf" -> Icons.Filled.PictureAsPdf
    item.mime?.startsWith("text/") == true -> Icons.Filled.Description
    item.mime?.contains("zip") == true || item.mime?.contains("compressed") == true -> Icons.Filled.FolderZip
    else -> Icons.AutoMirrored.Filled.InsertDriveFile
}

@Composable
fun iconTint(item: FileItem): Color = when {
    item.isDirectory -> MaterialTheme.colorScheme.primary
    item.mime == "application/pdf" -> Color(0xFFD93025)
    item.mime?.startsWith("image/") == true -> Color(0xFF1E8E3E)
    item.mime?.startsWith("video/") == true -> Color(0xFF8E24AA)
    else -> MaterialTheme.colorScheme.onSurfaceVariant
}

fun originLabel(item: FileItem): String? = when (item.origin) {
    Origin.FILEN_SHARED_WITH_ME -> "Shared by ${item.sharedBy ?: "someone"}"
    Origin.FILEN_SHARED_BY_ME -> "Shared with ${item.sharedWith ?: "others"}"
    else -> null
}

/** Visible marker that distinguishes received / outgoing shares from personal files. */
@Composable
fun OriginBadge(item: FileItem, mixed: Boolean = false) {
    // Shares are always labelled; personal/local items only where several sources are listed together.
    val label = originLabel(item) ?: when {
        !mixed -> return
        item.origin == Origin.LOCAL -> "This device"
        else -> "Filen"
    }
    val received = item.origin == Origin.FILEN_SHARED_WITH_ME
    val shared = received || item.origin == Origin.FILEN_SHARED_BY_ME
    val container = when {
        received -> MaterialTheme.colorScheme.tertiaryContainer
        shared -> MaterialTheme.colorScheme.secondaryContainer
        else -> MaterialTheme.colorScheme.surfaceVariant
    }
    Surface(shape = RoundedCornerShape(50), color = container) {
        Row(Modifier.padding(horizontal = 8.dp, vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
            // Lock first so a long (ellipsized) sharer address can never push it out of view.
            if (item.permission == Permission.READ_ONLY && received) {
                Icon(Icons.Filled.Lock, "Read only", Modifier.size(12.dp)); Spacer(Modifier.width(4.dp))
            }
            Icon(
                when { received -> Icons.Filled.People; shared -> Icons.Filled.Share
                    item.origin == Origin.LOCAL -> Icons.Filled.PhoneAndroid; else -> Icons.Filled.Cloud },
                null, Modifier.size(12.dp),
            )
            Spacer(Modifier.width(4.dp))
            Text(label, style = MaterialTheme.typography.labelSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

fun subtitle(item: FileItem, ctx: android.content.Context): String {
    val parts = ArrayList<String>()
    if (!item.isDirectory && item.size > 0) parts += Formatter.formatShortFileSize(ctx, item.size)
    formatDate(item.modified).takeIf { it.isNotEmpty() }?.let { parts += it }
    return parts.joinToString(" · ")
}

// ---------- item menu / actions ----------

class ItemActions(
    val open: (FileItem) -> Unit,
    val openWith: (FileItem) -> Unit,
    val share: (FileItem) -> Unit,
    val toggleFavorite: (FileItem) -> Unit,
    val isFavorite: (FileItem) -> Boolean,
)

@Composable
fun ItemMenu(item: FileItem, actions: ItemActions) {
    var open by remember { mutableStateOf(false) }
    var details by remember { mutableStateOf(false) }
    Box {
        IconButton({ open = true }) { Icon(Icons.Filled.MoreVert, "More") }
        DropdownMenu(open, { open = false }) {
            DropdownMenuItem({ Text("Open") }, { open = false; actions.open(item) })
            if (!item.isDirectory) {
                DropdownMenuItem({ Text("Open with…") }, { open = false; actions.openWith(item) })
                DropdownMenuItem({ Text("Share…") }, { open = false; actions.share(item) })
            }
            val fav = actions.isFavorite(item)
            DropdownMenuItem({ Text(if (fav) "Remove from favorites" else "Add to favorites") }, { open = false; actions.toggleFavorite(item) })
            DropdownMenuItem({ Text("Details") }, { open = false; details = true })
        }
    }
    if (details) DetailsDialog(item) { details = false }
}

@Composable
fun DetailsDialog(item: FileItem, onDismiss: () -> Unit) {
    val ctx = LocalContext.current
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = { TextButton(onDismiss) { Text("Close") } },
        title = { Text(item.name, maxLines = 2, overflow = TextOverflow.Ellipsis) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                @Composable fun row(k: String, v: String?) { if (!v.isNullOrBlank()) Text("$k: $v") }
                row("Location", when (item.origin) {
                    Origin.LOCAL -> "This device"
                    Origin.FILEN_PERSONAL -> "Filen (personal)"
                    Origin.FILEN_SHARED_WITH_ME -> "Filen (shared with me)"
                    Origin.FILEN_SHARED_BY_ME -> "Filen (shared by me)"
                })
                row("Type", item.mime ?: if (item.isDirectory) "Folder" else null)
                if (!item.isDirectory) row("Size", Formatter.formatFileSize(ctx, item.size))
                row("Modified", if (item.modified > 0) java.text.DateFormat.getDateTimeInstance().format(java.util.Date(item.modified)) else null)
                row("Shared by", item.sharedBy); row("Shared with", item.sharedWith)
                if (item.origin != Origin.LOCAL && item.origin != Origin.FILEN_PERSONAL)
                    row("Access", if (item.permission == Permission.READ_ONLY) "Read only" else "Can edit")
            }
        },
    )
}

// ---------- lists ----------

@Composable
fun FileRow(item: FileItem, actions: ItemActions, mixed: Boolean = false) {
    val ctx = LocalContext.current
    ListItem(
        modifier = Modifier.clickable { actions.open(item) },
        leadingContent = { Icon(iconFor(item), null, tint = iconTint(item), modifier = Modifier.size(32.dp)) },
        headlineContent = { Text(item.name, maxLines = 1, overflow = TextOverflow.Ellipsis) },
        supportingContent = {
            Column {
                subtitle(item, ctx).takeIf { it.isNotEmpty() }?.let { Text(it, maxLines = 1) }
                OriginBadge(item, mixed)
            }
        },
        trailingContent = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (actions.isFavorite(item)) Icon(Icons.Filled.Star, "Favorite", tint = Color(0xFFF9AB00), modifier = Modifier.size(18.dp))
                ItemMenu(item, actions)
            }
        },
    )
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun FileGridCell(item: FileItem, actions: ItemActions, mixed: Boolean = false) {
    Card(Modifier.fillMaxWidth().clickable { actions.open(item) }) {
        Column(Modifier.padding(8.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Box(Modifier.fillMaxWidth()) {
                Icon(iconFor(item), null, tint = iconTint(item), modifier = Modifier.size(48.dp).align(Alignment.Center))
                Box(Modifier.align(Alignment.TopEnd)) { ItemMenu(item, actions) }
                if (actions.isFavorite(item)) Icon(Icons.Filled.Star, null, tint = Color(0xFFF9AB00), modifier = Modifier.size(16.dp).align(Alignment.TopStart))
            }
            Text(item.name, maxLines = 2, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodySmall)
            OriginBadge(item, mixed)
        }
    }
}

@Composable
fun FileCollection(items: List<FileItem>, grid: Boolean, actions: ItemActions, modifier: Modifier = Modifier, mixed: Boolean = false, header: (@Composable () -> Unit)? = null) {
    if (grid) {
        LazyVerticalGrid(GridCells.Adaptive(120.dp), modifier, contentPadding = PaddingValues(8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            items(items, key = { it.id.value }) { FileGridCell(it, actions, mixed) }
        }
    } else {
        LazyColumn(modifier) {
            if (header != null) item { header() }
            items(items, key = { it.id.value }) { FileRow(it, actions, mixed) }
        }
    }
}

@Composable
fun EmptyState(text: String, modifier: Modifier = Modifier) {
    Box(modifier.fillMaxSize().padding(24.dp), Alignment.Center) {
        Text(text, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
fun SignInPrompt(onSignIn: () -> Unit) {
    Column(Modifier.fillMaxSize().padding(32.dp), Arrangement.Center, Alignment.CenterHorizontally) {
        Icon(Icons.Filled.Cloud, null, Modifier.size(56.dp), tint = MaterialTheme.colorScheme.primary)
        Spacer(Modifier.height(12.dp))
        Text("Sign in to Filen to see your cloud files and shares", style = MaterialTheme.typography.bodyLarge)
        Spacer(Modifier.height(16.dp))
        Button(onSignIn) { Text("Sign in") }
    }
}

