package app.filenfiles.ui

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.ui.platform.LocalContext
import app.filenfiles.core.WritableStorage
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Sort
import androidx.compose.material.icons.automirrored.filled.ViewList
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import app.filenfiles.SortKey
import app.filenfiles.core.Crumb
import app.filenfiles.core.ItemId

@Composable
fun FolderScreen(env: AppEnv, id: ItemId) {
    var title by remember { mutableStateOf("") }
    var refresh by remember { mutableIntStateOf(0) }
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val writable = env.app.router.writable(id)?.takeIf { it.canWrite(id) }
    var newFolder by remember { mutableStateOf(false) }
    // Android 13+: without this the upload notification (foreground service) is hidden. Asked when the first upload starts.
    val notifPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        if (uris.isNotEmpty() && writable != null) {
            env.app.transfers.enqueueUploads(id, uris)
            if (android.os.Build.VERSION.SDK_INT >= 33 &&
                androidx.core.content.ContextCompat.checkSelfPermission(ctx, android.Manifest.permission.POST_NOTIFICATIONS) !=
                android.content.pm.PackageManager.PERMISSION_GRANTED
            ) notifPermission.launch(android.Manifest.permission.POST_NOTIFICATIONS)
        }
    }
    Box(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize()) {
            AppBar(title, onBack = { env.nav.popBackStack() }, actions = {
                IconButton({ refresh++ }) { Icon(Icons.Filled.Refresh, "Refresh") }
                IconButton({ env.nav.navigate("search") }) { Icon(Icons.Filled.Search, "Search") }
            })
            FolderContent(env, id, refresh, onTitle = { title = it }, bottomPadding = if (writable != null) 88.dp else 0.dp)
        }
        // Stack-style round white "+" button: upload files / new folder (only where this folder accepts them).
        if (writable != null) {
            var menu by remember { mutableStateOf(false) }
            Box(Modifier.align(Alignment.BottomEnd).padding(16.dp)) {
                FloatingActionButton({ menu = true }, shape = androidx.compose.foundation.shape.CircleShape,
                    containerColor = MaterialTheme.colorScheme.surfaceContainerHigh, contentColor = MaterialTheme.colorScheme.primary) {
                    Icon(Icons.Filled.Add, "Add")
                }
                DropdownMenu(menu, { menu = false }) {
                    DropdownMenuItem({ Text("Upload files") }, { menu = false; picker.launch(arrayOf("*/*")) },
                        leadingIcon = { Icon(Icons.Filled.Upload, null) })
                    DropdownMenuItem({ Text("New folder") }, { menu = false; newFolder = true },
                        leadingIcon = { Icon(Icons.Filled.CreateNewFolder, null) })
                }
            }
        }
    }
    if (newFolder && writable != null) NewFolderDialog(
        onDismiss = { newFolder = false },
        onCreate = { name ->
            newFolder = false
            scope.launch {
                try { writable.createFolder(id, name); refresh++ }
                catch (e: CancellationException) { throw e }
                catch (e: Throwable) { Toast.makeText(ctx, e.friendly(), Toast.LENGTH_LONG).show() }
            }
        },
    )
}

@Composable
private fun NewFolderDialog(onDismiss: () -> Unit, onCreate: (String) -> Unit) {
    var name by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("New folder") },
        text = { OutlinedTextField(name, { name = it }, singleLine = true, label = { Text("Name") }) },
        confirmButton = { TextButton({ onCreate(name.trim()) }, enabled = name.isNotBlank()) { Text("Create") } },
        dismissButton = { TextButton(onDismiss) { Text("Cancel") } },
    )
}

