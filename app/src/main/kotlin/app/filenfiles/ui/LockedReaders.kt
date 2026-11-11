package app.filenfiles.ui

import android.content.Context
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext
import app.filenfiles.FilesApp

/** The app's name as the owner knows it ("Finance"); falls back to the package name if Android won't tell us. */
fun appLabel(ctx: Context, pkg: String): String = runCatching {
    val pm = ctx.packageManager
    pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString()
}.getOrDefault(pkg)

/**
 * Asked once, right after unlocking, when an app that holds a folder the owner gave it was refused while locked
 * (see FilenDocumentsProvider.lockedReadAllowed). The answer can be changed later in Settings > Security.
 */
@Composable
fun LockedReaderDialog(app: FilesApp, pkg: String) {
    val ctx = LocalContext.current
    val name = appLabel(ctx, pkg)
    fun answer(allow: Boolean) { app.prefs.setLockedReader(pkg, allow, name); app.appLock.askReader = null }
    AlertDialog(
        onDismissRequest = { app.appLock.askReader = null }, // not answered: asked again next time
        title = { Text("Let $name read while locked?") },
        text = { Text("$name can then open the Filen folder you gave it without unlocking Filen Files. Nothing else.") },
        confirmButton = { TextButton({ answer(true) }) { Text("Allow") } },
        dismissButton = { TextButton({ answer(false) }) { Text("Not now") } },
    )
}
