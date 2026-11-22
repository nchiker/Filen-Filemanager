package app.filenfiles.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.PersistableBundle
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import app.filenfiles.core.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

/** Copies [text] and tells Android it is sensitive (keeps it out of clipboard previews and suggestions). Never logs it. */
fun copySensitive(ctx: Context, label: String, text: String) {
    val clip = ClipData.newPlainText(label, text)
    clip.description.extras = PersistableBundle().apply { putBoolean("android.content.extra.IS_SENSITIVE", true) }
    ctx.getSystemService(ClipboardManager::class.java).setPrimaryClip(clip)
}

/**
 * Create, change, copy or remove the public link of one personal-drive item. Anyone with the URL can read the item (and, for a folder,
 * everything inside it) until the link expires or is removed; the key is in the URL, so Filen itself cannot read the content.
 */
@Composable
fun LinkDialog(env: AppEnv, item: FileItem, onClose: () -> Unit, onChanged: () -> Unit = {}) {
    val links = env.app.router.linkStorage()
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var loading by remember { mutableStateOf(true) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var info by remember { mutableStateOf<LinkInfo?>(null) }
    var expiry by remember { mutableStateOf(LinkExpiry.ONE_WEEK) }
    var downloadable by remember { mutableStateOf(true) }
    var password by remember { mutableStateOf("") }
    var removePassword by remember { mutableStateOf(false) }
    var expiryMenu by remember { mutableStateOf(false) }

    LaunchedEffect(item.id) {
        try {
            info = links?.linkOf(item)
            info?.let { expiry = it.expiry; downloadable = it.downloadable }
        } catch (e: CancellationException) { throw e } catch (e: Exception) { error = e.friendly() }
        loading = false
    }

    fun act(block: suspend () -> Unit) {
        busy = true; error = null
        // appScope: closing the dialog must not cancel a half-done link change
        env.app.appScope.launch {
            try { block(); onChanged() } catch (e: CancellationException) { throw e } catch (e: Exception) { error = e.friendly() }
            busy = false
        }
    }

    AlertDialog(
        onDismissRequest = { if (!busy) onClose() },
        title = { Text(if (info == null) "Public link" else "Public link on") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (loading) LinearProgressIndicator(Modifier.fillMaxWidth())
                if (item.isDirectory) Text("Anyone with the link can see everything in this folder.", style = MaterialTheme.typography.bodySmall)
                else Text("Anyone with the link can open this file.", style = MaterialTheme.typography.bodySmall)
                Box {
                    OutlinedButton({ expiryMenu = true }, enabled = !busy) { Text("Expires: ${expiry.label}") }
                    DropdownMenu(expiryMenu, { expiryMenu = false }) {
                        LinkExpiry.entries.forEach { e -> DropdownMenuItem({ Text(e.label) }, { expiry = e; expiryMenu = false }) }
                    }
                }
                OutlinedTextField(
                    password, { password = it; if (it.isNotEmpty()) removePassword = false }, Modifier.fillMaxWidth(), singleLine = true,
                    label = { Text(if (info?.hasPassword == true) "New password (leave empty to keep)" else "Password (optional)") },
                    visualTransformation = PasswordVisualTransformation(), enabled = !busy,
                )
                if (info?.hasPassword == true) Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(removePassword, { removePassword = it; if (it) password = "" }, enabled = !busy)
                    Text("Remove the password")
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Switch(downloadable, { downloadable = it }, enabled = !busy)
                    Spacer(Modifier.width(8.dp)); Text("Allow downloads")
                }
                info?.url?.let { url ->
                    androidx.compose.foundation.text.selection.SelectionContainer {
                        Text(url, style = MaterialTheme.typography.bodySmall, maxLines = 3, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
                    }
                }
                error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
                if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
            }
        },
        confirmButton = {
            Row {
                info?.url?.let { url ->
                    TextButton({ copySensitive(ctx, "Public link", url); android.widget.Toast.makeText(ctx, "Link copied", android.widget.Toast.LENGTH_SHORT).show() }, enabled = !busy) { Text("Copy") }
                }
                TextButton({
                    act {
                        val change = when {
                            password.isNotEmpty() -> PasswordChange.Set(password)
                            removePassword -> PasswordChange.Remove
                            else -> PasswordChange.Keep
                        }
                        info = links!!.saveLink(item, LinkSettings(expiry, change, downloadable))
                        password = ""; removePassword = false
                    }
                }, enabled = !busy && !loading && links != null) { Text(if (info == null) "Create link" else "Save") }
            }
        },
        dismissButton = {
            Row {
                if (info != null) TextButton({
                    act { links!!.removeLink(item); info = null; password = ""; env.app.appScope.launch { env.snackbar.showSnackbar("Public link removed") } } // showSnackbar suspends until it is gone: don't keep the dialog busy meanwhile
                }, enabled = !busy) { Text("Remove link", color = MaterialTheme.colorScheme.error) }
                TextButton(onClose, enabled = !busy) { Text("Close") }
            }
        },
    )
}
