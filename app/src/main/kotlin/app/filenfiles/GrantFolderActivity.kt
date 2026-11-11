package app.filenfiles

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.provider.DocumentsContract
import androidx.activity.compose.setContent
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.unit.dp
import androidx.core.graphics.drawable.toBitmap
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.lifecycleScope
import app.filenfiles.core.ItemId
import app.filenfiles.docs.FilenDocumentsProvider
import app.filenfiles.filen.FailureKind
import app.filenfiles.filen.FilenSession
import app.filenfiles.filen.failureKind
import app.filenfiles.filen.friendlyOrNull
import app.filenfiles.ui.FilesTheme
import app.filenfiles.ui.LockScreen
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Hands another app a backup folder in Filen Drive after one "Allow" tap - no folder browsing.
 *
 * Contract (agreed with Stack): start with startActivityForResult and action [ACTION]; optional [EXTRA_FOLDER_NAME].
 * RESULT_OK: data = tree URI of the folder (top level of Filen Drive, created or reused), flags READ|WRITE|PERSISTABLE|
 * PREFIX - the caller takes the persistable permission - plus [EXTRA_FOLDER_NAME] = the name used.
 * RESULT_CANCELED: [EXTRA_ERROR] = not_signed_in | offline | user_cancelled | no_caller | failed (+ [EXTRA_MESSAGE]).
 *
 * The caller's name and icon come from PackageManager (getCallingPackage), never from extras. If the app lock is on and
 * locked, the owner unlocks first. Allow also lets that app use the folder while Filen Files is locked (list, read,
 * add files, delete to the Filen trash; see FilenDocumentsProvider.lockedAccessAllowed).
 */
class GrantFolderActivity : FragmentActivity() {
    private sealed interface Step {
        data object Loading : Step
        data object NotSignedIn : Step
        data object Confirm : Step
        data object Working : Step
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val app = application as FilesApp
        val caller = callingPackage
        if (caller == null || caller == packageName) { cancel("no_caller"); return }
        val pm = packageManager
        val label = runCatching { pm.getApplicationLabel(pm.getApplicationInfo(caller, 0)).toString() }.getOrDefault(caller)
        val icon = runCatching { pm.getApplicationIcon(caller).toBitmap(96, 96).asImageBitmap() }.getOrNull()
        val folder = cleanName(intent.getStringExtra(EXTRA_FOLDER_NAME)) ?: cleanName("$label backups") ?: "Backups"
        setFinishOnTouchOutside(false)

        setContent {
            var step by remember { mutableStateOf<Step>(Step.Loading) }
            LaunchedEffect(Unit) {
                // Session restore runs in the background after a cold start.
                repeat(200) { if (app.session.state.value !is FilenSession.State.Unknown) return@repeat; delay(50) }
                step = if (app.session.state.value is FilenSession.State.LoggedIn) Step.Confirm else Step.NotSignedIn
            }
            FilesTheme(androidx.compose.foundation.isSystemInDarkTheme()) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    if (app.appLock.isLocked) { LockScreen(app, this@GrantFolderActivity); return@Box }
                    when (step) {
                        Step.Loading -> CircularProgressIndicator()
                        Step.NotSignedIn -> AlertDialog(
                            onDismissRequest = { cancel("not_signed_in") },
                            title = { Text("Sign in to Filen first") },
                            text = { Text("Open Filen Files and sign in, then try again from $label.") },
                            confirmButton = { TextButton({ cancel("not_signed_in") }) { Text("OK") } },
                        )
                        Step.Confirm, Step.Working -> AlertDialog(
                            onDismissRequest = { if (step == Step.Confirm) cancel("user_cancelled") },
                            icon = icon?.let { { Image(it, null, Modifier.size(48.dp)) } },
                            title = { Text("Let $label save backups in Filen Drive › $folder?") },
                            text = { Text("$label can then add and remove files in that folder, also while Filen Files is locked.") },
                            confirmButton = {
                                TextButton(enabled = step == Step.Confirm, onClick = {
                                    step = Step.Working
                                    lifecycleScope.launch { grant(app, caller, label, folder) }
                                }) { Text(if (step == Step.Working) "Setting up…" else "Allow") }
                            },
                            dismissButton = { TextButton(enabled = step == Step.Confirm, onClick = { cancel("user_cancelled") }) { Text("Cancel") } },
                        )
                    }
                }
            }
        }
    }

    override fun onStart() { super.onStart(); (application as FilesApp).appLock.onStart() }
    override fun onStop() { super.onStop(); (application as FilesApp).appLock.onStop() }

    /** Reuses a top-level folder with that name, or creates it, then returns its tree URI with a persistable grant. */
    private suspend fun grant(app: FilesApp, caller: String, label: String, name: String) {
        val root = ItemId("filen:root")
        try {
            val id = withContext(Dispatchers.IO) {
                app.router.list(root).items.firstOrNull { it.isDirectory && it.name == name }?.id
                    ?: app.router.writable(root)!!.createFolder(root, name).also { app.docsChanged(root); app.notifyChanged() }
            }
            app.prefs.grantBackupWriter(caller, label)
            val uri = DocumentsContract.buildTreeDocumentUri(FilenDocumentsProvider.AUTHORITY, id.value)
            setResult(Activity.RESULT_OK, Intent().setData(uri).putExtra(EXTRA_FOLDER_NAME, name).addFlags(
                Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION or
                    Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION or Intent.FLAG_GRANT_PREFIX_URI_PERMISSION))
            finish()
        } catch (e: Throwable) {
            if (e is kotlinx.coroutines.CancellationException) throw e
            android.util.Log.w("FilenDebug", "Backup folder for $caller failed", e)
            if (e.failureKind() == FailureKind.NETWORK) cancel("offline")
            else cancel("failed", e.friendlyOrNull() ?: "Couldn't set up the folder")
        }
    }

    private fun cancel(error: String, message: String? = null) {
        setResult(Activity.RESULT_CANCELED, Intent().putExtra(EXTRA_ERROR, error).apply { if (message != null) putExtra(EXTRA_MESSAGE, message) })
        finish()
    }

    companion object {
        const val ACTION = "app.filenfiles.action.GRANT_BACKUP_FOLDER"
        const val EXTRA_FOLDER_NAME = "app.filenfiles.extra.FOLDER_NAME"
        const val EXTRA_ERROR = "app.filenfiles.extra.ERROR"
        const val EXTRA_MESSAGE = "app.filenfiles.extra.MESSAGE"

        fun cleanName(raw: String?): String? =
            raw?.replace('/', '-')?.replace(Regex("[\\u0000-\\u001f]"), "")?.trim()?.take(100)?.takeIf { it.isNotBlank() && it != "." && it != ".." }
    }
}
