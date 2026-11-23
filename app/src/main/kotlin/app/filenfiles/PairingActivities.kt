package app.filenfiles

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.provider.DocumentsContract
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.selection.selectable
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.lifecycleScope
import app.filenfiles.core.ItemId
import app.filenfiles.docs.FilenDocumentsProvider
import app.filenfiles.filen.FailureKind
import app.filenfiles.filen.FilenSession
import app.filenfiles.filen.FilenStorageProvider
import app.filenfiles.filen.StackLink
import app.filenfiles.filen.failureKind
import app.filenfiles.filen.friendlyOrNull
import app.filenfiles.ui.FilesTheme
import app.filenfiles.ui.LockScreen
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Stack partner pairing (docs/STACK_PARTNER_FOLDERS_SCOPE.md). Contacts are the channel: nobody types an email.
 * Only a pinned caller (Stack; see StackLink) may start these; the caller is the activity's calling package, never an extra.
 * Each shows the other person's email in full and does nothing until Allow. Results and error extras are those of
 * GRANT_BACKUP_FOLDER (not_signed_in | offline | user_cancelled | no_caller | failed) plus waiting_for_owner / waiting_for_partner.
 */
abstract class PairingActivity : FragmentActivity() {
    protected sealed interface Step {
        data object Loading : Step
        data object NotSignedIn : Step
        data object Confirm : Step
        data class Working(val text: String) : Step
    }

    /** Lets the subclass put its dialog text in. */
    protected abstract val title: (caller: String) -> String
    protected abstract val body: String
    protected var step by mutableStateOf<Step>(Step.Loading)
    protected lateinit var app: FilesApp
    protected var callerLabel = ""
    protected var callerPkg = ""
    /** Returns false to stop (the subclass has already finished the activity). */
    protected abstract fun prepare(): Boolean
    protected abstract suspend fun onAllow()
    @Composable protected open fun Extra() {}

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        app = application as FilesApp
        val caller = callingPackage
        val uid = caller?.let { runCatching { packageManager.getPackageUid(it, 0) }.getOrNull() }
        if (caller == null || caller == packageName || uid == null || !StackLink.callerAllowed(this, uid)) { cancel("no_caller"); return }
        callerPkg = caller
        callerLabel = runCatching { packageManager.getApplicationLabel(packageManager.getApplicationInfo(caller, 0)).toString() }.getOrDefault(caller)
        if (!prepare()) return
        setFinishOnTouchOutside(false)
        setContent {
            LaunchedEffect(Unit) {
                repeat(200) { if (app.session.state.value !is FilenSession.State.Unknown) return@repeat; delay(50) }
                step = if (app.session.state.value is FilenSession.State.LoggedIn) Step.Confirm else Step.NotSignedIn
            }
            FilesTheme(androidx.compose.foundation.isSystemInDarkTheme()) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    if (app.appLock.isLocked) { LockScreen(app, this@PairingActivity); return@Box }
                    when (val s = step) {
                        Step.Loading -> CircularProgressIndicator()
                        Step.NotSignedIn -> AlertDialog(
                            onDismissRequest = { cancel("not_signed_in") },
                            title = { Text("Sign in to Filen first") },
                            text = { Text("Open Filen Files and sign in, then try again from $callerLabel.") },
                            confirmButton = { TextButton({ cancel("not_signed_in") }) { Text("OK") } },
                        )
                        Step.Confirm, is Step.Working -> AlertDialog(
                            onDismissRequest = { if (s == Step.Confirm) cancel("user_cancelled") },
                            title = { Text(title(callerLabel)) },
                            text = { Column { Text(if (s is Step.Working) s.text else body); Extra() } },
                            confirmButton = {
                                TextButton(enabled = s == Step.Confirm, onClick = {
                                    step = Step.Working("Setting up…")
                                    lifecycleScope.launch {
                                        try { onAllow() } catch (e: Throwable) {
                                            if (e is CancellationException) throw e
                                            android.util.Log.w("FilenDebug", "Pairing for $callerPkg failed", e)
                                            if (e.failureKind() == FailureKind.NETWORK) cancel("offline")
                                            else cancel("failed", e.friendlyOrNull() ?: "Couldn't finish pairing")
                                        }
                                    }
                                }) { Text(if (s is Step.Working) "Please wait…" else "Allow") }
                            },
                            dismissButton = { TextButton({ cancel("user_cancelled") }) { Text("Cancel") } },
                        )
                    }
                }
            }
        }
    }

    override fun onStart() { super.onStart(); app.appLock.onStart() }
    override fun onStop() { super.onStop(); app.appLock.onStop() }

    protected fun finishWithTree(id: ItemId, write: Boolean, extra: Intent.() -> Unit = {}) {
        val uri = DocumentsContract.buildTreeDocumentUri(FilenDocumentsProvider.AUTHORITY, id.value)
        var flags = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION or Intent.FLAG_GRANT_PREFIX_URI_PERMISSION
        if (write) flags = flags or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
        setResult(Activity.RESULT_OK, Intent().setData(uri).addFlags(flags).apply(extra))
        finish()
    }

    protected fun cancel(error: String, message: String? = null) {
        setResult(Activity.RESULT_CANCELED, Intent().putExtra(GrantFolderActivity.EXTRA_ERROR, error).apply {
            if (message != null) putExtra(GrantFolderActivity.EXTRA_MESSAGE, message)
        })
        finish()
    }

    companion object {
        const val FOLDER = "Stack shared"
        const val POLL_MS = 3_000L
        const val TIMEOUT_MS = 5 * 60_000L
    }
}

