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
    data class Rename(val item: FileItem) : Op
    data class Move(val items: List<FileItem>) : Op
    data class Delete(val items: List<FileItem>) : Op
    data class Copy(val items: List<FileItem>) : Op
    data class Compress(val items: List<FileItem>) : Op
    data class Extract(val item: FileItem) : Op
    data class History(val item: FileItem) : Op
    data class ShareWith(val items: List<FileItem>) : Op
    data class StopSharing(val item: FileItem) : Op
}

/** "“name”" for one item, "3 items" for several. */
fun itemsLabel(items: List<FileItem>) = if (items.size == 1) "“${items[0].name}”" else "${items.size} items"

/** Hosts the dialogs and execution of rename / move / copy / delete / share on one item or a selection. */
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
    var crossMove by remember { mutableStateOf<Pair<List<FileItem>, ItemId>?>(null) }
    crossMove?.let { (items, dest) ->
        AlertDialog(
            onDismissRequest = { crossMove = null },
            title = { Text("Move ${itemsLabel(items)}?") },
            text = {
                Text("They are copied to the new location first. Originals are deleted only after everything has been copied" +
                    when {
                        items.all { it.origin == Origin.LOCAL } -> ", and local deletions can't be undone."
                        items.none { it.origin == Origin.LOCAL } -> " (they go to the Filen trash)."
                        else -> " (local ones are deleted for good, Filen ones go to the Filen trash)."
                    })
            },
            confirmButton = {
                TextButton({
                    crossMove = null
                    items.forEach { env.app.transfers.enqueueCopy(it, dest, move = true) }
                    scope.launch { env.snackbar.showSnackbar("Moving ${itemsLabel(items)}…") }
                }) { Text("Move") }
            },
            dismissButton = { TextButton({ crossMove = null }) { Text("Cancel") } },
        )
    }
    /** Trashes Filen items (one Undo restores them all); local items are deleted for good. */
    suspend fun deleteAll(items: List<FileItem>) {
        val undos = items.mapNotNull { env.app.router.modifier(it)!!.trash(it) }
        env.app.notifyChanged()
        val r = env.snackbar.showSnackbar(
            if (undos.isNotEmpty()) "Moved ${itemsLabel(items)} to trash" else "Deleted ${itemsLabel(items)}",
            actionLabel = if (undos.isNotEmpty()) "Undo" else null, duration = SnackbarDuration.Long)
        if (r == SnackbarResult.ActionPerformed) {
            try { undos.forEach { it() }; env.app.notifyChanged() }
            catch (e: CancellationException) { throw e }
            catch (e: Throwable) { env.snackbar.showSnackbar("Undo failed: ${e.friendly()}") }
        }
    }
    when (op) {
        null -> Unit
        is Op.ShareWith -> ShareDialog(env, op.items, onClose)
        is Op.StopSharing -> AlertDialog(
            onDismissRequest = onClose,
            title = { Text("Stop sharing “${op.item.name}”?") },
            text = {
                Text("It will disappear from " + (op.item.sharedWith ?: "the people you shared it with") +
                    ". Your own copy is not affected.")
            },
            confirmButton = {
                TextButton({
                    run("Couldn't stop sharing") {
                        env.app.router.sharing()!!.unshare(op.item)
                        scope.launch { env.snackbar.showSnackbar("Stopped sharing “${op.item.name}”") }
                    }
                }) { Text("Stop sharing") }
            },
            dismissButton = { TextButton(onClose) { Text("Cancel") } },
        )
        is Op.Rename -> RenameDialog(op.item, onClose) { name ->
            run("Rename failed") {
                env.app.router.modifier(op.item)!!.rename(op.item, name)
            }
        }
        is Op.Move -> DestinationDialog(env, op.items, "Move", onClose) { dest ->
            val same = op.items.filter { it.id.scheme == dest.scheme }
            val other = op.items.filter { it.id.scheme != dest.scheme }
            if (other.isNotEmpty()) crossMove = other to dest // other storage: copy first, delete the originals afterwards
            if (same.isEmpty()) onClose() else run("Move failed") {
                same.forEach { env.app.router.modifier(it)!!.move(it, dest) }
                scope.launch { env.snackbar.showSnackbar("Moved ${itemsLabel(same)}") }
            }
        }
        is Op.History -> VersionHistoryDialog(env, op.item, onClose)
        is Op.Compress -> DestinationDialog(env, op.items, "Compress", onClose) { dest ->
            onClose()
            env.app.transfers.enqueueZip(op.items, dest)
            scope.launch { env.snackbar.showSnackbar("Compressing ${itemsLabel(op.items)}…") }
        }
        is Op.Extract -> DestinationDialog(env, listOf(op.item), "Extract", onClose) { dest ->
            onClose()
            env.app.transfers.enqueueUnzip(op.item, dest)
            scope.launch { env.snackbar.showSnackbar("Extracting “${op.item.name}”…") }
        }
        is Op.Copy -> DestinationDialog(env, op.items, "Copy", onClose) { dest ->
            onClose()
            op.items.forEach { env.app.transfers.enqueueCopy(it, dest, move = false) }
            scope.launch { env.snackbar.showSnackbar("Copying ${itemsLabel(op.items)}…") }
        }
        is Op.Delete -> if (op.items.any { it.origin == Origin.LOCAL || it.origin == Origin.EXTERNAL }) {
            // Local deletion is permanent, so ask first.
            AlertDialog(
                onDismissRequest = onClose,
                title = { Text("Delete ${itemsLabel(op.items)}?") },
                text = {
                    Text(if (op.items.all { it.origin == Origin.EXTERNAL }) {
                        "${if (op.items.size == 1) "It is" else "They are"} deleted from that cloud storage. Whether ${if (op.items.size == 1) "it" else "they"} can be restored depends on the service (many keep deleted files in their own trash for a while)."
                    } else if (op.items.size == 1) {
                        if (op.items[0].isDirectory) "The folder and everything in it will be permanently deleted." else "This file will be permanently deleted. This can't be undone."
                    } else "Items on this device are permanently deleted (folders with everything in them); Filen items go to the Filen trash.")
                },
                confirmButton = { TextButton({ run("Delete failed") { deleteAll(op.items) } }) { Text("Delete") } },
                dismissButton = { TextButton(onClose) { Text("Cancel") } },
            )
        } else {
            // Filen: goes to the trash, with an Undo.
            LaunchedEffect(op) { run("Delete failed") { deleteAll(op.items) } }
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

/** Destination picker for moving/copying/compressing/extracting [items]. */
@Composable
private fun DestinationDialog(env: AppEnv, items: List<FileItem>, verb: String, onDismiss: () -> Unit, onPick: (ItemId) -> Unit) {
    val first = items.first()
    // A copy starts at the list of locations; everything else starts where the item lives.
    val start = if (verb == "Copy") null
        else if (first.origin == Origin.LOCAL) ItemId("local:" + (java.io.File(first.id.key).parent ?: "/")) else ItemId("filen:root")
    FolderPickerDialog(env, "$verb ${itemsLabel(items)}", "$verb here", start, items, verb == "Move", false, onDismiss, onPick)
}

/**
 * Folder browser: starts at [start] (null = the list of writable locations), can cross between Filen and this device
 * (or stay in Filen with [filenOnly]), and never offers [items] (or a place inside them) as the destination.
 */
@Composable
fun FolderPickerDialog(
    env: AppEnv, title: String, confirmLabel: String, start: ItemId?, items: List<FileItem> = emptyList(),
    blockSameFolder: Boolean = false, filenOnly: Boolean = false, onDismiss: () -> Unit, onPick: (ItemId) -> Unit,
) {
    var current by remember(start, items) { mutableStateOf<ItemId?>(start) }
    val here = current
    val state = loadState(here?.value ?: "locations", env.app.changeCounter) {
        if (here == null) null to env.app.router.roots().filter { r ->
            env.app.router.writable(r.id)?.canWrite(r.id) == true && (!filenOnly || r.id.scheme == "filen")
        } else env.app.router.list(here) to emptyList<app.filenfiles.core.Folder>()
    }
    val writable = here?.let { env.app.router.writable(it) }
    val listing = (state as? Load.Ok)?.value?.first
    // A folder can't go into itself or its own subtree.
    val insideItself = items.any { i -> i.isDirectory && listing?.trail?.any { it.id == i.id } == true }
    // Moving a device item into the folder it already lives in would only clash with itself.
    val alreadyHere = blockSameFolder && here != null && items.isNotEmpty() && items.all { it.origin == Origin.LOCAL && java.io.File(it.id.key).parent == here.key }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title, maxLines = 1, overflow = TextOverflow.Ellipsis) },
        text = {
            Column(Modifier.heightIn(max = 380.dp)) {
                when (val s = state) {
                    Load.Loading -> Box(Modifier.fillMaxWidth().height(120.dp), Alignment.Center) { CircularProgressIndicator() }
                    is Load.Fail -> Text(s.message, color = MaterialTheme.colorScheme.error)
                    is Load.Ok -> {
                        val (l, roots) = s.value
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            IconButton({ current = l?.trail?.let { t -> if (t.size > 1) t[t.size - 2].id else null } }, enabled = here != null) {
                                Icon(Icons.AutoMirrored.Filled.ArrowBack, "Up")
                            }
                            Text(l?.folder?.name ?: "Choose a location", style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                        val folders: List<Pair<ItemId, String>> = if (l == null) roots.map { it.id to it.name }
                        else l.items.filter { f -> f.isDirectory && items.none { it.id == f.id } }.sortedBy { it.name.lowercase() }.map { it.id to it.name }
                        if (folders.isEmpty()) Text("No subfolders", Modifier.padding(12.dp), color = MaterialTheme.colorScheme.onSurfaceVariant)
                        LazyColumn {
                            items(folders, key = { it.first.value }) { (id, name) ->
                                ListItem(
                                    modifier = Modifier.clickable { current = id },
                                    leadingContent = { Icon(Icons.Filled.Folder, null, tint = MaterialTheme.colorScheme.primary) },
                                    headlineContent = { Text(name, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                                )
                            }
                        }
                        if (insideItself) Text("A folder can't be put inside itself.", color = MaterialTheme.colorScheme.error)
                        else if (alreadyHere) Text("Already in this folder.", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        },
        confirmButton = {
            TextButton({ onPick(here!!) }, enabled = here != null && state is Load.Ok && writable?.canWrite(here) == true && !insideItself && !alreadyHere) { Text(confirmLabel) }
        },
        dismissButton = { TextButton(onDismiss) { Text("Cancel") } },
    )
}

/** Pick a Filen contact to share [item] with; a contact request can be sent to someone who isn't one yet. */
@Composable
private fun ShareDialog(env: AppEnv, items: List<FileItem>, onClose: () -> Unit) {
    val sharing = env.app.router.sharing()
    val scope = rememberCoroutineScope()
    var reload by remember { mutableIntStateOf(0) }
    val contacts = loadState(refresh = reload) { sharing?.contacts() ?: error("Sign in to Filen to share") }
    var chosen by remember { mutableStateOf<app.filenfiles.core.ContactInfo?>(null) }
    var email by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    fun report(msg: String) { scope.launch { env.snackbar.showSnackbar(msg) } }

    val pick = chosen
    if (pick != null) {
        AlertDialog(
            onDismissRequest = { chosen = null },
            title = { Text("Share ${itemsLabel(items)}?") },
            text = {
                Text((if (pick.name != null) "${pick.name} (${pick.email})" else pick.email) + " will get " +
                    (if (items.any { it.isDirectory }) "access to ${if (items.size == 1) "this folder" else "these items"} and everything inside" else "access to ${if (items.size == 1) "this file" else "these files"}") +
                    ". You can stop sharing later from Shared by me.")
            },
            confirmButton = {
                TextButton({
                    chosen = null; onClose()
                    // App scope: this dialog leaves composition right now, which would cancel a screen-scoped launch
                    // (a folder share takes a while and was being cancelled midway).
                    env.app.appScope.launch {
                        try { items.forEach { sharing!!.share(it, pick) }; env.app.notifyChanged(); env.snackbar.showSnackbar("Shared ${itemsLabel(items)} with ${pick.name ?: pick.email}") }
                        catch (e: CancellationException) { throw e }
                        catch (e: Throwable) { env.snackbar.showSnackbar("Couldn't share: ${e.friendly()}") }
                    }
                }) { Text("Share") }
            },
            dismissButton = { TextButton({ chosen = null }) { Text("Back") } },
        )
        return
    }
    AlertDialog(
        onDismissRequest = onClose,
        title = { Text("Share ${itemsLabel(items)}", maxLines = 1, overflow = TextOverflow.Ellipsis) },
        text = {
            Column(Modifier.heightIn(max = 420.dp)) {
                when (val c = contacts) {
                    Load.Loading -> Box(Modifier.fillMaxWidth().height(80.dp), Alignment.Center) { CircularProgressIndicator() }
                    is Load.Fail -> Text(c.message, color = MaterialTheme.colorScheme.error)
                    is Load.Ok -> if (c.value.isEmpty()) {
                        Text("You have no Filen contacts yet. Send a request below; once they accept, they will appear here.",
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    } else LazyColumn(Modifier.weight(1f, fill = false)) {
                        items(c.value, key = { it.id }) { p ->
                            ListItem(
                                modifier = Modifier.clickable { chosen = p },
                                headlineContent = { Text(p.name ?: p.email, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                                supportingContent = if (p.name != null) ({ Text(p.email, maxLines = 1, overflow = TextOverflow.Ellipsis) }) else null,
                            )
                        }
                    }
                }
                HorizontalDivider(Modifier.padding(vertical = 8.dp))
                OutlinedTextField(email, { email = it }, singleLine = true, label = { Text("Add a contact by email") },
                    modifier = Modifier.fillMaxWidth())
                TextButton({
                    val addr = email.trim(); busy = true
                    scope.launch {
                        try { sharing!!.addContact(addr); email = ""; report("Contact request sent to $addr"); reload++ }
                        catch (e: CancellationException) { throw e }
                        catch (e: Throwable) { report("Couldn't send request: ${e.friendly()}") }
                        finally { busy = false }
                    }
                }, enabled = !busy && android.util.Patterns.EMAIL_ADDRESS.matcher(email.trim()).matches()) { Text("Send contact request") }
            }
        },
        confirmButton = { TextButton(onClose) { Text("Close") } },
    )
}

/** Earlier versions of a Filen file: restore one (it becomes the current content) or delete it for good. */
@Composable
private fun VersionHistoryDialog(env: AppEnv, item: FileItem, onClose: () -> Unit) {
    val storage = env.app.router.versioning(item)
    val scope = rememberCoroutineScope()
    var reload by remember { mutableIntStateOf(0) }
    val state = loadState(refresh = reload) {
        val s = storage ?: error("Version history isn't available for this item")
        s.versions(item) to runCatching { s.versioningEnabled() }.getOrDefault(true)
    }
    var confirmEnable by remember { mutableStateOf(false) }
    var confirmRestore by remember { mutableStateOf<app.filenfiles.core.VersionInfo?>(null) }
    var confirmDelete by remember { mutableStateOf<app.filenfiles.core.VersionInfo?>(null) }
    val ctx = androidx.compose.ui.platform.LocalContext.current

    if (confirmEnable) AlertDialog(
        onDismissRequest = { confirmEnable = false },
        title = { Text("Turn on version history?") },
        text = { Text("This changes a setting on your Filen account: from now on, replacing a file keeps its earlier content as a version. Versions count towards your storage.") },
        confirmButton = {
            TextButton({
                confirmEnable = false
                scope.launch {
                    try { storage!!.setVersioningEnabled(true); reload++ }
                    catch (e: CancellationException) { throw e }
                    catch (e: Throwable) { android.widget.Toast.makeText(ctx, e.friendly(), android.widget.Toast.LENGTH_LONG).show() }
                }
            }) { Text("Turn on") }
        },
        dismissButton = { TextButton({ confirmEnable = false }) { Text("Cancel") } },
    )
    confirmRestore?.let { v ->
        AlertDialog(
            onDismissRequest = { confirmRestore = null },
            title = { Text("Restore this version?") },
            text = { Text("“${item.name}” will go back to the version from ${java.text.DateFormat.getDateTimeInstance().format(java.util.Date(v.modified))}. The current content is kept as an earlier version.") },
            confirmButton = {
                TextButton({
                    confirmRestore = null; onClose()
                    env.app.appScope.launch {
                        try { storage!!.restoreVersion(item, v); env.app.notifyChanged(); env.snackbar.showSnackbar("Restored an earlier version of “${item.name}”") }
                        catch (e: CancellationException) { throw e }
                        catch (e: Throwable) { env.snackbar.showSnackbar("Couldn't restore: ${e.friendly()}") }
                    }
                }) { Text("Restore") }
            },
            dismissButton = { TextButton({ confirmRestore = null }) { Text("Cancel") } },
        )
    }
    confirmDelete?.let { v ->
        AlertDialog(
            onDismissRequest = { confirmDelete = null },
            title = { Text("Delete this version?") },
            text = { Text("It is removed permanently and can't be restored. The current file is not affected.") },
            confirmButton = {
                TextButton({
                    confirmDelete = null
                    scope.launch {
                        try { storage!!.deleteVersion(item, v); reload++ }
                        catch (e: CancellationException) { throw e }
                        catch (e: Throwable) { android.widget.Toast.makeText(ctx, e.friendly(), android.widget.Toast.LENGTH_LONG).show() }
                    }
                }) { Text("Delete") }
            },
            dismissButton = { TextButton({ confirmDelete = null }) { Text("Cancel") } },
        )
    }
    AlertDialog(
        onDismissRequest = onClose,
        title = { Text("Versions of “${item.name}”", maxLines = 2, overflow = TextOverflow.Ellipsis) },
        text = {
            Column(Modifier.heightIn(max = 400.dp)) {
                when (val s = state) {
                    Load.Loading -> Box(Modifier.fillMaxWidth().height(80.dp), Alignment.Center) { CircularProgressIndicator() }
                    is Load.Fail -> Text(s.message, color = MaterialTheme.colorScheme.error)
                    is Load.Ok -> if (s.value.first.isEmpty()) Column {
                        Text(if (s.value.second) "No earlier versions. When a file is replaced by a new upload with the same name, the old content shows up here."
                            else "Version history is switched off for your Filen account, so replaced files are simply overwritten and no earlier versions are kept.",
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                        if (!s.value.second) TextButton({ confirmEnable = true }) { Text("Turn on version history…") }
                    }
                    else LazyColumn {
                        items(s.value.first, key = { it.id }) { v ->
                            ListItem(
                                headlineContent = { Text(java.text.DateFormat.getDateTimeInstance().format(java.util.Date(v.modified))) },
                                supportingContent = { Text(android.text.format.Formatter.formatShortFileSize(ctx, v.size)) },
                                trailingContent = {
                                    Row {
                                        TextButton({ confirmRestore = v }) { Text("Restore") }
                                        TextButton({ confirmDelete = v }) { Text("Delete") }
                                    }
                                },
                            )
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClose) { Text("Close") } },
    )
}