/** Breadcrumbs + sort/view controls + the listing itself. Used by folder screens and the Shared tab. */
@Composable
fun FolderContent(env: AppEnv, id: ItemId, refresh: Int, showCrumbs: Boolean = true, onTitle: (String) -> Unit = {}, bottomPadding: androidx.compose.ui.unit.Dp = 0.dp) {
    var retry by remember { mutableIntStateOf(0) }
    val signedIn = rememberSignedIn(env)
    // Entering a folder shows its last known listing at once; any later reload (refresh, edits) starts from a spinner.
    var state by remember(id.value) {
        mutableStateOf<Load<app.filenfiles.core.Listing>>(env.app.listingCache[id.value]?.let { Load.Ok(it) } ?: Load.Loading)
    }
    var firstLoad by remember(id.value) { mutableStateOf(true) }
    LaunchedEffect(id.value, refresh, retry, signedIn, env.app.changeCounter) {
        if (!firstLoad) state = Load.Loading
        firstLoad = false
        state = try {
            Load.Ok(env.app.router.list(id).also { env.app.listingCache[id.value] = it })
        } catch (e: CancellationException) { throw e } catch (e: Throwable) { Load.Fail(e.friendly()) }
    }
    LaunchedEffect(state) { (state as? Load.Ok)?.let { onTitle(it.value.folder.name) } }
    val prefs = env.app.prefs
    LoadHost(state, onRetry = { retry++ }) { listing ->
        Column(Modifier.fillMaxSize()) {
            if (showCrumbs) Breadcrumbs(listing.trail) { c ->
                if (c.id == id) return@Breadcrumbs
                val isSharedRoot = c.id.key == "root" && c.id.scheme != "filen"
                when {
                    // Roots of the shared namespaces live in the Shared tab, not on a folder route.
                    isSharedRoot -> if (!env.nav.popBackStack("shared", false)) env.nav.navigate("shared")
                    // Prefer unwinding to the crumb if it is already on the back stack (keeps system Back sane).
                    env.nav.popBackStack(folderRoute(c.id), false) -> Unit
                    else -> env.nav.navigate(folderRoute(c.id))
                }
            }
            ViewControls(env)
            if (listing.items.isEmpty()) EmptyState(when (id.value) {
                "filen-in:root" -> "Nothing has been shared with you yet"
                "filen-out:root" -> "You haven't shared anything yet"
                else -> "This folder is empty"
            })
            else FileCollection(prefs.sort(listing.items), prefs.grid, env.actions, Modifier.weight(1f), bottomPadding = bottomPadding)
        }
    }
}

@Composable
private fun Breadcrumbs(trail: List<Crumb>, onClick: (Crumb) -> Unit) {
    LazyRow(Modifier.fillMaxWidth().padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        itemsIndexed(trail) { i, c ->
            if (i > 0) Icon(Icons.Filled.ChevronRight, null, Modifier.size(18.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
            val last = i == trail.lastIndex
            TextButton({ onClick(c) }, contentPadding = PaddingValues(horizontal = 6.dp)) {
                Text(c.name, fontWeight = if (last) FontWeight.Bold else FontWeight.Normal, maxLines = 1)
            }
        }
    }
}

@Composable
private fun ViewControls(env: AppEnv) {
    val prefs = env.app.prefs
    var menu by remember { mutableStateOf(false) }
    Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
        Box {
            TextButton({ menu = true }) {
                Icon(Icons.AutoMirrored.Filled.Sort, null, Modifier.size(18.dp)); Spacer(Modifier.width(6.dp))
                Text("${prefs.sortKey.name.lowercase().replaceFirstChar { it.uppercase() }} ${if (prefs.ascending) "↑" else "↓"}")
            }
            DropdownMenu(menu, { menu = false }) {
                SortKey.entries.forEach { k ->
                    DropdownMenuItem(
                        { Text(k.name.lowercase().replaceFirstChar { it.uppercase() }) },
                        { prefs.updateSort(k, if (prefs.sortKey == k) !prefs.ascending else true); menu = false },
                        trailingIcon = { if (prefs.sortKey == k) Icon(Icons.Filled.Check, null) },
                    )
                }
            }
        }
        Spacer(Modifier.weight(1f))
        IconButton({ prefs.updateGrid(!prefs.grid) }) {
            if (prefs.grid) Icon(Icons.AutoMirrored.Filled.ViewList, "List view") else Icon(Icons.Filled.GridView, "Grid view")
        }
    }
}