/** Partner phone: `GRANT_RECEIVED_FOLDER` with `OWNER_EMAIL` -> a read-only tree grant on the owner's "Stack shared" folder. */
class GrantReceivedFolderActivity : PairingActivity() {
    private var owner = ""
    override val title: (String) -> String = { "Let $it read documents $owner shares with you?" }
    override val body get() = "$callerLabel can then read the folder “$FOLDER” that $owner shares with you in Filen. It cannot change it, also while Filen Files is locked."

    override fun prepare(): Boolean {
        owner = intent.getStringExtra(EXTRA_OWNER_EMAIL)?.trim().orEmpty()
        if (!owner.contains('@') || owner.length > 254 || owner.any { it.isWhitespace() || it.isISOControl() }) { cancel("failed", "No account to connect to"); return false }
        return true
    }

    override suspend fun onAllow() {
        val filen = app.filen
        withContext(Dispatchers.IO) { filen.connectTo(owner) }
        step = Step.Working("Waiting for $owner to finish on their phone…")
        val end = System.currentTimeMillis() + TIMEOUT_MS
        var found: ItemId? = null
        while (found == null && System.currentTimeMillis() < end) {
            found = withContext(Dispatchers.IO) { filen.receivedFolderFrom(owner, FOLDER) }
            if (found == null) delay(POLL_MS)
        }
        if (found == null) { cancel("waiting_for_owner"); return }
        app.prefs.setLockedReader(callerPkg, true, callerLabel)
        finishWithTree(found, write = false) { putExtra(GrantFolderActivity.EXTRA_FOLDER_NAME, FOLDER) }
    }

    companion object {
        const val ACTION = "app.filenfiles.action.GRANT_RECEIVED_FOLDER"
        const val EXTRA_OWNER_EMAIL = "app.filenfiles.extra.OWNER_EMAIL"
    }
}

/** Owner phone: `GRANT_SHARE_FOLDER` -> accepts the partner's contact request, shares "Stack shared" with them, read+write tree grant. */
class GrantShareFolderActivity : PairingActivity() {
    private var requests by mutableStateOf<List<FilenStorageProvider.ContactRequest>>(emptyList())
    private var chosen by mutableStateOf<FilenStorageProvider.ContactRequest?>(null)
    private var folderName = FOLDER
    override val title: (String) -> String = { _ ->
        chosen?.let { "Share Filen Drive › $folderName with ${it.email}?" } ?: "Waiting for your partner to scan…"
    }
    override val body get() = chosen?.let { "$callerLabel can then add and replace files in that folder, also while Filen Files is locked. ${it.email} will be able to read it." }
        ?: "Your partner scans the code on their phone; this will continue by itself."

    override fun prepare(): Boolean {
        folderName = GrantFolderActivity.cleanName(intent.getStringExtra(GrantFolderActivity.EXTRA_FOLDER_NAME)) ?: FOLDER
        lifecycleScope.launch {
            while (true) { // watch for the partner's request until the user acts
                runCatching { withContext(Dispatchers.IO) { app.filen.incomingRequests() } }.getOrNull()?.let { list ->
                    requests = list
                    if (chosen == null || list.none { it.uuid == chosen?.uuid }) chosen = list.singleOrNull()
                }
                delay(POLL_MS)
            }
        }
        return true
    }

    @Composable override fun Extra() {
        if (requests.size > 1) Column(Modifier.padding(top = 8.dp)) {
            Text("Several people asked to connect. Who is your partner?")
            requests.forEach { r ->
                Row(Modifier.fillMaxWidth().selectable(chosen?.uuid == r.uuid) { chosen = r }, verticalAlignment = Alignment.CenterVertically) {
                    RadioButton(chosen?.uuid == r.uuid, { chosen = r }); Text(r.email)
                }
            }
        }
    }

    override suspend fun onAllow() {
        val req = chosen
        if (req == null) { step = Step.Confirm; return } // nobody to share with yet
        val filen = app.filen
        val root = ItemId("filen:root")
        val id = withContext(Dispatchers.IO) {
            val folder = app.router.list(root).items.firstOrNull { it.isDirectory && it.name == folderName }?.id
                ?: app.router.writable(root)!!.createFolder(root, folderName).also { app.docsChanged(root); app.notifyChanged() }
            filen.shareFolderWith(folder, req.email, req)
            folder
        }
        app.prefs.grantBackupWriter(callerPkg, callerLabel)
        finishWithTree(id, write = true) { putExtra(GrantFolderActivity.EXTRA_FOLDER_NAME, folderName) }
    }

    companion object { const val ACTION = "app.filenfiles.action.GRANT_SHARE_FOLDER" }
}
