package app.filenfiles

import android.app.Activity
import android.content.ClipData
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.DocumentsContract
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.dp
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.lifecycleScope
import app.filenfiles.core.ItemId
import app.filenfiles.docs.FilenDocumentsProvider
import app.filenfiles.filen.FailureKind
import app.filenfiles.filen.FilenSession
import app.filenfiles.filen.FilenStorageProvider
import app.filenfiles.filen.FolderPlan
import app.filenfiles.filen.Partner
import app.filenfiles.filen.PartnerState
import app.filenfiles.filen.Person
import app.filenfiles.filen.StackLink
import app.filenfiles.filen.StopChoice
import app.filenfiles.filen.failureKind
import app.filenfiles.filen.friendlyOrNull
import app.filenfiles.ui.AlertDialog
import app.filenfiles.ui.FilesTheme
import app.filenfiles.ui.TapGuard
import app.filenfiles.ui.LockScreen
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Partner screens for Stack and Ami Pro (docs/FOLDER_SHARING_INTERFACES.md I4 F7, F8). Only a pinned caller may start
 * them; the caller is the activity's calling package, never an extra. Nothing is shared or granted before a tap on a
 * button that names what happens. Errors use GRANT_BACKUP_FOLDER's extras: not_signed_in | offline | user_cancelled |
 * no_caller | failed.
 */
abstract class PartnerActivity : FragmentActivity() {
    protected lateinit var app: FilesApp
    protected var callerPkg = ""
    protected var callerLabel = ""
    private var ready by mutableStateOf<Boolean?>(null) // null = checking, false = signed out

    /** Returns false to stop (the subclass has already finished the activity). */
    protected abstract fun prepare(): Boolean
    /** The screen, shown once signed in and unlocked. */
    @Composable protected abstract fun Content()

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
        window.decorView.filterTouchesWhenObscured = true // tapjacking: no touches while another window covers this one
        setContent {
            LaunchedEffect(Unit) {
                repeat(200) { if (app.session.state.value !is FilenSession.State.Unknown) return@repeat; delay(50) }
                ready = app.session.state.value is FilenSession.State.LoggedIn
            }
            FilesTheme(androidx.compose.foundation.isSystemInDarkTheme()) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    TapGuard()
                    if (app.appLock.isLocked) { LockScreen(app, this@PartnerActivity); return@Box }
                    when (ready) {
                        null -> CircularProgressIndicator()
                        false -> AlertDialog(
                            onDismissRequest = { cancel("not_signed_in") },
                            title = { Text("Sign in to Filen first") },
                            text = { Column { TapGuard(); Text("Open Filen Files and sign in, then try again.") } },
                            confirmButton = { TextButton({ cancel("not_signed_in") }) { Text("OK") } },
                        )
                        true -> Content()
                    }
                }
            }
        }
    }

    override fun onStart() { super.onStart(); app.appLock.onStart() }
    override fun onStop() { super.onStop(); app.appLock.onStop() }

    protected fun cancel(error: String, message: String? = null) {
        setResult(Activity.RESULT_CANCELED, Intent().putExtra(GrantFolderActivity.EXTRA_ERROR, error).apply {
            if (message != null) putExtra(GrantFolderActivity.EXTRA_MESSAGE, message)
        })
        finish()
    }

    protected fun failMessage(e: Throwable) = if (e.failureKind() == FailureKind.NETWORK) "No connection. Try again." else e.friendlyOrNull() ?: "Something went wrong."

    companion object {
        const val EXTRA_PARTNER_NAME = "app.filenfiles.extra.PARTNER_NAME"
        const val EXTRA_STATE = "app.filenfiles.extra.STATE"
        const val EXTRA_SHARED = "app.filenfiles.extra.SHARED"
        const val EXTRA_STOP = "app.filenfiles.extra.STOP"
        const val EXTRA_FILEN_UUID = "app.filenfiles.extra.FILEN_UUID"
        /** STOP result: the name of the shared folder that was stopped instead, when the item was shared only through it. */
        const val EXTRA_VIA_FOLDER = "app.filenfiles.extra.VIA_FOLDER"
    }
}

