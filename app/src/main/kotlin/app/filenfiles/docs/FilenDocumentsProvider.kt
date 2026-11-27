package app.filenfiles.docs

import android.Manifest
import android.app.PendingIntent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Binder
import android.os.Looper
import android.os.Process
import android.provider.DocumentsContract
import android.content.Intent
import android.content.res.AssetFileDescriptor
import android.database.Cursor
import android.database.MatrixCursor
import android.graphics.Point
import android.os.CancellationSignal
import android.os.Handler
import android.os.HandlerThread
import android.os.OperationCanceledException
import android.os.ParcelFileDescriptor
import android.provider.DocumentsContract.Document
import android.provider.DocumentsContract.Root
import android.provider.DocumentsProvider
import android.app.AuthenticationRequiredException
import app.filenfiles.FilesApp
import app.filenfiles.MainActivity
import app.filenfiles.R
import app.filenfiles.core.FileItem
import app.filenfiles.core.FilenNames
import app.filenfiles.core.Folder
import app.filenfiles.core.Listing
import app.filenfiles.filen.FailureKind
import app.filenfiles.filen.failureKind
import app.filenfiles.core.ItemId
import app.filenfiles.core.Origin
import app.filenfiles.core.Permission
import app.filenfiles.core.UploadStatus
import app.filenfiles.transfer.TransferManager
import app.filenfiles.transfer.TransferState
import app.filenfiles.transfer.TransferStore
import app.filenfiles.filen.DocsRules
import app.filenfiles.filen.FilenSession
import app.filenfiles.filen.FilenStorageProvider
import app.filenfiles.filen.StoredListing
import app.filenfiles.filen.ChainCheck
import app.filenfiles.filen.LocalCache
import app.filenfiles.filen.friendlyOrNull
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.runBlocking
import java.io.File
import java.io.FileNotFoundException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong
import kotlin.coroutines.coroutineContext

/**
 * Lets other apps (and the system file picker) browse and open Filen files.
 *
 * Roots: "Filen Drive" (personal; browse, open, create, rename, move, delete, thumbnails, search) and
 * "Filen – Shared with me" (received shares; browse/open only). Document ids are the app's own [ItemId] strings.
 * It runs inside the app process, so it shares the signed-in session, router and caches with the UI. Providers are
 * created before Application.onCreate, so [FilesApp] is only touched lazily from the query methods.
 *
 * Writes: new files are created through [createDocument] and uploaded through the transfer queue when the writer closes
 * the file. Existing files are read-only (Filen has no safe overwrite-in-place we can rely on).
 */
class FilenDocumentsProvider : DocumentsProvider() {
    /** The app singleton, once Application.onCreate has finished (provider calls can race with process start). */
    private val app: FilesApp get() = (context!!.applicationContext as FilesApp).also { it.awaitReady() }

    private class Pending(val id: String, val parent: ItemId, val name: String, val mime: String, val dir: File) {
        @Volatile var transferId: Long = -1
        @Volatile var closed = false
        val size: Long get() = File(dir, name).takeIf { it.exists() }?.length() ?: 0
    }

    private val pending = ConcurrentHashMap<String, Pending>()

    /** A file on its way to Filen as other apps see it: still being written, queued, uploading, waiting or failed. */
    private class PendingRow(val id: String, val parent: ItemId, val name: String, val mime: String, val size: Long, val summary: String)

    /**
     * Rows for uploads that are not finished. A file being written lives in [pending]; once the writer closed it,
     * the persisted transfer queue is the source of truth, so the row survives a process restart and keeps its id.
     */
    private fun pendingRow(id: String): PendingRow? {
        pending[id]?.takeIf { !it.closed }?.let { return PendingRow(it.id, it.parent, it.name, it.mime, it.size, UploadStatus.UPLOADING) }
        val t = app.transfers.byDocId(id) ?: return null
        if (!(t.active || t.state == TransferState.FAILED)) return null
        return PendingRow(id, t.parent, t.name, t.mime ?: "application/octet-stream", maxOf(t.size, t.file?.length() ?: 0L),
            UploadStatus.summary(t.state == TransferState.WAITING_NETWORK, t.state == TransferState.FAILED, t.error))
    }

    private fun pendingRowsIn(parent: ItemId): List<PendingRow> =
        (pending.values.filter { it.parent == parent && !it.closed }.mapNotNull { pendingRow(it.id) } +
            app.transfers.providerUploads().filter { it.parent == parent }.mapNotNull { pendingRow(it.docId!!) }).distinctBy { it.id }

    private fun pendingParent(id: String): String? = pending[id]?.parent?.value ?: app.transfers.byDocId(id)?.parent?.value
    /** A `filen-pending:` id whose upload has finished -> the real document id (anything else comes back unchanged). */
    private fun real(documentId: String): String =
        if (documentId.startsWith(PENDING_PREFIX)) app.transfers.docIds.get(documentId) ?: documentId else documentId

    private val pendingSeq = AtomicLong(1)
    /** child document id → parent document id, learned from listings and kept on disk (I4 F1); backs [isChildDocument]. */
    private val parents = object {
        operator fun get(id: String): String? = app.filen.docState.parent(id)
        operator fun set(id: String, parent: String) = app.filen.docState.setParent(id, parent)
        fun remove(id: String): String? = app.filen.docState.removeParent(id)
    }
    private val writerThread by lazy { HandlerThread("docs-writer").apply { start() } }

