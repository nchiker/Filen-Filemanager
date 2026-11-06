package app.filenfiles.docs

import android.app.PendingIntent
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
import app.filenfiles.core.Folder
import app.filenfiles.core.Listing
import app.filenfiles.filen.FailureKind
import app.filenfiles.filen.failureKind
import app.filenfiles.core.ItemId
import app.filenfiles.core.Origin
import app.filenfiles.core.UploadStatus
import app.filenfiles.transfer.TransferManager
import app.filenfiles.filen.FilenSession
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
        if (!(t.active || t.state == TransferManager.State.FAILED)) return null
        return PendingRow(id, t.parent, t.name, t.mime ?: "application/octet-stream", maxOf(t.size, t.file?.length() ?: 0L),
            UploadStatus.summary(t.state == TransferManager.State.WAITING_NETWORK, t.state == TransferManager.State.FAILED, t.error))
    }

    private fun pendingRowsIn(parent: ItemId): List<PendingRow> =
        (pending.values.filter { it.parent == parent && !it.closed }.mapNotNull { pendingRow(it.id) } +
            app.transfers.providerUploads().filter { it.parent == parent }.mapNotNull { pendingRow(it.docId!!) }).distinctBy { it.id }

    private fun pendingParent(id: String): String? = pending[id]?.parent?.value ?: app.transfers.byDocId(id)?.parent?.value
    private val pendingSeq = AtomicLong(1)
    /** child document id → parent document id, learned from listings; backs [isChildDocument]. */
    private val parents = ConcurrentHashMap<String, String>()
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

    /** Waits (briefly) for session restore, which runs on a background thread after process start. */
    private fun requireSignedIn() {
        if (app.appLock.isLocked) { // opens the app, which asks to unlock
            val ctx = context!!
            val pi = PendingIntent.getActivity(ctx, 0, Intent(ctx, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
            throw AuthenticationRequiredException(SecurityException("Filen Files is locked"), pi)
        }
        val session = app.session
        val deadline = System.currentTimeMillis() + 10_000
        while (session.state.value is FilenSession.State.Unknown && System.currentTimeMillis() < deadline) Thread.sleep(50)
        if (session.state.value !is FilenSession.State.LoggedIn) {
            val ctx = context!!
            val pi = PendingIntent.getActivity(ctx, 0, Intent(ctx, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
            throw AuthenticationRequiredException(SecurityException("Sign in to Filen"), pi)
        }
    }

    private fun idOf(documentId: String): ItemId {
        val id = ItemId(documentId)
        if (id.scheme != "filen" && id.scheme != "filen-in") throw FileNotFoundException("Unknown document $documentId")
        return id
    }

    private fun <T> io(block: () -> T): T = try { block() } catch (e: Exception) {
        if (e is OperationCanceledException || e is FileNotFoundException || e is AuthenticationRequiredException) throw e
        throw java.io.IOException(e.friendlyOrNull() ?: e.message ?: e.javaClass.simpleName, e)
    }

    // ---------- roots ----------

    override fun queryRoots(projection: Array<out String>?): Cursor {
        val c = MatrixCursor(projection ?: ROOT_PROJECTION)
        val s = app.session.state.value
        if (s !is FilenSession.State.LoggedIn || app.appLock.isLocked) return c
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
        return c
    }

    // ---------- documents ----------

    private fun MatrixCursor.addRoot(id: String) {
        val personal = id == "filen:root"
        newRow().apply {
            add(Document.COLUMN_DOCUMENT_ID, id)
            add(Document.COLUMN_DISPLAY_NAME, if (personal) "Filen Drive" else "Shared with me")
            add(Document.COLUMN_MIME_TYPE, Document.MIME_TYPE_DIR)
            add(Document.COLUMN_SIZE, null); add(Document.COLUMN_LAST_MODIFIED, null)
            add(Document.COLUMN_FLAGS, if (personal) Document.FLAG_DIR_SUPPORTS_CREATE else 0)
        }
    }

    private fun MatrixCursor.addItem(item: FileItem) {
        var flags = 0
        val mime = if (item.isDirectory) Document.MIME_TYPE_DIR else item.mime ?: "application/octet-stream"
        if (item.isDirectory && item.origin == Origin.FILEN_PERSONAL) flags = flags or Document.FLAG_DIR_SUPPORTS_CREATE
        if (!item.isDirectory && mime.startsWith("image/")) flags = flags or Document.FLAG_SUPPORTS_THUMBNAIL
        if (app.router.modifier(item) != null) flags = flags or Document.FLAG_SUPPORTS_DELETE or Document.FLAG_SUPPORTS_RENAME or Document.FLAG_SUPPORTS_MOVE
        newRow().apply {
            add(Document.COLUMN_DOCUMENT_ID, item.id.value)
            add(Document.COLUMN_DISPLAY_NAME, item.name)
            add(Document.COLUMN_MIME_TYPE, mime)
            add(Document.COLUMN_SIZE, if (item.isDirectory) null else item.size)
            add(Document.COLUMN_LAST_MODIFIED, item.modified.takeIf { it > 0 })
            add(Document.COLUMN_FLAGS, flags)
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
        val c = MatrixCursor(projection ?: DOC_PROJECTION)
        if (documentId.startsWith(PENDING_PREFIX)) {
            val row = pendingRow(documentId)
            if (row != null) { c.addPending(row); return c }
            // Finished (or dismissed) meanwhile: the real file appears under its own id when the folder is listed.
            throw FileNotFoundException("$documentId is no longer pending - list the folder to find the uploaded file")
        }
        requireSignedIn()
        if (documentId == "filen:root" || documentId == "filen-in:root") { c.addRoot(documentId); return c }
        val item = io { blocking { app.router.stat(idOf(documentId)) } } ?: throw FileNotFoundException(documentId)
        c.addItem(item)
        return c
    }

    override fun queryChildDocuments(parentDocumentId: String, projection: Array<out String>?, sortOrder: String?): Cursor {
        requireSignedIn()
        val parent = idOf(parentDocumentId)
        // Offline, a fresh listing can't be fetched. Answer from the last listing we saw (if any) plus the uploads we know
        // about, so a client that just wrote a file can still see it "Waiting for network…" instead of getting an error.
        var stale = false
        val listing = try {
            blocking { app.router.list(parent) }
        } catch (e: OperationCanceledException) { throw e
        } catch (e: Throwable) {
            if (e.failureKind() == FailureKind.NETWORK && (app.listingCache[parent.value] != null || pendingRowsIn(parent).isNotEmpty())) {
                stale = true
                app.listingCache[parent.value] ?: Listing(Folder(parent, "", Origin.FILEN_PERSONAL), emptyList(), emptyList())
            } else io<Listing> { throw e }
        }
        if (!stale) app.listingCache[parent.value] = listing // remembered for offline answers (dropped on sign-out)
        if (stale) android.util.Log.d("FilenDebug", "Offline: answering the listing of $parentDocumentId from the cache and the upload queue")
        val c = MatrixCursor(projection ?: DOC_PROJECTION)
        c.setNotificationUri(context!!.contentResolver, android.provider.DocumentsContract.buildChildDocumentsUri(AUTHORITY, parentDocumentId))
        listing.items.forEach { parents[it.id.value] = parentDocumentId; c.addItem(it) }
        // Just-finished uploads that Filen's listing doesn't show yet (it can lag a few seconds): no gap for the client.
        app.transfers.recentUploads.missingFrom(parent, listing.items.map { it.id }).forEach { parents[it.id.value] = parentDocumentId; c.addItem(it) }
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
        if (parentDocumentId == documentId) return false
        var cur: String? = documentId
        var guard = 0
        while (cur != null && guard++ < 64) {
            val p = parents[cur] ?: pendingParent(cur) ?: break
            if (p == parentDocumentId) return true
            cur = p
        }
        // Not learned from a listing yet (cold start): a folder's own breadcrumb trail tells us.
        return runCatching {
            val id = ItemId(documentId)
            if (id.key == "root" || (id.scheme != "filen" && id.scheme != "filen-in")) return@runCatching false
            requireSignedIn()
            blocking { app.router.list(id) }.trail.any { it.id.value == parentDocumentId }
        }.getOrDefault(false)
    }

    // ---------- reading ----------

    override fun openDocument(documentId: String, mode: String, signal: CancellationSignal?): ParcelFileDescriptor {
        pending[documentId]?.let { return openForWrite(it, mode) }
        if (documentId.startsWith(PENDING_PREFIX)) throw java.lang.UnsupportedOperationException("This file was already written")
        requireSignedIn()
        if (mode != "r") throw java.lang.UnsupportedOperationException("Existing Filen files are read-only here")
        val item = io { blocking(signal) { app.router.stat(idOf(documentId)) } } ?: throw FileNotFoundException(documentId)
        if (item.isDirectory) throw FileNotFoundException("$documentId is a folder")
        val file = io { blocking(signal) { app.router.materialize(item) } }
        return ParcelFileDescriptor.open(File(file.path), ParcelFileDescriptor.MODE_READ_ONLY)
    }

    override fun openDocumentThumbnail(documentId: String, sizeHint: Point, signal: CancellationSignal?): AssetFileDescriptor {
        requireSignedIn()
        val item = io { blocking(signal) { app.router.stat(idOf(documentId)) } } ?: throw FileNotFoundException(documentId)
        val px = maxOf(sizeHint.x, sizeHint.y).coerceIn(128, 512)
        val path = io { blocking(signal) { app.router.thumbnail(item, px) } } ?: throw FileNotFoundException("No thumbnail")
        return AssetFileDescriptor(ParcelFileDescriptor.open(File(path), ParcelFileDescriptor.MODE_READ_ONLY), 0, AssetFileDescriptor.UNKNOWN_LENGTH)
    }

    // ---------- writing ----------

    override fun createDocument(parentDocumentId: String, mimeType: String, displayName: String): String {
        requireSignedIn()
        val parent = idOf(parentDocumentId)
        val w = app.router.writable(parent)?.takeIf { it.canWrite(parent) } ?: throw SecurityException("This folder is read-only")
        if (displayName.isBlank() || '/' in displayName) throw IllegalArgumentException("Invalid name")
        if (mimeType == Document.MIME_TYPE_DIR) {
            val id = io { blocking { w.createFolder(parent, displayName) } }
            parents[id.value] = parentDocumentId
            notifyParent(parentDocumentId); return id.value
        }
        val n = pendingSeq.getAndIncrement()
        val dir = File(LocalCache.dir(context!!, LocalCache.UPLOAD), "doc-${System.nanoTime()}-$n").apply { mkdirs() }
        // Time-based so an id handed out before a process restart can never be reused for a different file.
        val p = Pending("$PENDING_PREFIX${System.currentTimeMillis()}-$n", parent, displayName.replace('/', '_'), mimeType, dir)
        pending[p.id] = p
        parents[p.id] = parentDocumentId
        return p.id
    }

    /** The writer gets a private temp file; when it closes cleanly the file is handed to the upload queue. */
    private fun openForWrite(p: Pending, mode: String): ParcelFileDescriptor {
        if (!mode.contains('w')) throw FileNotFoundException("Write-only until uploaded")
        if (p.closed) throw java.lang.UnsupportedOperationException("This file was already written")
        val file = File(p.dir, p.name)
        return ParcelFileDescriptor.open(
            file, ParcelFileDescriptor.parseMode(mode), Handler(writerThread.looper),
        ) { error ->
            if (error != null) { p.dir.deleteRecursively(); pending.remove(p.id); return@open }
            p.transferId = app.transfers.enqueueFile(p.parent, file, p.name, p.mime, docId = p.id)
            p.closed = true // order matters: the queued transfer already shows the row, so there is never a moment without it
            pending.remove(p.id) // from here on the persisted transfer is the source of truth for this row
            notifyParent(p.parent.value)
        }
    }

    override fun deleteDocument(documentId: String) {
        requireSignedIn()
        pending.remove(documentId)?.let { it.dir.deleteRecursively(); return }
        app.transfers.byDocId(documentId)?.let { t -> app.transfers.cancel(t.id); app.transfers.dismiss(t.id); notifyParent(t.parent.value); return }
        val item = io { blocking { app.router.stat(idOf(documentId)) } } ?: throw FileNotFoundException(documentId)
        val w = app.router.modifier(item) ?: throw SecurityException("This item can't be deleted")
        io { blocking { w.trash(item) } } // recoverable: goes to the Filen trash
        app.notifyChanged(); parents.remove(documentId)?.let(::notifyParent)
    }

    override fun renameDocument(documentId: String, displayName: String): String? {
        requireSignedIn()
        val item = io { blocking { app.router.stat(idOf(documentId)) } } ?: throw FileNotFoundException(documentId)
        val w = app.router.modifier(item) ?: throw SecurityException("This item can't be renamed")
        io { blocking { w.rename(item, displayName) } }
        app.notifyChanged(); parents[documentId]?.let(::notifyParent)
        return null // the id (uuid) is unchanged
    }

    override fun moveDocument(sourceDocumentId: String, sourceParentDocumentId: String, targetParentDocumentId: String): String {
        requireSignedIn()
        val item = io { blocking { app.router.stat(idOf(sourceDocumentId)) } } ?: throw FileNotFoundException(sourceDocumentId)
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
        private const val PENDING_PREFIX = "filen-pending:"
        private val ROOT_PROJECTION = arrayOf(
            Root.COLUMN_ROOT_ID, Root.COLUMN_DOCUMENT_ID, Root.COLUMN_TITLE, Root.COLUMN_SUMMARY, Root.COLUMN_ICON, Root.COLUMN_FLAGS,
        )
        private val DOC_PROJECTION = arrayOf(
            Document.COLUMN_DOCUMENT_ID, Document.COLUMN_DISPLAY_NAME, Document.COLUMN_MIME_TYPE,
            Document.COLUMN_SIZE, Document.COLUMN_LAST_MODIFIED, Document.COLUMN_FLAGS, Document.COLUMN_SUMMARY,
        )
    }
}