/**
 * What the partner screen found out; [state] is CONNECTED or REQUESTED. [name] is the cleaned nickname only (what other
 * apps get); [label] is "nick (email)" and is what every sheet of ours shows, so a nickname can never pass for someone else.
 */
private class PartnerDone(val state: PartnerState, val name: String, val email: String, val label: String)

private fun doneFor(state: PartnerState, p: Person) = PartnerDone(state, Partner.displayName(p), p.email, Partner.label(p))

/**
 * One screen: accept the partner's request, or enter their email to send one. Calls [onDone] with the result (the
 * partner is also remembered, tied to this account).
 */
@Composable
private fun PartnerSetup(app: FilesApp, onDone: (PartnerDone) -> Unit, onCancel: () -> Unit, onFail: (Throwable) -> String) {
    val scope = rememberCoroutineScope()
    var loading by remember { mutableStateOf(true) }
    var incoming by remember { mutableStateOf<List<FilenStorageProvider.ContactRequest>>(emptyList()) }
    var email by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val account = app.filen.accountId

    fun finish(state: PartnerState, mail: String, nick: String?) {
        app.prefs.setPartner(account, mail)
        onDone(doneFor(state, Person(mail, nick)))
    }
    fun run(block: suspend () -> Unit) {
        busy = true; error = null
        scope.launch {
            try { block() } catch (e: CancellationException) { throw e } catch (e: Throwable) { error = onFail(e) } finally { busy = false }
        }
    }
    LaunchedEffect(Unit) {
        try {
            val lists = withContext(Dispatchers.IO) { app.filen.partnerLists() }
            val saved = app.prefs.partnerFor(account)
            val have = saved?.let { s -> lists.contacts.firstOrNull { it.email.equals(s, true) } }
            if (have != null) { onDone(doneFor(PartnerState.CONNECTED, have)); return@LaunchedEffect }
            incoming = lists.incoming
        } catch (e: CancellationException) { throw e } catch (e: Throwable) { error = onFail(e) }
        loading = false
    }
    if (loading && error == null) { CircularProgressIndicator(); return }
    AlertDialog(
        onDismissRequest = { if (!busy) onCancel() },
        title = { Text("Add your partner") },
        text = {
            Column {
                TapGuard()
                if (incoming.isNotEmpty()) {
                    Text("Asked to connect:")
                    incoming.forEach { r ->
                        TextButton(enabled = !busy, modifier = Modifier.fillMaxWidth(), onClick = { run { withContext(Dispatchers.IO) { app.filen.acceptRequest(r) }; finish(PartnerState.CONNECTED, r.email, r.nick) } }) {
                            Text("Accept ${Partner.label(r.person)}", maxLines = 3, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
                        }
                    }
                    Spacer(Modifier.height(8.dp))
                }
                OutlinedTextField(email, { email = it }, singleLine = true, label = { Text("Partner's Filen email") }, modifier = Modifier.fillMaxWidth())
                error?.let { Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(top = 8.dp)) }
            }
        },
        confirmButton = {
            TextButton(enabled = !busy && android.util.Patterns.EMAIL_ADDRESS.matcher(email.trim()).matches(), onClick = {
                run {
                    val mail = email.trim()
                    val r = withContext(Dispatchers.IO) { app.filen.connectTo(mail) }
                    finish(if (r == FilenStorageProvider.Contacted.CONNECTED) PartnerState.CONNECTED else PartnerState.REQUESTED, mail, null)
                }
            }) { Text(if (busy) "Please wait…" else "Send request") }
        },
        dismissButton = { TextButton(enabled = !busy, onClick = onCancel) { Text("Cancel") } },
    )
}