    override fun onCreate() = true

    // ---------- plumbing ----------

    private fun <T> blocking(signal: CancellationSignal? = null, block: suspend () -> T): T = try {
        runBlocking(Dispatchers.IO) {
            val job: Job = coroutineContext[Job]!!
            signal?.setOnCancelListener { job.cancel() }
            try { block() } finally { signal?.setOnCancelListener(null) }
        }
    } catch (e: CancellationException) { throw OperationCanceledException() }

    private fun authError(message: String): AuthenticationRequiredException {
        val ctx = context!!
        val pi = PendingIntent.getActivity(ctx, 0, Intent(ctx, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        return AuthenticationRequiredException(SecurityException(message), pi) // userAction opens the app (lock screen / sign-in)
    }

    /**
     * Refuses the call while the app lock is locked (opens the app, which asks to unlock), then waits briefly for the
     * session restore that runs on a background thread after process start.
     *
     * [doc]: the call only touches that document (or, for a create, that folder). While locked it is still answered for
     * an app the owner allowed, and only inside the folder that app was given (see [lockedAccessAllowed]); [write] calls
     * (create a file, delete) only for an app that got its folder through GrantFolderActivity.
     * Must run on the binder thread: it reads the caller's identity.
     */
    private fun requireSignedIn(doc: String? = null, write: Boolean = false) {
        if (app.appLock.isLockedForOthers) {
            val uid = Binder.getCallingUid(); val pid = Binder.getCallingPid()
            if (doc == null) throw authError(LOCKED)
            awaitSession()
            if (!lockedAccessAllowed(doc, uid, pid, write)) throw authError(LOCKED)
            return
        }
        awaitSession()
    }

    private fun awaitSession() {
        val session = app.session
        val deadline = System.currentTimeMillis() + 10_000
        while (session.state.value is FilenSession.State.Unknown && System.currentTimeMillis() < deadline) Thread.sleep(50)
        if (session.state.value !is FilenSession.State.LoggedIn) throw authError("Sign in to Filen")
    }

    /**
     * Access while locked. All of these must hold:
     *  - the caller is not the system picker / Files app (they hold MANAGE_DOCUMENTS) - browsing stays locked;
     *  - the caller holds a PERSISTED grant (read, or write for [write]) whose tree or document covers [documentId] -
     *    from the owner's own picker choice or GrantFolderActivity;
     *  - the owner allowed that app ([Prefs.lockedReaders]); for [write] it also got its folder from GrantFolderActivity
     *    ([Prefs.lockedWriters]).
     * A reader that passes the first two but isn't allowed yet is remembered, and the owner is asked once after unlocking.
     */
    private fun lockedAccessAllowed(documentId: String, uid: Int, pid: Int, write: Boolean): Boolean {
        val ctx = context!!
        if (uid == Process.myUid()) return false
        if (ctx.checkPermission(Manifest.permission.MANAGE_DOCUMENTS, pid, uid) == PackageManager.PERMISSION_GRANTED) return false
        val pkgs = ctx.packageManager.getPackagesForUid(uid)?.toList().orEmpty()
        if (pkgs.isEmpty()) return false
        val mode = if (write) Intent.FLAG_GRANT_WRITE_URI_PERMISSION else Intent.FLAG_GRANT_READ_URI_PERMISSION
        val covered = ctx.contentResolver.outgoingPersistedUriPermissions.any { g ->
            (if (write) g.isWritePermission else g.isReadPermission) && g.uri.authority == AUTHORITY &&
                ctx.checkUriPermission(g.uri, pid, uid, mode) == PackageManager.PERMISSION_GRANTED &&
                covers(g.uri, documentId)
        }
        if (!covered) return false
        val prefs = app.prefs
        if (write) return pkgs.any { it in prefs.lockedReaders && it in prefs.lockedWriters }
        if (pkgs.any { it in prefs.lockedReaders }) return true
        pkgs.firstOrNull { it !in prefs.lockedReadersAsked }?.let { pkg ->
            android.os.Handler(Looper.getMainLooper()).post { app.appLock.askReader = pkg }
        }
        return false
    }

    /** Whether a granted URI (a tree, or a single document) covers [documentId]. */
    private fun covers(grant: Uri, documentId: String): Boolean = runCatching {
        if (DocumentsContract.isTreeUri(grant)) {
            val top = DocumentsContract.getTreeDocumentId(grant)
            documentId == top || isDescendant(top, documentId)
        } else DocumentsContract.getDocumentId(grant) == documentId
    }.getOrDefault(false)

    /**
     * A write-mode call (open for write, create, delete, rename, move) from an app that holds a persisted write grant on
     * a folder: the parent chain from [documentId] up to a granted folder is confirmed by Filen now (one lookup per
     * level), never taken from the stored parent map, which can be stale. When Filen cannot be asked (offline) the write
     * is refused (IOException) rather than allowed on stale data. Callers without such a grant (this app, the system
     * picker) are not affected. Must run on the binder thread: it reads the caller's identity.
     */
    private fun requireFreshChain(documentId: String) {
        val ctx = context!!
        val uid = Binder.getCallingUid(); val pid = Binder.getCallingPid()
        if (uid == Process.myUid()) return
        if (ctx.checkPermission(Manifest.permission.MANAGE_DOCUMENTS, pid, uid) == PackageManager.PERMISSION_GRANTED) return
        val trees = HashSet<String>(); val docs = HashSet<String>()
        for (g in ctx.contentResolver.outgoingPersistedUriPermissions) {
            if (!g.isWritePermission || g.uri.authority != AUTHORITY) continue
            if (ctx.checkUriPermission(g.uri, pid, uid, Intent.FLAG_GRANT_WRITE_URI_PERMISSION) != PackageManager.PERMISSION_GRANTED) continue
            runCatching { if (DocumentsContract.isTreeUri(g.uri)) trees.add(DocumentsContract.getTreeDocumentId(g.uri)) else docs.add(DocumentsContract.getDocumentId(g.uri)) }
        }
        if (trees.isEmpty() || documentId in docs) return // not under a persisted tree grant
        var start = documentId
        if (start.startsWith(PENDING_PREFIX)) start = pendingParent(start) ?: throw FileNotFoundException(documentId) // our own, not yet uploaded
        val verdict = ChainCheck.verify(start, trees) { cur ->
            when (val r = io { blocking { app.filen.verifiedParent(ItemId(cur)) } }) {
                is FilenStorageProvider.ParentLookup.Found -> { parents[cur] = r.parent.value; ChainCheck.Step.Up(r.parent.value) }
                FilenStorageProvider.ParentLookup.Top -> ChainCheck.Step.Top
                FilenStorageProvider.ParentLookup.Gone -> ChainCheck.Step.Gone
            }
        }
        when (verdict) {
            ChainCheck.Verdict.INSIDE -> Unit
            ChainCheck.Verdict.GONE -> throw FileNotFoundException(documentId)
            ChainCheck.Verdict.OUTSIDE -> throw SecurityException("$documentId is no longer inside the folder this app was given")
        }
    }

    private fun idOf(documentId: String): ItemId {
        val id = ItemId(documentId)
        if (id.scheme != "filen" && id.scheme != "filen-in" && id.scheme != "filen-out") throw FileNotFoundException("Unknown document $documentId")
        return id
    }

    /**
     * What [documentId] names now. A Filen file id is followed through its versions to the current one, so an id handed
     * out before the file was saved again (by us or elsewhere) keeps working. Offline, the last known item answers.
     * Second: true when the file is in the trash.
     */
    private fun current(documentId: String, signal: CancellationSignal? = null): Pair<FileItem, Boolean>? {
        val id = idOf(documentId)
        if (id.key == "root") return io { blocking(signal) { app.router.stat(id) } }?.let { it to false }
        if (id.scheme != "filen") {
            // Received / outgoing share: null only when the server confirms it is gone; offline falls back to the last listing.
            return io {
                blocking(signal) {
                    try { app.filen.statShared(id) } catch (e: CancellationException) { throw e
                    } catch (e: Exception) {
                        if (e is FileNotFoundException || e.failureKind() == FailureKind.AUTH) throw e
                        app.filen.docState.storedItem(id.value) ?: throw e
                    }
                }
            }?.let { it to false }
        }
        return io {
            blocking(signal) {
                try {
                    app.filen.currentHead(id)?.let { it.item to it.trashed } ?: app.router.freshStat(id)?.let { it to false }
                } catch (e: CancellationException) { throw e
                } catch (e: Exception) {
                    if (e.failureKind() != FailureKind.NETWORK) throw e
                    app.router.stat(id)?.let { it to false }
                }
            }
        }
    }

    private fun <T> io(block: () -> T): T = try { block() } catch (e: Exception) {
        if (e is OperationCanceledException || e is FileNotFoundException || e is AuthenticationRequiredException) throw e
        throw java.io.IOException(e.friendlyOrNull() ?: e.message ?: e.javaClass.simpleName, e)
    }

    // ---------- roots ----------

    override fun queryRoots(projection: Array<out String>?): Cursor {
        val c = MatrixCursor(projection ?: ROOT_PROJECTION)
        val session = app.session
        val deadline = System.currentTimeMillis() + 10_000 // cold start: let the session restore finish first
        while (session.state.value is FilenSession.State.Unknown && System.currentTimeMillis() < deadline) Thread.sleep(50)
        val s = session.state.value
        if (s !is FilenSession.State.LoggedIn) return c
        if (app.appLock.isLockedForOthers) {
            // Locked: only the drive's name, so pickers still offer it. Opening it throws AuthenticationRequiredException,
            // which the picker shows as an unlock button (our userAction); unlocking notifies the roots URI to reload.
            // FLAG_SUPPORTS_IS_CHILD keeps it visible in folder pickers (ACTION_OPEN_DOCUMENT_TREE). No account, no search.
            c.newRow().apply {
                add(Root.COLUMN_ROOT_ID, "filen"); add(Root.COLUMN_DOCUMENT_ID, "filen:root")
                add(Root.COLUMN_TITLE, "Filen Drive"); add(Root.COLUMN_ICON, R.drawable.ic_launcher)
                add(Root.COLUMN_FLAGS, Root.FLAG_SUPPORTS_CREATE or Root.FLAG_SUPPORTS_IS_CHILD)
            }
            return c
        }
        c.newRow().apply {
            add(Root.COLUMN_ROOT_ID, "filen"); add(Root.COLUMN_DOCUMENT_ID, "filen:root")
            add(Root.COLUMN_TITLE, "Filen Drive"); add(Root.COLUMN_SUMMARY, s.email)
            add(Root.COLUMN_ICON, R.drawable.ic_launcher)
            add(Root.COLUMN_FLAGS, Root.FLAG_SUPPORTS_CREATE or Root.FLAG_SUPPORTS_IS_CHILD or Root.FLAG_SUPPORTS_SEARCH)
        }
        c.newRow().apply {
            add(Root.COLUMN_ROOT_ID, "filen-in"); add(Root.COLUMN_DOCUMENT_ID, "filen-in:root")
            add(Root.COLUMN_TITLE, "Filen – Shared with me"); add(Root.COLUMN_SUMMARY, s.email)
            add(Root.COLUMN_ICON, R.drawable.ic_launcher)
            add(Root.COLUMN_FLAGS, Root.FLAG_SUPPORTS_IS_CHILD)
        }
        c.newRow().apply { // I4 F4: what this account shares with others; read only
            add(Root.COLUMN_ROOT_ID, "filen-out"); add(Root.COLUMN_DOCUMENT_ID, "filen-out:root")
            add(Root.COLUMN_TITLE, "Filen – Shared by me"); add(Root.COLUMN_SUMMARY, s.email)
            add(Root.COLUMN_ICON, R.drawable.ic_launcher)
            add(Root.COLUMN_FLAGS, Root.FLAG_SUPPORTS_IS_CHILD)
        }
        return c
    }

    // ---------- documents ----------

    private fun MatrixCursor.addRoot(id: String) {
        val personal = id == "filen:root"
        newRow().apply {
            add(Document.COLUMN_DOCUMENT_ID, id)
            add(Document.COLUMN_DISPLAY_NAME, if (personal) "Filen Drive" else if (id == "filen-out:root") "Shared by me" else "Shared with me")
            add(Document.COLUMN_MIME_TYPE, Document.MIME_TYPE_DIR)
            add(Document.COLUMN_SIZE, null); add(Document.COLUMN_LAST_MODIFIED, null)
            add(Document.COLUMN_FLAGS, if (personal) Document.FLAG_DIR_SUPPORTS_CREATE else 0)
        }
    }

    private fun MatrixCursor.addItem(item: FileItem, documentId: String = item.id.value) {
        var flags = 0
        val mime = if (item.isDirectory) Document.MIME_TYPE_DIR else item.mime ?: "application/octet-stream"
        if (item.isDirectory && item.origin == Origin.FILEN_PERSONAL) flags = flags or Document.FLAG_DIR_SUPPORTS_CREATE
        if (!item.isDirectory && mime.startsWith("image/")) flags = flags or Document.FLAG_SUPPORTS_THUMBNAIL
        if (app.router.modifier(item) != null) flags = flags or Document.FLAG_SUPPORTS_DELETE or Document.FLAG_SUPPORTS_RENAME or Document.FLAG_SUPPORTS_MOVE
        // Saving over a file (needs versioning on, checked when it is opened for writing).
        if (!item.isDirectory && item.origin == Origin.FILEN_PERSONAL && item.lineage != null) flags = flags or Document.FLAG_SUPPORTS_WRITE
        val save = item.lineage?.let { app.transfers.pendingSave(it) }
        newRow().apply {
            add(Document.COLUMN_DOCUMENT_ID, documentId)
            add(Document.COLUMN_DISPLAY_NAME, item.name)
            add(Document.COLUMN_MIME_TYPE, mime)
            add(Document.COLUMN_SIZE, if (item.isDirectory) null else item.size)
            add(Document.COLUMN_LAST_MODIFIED, item.modified.takeIf { it > 0 })
            add(Document.COLUMN_FLAGS, flags)
            add(COLUMN_VERSION, DocsRules.version(item)) // I4 F3
            // A save not uploaded yet: "Uploading…" / "Waiting for network…" / "Upload failed: reason".
            if (save != null) add(Document.COLUMN_SUMMARY, UploadStatus.summary(save.state == TransferState.WAITING_NETWORK, save.state == TransferState.FAILED, save.error))
        }
    }

    private fun MatrixCursor.addPending(p: PendingRow) {
        newRow().apply {
            add(Document.COLUMN_DOCUMENT_ID, p.id); add(Document.COLUMN_DISPLAY_NAME, p.name)
            add(Document.COLUMN_MIME_TYPE, p.mime); add(Document.COLUMN_SIZE, p.size)
            add(Document.COLUMN_LAST_MODIFIED, System.currentTimeMillis())
            add(Document.COLUMN_FLAGS, Document.FLAG_SUPPORTS_WRITE)
            add(Document.COLUMN_SUMMARY, p.summary) // "Uploading…" / "Waiting for network…" / "Upload failed: reason"
        }
    }

    override fun queryDocument(documentId: String, projection: Array<out String>?): Cursor {
        @Suppress("NAME_SHADOWING") val documentId = real(documentId)
        val c = MatrixCursor(projection ?: DOC_PROJECTION)
        if (documentId.startsWith(PENDING_PREFIX)) {
            val row = pendingRow(documentId)
            if (row != null) { c.addPending(row); return c }
            // Finished (or dismissed) meanwhile: the real file appears under its own id when the folder is listed.
            throw FileNotFoundException("$documentId is no longer pending - list the folder to find the uploaded file")
        }
        // Pickers load the root document before its children and treat any failure there as a generic error; the unlock
        // button only appears when queryChildDocuments throws AuthenticationRequiredException. So while locked the
        // drive's root row (name only, same row as unlocked) still answers - it is the root we list anyway.
        if (documentId == "filen:root" && app.appLock.isLockedForOthers) { c.addRoot(documentId); return c }
        requireSignedIn(doc = documentId)
        if (documentId == "filen:root" || documentId == "filen-in:root" || documentId == "filen-out:root") { c.addRoot(documentId); return c }
        val item = current(documentId)?.first ?: throw FileNotFoundException(documentId)
        c.addItem(item, documentId) // the id the caller holds, even if the file has a newer version's id now
        return c
    }

    override fun queryChildDocuments(parentDocumentId: String, projection: Array<out String>?, sortOrder: String?): Cursor {
        requireSignedIn(doc = parentDocumentId)
        val parent = idOf(parentDocumentId)
        val docState = app.filen.docState
        // Offline, a fresh listing can't be fetched. Answer from the last listing we saw (kept on disk, so also after a
        // process death) plus the uploads we know about, marked FRESH=false so a client never reads it as "the folder
        // is empty now". Only a listing that came from Filen in this call says FRESH=true (I4 F2, I6).
        var failure: Throwable? = null
        var listing: Listing? = null
        try {
            listing = blocking { if (parent.scheme == "filen-out") app.filen.listForProvider(parent) else app.router.list(parent) }
        } catch (e: OperationCanceledException) { throw e
        } catch (e: Throwable) { failure = e }
        var stored: StoredListing? = null
        if (failure != null) {
            val kind = failure.failureKind()
            val gone = if (failure is FileNotFoundException) true
                else if (kind == FailureKind.AUTH) null
                else runCatching { blocking { app.filen.parentLookup(parent) } is FilenStorageProvider.ParentLookup.Gone }.getOrNull()
            stored = docState.listing(parent.value)
            when (DocsRules.decideList(kind, gone, stored != null || pendingRowsIn(parent).isNotEmpty())) {
                DocsRules.ListOutcome.NOT_FOUND -> { if (parent.scheme != "filen") docState.markGone(parent.value); throw FileNotFoundException(parentDocumentId) }
                DocsRules.ListOutcome.AUTH -> throw authError("Sign in to Filen")
                DocsRules.ListOutcome.IO -> io<Unit> { throw failure }
                else -> {
                    // An item the server last said is gone is not in an offline answer either.
                    listing = Listing(Folder(parent, "", Origin.FILEN_PERSONAL), stored?.items?.filter { !docState.isGone(it.id.value) } ?: emptyList(), emptyList())
                    android.util.Log.d("FilenDebug", "Offline: answering a listing from the stored copy and the upload queue")
                }
            }
        }
        val fresh = failure == null
        val items = listing!!.items
        if (fresh) { app.listingCache[parent.value] = listing; docState.putListing(parent.value, items) }
        val c = MatrixCursor(projection ?: DOC_PROJECTION)
        c.setNotificationUri(context!!.contentResolver, android.provider.DocumentsContract.buildChildDocumentsUri(AUTHORITY, parentDocumentId))
        c.extras = android.os.Bundle().apply { putBoolean(EXTRA_FRESH, fresh); putString(EXTRA_ACCOUNT, app.filen.accountId ?: "") }
        // Parent links come from listings the server just gave; an offline (stored) answer never rewrites them.
        items.forEach { if (fresh) parents[it.id.value] = parentDocumentId; c.addItem(it) }
        // Just-finished uploads that Filen's listing doesn't show yet (it can lag a few seconds): no gap for the client.
        app.transfers.recentUploads.missingFrom(parent, items.map { it.id }).forEach { parents[it.id.value] = parentDocumentId; c.addItem(it) }
        // The same lag in "Shared by me": a file this app just saved or uploaded into a folder it shares can be missing from the shared
        // listing for a few seconds while its old version is already gone. Without the receipt a partner would see the file vanish and come
        // back under a new id (and Stack would lose its stacks and tags). Own uploads only, into this folder, and only when Filen says
        // right now that this version is still the file's current one, not trashed and still in this folder (a rename or move since is not
        // repeated from the receipt).
        if (fresh && parent.scheme == "filen-out" && parent.key != "root") {
            val drive = ItemId("filen:" + parent.key)
            val have = items.map { it.id.key }.toSet()
            val names = items.map { it.name }.toSet()
            app.transfers.recentUploads.missingFrom(drive, emptyList())
                .filter { !it.isDirectory && it.id.key !in have && it.name !in names }
                .forEach { u ->
                    val head = runCatching {
                        blocking {
                            app.filen.currentHead(u.id)?.takeIf { !it.trashed && it.item.id.key == u.id.key && app.filen.currentParent(it.item.id) == drive }
                        }
                    }.getOrNull() ?: return@forEach
                    if (head.item.name in names) return@forEach
                    val out = ItemId("filen-out:" + u.id.key)
                    parents[out.value] = parentDocumentId
                    c.addItem(head.item.copy(id = out, origin = Origin.FILEN_SHARED_BY_ME, permission = Permission.READ_ONLY, lineage = null), out.value)
                }
        }
        pendingRowsIn(parent).forEach { c.addPending(it) }
        return c
    }

    override fun querySearchDocuments(rootId: String, query: String, projection: Array<out String>?): Cursor {
        requireSignedIn()
        val c = MatrixCursor(projection ?: DOC_PROJECTION)
        if (rootId != "filen") return c
        io { blocking { app.router.search(query) } }.filter { it.origin == Origin.FILEN_PERSONAL }.take(50).forEach { c.addItem(it) }
        return c
    }

    override fun isChildDocument(parentDocumentId: String, documentId: String): Boolean {
        @Suppress("NAME_SHADOWING") val documentId = real(documentId)
        // The framework calls this for tree URIs before every other call; while locked only an allowed app's own folder answers.
        if (app.appLock.isLockedForOthers) requireSignedIn(parentDocumentId)
        return isDescendant(parentDocumentId, documentId)
    }

    /**
     * Lets pickers (DocumentsUI's initial location) open a folder directly: the chain of ids from [parentDocumentId] (or the drive
     * root) to [childDocumentId], from the same parent chain as [isDescendant]. Only for items Filen can confirm; otherwise not found.
     */
    override fun findDocumentPath(parentDocumentId: String?, childDocumentId: String): DocumentsContract.Path {
        val child = real(childDocumentId)
        // The drive root answers while locked (as queryDocument does), so the picker can show the unlock button there.
        if (!(child == "filen:root" && app.appLock.isLockedForOthers)) requireSignedIn(doc = child)
        val ids = DocsRules.documentPath(parentDocumentId, child) { id -> parents[id] ?: pendingParent(id) ?: fetchParent(id) }
            ?: throw FileNotFoundException("$childDocumentId is not below ${parentDocumentId ?: "a Filen drive"}")
        return DocumentsContract.Path(if (parentDocumentId == null) ids[0].substringBefore(':') else null, ids)
    }

    /**
     * Walks up from [documentId] until it meets [parentDocumentId] (true) or a root / unknown item (false). Parents come
     * from listings seen in this process, the upload queue, or - after a cold start - from Filen itself, so a file several
     * levels below a granted folder still resolves. Never checks the lock: callers do.
     */
    private fun isDescendant(parentDocumentId: String, documentId: String): Boolean {
        if (parentDocumentId == documentId) return false
        var cur = documentId
        repeat(64) {
            val p = parents[cur] ?: pendingParent(cur) ?: (fetchParent(cur) ?: return false)
            if (p == parentDocumentId) return true
            cur = p
        }
        return false
    }

    /**
     * The parent of [documentId] from Filen. null = a root (the item is really outside any folder asked about). Throws
     * [FileNotFoundException] only when Filen confirms the item or its share is gone, [AuthenticationRequiredException]
     * when signed out, and [java.io.IOException] for anything else (offline, timeout): never a "not a child" answer
     * for a lookup that failed (I4 F1).
     */
    private fun fetchParent(documentId: String): String? {
        val id = ItemId(documentId)
        if (id.key == "root" || (id.scheme != "filen" && id.scheme != "filen-in" && id.scheme != "filen-out")) return null
        awaitSession()
        val r = io { blocking { app.filen.parentLookup(id) } }
        return when (r) {
            is FilenStorageProvider.ParentLookup.Found -> r.parent.value.also { parents[documentId] = it }
            FilenStorageProvider.ParentLookup.Top -> null
            FilenStorageProvider.ParentLookup.Gone -> throw FileNotFoundException(documentId)
        }
    }

    // ---------- reading ----------

    override fun openDocument(documentId: String, mode: String, signal: CancellationSignal?): ParcelFileDescriptor {
        val caller = Binder.getCallingUid() // on the binder thread, before anything hops off it
        pending[documentId]?.let { return openForWrite(it, mode, caller) }
        if (documentId.startsWith(PENDING_PREFIX)) {
            val real = real(documentId)
            if (real == documentId) {
                // Written and closed, still on its way to Filen: a reader gets the local copy (read-only) until the upload lands.
                val local = app.transfers.byDocId(documentId)?.file?.takeIf { it.isFile }
                if (mode == "r" && local != null) return ParcelFileDescriptor.open(local, ParcelFileDescriptor.MODE_READ_ONLY)
                throw java.lang.UnsupportedOperationException("This file was already written")
            }
            return openDocument(real, mode, signal) // uploaded meanwhile: the real document
        }
        requireSignedIn(doc = documentId.takeIf { mode == "r" }) // writes never pass while locked
        if (mode != "r") requireFreshChain(documentId)
        val (item, trashed) = current(documentId, signal) ?: throw FileNotFoundException(documentId)
        if (item.isDirectory) throw FileNotFoundException("$documentId is a folder")
        if (mode != "r") return openSaveOver(documentId, item, trashed, mode, caller, signal)
        // A save another app made that is not uploaded yet is the newest content: readers get it.
        item.lineage?.let { app.transfers.pendingSave(it) }?.file?.takeIf { it.isFile }?.let {
            return ParcelFileDescriptor.open(it, ParcelFileDescriptor.MODE_READ_ONLY)
        }
        val file = io { blocking(signal) { app.router.materialize(item) } }
        item.lineage?.let { app.transfers.noteRead(caller, it, item.id.key) } // what a later save by this app builds on
        return ParcelFileDescriptor.open(File(file.path), ParcelFileDescriptor.MODE_READ_ONLY)
    }

    /**
     * Writing to an existing Filen file. The app writes a private copy; when it closes it cleanly the copy is queued as
     * a save ([TransferManager.enqueueSave]), which checks the file again just before uploading and keeps a conflict copy
     * instead when it can't safely add a version. Needs file versioning, so the replaced content is always kept.
     */
    private fun openSaveOver(documentId: String, item: FileItem, trashed: Boolean, mode: String, caller: Int, signal: CancellationSignal?): ParcelFileDescriptor {
        val lineage = item.lineage
        if (item.origin != Origin.FILEN_PERSONAL || lineage == null) throw java.lang.UnsupportedOperationException("This file can't be changed here")
        if (trashed) throw java.lang.UnsupportedOperationException("“${item.name}” is in the Filen trash")
        val versioning = io { blocking(signal) { app.filen.versioningEnabled() } }
        if (!versioning) throw java.lang.UnsupportedOperationException(
            "Saving over Filen files needs file versioning (so the old content is kept). Turn it on in Filen Files, then save again.")
        val parent = io { blocking(signal) { app.filen.currentParent(item.id) } } ?: throw FileNotFoundException("Couldn't tell which folder “${item.name}” is in")
        val n = pendingSeq.getAndIncrement()
        val dir = File(LocalCache.outbox(context!!), "doc-save-${System.nanoTime()}-$n").apply { mkdirs() }
        val file = File(dir, item.name.replace('/', '_').ifBlank { "file" })
        try {
            // Modes that keep content ("rw", "wa") start from the newest content: an unsent save of this file, else Filen's.
            var before: String? = null
            var copied: String? = null
            var copiedFrom: app.filenfiles.transfer.Transfer? = null
            if ((mode.contains('r') && !mode.contains('t')) || mode.contains('a')) {
                copiedFrom = app.transfers.pendingSave(lineage)?.takeIf { it.file?.isFile == true }
                val from = copiedFrom?.file ?: File(io { blocking(signal) { app.router.materialize(item) } }.path).also { copied = item.id.key }
                from.copyTo(file, overwrite = true)
                before = sha256(file)
            }
            // The version this edit starts from is what the app was given, NOT whatever is current now.
            val base = app.transfers.baseFor(caller, lineage, copied, copiedFrom)
            app.transfers.describeWrite(dir, documentId, parent, item.name, item.mime ?: "application/octet-stream", lineage, base, caller)
            return ParcelFileDescriptor.open(file, ParcelFileDescriptor.parseMode(mode), Handler(writerThread.looper)) { error ->
                // A failed write is discarded; so is one that changed nothing (an app that opened "rw" just to read).
                if (error != null || (before != null && runCatching { sha256(file) }.getOrNull() == before)) {
                    File(dir.path + TransferStore.META).delete(); dir.deleteRecursively(); return@open
                }
                app.transfers.enqueueSave(parent, file, item.name, item.mime, lineage, base, caller)
                parents[documentId]?.let(::notifyParent)
            }
        } catch (e: Exception) {
            File(dir.path + TransferStore.META).delete(); dir.deleteRecursively()
            throw e
        }
    }

    private fun sha256(f: File): String {
        val d = java.security.MessageDigest.getInstance("SHA-256")
        f.inputStream().use { i -> val b = ByteArray(1 shl 16); while (true) { val n = i.read(b); if (n < 0) break; d.update(b, 0, n) } }
        return d.digest().joinToString("") { "%02x".format(it) }
    }

    override fun openDocumentThumbnail(documentId: String, sizeHint: Point, signal: CancellationSignal?): AssetFileDescriptor {
        @Suppress("NAME_SHADOWING") val documentId = real(documentId)
        requireSignedIn()
        val item = io { blocking(signal) { app.router.stat(idOf(documentId)) } } ?: throw FileNotFoundException(documentId)
        val px = maxOf(sizeHint.x, sizeHint.y).coerceIn(128, 512)
        val path = io { blocking(signal) { app.router.thumbnail(item, px) } } ?: throw FileNotFoundException("No thumbnail")
        return AssetFileDescriptor(ParcelFileDescriptor.open(File(path), ParcelFileDescriptor.MODE_READ_ONLY), 0, AssetFileDescriptor.UNKNOWN_LENGTH)
    }

    // ---------- writing ----------

    override fun createDocument(parentDocumentId: String, mimeType: String, displayName: String): String {
        // While locked only an app that got this folder from GrantFolderActivity may add FILES to it (no folders).
        requireSignedIn(doc = parentDocumentId.takeIf { mimeType != Document.MIME_TYPE_DIR }, write = true)
        val parent = idOf(parentDocumentId)
        requireFreshChain(parentDocumentId)
        val w = app.router.writable(parent)?.takeIf { it.canWrite(parent) } ?: throw SecurityException("This folder is read-only")
        if (displayName.isBlank() || '/' in displayName) throw IllegalArgumentException("Invalid name")
        if (mimeType == Document.MIME_TYPE_DIR) {
            val id = io { blocking { w.createFolder(parent, FilenNames.sanitize(displayName)) } }
            parents[id.value] = parentDocumentId
            notifyParent(parentDocumentId); return id.value
        }
        val n = pendingSeq.getAndIncrement()
        val dir = File(LocalCache.outbox(context!!), "doc-${System.nanoTime()}-$n").apply { mkdirs() }
        // Time-based so an id handed out before a process restart can never be reused for a different file.
        // A name Filen would refuse is adjusted now; refusing it after the app has written would strand its save.
        val p = Pending("$PENDING_PREFIX${System.currentTimeMillis()}-$n", parent, FilenNames.sanitize(displayName), mimeType, dir)
        app.transfers.describeWrite(dir, p.id, parent, p.name, p.mime)
        pending[p.id] = p
        parents[p.id] = parentDocumentId
        return p.id
    }

    /** The writer gets a private temp file; when it closes cleanly the file is handed to the upload queue. */
    private fun openForWrite(p: Pending, mode: String, caller: Int): ParcelFileDescriptor {
        if (!mode.contains('w')) throw FileNotFoundException("Write-only until uploaded")
        if (p.closed) throw java.lang.UnsupportedOperationException("This file was already written")
        val file = File(p.dir, p.name)
        return ParcelFileDescriptor.open(
            file, ParcelFileDescriptor.parseMode(mode), Handler(writerThread.looper),
        ) { error ->
            if (error != null) { p.dir.deleteRecursively(); pending.remove(p.id); return@open }
            p.transferId = app.transfers.enqueueFile(p.parent, file, p.name, p.mime, docId = p.id, writer = caller)
            p.closed = true // order matters: the queued transfer already shows the row, so there is never a moment without it
            pending.remove(p.id) // from here on the persisted transfer is the source of truth for this row
            notifyParent(p.parent.value)
        }
    }

    override fun deleteDocument(documentId: String) {
        @Suppress("NAME_SHADOWING") val documentId = real(documentId)
        requireSignedIn(doc = documentId, write = true) // to the Filen trash; while locked only for a GrantFolderActivity app
        pending.remove(documentId)?.let { it.dir.deleteRecursively(); return }
        app.transfers.byDocId(documentId)?.let { t -> app.transfers.cancel(t.id); app.transfers.dismiss(t.id); notifyParent(t.parent.value); return }
        requireFreshChain(documentId)
        val item = current(documentId)?.first ?: throw FileNotFoundException(documentId)
        val w = app.router.modifier(item) ?: throw SecurityException("This item can't be deleted")
        io { blocking { w.trash(item) } } // recoverable: goes to the Filen trash
        app.notifyChanged(); parents.remove(documentId)?.let(::notifyParent)
    }

    override fun renameDocument(documentId: String, displayName: String): String? {
        @Suppress("NAME_SHADOWING") val documentId = real(documentId)
        requireSignedIn()
        requireFreshChain(documentId)
        val item = current(documentId)?.first ?: throw FileNotFoundException(documentId)
        val w = app.router.modifier(item) ?: throw SecurityException("This item can't be renamed")
        io { blocking { w.rename(item, displayName) } }
        app.notifyChanged(); parents[documentId]?.let(::notifyParent)
        return null // the id (uuid) is unchanged
    }

    override fun moveDocument(sourceDocumentId: String, sourceParentDocumentId: String, targetParentDocumentId: String): String {
        @Suppress("NAME_SHADOWING") val sourceDocumentId = real(sourceDocumentId)
        requireSignedIn()
        requireFreshChain(sourceDocumentId); requireFreshChain(targetParentDocumentId)
        val item = current(sourceDocumentId)?.first ?: throw FileNotFoundException(sourceDocumentId)
        val w = app.router.modifier(item) ?: throw SecurityException("This item can't be moved")
        val target = idOf(targetParentDocumentId)
        if (app.router.writable(target)?.canWrite(target) != true) throw SecurityException("Target folder is read-only")
        val moved = io { blocking { w.move(item, target) } }
        parents[moved.id.value] = targetParentDocumentId
        app.notifyChanged(); notifyParent(sourceParentDocumentId); notifyParent(targetParentDocumentId)
        return moved.id.value
    }

    private fun notifyParent(parentId: String) {
        context?.contentResolver?.notifyChange(android.provider.DocumentsContract.buildChildDocumentsUri(AUTHORITY, parentId), null)
    }

    companion object {
        const val AUTHORITY = "app.filenfiles.documents"
        /** I4 F3: opaque; changes whenever the file's content changes. */
        const val COLUMN_VERSION = "app.filenfiles.version"
        /** I4 F2: boolean on every child listing - true only when it was listed from Filen in this call. */
        const val EXTRA_FRESH = "app.filenfiles.extra.FRESH"
        /** I4 F2/F6: the opaque account id the listing belongs to. */
        const val EXTRA_ACCOUNT = "app.filenfiles.extra.ACCOUNT"
        private const val LOCKED = "Filen Files is locked"
        private const val PENDING_PREFIX = "filen-pending:"
        private val ROOT_PROJECTION = arrayOf(
            Root.COLUMN_ROOT_ID, Root.COLUMN_DOCUMENT_ID, Root.COLUMN_TITLE, Root.COLUMN_SUMMARY, Root.COLUMN_ICON, Root.COLUMN_FLAGS,
        )
        private val DOC_PROJECTION = arrayOf(
            Document.COLUMN_DOCUMENT_ID, Document.COLUMN_DISPLAY_NAME, Document.COLUMN_MIME_TYPE,
            Document.COLUMN_SIZE, Document.COLUMN_LAST_MODIFIED, Document.COLUMN_FLAGS, Document.COLUMN_SUMMARY, COLUMN_VERSION,
        )
    }
}
