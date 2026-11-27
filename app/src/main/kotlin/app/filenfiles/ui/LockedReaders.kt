package app.filenfiles.ui

import android.content.Context
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext
import app.filenfiles.FilesApp
import androidx.compose.runtime.getValue
import app.filenfiles.LockedAccess
import app.filenfiles.core.ItemId
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** The app's name as the owner knows it ("Finance"); falls back to the package name if Android won't tell us. */
fun appLabel(ctx: Context, pkg: String): String = runCatching {
    val pm = ctx.packageManager
    pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString()
}.getOrDefault(pkg)

/** Asked once per (app, folder) after unlocking when a pinned app's locked write was refused (see Prefs.noteRefusedWrite). */
@Composable
fun LockedWriterDialog(app: FilesApp, entry: String) {
    val ctx = LocalContext.current
    val pkg = LockedAccess.pkgOf(entry); val tree = LockedAccess.treeOf(entry)
    val name = appLabel(ctx, pkg)
    // The real folder name; when it cannot be resolved nothing is asked and the request stays pending.
    val folder by androidx.compose.runtime.produceState<String?>(null, tree) {
        value = runCatching { withContext(Dispatchers.IO) { app.router.stat(ItemId(LockedAccess.treeDocId(tree)))?.name } }.getOrNull()?.takeIf { it.isNotBlank() }
    }
    val folderName = folder ?: return
    fun answer(allow: Boolean) = app.prefs.answerWrite(pkg, tree, allow, name)
    AlertDialog(
        onDismissRequest = { }, // not answered: stays remembered and is asked again next time
        title = { Text("Let $name read, add and remove files in “$folderName” while Filen Files is locked?") },
        text = { Text("$name can then read, add and remove files in that folder without unlocking Filen Files. Nothing else in your Filen.") },
        confirmButton = { TextButton({ answer(true) }) { Text("Allow") } },
        dismissButton = { TextButton({ answer(false) }) { Text("Not now") } },
    )
}

/**
 * Asked once per (app, folder), right after unlocking, when an app holding a grant the owner gave it was refused while locked
 * (see FilenDocumentsProvider.lockedAccessAllowed). The answer can be changed later in Settings > Security.
 */
@Composable
fun LockedReaderDialog(app: FilesApp, entry: String) {
    val ctx = LocalContext.current
    val pkg = LockedAccess.pkgOf(entry); val tree = LockedAccess.treeOf(entry)
    val name = appLabel(ctx, pkg)
    val scope = LockedAccess.scope(tree)
    fun answer(allow: Boolean) { app.prefs.setLockedReader(pkg, tree, allow, name); app.appLock.askReader = null }
    AlertDialog(
        onDismissRequest = { app.appLock.askReader = null }, // not answered: asked again next time
        title = { Text("Let $name read $scope while locked?") },
        text = { Text("$name can then read $scope without unlocking Filen Files. Nothing else in your Filen.") },
        confirmButton = { TextButton({ answer(true) }) { Text("Allow") } },
        dismissButton = { TextButton({ answer(false) }) { Text("Not now") } },
    )
}