/**
 * `app.filenfiles.action.PARTNER_SETUP` (I4 F8): partner request / accept, then asks to let the calling app read
 * "Shared with me" and "Shared by me". Result: persistable READ grants as clip data, extras PARTNER_NAME and STATE.
 */
class PartnerSetupActivity : PartnerActivity() {
    private var done by mutableStateOf<PartnerDone?>(null)
    private var granting by mutableStateOf(false)
    override fun prepare() = true

    @Composable override fun Content() {
        val d = done
        if (d == null) { PartnerSetup(app, { done = it }, { cancel("user_cancelled") }, ::failMessage); return }
        AlertDialog(
            onDismissRequest = { if (!granting) cancel("user_cancelled") },
            title = { Text("Let $callerLabel read shared files?") },
            text = {
                Column {
                    TapGuard()
                    Text((if (d.state == PartnerState.REQUESTED) "Request sent to ${d.label}. " else "Your partner: ${d.label}. ") +
                        "$callerLabel can read “Shared with me” and “Shared by me”, even when Filen Files is locked. Nothing else in your Filen, and it cannot change anything.")
                }
            },
            confirmButton = { TextButton(enabled = !granting, onClick = { granting = true; grant(d) }) { Text("Allow") } },
            dismissButton = { TextButton(enabled = !granting, onClick = { cancel("user_cancelled") }) { Text("Cancel") } },
        )
    }

    private fun grant(d: PartnerDone) {
        fun tree(id: String): Uri = DocumentsContract.buildTreeDocumentUri(FilenDocumentsProvider.AUTHORITY, id)
        val inUri = tree("filen-in:root"); val outUri = tree("filen-out:root")
        // While locked, this app may read exactly these two trees (not any other grant it holds, e.g. the whole drive).
        app.prefs.allowReaderTrees(callerPkg, listOf(inUri.toString(), outUri.toString()), callerLabel)
        val flags = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION or Intent.FLAG_GRANT_PREFIX_URI_PERMISSION
        val clip = ClipData.newRawUri("Filen", inUri).apply { addItem(ClipData.Item(outUri)) }
        val result = Intent().setData(inUri).addFlags(flags).putExtra(EXTRA_PARTNER_NAME, d.name).putExtra(EXTRA_STATE, d.state.wire)
        result.clipData = clip
        setResult(Activity.RESULT_OK, result)
        finish()
    }

    companion object { const val ACTION = "app.filenfiles.action.PARTNER_SETUP" }
}

/**
 * `app.filenfiles.action.SHARE_WITH_PARTNER` (I4 F7): Filen's own confirm sheet to share one file or folder of the drive
 * with the partner (or, with STOP, to stop). Nothing is shared without the tap. Without a partner yet it offers the
 * partner setup first. Result: RESULT_OK + SHARED, or RESULT_CANCELED.
 */
class SharePartnerActivity : PartnerActivity() {
    private var target: ItemId? = null
    private var stop = false
    private var itemName by mutableStateOf<String?>(null)
    private var isDir = false
    private var partner by mutableStateOf<PartnerDone?>(null)
    private var message by mutableStateOf<String?>(null)
    private var busy by mutableStateOf(false)
    private var needSetup by mutableStateOf(false)
    private var loaded by mutableStateOf(false)
    /** STOP only: how the partner has access (see [StopChoice]); Direct until [load] has asked. */
    private var stopChoice by mutableStateOf<StopChoice>(StopChoice.Direct)

    override fun prepare(): Boolean {
        stop = intent.getBooleanExtra(EXTRA_STOP, false)
        val uuid = intent.getStringExtra(EXTRA_FILEN_UUID)?.trim()?.lowercase()
            ?: intent.data?.let { uuidOf(it) }
        if (uuid == null || !UUID.matches(uuid)) { cancel("failed", "Nothing to share"); return false }
        target = ItemId("filen:$uuid")
        return true
    }

