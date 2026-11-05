package app.filenfiles.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DeleteForever
import androidx.compose.material.icons.filled.RestoreFromTrash
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.filenfiles.core.FileItem
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

/** Filen trash: restore items, delete them forever, or empty everything. */
@Composable
fun TrashScreen(env: AppEnv) {
    val trash = env.app.router.trashStorage()
    val scope = rememberCoroutineScope()
    val ctx = LocalContext.current
    var refresh by remember { mutableIntStateOf(0) }
    var confirmEmpty by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf<FileItem?>(null) }
    // The server's trash listing lags behind restore/delete, so hide handled rows locally until the next real load.
    var gone by remember { mutableStateOf(setOf<String>()) }
    val state = loadState(refresh = refresh) { trash?.listTrash() ?: error("Sign in to Filen to see its trash") }

    fun act(failure: String, ok: String?, removed: FileItem? = null, block: suspend () -> Unit) {
        scope.launch {
            try {
                block(); if (removed != null) gone = gone + removed.id.value else gone = emptySet()
                refresh++; env.app.notifyChanged()
                if (ok != null) env.snackbar.showSnackbar(ok)
            } catch (e: CancellationException) { throw e }
            catch (e: Throwable) { env.snackbar.showSnackbar("$failure: ${e.friendly()}"); refresh++ }
        }
    }

    Column(Modifier.fillMaxSize()) {
        AppBar("Trash", onBack = { env.nav.popBackStack() }, actions = {
            val hasItems = (state as? Load.Ok)?.value?.any { it.id.value !in gone } == true
            TextButton({ confirmEmpty = true }, enabled = hasItems) { Text("Empty") }
        })
        LoadHost(state, onRetry = { refresh++ }) { all ->
            val items = all.filter { it.id.value !in gone }
            if (items.isEmpty()) EmptyState("Trash is empty")
            else LazyColumn(Modifier.fillMaxSize()) {
                items(items, key = { it.id.value }) { item ->
                    ListItem(
                        leadingContent = { ItemThumb(item, 44.dp) },
                        headlineContent = { Text(item.name, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                        supportingContent = { Text(subtitle(item, ctx), maxLines = 1) },
                        trailingContent = {
                            Row {
                                IconButton({ act("Restore failed", "Restored “${item.name}”", item) { trash!!.restore(item) } }) {
                                    Icon(Icons.Filled.RestoreFromTrash, "Restore")
                                }
                                IconButton({ confirmDelete = item }) { Icon(Icons.Filled.DeleteForever, "Delete forever") }
                            }
                        },
                    )
                }
            }
        }
    }

    if (confirmEmpty) AlertDialog(
        onDismissRequest = { confirmEmpty = false },
        title = { Text("Empty trash?") },
        text = { Text("Everything in the trash will be permanently deleted. This can't be undone.") },
        confirmButton = { TextButton({ confirmEmpty = false; act("Empty failed", "Trash emptied") { trash!!.emptyTrash() } }) { Text("Empty trash") } },
        dismissButton = { TextButton({ confirmEmpty = false }) { Text("Cancel") } },
    )
    confirmDelete?.let { item ->
        AlertDialog(
            onDismissRequest = { confirmDelete = null },
            title = { Text("Delete “${item.name}” forever?") },
            text = { Text("This can't be undone.") },
            confirmButton = { TextButton({ confirmDelete = null; act("Delete failed", "Deleted “${item.name}”", item) { trash!!.deletePermanently(item) } }) { Text("Delete forever") } },
            dismissButton = { TextButton({ confirmDelete = null }) { Text("Cancel") } },
        )
    }
}
