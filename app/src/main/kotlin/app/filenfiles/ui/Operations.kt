package app.filenfiles.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.filenfiles.core.FileItem
import app.filenfiles.core.ItemId
import app.filenfiles.core.Origin
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

sealed interface Op {
    val item: FileItem
    data class Rename(override val item: FileItem) : Op
    data class Move(override val item: FileItem) : Op
    data class Delete(override val item: FileItem) : Op
}

/** Hosts the dialogs and execution of rename / move / delete on a single item. */
@Composable
fun OperationHost(env: AppEnv, op: Op?, onClose: () -> Unit) {
    val scope = rememberCoroutineScope()
    fun run(failurePrefix: String, block: suspend () -> Unit) {
        onClose()
        scope.launch {
            try { block(); env.app.notifyChanged() }
            catch (e: CancellationException) { throw e }
            catch (e: Throwable) { env.snackbar.showSnackbar("$failurePrefix: ${e.friendly()}") }
        }
    }
    when (op) {
        null -> Unit
        is Op.Rename -> RenameDialog(op.item, onClose) { name ->
            run("Rename failed") {
                env.app.router.modifier(op.item)!!.rename(op.item, name)
            }
        }
        is Op.Move -> MoveDialog(env, op.item, onClose) { dest ->
            run("Move failed") {
                env.app.router.modifier(op.item)!!.move(op.item, dest)
                scope.launch { env.snackbar.showSnackbar("Moved “${op.item.name}”") }
            }
        }
        is Op.Delete -> if (op.item.origin == Origin.LOCAL) {
            // Local deletion is permanent, so ask first.
            AlertDialog(
                onDismissRequest = onClose,
                title = { Text("Delete “${op.item.name}”?") },
                text = { Text(if (op.item.isDirectory) "The folder and everything in it will be permanently deleted." else "This file will be permanently deleted. This can't be undone.") },
                confirmButton = { TextButton({ run("Delete failed") { env.app.router.modifier(op.item)!!.trash(op.item) } }) { Text("Delete") } },
                dismissButton = { TextButton(onClose) { Text("Cancel") } },
            )
        } else {
            // Filen: goes to the trash, with an Undo.
            LaunchedEffect(op) {
                run("Delete failed") {
                    val undo = env.app.router.modifier(op.item)!!.trash(op.item)
                    scope.launch {
                        val r = env.snackbar.showSnackbar("Moved “${op.item.name}” to trash", actionLabel = if (undo != null) "Undo" else null, duration = SnackbarDuration.Long)
                        if (r == SnackbarResult.ActionPerformed && undo != null) {
                            try { undo(); env.app.notifyChanged() }
                            catch (e: CancellationException) { throw e }
                            catch (e: Throwable) { env.snackbar.showSnackbar("Undo failed: ${e.friendly()}") }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun RenameDialog(item: FileItem, onDismiss: () -> Unit, onConfirm: (String) -> Unit) {
    var name by remember(item) { mutableStateOf(item.name) }
    val valid = name.isNotBlank() && '/' !in name && name != item.name
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Rename") },
        text = { OutlinedTextField(name, { name = it }, singleLine = true, label = { Text("Name") }, isError = '/' in name) },
        confirmButton = { TextButton({ onConfirm(name.trim()) }, enabled = valid) { Text("Rename") } },
        dismissButton = { TextButton(onDismiss) { Text("Cancel") } },
    )
}

/** A small folder browser: pick a destination inside the same storage as the item. */
@Composable
private fun MoveDialog(env: AppEnv, item: FileItem, onDismiss: () -> Unit, onPick: (ItemId) -> Unit) {
    val start = remember(item) {
        if (item.origin == Origin.LOCAL) ItemId("local:" + (java.io.File(item.id.key).parent ?: "/")) else ItemId("filen:root")
    }
    var current by remember(item) { mutableStateOf(start) }
    val state = loadState(current.value, env.app.changeCounter) { env.app.router.list(current) }
    val writable = env.app.router.writable(current)
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Move “${item.name}”", maxLines = 1, overflow = TextOverflow.Ellipsis) },
        text = {
            Column(Modifier.heightIn(max = 380.dp)) {
                when (val s = state) {
                    Load.Loading -> Box(Modifier.fillMaxWidth().height(120.dp), Alignment.Center) { CircularProgressIndicator() }
                    is Load.Fail -> Text(s.message, color = MaterialTheme.colorScheme.error)
                    is Load.Ok -> {
                        val trail = s.value.trail
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            IconButton({ current = trail[trail.size - 2].id }, enabled = trail.size > 1) {
                                Icon(Icons.AutoMirrored.Filled.ArrowBack, "Up")
                            }
                            Text(s.value.folder.name, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                        val folders = s.value.items.filter { it.isDirectory && it.id != item.id }.sortedBy { it.name.lowercase() }
                        if (folders.isEmpty()) Text("No subfolders", Modifier.padding(12.dp), color = MaterialTheme.colorScheme.onSurfaceVariant)
                        LazyColumn {
                            items(folders, key = { it.id.value }) { f ->
                                ListItem(
                                    modifier = Modifier.clickable { current = f.id },
                                    leadingContent = { Icon(Icons.Filled.Folder, null, tint = MaterialTheme.colorScheme.primary) },
                                    headlineContent = { Text(f.name, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                                )
                            }
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton({ onPick(current) }, enabled = state is Load.Ok && writable?.canWrite(current) == true) { Text("Move here") } },
        dismissButton = { TextButton(onDismiss) { Text("Cancel") } },
    )
}