    private fun uuidOf(uri: Uri): String? {
        if (uri.authority != FilenDocumentsProvider.AUTHORITY) return null
        val id = runCatching {
            if (DocumentsContract.isDocumentUri(this, uri)) DocumentsContract.getDocumentId(uri) else DocumentsContract.getTreeDocumentId(uri)
        }.getOrNull() ?: return null
        val real = if (id.startsWith("filen-pending:")) app.transfers.docIds.get(id) ?: return null else id
        return real.takeIf { it.startsWith("filen:") }?.removePrefix("filen:")?.lowercase()
    }

    @Composable override fun Content() {
        LaunchedEffect(Unit) { load() }
        if (!loaded) { CircularProgressIndicator(); return }
        val name = itemName ?: return
        val p = partner
        if (needSetup || p == null) {
            if (needSetup) { PartnerSetup(app, { partner = it; needSetup = false; message = if (it.state == PartnerState.REQUESTED) "Waiting for ${it.label} to accept. Try again then." else null },
                { needSetup = false }, ::failMessage); return }
            AlertDialog(
                onDismissRequest = { cancel("user_cancelled") },
                title = { Text("Add a partner first") },
                text = { Column { TapGuard(); Text("Share files with one person you trust.") } },
                confirmButton = { TextButton({ needSetup = true }) { Text("Add partner") } },
                dismissButton = { TextButton({ cancel("user_cancelled") }) { Text("Cancel") } },
            )
            return
        }
        AlertDialog(
            onDismissRequest = { if (!busy) cancel("user_cancelled") },
            title = { Text(if (stop) "Stop sharing with ${p.label}?" else "Share with ${p.label}?") },
            text = {
                val sc = stopChoice
                Column {
                    TapGuard()
                    Text(if (stop && sc is StopChoice.ViaFolder) "${p.label} can still open “$name” through the shared folder “${sc.name}”. Stop sharing “${sc.name}” instead?"
                        else if (stop && sc is StopChoice.NotShared) "“$name” is not shared with ${p.label}."
                        else if (stop) "${p.label} can no longer open “$name”."
                        else if (isDir) "${p.label} can open “$name” and everything in it. You can stop later."
                        else "${p.label} can open “$name”. You can stop later.")
                    message?.let { Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(top = 8.dp)) }
                }
            },
            confirmButton = {
                TextButton(enabled = !busy && p.state == PartnerState.CONNECTED, onClick = { go(p) }) {
                    val sc = stopChoice
                    Text(if (busy) "Please wait…" else if (stop && sc is StopChoice.ViaFolder) "Stop sharing “${sc.name}”" else if (stop && sc is StopChoice.NotShared) "OK" else if (stop) "Stop sharing" else "Share")
                }
            },
            dismissButton = { TextButton(enabled = !busy, onClick = { cancel("user_cancelled") }) { Text("Cancel") } },
        )
    }

    private suspend fun load() {
        try {
            val id = target!!
            val item = withContext(Dispatchers.IO) {
                app.filen.currentHead(id)?.takeIf { !it.trashed }?.item ?: app.router.freshStat(id)
            }
            if (item == null || !app.filen.canShare(item)) { cancel("failed", "That item is not in your Filen drive"); return }
            target = item.id; itemName = item.name; isDir = item.isDirectory
            val saved = app.prefs.partnerFor(app.filen.accountId)
            if (saved != null) {
                val st = withContext(Dispatchers.IO) { app.filen.partnerStatus(saved) }
                val who = st.who
                if (who != null && st.state != PartnerState.NONE) partner = doneFor(
                    if (st.state == PartnerState.CONNECTED) PartnerState.CONNECTED else PartnerState.REQUESTED, who)
                if (partner?.state == PartnerState.REQUESTED) message = "Waiting for ${partner!!.label} to accept. Try again then."
                // STOP: does the partner have access to this item itself, or only through a folder above it?
                if (stop && saved != null && partner?.state == PartnerState.CONNECTED) {
                    stopChoice = StopChoice.of(withContext(Dispatchers.IO) { app.filen.shareState(item.id.key, saved) })
                }
            }
            loaded = true
        } catch (e: CancellationException) { throw e } catch (e: Throwable) {
            cancel(if (e.failureKind() == FailureKind.NETWORK) "offline" else "failed", failMessage(e))
        }
    }

    private fun go(p: PartnerDone) {
        val sc = stopChoice
        if (stop && sc is StopChoice.NotShared) { setResult(Activity.RESULT_OK, Intent().putExtra(EXTRA_SHARED, false)); finish(); return }
        busy = true; message = null
        lifecycleScope.launch {
            try {
                val via = if (stop && sc is StopChoice.ViaFolder) sc else null
                val shared = withContext(Dispatchers.IO) {
                    if (via != null) app.filen.sharePartner(ItemId("filen:${via.uuid}"), p.email, true) // stops the folder
                    else app.filen.sharePartner(target!!, p.email, stop)
                }
                app.notifyChanged()
                runCatching { contentResolver.notifyChange(DocumentsContract.buildChildDocumentsUri(FilenDocumentsProvider.AUTHORITY, "filen-out:root"), null) }
                // SHARED = is the asked item shared with the partner afterwards: no, after stopping the folder it is not.
                setResult(Activity.RESULT_OK, Intent().putExtra(EXTRA_SHARED, shared).apply { if (via != null) putExtra(EXTRA_VIA_FOLDER, via.name) })
                finish()
            } catch (e: CancellationException) { throw e } catch (e: Throwable) { message = failMessage(e); busy = false }
        }
    }

    companion object {
        const val ACTION = "app.filenfiles.action.SHARE_WITH_PARTNER"
        private val UUID = Regex("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}")
    }
}

/**
 * `app.filenfiles.action.CREATE_SHARED_FOLDER` (I4 F11): one confirm sheet, then a folder in the drive root (or an existing
 * one already shared with the partner) is shared with the partner and returned as a persistable READ+WRITE tree grant.
 * No partner yet: partner setup first. Result: RESULT_OK with clip data, or RESULT_CANCELED with ERROR.
 */
class CreateSharedFolderActivity : PartnerActivity() {
    private var folderName = "Scans"
    /** What the confirm sheet promises: use the folder this app's earlier call made, or create one (maybe "[name] 2"). */
    private var plan by mutableStateOf<FolderPlan?>(null)
    private var partner by mutableStateOf<PartnerDone?>(null)
    private var needSetup by mutableStateOf(false)
    private var loaded by mutableStateOf(false)
    private var busy by mutableStateOf(false)
    private var message by mutableStateOf<String?>(null)

    override fun prepare(): Boolean {
        folderName = GrantFolderActivity.cleanName(intent.getStringExtra(EXTRA_NAME)) ?: "Scans"
        return true
    }

    @Composable override fun Content() {
        LaunchedEffect(Unit) { load() }
        if (!loaded) { CircularProgressIndicator(); return }
        val p = partner
        if (needSetup || p == null) {
            if (needSetup) { PartnerSetup(app, { partner = it; needSetup = false; message = if (it.state == PartnerState.REQUESTED) "Waiting for ${it.label} to accept. Try again then." else null
                lifecycleScope.launch { try { refreshPlan() } catch (e: CancellationException) { throw e } catch (e: Throwable) { message = failMessage(e) } } },
                { cancel("user_cancelled") }, ::failMessage); return }
            AlertDialog(
                onDismissRequest = { cancel("user_cancelled") },
                title = { Text("Add a partner first") },
                text = { Column { TapGuard(); Text("Share files with one person you trust.") } },
                confirmButton = { TextButton({ needSetup = true }) { Text("Add partner") } },
                dismissButton = { TextButton({ cancel("user_cancelled") }) { Text("Cancel") } },
            )
            return
        }
        AlertDialog(
            onDismissRequest = { if (!busy) cancel("user_cancelled") },
            title = {
                Text(when (val pl = plan) {
                    is FolderPlan.Reuse -> "Use your shared folder “${pl.name}”?"
                    is FolderPlan.Create -> "Create “${pl.name}” in your Filen and share it with ${p.label}?"
                    null -> "Share a folder with ${p.label}?"
                })
            },
            text = {
                Column {
                    TapGuard()
                    Text((if (plan is FolderPlan.Reuse) "It is already shared with ${p.label}. " else "") +
                        "$callerLabel can read, add and remove files in it, even when Filen Files is locked. Nothing else in your Filen. ${p.label} can open them.")
                    message?.let { Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(top = 8.dp)) }
                }
            },
            confirmButton = { TextButton(enabled = !busy && p.state == PartnerState.CONNECTED && plan != null, onClick = { go(p) }) { Text(if (busy) "Please wait…" else if (plan is FolderPlan.Reuse) "Use it" else "Create and share") } },
            dismissButton = { TextButton(enabled = !busy, onClick = { cancel("user_cancelled") }) { Text("Cancel") } },
        )
    }

    private suspend fun load() {
        try {
            val saved = app.prefs.partnerFor(app.filen.accountId)
            if (saved != null) {
                val st = withContext(Dispatchers.IO) { app.filen.partnerStatus(saved) }
                val who = st.who
                if (who != null && st.state != PartnerState.NONE) partner = doneFor(
                    if (st.state == PartnerState.CONNECTED) PartnerState.CONNECTED else PartnerState.REQUESTED, who)
                if (partner?.state == PartnerState.REQUESTED) message = "Waiting for ${partner!!.label} to accept. Try again then."
            }
            refreshPlan()
            loaded = true
        } catch (e: CancellationException) { throw e } catch (e: Throwable) {
            cancel(if (e.failureKind() == FailureKind.NETWORK) "offline" else "failed", failMessage(e))
        }
    }

    private fun recorded() = app.prefs.f11Folder(callerPkg, app.filen.accountId)

    /** Decides reuse or create now, so the sheet says what will really happen. Needs a connected partner. */
    private suspend fun refreshPlan() {
        val p = partner?.takeIf { it.state == PartnerState.CONNECTED } ?: return
        plan = withContext(Dispatchers.IO) { app.filen.planSharedFolder(folderName, p.email, recorded()) }
    }

    private fun go(p: PartnerDone) {
        busy = true; message = null
        lifecycleScope.launch {
            try {
                val made = withContext(Dispatchers.IO) { app.filen.ensureSharedFolder(folderName, p.email, recorded()) }
                val id = made.id; val name = made.name
                if (made.created) app.prefs.setF11Folder(callerPkg, app.filen.accountId, id.key) // only this folder is ever reused
                app.docsChanged(ItemId("filen:root")); app.notifyChanged()
                runCatching { contentResolver.notifyChange(DocumentsContract.buildChildDocumentsUri(FilenDocumentsProvider.AUTHORITY, "filen-out:root"), null) }
                val uri = DocumentsContract.buildTreeDocumentUri(FilenDocumentsProvider.AUTHORITY, id.value)
                app.prefs.grantBackupWriter(callerPkg, uri.toString(), callerLabel) // only this folder, while locked
                val flags = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION or
                    Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION or Intent.FLAG_GRANT_PREFIX_URI_PERMISSION
                val result = Intent().setData(uri).addFlags(flags).putExtra(GrantFolderActivity.EXTRA_FOLDER_NAME, name)
                result.clipData = ClipData.newRawUri("Filen", uri)
                setResult(Activity.RESULT_OK, result)
                finish()
            } catch (e: CancellationException) { throw e } catch (e: Throwable) { message = failMessage(e); busy = false }
        }
    }

    companion object {
        const val ACTION = "app.filenfiles.action.CREATE_SHARED_FOLDER"
        const val EXTRA_NAME = "app.filenfiles.extra.NAME"
    }
}
