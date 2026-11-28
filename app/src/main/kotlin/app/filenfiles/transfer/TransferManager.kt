package app.filenfiles.transfer

import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.Uri
import android.provider.OpenableColumns
import androidx.core.content.ContextCompat
import app.filenfiles.FilesApp
import app.filenfiles.core.CopyLedger
import app.filenfiles.core.ItemId
import app.filenfiles.filen.FilenSession
import app.filenfiles.core.Names
import app.filenfiles.core.RecentUploads
import app.filenfiles.core.SaveOver
import app.filenfiles.filen.FailureKind
import app.filenfiles.filen.LocalCache
import app.filenfiles.filen.failureKind
import app.filenfiles.filen.friendlyOrNull
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.first
import org.json.JSONArray
import org.json.JSONObject
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File
import java.util.concurrent.atomic.AtomicLong

/**
 * App-scoped upload queue. Unlike a screen-scoped coroutine it survives navigation, reports progress for the
 * whole app, can be cancelled and waits for connectivity after a network failure. A retry after an upload that did
 * reach the server may leave a numbered duplicate; it never replaces a file. A foreground service keeps the
 * process alive while work is pending.
 */
@OptIn(FlowPreview::class)
class TransferManager(private val app: FilesApp) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val queue = Channel<Long>(Channel.UNLIMITED)
    private val jobs = HashMap<Long, Job>()
    private val store = TransferStore(
        app.filesDir, LocalCache.outbox(app.applicationContext), LocalCache.dir(app.applicationContext, LocalCache.UPLOAD),
        account = { (app.session.state.value as? FilenSession.State.LoggedIn)?.email },
    )
    val items: StateFlow<List<Transfer>> = store.items

    /** False until [resume]/[clearAll] ran after the session state is known: the service must not stop before that. */
    private val _settled = MutableStateFlow(false)
    val settled: StateFlow<Boolean> = _settled
    private val scheduled = HashSet<Long>() // ids already handed to the worker queue

    init {
        store.open()
        // Archive scratch space is rebuilt from scratch by a resumed job, so whatever is there is leftover.
        listOf("archive", "extract").forEach { LocalCache.dir(app.applicationContext, it).deleteRecursively() }
        // Persist when the *shape* of the list changes (not on every progress tick).
        scope.launch {
            store.items.map { l -> l.filter { it.active || it.state == TransferState.FAILED }.map { "${it.id}|${it.state}|${it.attempt}|${it.size}|${it.error}|${it.startedAt}|${it.staged}|${it.kind}|${it.file}|${it.sources}" } }
                .distinctUntilChanged().debounce(300).collect { store.save() }
        }
        scope.launch {
            for (id in queue) {
                val t = store.find(id) ?: continue
                if (t.state != TransferState.QUEUED) continue // cancelled while waiting
                val job = scope.launch { process(id) }
                synchronized(jobs) { jobs[id] = job }
                job.join()
                synchronized(jobs) { jobs.remove(id) }
                store.afterJob(t) // finished, cancelled or dismissed: its content goes; failed or held: it stays for Retry
            }
        }
    }

    private fun running(id: Long) = synchronized(jobs) { jobs[id]?.isActive == true }
    private fun find(id: Long) = store.find(id)
    private fun update(id: Long, f: (Transfer) -> Transfer) = store.update(id, f)
    private fun step(id: Long, f: (Transfer) -> Transfer) = store.step(id, f)
    private fun fail(id: Long, msg: String) = store.fail(id, msg)

    /** Queues the picked documents for upload into [parent]. */
    fun enqueueUploads(parent: ItemId, uris: List<Uri>) {
        val ctx = app.applicationContext
        val added = uris.map { uri ->
            var name = "upload"; var size = -1L
            runCatching {
                ctx.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)?.use {
                    if (it.moveToFirst()) { name = it.getString(0) ?: name; if (!it.isNull(1)) size = it.getLong(1) }
                }
            }
            // Lets the Uri stay readable after a process restart (not every provider offers it; staging covers the rest).
            runCatching { ctx.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) }
            Transfer(store.nextId(), name, uri.toString(), parent, ctx.contentResolver.getType(uri), size)
        }
        store.add(added)
        startService()
        added.forEach { schedule(it.id) }
    }

    private fun schedule(id: Long) { synchronized(scheduled) { scheduled.add(id) }; queue.trySend(id) }

    /** Queues a copy (or move, which deletes the original after a complete copy) of [source] into the folder [dest]. */
    fun enqueueCopy(source: app.filenfiles.core.FileItem, dest: ItemId, move: Boolean): Long {
        val t = Transfer(store.nextId(), source.name, "", dest, source.mime, source.size,
            kind = TransferKind.COPY, source = source.id, move = move)
        store.add(listOf(t))
        startService()
        schedule(t.id)
        return t.id
    }

    /** Queues compressing [items] into a new .zip inside the folder [dest]. */
    fun enqueueZip(items: List<app.filenfiles.core.FileItem>, dest: ItemId): Long {
        val t = Transfer(store.nextId(), ArchiveRunner.archiveName(items), "", dest, "application/zip", -1,
            kind = TransferKind.ZIP, sources = items.map { it.id })
        store.add(listOf(t))
        startService(); schedule(t.id); return t.id
    }

    /** Queues extracting the .zip [archive] into a new folder inside [dest]. */
    fun enqueueUnzip(archive: app.filenfiles.core.FileItem, dest: ItemId): Long {
        val t = Transfer(store.nextId(), archive.name, "", dest, archive.mime, archive.size, kind = TransferKind.UNZIP, source = archive.id)
        store.add(listOf(t))
        startService(); schedule(t.id); return t.id
    }

    /**
     * Queues adding the picked documents to the zip [zip] (inside [prefix], an archive folder like "docs/"). The files
     * are copied to private staging first, so the job survives the picker's Uri grants going away.
     */
    fun enqueueAddToZip(zip: app.filenfiles.core.FileItem, uris: List<Uri>, prefix: String) {
        val ctx = app.applicationContext
        val id = store.nextId()
        val parent = app.router.parentOf(zip.id) ?: ItemId("none:")
        val t = Transfer(id, zip.name, "", parent, "application/zip", -1, state = TransferState.PREPARING,
            kind = TransferKind.ADDZIP, source = zip.id, prefix = prefix)
        store.add(listOf(t))
        startService()
        scope.launch {
            val dir = store.stagingDir(id).apply { mkdirs() }
            try {
                val staged = ArrayList<ItemId>()
                val used = ArrayList<String>()
                for (uri in uris) {
                    var name = "file"
                    runCatching { ctx.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { if (it.moveToFirst()) name = it.getString(0) ?: name } }
                    name = Names.unique(used, name.replace('/', '_').ifBlank { "file" }); used += name
                    val f = File(dir, name)
                    val input = ctx.contentResolver.openInputStream(uri) ?: throw java.io.IOException("Couldn't read “$name”.")
                    input.use { i -> f.outputStream().use { o -> i.copyTo(o) } }
                    staged += ItemId("local:${f.absolutePath}")
                }
                update(id) { if (it.state == TransferState.PREPARING) it.copy(state = TransferState.QUEUED, sources = staged) else it }
                schedule(id)
            } catch (e: CancellationException) { throw e
            } catch (e: Throwable) {
                dir.deleteRecursively()
                fail(id, e.friendlyOrNull() ?: e.message ?: "Couldn't read the files to add")
            }
        }
    }

    /** Queues adding existing [items] (files on any storage) to the zip [zip], at its top level. */
    fun enqueueAddItemsToZip(zip: app.filenfiles.core.FileItem, items: List<app.filenfiles.core.FileItem>) {
        val t = Transfer(store.nextId(), zip.name, "", app.router.parentOf(zip.id) ?: ItemId("none:"), "application/zip", -1,
            kind = TransferKind.ADDZIP, source = zip.id, sources = items.map { it.id })
        store.add(listOf(t))
        startService(); schedule(t.id)
    }

    /** Queues an already-local file (written by another app through the documents provider). Takes ownership of [file]. */
    fun enqueueFile(parent: ItemId, file: File, name: String, mime: String?, docId: String? = null, writer: Int = -1): Long {
        val t = Transfer(store.nextId(), name, Uri.fromFile(file).toString(), parent, mime, file.length(), file = file, docId = docId, writer = writer)
        store.add(listOf(t))
        store.save() // now, not after the debounce: the writer has been told its save worked
        startService()
        schedule(t.id)
        return t.id
    }

    /**
     * Queues new content another app saved over the existing Filen file [lineage] (edited from version [base]). Takes
     * ownership of [file]. Saves of one file run in the order they were made (the queue runs one job at a time).
     */
    fun enqueueSave(parent: ItemId, file: File, name: String, mime: String?, lineage: String, base: String?, writer: Int): Long {
        val t = Transfer(store.nextId(), name, Uri.fromFile(file).toString(), parent, mime, file.length(), file = file,
            kind = TransferKind.SAVE, lineage = lineage, base = base, writer = writer)
        store.add(listOf(t))
        store.save() // now: the writer has been told its save worked
        startService()
        schedule(t.id)
        return t.id
    }

    /** The newest unsent save of the file [lineage], if any: readers get its content until it is uploaded. */
    fun pendingSave(lineage: String): Transfer? = store.pendingSave(lineage)

    /** Uploads that finished in the last couple of minutes (the Filen listing can lag behind them). */
    val recentUploads = RecentUploads()

    /** Whether anything is queued, running, or waiting for the network. */
    val hasActive: Boolean get() = store.items.value.any { it.active }

    /** Documents-provider writes that are queued, running, waiting or failed (not yet dismissed), oldest first. */
    fun providerUploads(): List<Transfer> = store.providerUploads()

    /** pending document id -> real id, for files other apps wrote through the documents provider and that finished uploading. */
    val docIds = DocIdMap(File(app.filesDir, "docids.json"))

    fun byDocId(docId: String): Transfer? = store.byDocId(docId)

    /** True while the transfer is still pending (queued, running, waiting or failed-but-retryable). */
    fun isPending(id: Long) = find(id)?.let { it.state != TransferState.DONE && it.state != TransferState.CANCELLED } == true

    fun describeWrite(dir: File, docId: String, parent: ItemId, name: String, mime: String, lineage: String? = null, base: String? = null, writer: Int = -1) =
        store.describeWrite(dir, docId, parent, name, mime, lineage, base, writer)

    fun noteRead(uid: Int, lineage: String, version: String) = store.noteRead(uid, lineage, version)

    fun baseFor(uid: Int, lineage: String, copied: String?, copiedFrom: Transfer?): String? = store.baseFor(uid, lineage, copied, copiedFrom)

    /** Stops [id]. What it owns is deleted once its job has stopped; an upload already being named still finishes and says so. */
    fun cancel(id: Long) {
        val job = synchronized(jobs) { jobs[id] }
        store.cancel(id, running = job?.isActive == true) ?: return
        job?.cancel()
    }

    fun cancelAll() = store.items.value.filter { it.active }.forEach { cancel(it.id) }

    fun retry(id: Long) {
        if (!store.retry(id)) return
        startService()
        schedule(id)
    }

    fun dismiss(id: Long) = store.dismiss(id, running(id))
    fun clearFinished() = store.items.update { l -> l.filter { it.active } }

    fun unfinishedCount(): Int = store.unfinishedCount()

    // ---------- persistence / lifecycle ----------

    /** Called once the session state is known and the user is signed in: starts everything that was left queued. */
    fun resume() {
        val pending = store.resumeStates().filter { synchronized(scheduled) { it !in scheduled } }
        if (pending.isNotEmpty()) startService()
        pending.forEach(::schedule)
        _settled.value = true
        // Name uploads a crash or a failed rename left under their temporary name, once nothing that could reuse them runs.
        scope.launch {
            store.items.first { l -> l.none { it.active } }
            runCatching { app.filen.recoverUploads() }.getOrNull()?.takeIf { it > 0 }?.let { app.notifyChanged() }
        }
    }

    /**
     * Signed out without the user asking (a stored session that can't be restored, for example): nothing can run, and
     * nothing is discarded. Pending work is kept and resumes when the same account signs in again.
     */
    fun hold() {
        val held = store.hold()
        held.forEach { id -> synchronized(jobs) { jobs[id] }?.cancel() }
        synchronized(scheduled) { scheduled.removeAll(held.toSet()) }
        _settled.value = true
    }

    /**
     * The user signed out of [leaving]: its pending work is cancelled and forgotten. Work held for another account stays,
     * with its files.
     */
    fun clearAll(leaving: String?) {
        store.clearFor(leaving).forEach { t -> synchronized(jobs) { jobs[t.id] }?.cancel() }
        _settled.value = true
    }

    // ---------- worker ----------

    private suspend fun process(id: Long) {
        val ctx = app.applicationContext
        val t0 = find(id) ?: return
        if (t0.kind == TransferKind.COPY) { processCopy(t0); return }
        if (t0.kind == TransferKind.SAVE) { processSave(t0); return }
        if (t0.kind == TransferKind.ZIP || t0.kind == TransferKind.UNZIP || t0.kind == TransferKind.ADDZIP) { processArchive(t0); return }
        val storage = app.router.writable(t0.parent)
        if (storage == null) { fail(id, "This folder doesn't accept uploads."); return }
        val staging = store.stagingDir(id).apply { mkdirs() }
        val staged = t0.file ?: File(staging, t0.name.replace('/', '_').ifBlank { "upload" })
        try {
            step(id) { it.copy(state = TransferState.PREPARING, done = 0) }
            if (t0.file == null && !(t0.staged && staged.isFile)) {
                // Stage a private copy: the SDK uploads from a path, and the picked Uri may stop being readable.
                if (t0.size >= 0) LocalCache.requireSpace(ctx, t0.size)
                val input = try { ctx.contentResolver.openInputStream(Uri.parse(t0.uri))!! } catch (e: Exception) {
                    if (e is SecurityException || e is java.io.FileNotFoundException || e is NullPointerException)
                        throw java.io.IOException("The original file is no longer available. Pick it again to upload it.")
                    throw e
                }
                val part = File(staging, staged.name + ".part")
                input.use { i -> part.outputStream().use { o -> i.copyTo(o) } }
                if (!part.renameTo(staged)) throw java.io.IOException("Couldn't stage “${t0.name}”.")
                update(id) { it.copy(staged = true) }
            }
            if (t0.file != null && !t0.file.isFile) throw java.io.IOException("The file to upload is gone.")
            val size = staged.length()
            update(id) { it.copy(size = size) }
            // No "did an earlier attempt land?" guess by name and size: an upload never replaces a file, so such a match can
            // be an older file, and taking it for this one would discard the new content. Worst case a retry leaves a "(1)" copy.
            val maxAttempts = 4
            var attempt = 1
            while (true) {
                if (find(id)?.active != true) return
                step(id) { it.copy(state = TransferState.UPLOADING, done = 0, attempt = attempt, error = null) }
                val started = System.currentTimeMillis()
                update(id) { it.copy(startedAt = started) }
                try {
                    val uploaded = storage.upload(t0.parent, staged.absolutePath, t0.name, t0.mime) { sent -> step(id) { it.copy(done = sent) } }
                    finishOk(id, uploaded); return
                } catch (e: CancellationException) { throw e
                } catch (e: Throwable) {
                    if (e.failureKind() != FailureKind.NETWORK || attempt >= maxAttempts) { fail(id, e.friendlyOrNull() ?: e.message ?: "Upload failed"); return }
                    step(id) { it.copy(state = TransferState.WAITING_NETWORK, error = "Waiting for network…") }
                    if (!awaitNetwork(ctx, 10 * 60_000L)) { fail(id, "No network connection. Retry when you're back online."); return }
                    attempt++
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            fail(id, e.friendlyOrNull() ?: e.message ?: "Upload failed")
        }
        // The staged copy is kept until the transfer finished, was cancelled or dismissed (see TransferStore.afterJob).
    }

    /**
     * Another app's save over an existing file. Checked just before the upload ([SaveOver.plan]); anything the check
     * can't vouch for (changed elsewhere, gone, trashed, versioning off) is uploaded as a separate conflict copy, so
     * neither the old nor the new content is ever lost. The local copy is kept until one of the two uploads landed.
     */
    private suspend fun processSave(t0: Transfer) {
        val id = t0.id
        val file = t0.file
        val lineage = t0.lineage
        try {
            step(id) { it.copy(state = TransferState.PREPARING, done = 0, startedAt = System.currentTimeMillis()) }
            if (file == null || !file.isFile || lineage == null) throw java.io.IOException("The saved file is gone.")
            var attempt = 1
            while (true) {
                if (find(id)?.active != true) return
                step(id) { it.copy(state = TransferState.UPLOADING, done = 0, attempt = attempt, error = null) }
                try {
                    val filen = app.filen
                    val versioning = filen.versioningEnabled()
                    val head = filen.headOfLineage(lineage)
                    val headInfo = head?.let { h ->
                        // No guessing where it is: uploading its name into the wrong folder would hit another file.
                        val folder = if (h.trashed) t0.parent else filen.currentParent(h.item.id)
                            ?: throw java.io.IOException("Couldn't tell which folder “${h.item.name}” is in. Retry.")
                        SaveOver.Head(h.item.id.key, folder.value, h.item.name, h.trashed)
                    }
                    val plan = SaveOver.plan(versioning, headInfo, t0.base, store.ownSaves(lineage, t0.writer, before = id))
                    val progress: (Long) -> Unit = { sent -> step(id) { it.copy(done = sent) } }
                    when (plan) {
                        is SaveOver.Plan.Replace -> {
                            val result = filen.uploadVersion(ItemId(plan.parent), file.absolutePath, plan.name, t0.mime, headInfo!!.uuid, lineage, progress)
                            val saved = result.item; val previous = result.previous
                            store.recordOwnSave(lineage, saved.id.key, id, t0.writer)
                            val note = when {
                                saved.lineage != null && saved.lineage != lineage ->
                                    "Saved, but as a new version of another file named “${plan.name}” (the original was moved away meanwhile). Both are kept; see its version history."
                                previous != null && previous != headInfo!!.uuid ->
                                    "Saved. Another change to “${plan.name}” was saved at the same moment; both are in its version history."
                                else -> null
                            }
                            finishOk(id, saved, listOfNotNull(note, result.shareProblem).joinToString(" ").ifEmpty { null })
                        }
                        is SaveOver.Plan.Copy -> {
                            val folder = headInfo?.takeIf { !it.trashed }?.parent?.let(::ItemId) ?: t0.parent
                            val copy = filen.upload(folder, file.absolutePath, SaveOver.conflictName(headInfo?.name ?: t0.name), t0.mime, progress)
                            finishOk(id, copy, "Saved as “${copy.name}” because ${plan.reason}. The original was not changed.")
                        }
                    }
                    return
                } catch (e: CancellationException) { throw e
                } catch (e: Throwable) {
                    if (e.failureKind() != FailureKind.NETWORK || attempt >= 4) { fail(id, e.friendlyOrNull() ?: e.message ?: "Save failed"); return }
                    step(id) { it.copy(state = TransferState.WAITING_NETWORK, error = "Waiting for network…") }
                    if (!awaitNetwork(app.applicationContext, 10 * 60_000L)) { fail(id, "No network connection. Retry when you're back online."); return }
                    attempt++
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            fail(id, e.friendlyOrNull() ?: e.message ?: "Save failed")
        }
    }

    private suspend fun processCopy(t0: Transfer) {
        val id = t0.id
        try {
            step(id) { it.copy(state = TransferState.PREPARING, done = 0, startedAt = maxOf(it.startedAt, System.currentTimeMillis())) }
            CopyRunner(
                app, CopyLedger(store.ledgerFile(id)),
                setTotal = { total -> step(id) { it.copy(size = total, state = TransferState.UPLOADING) } },
                setDone = { done -> step(id) { it.copy(done = done) } },
                setWaiting = { w -> step(id) { if (w) it.copy(state = TransferState.WAITING_NETWORK, error = "Waiting for network…") else it.copy(state = TransferState.UPLOADING, error = null) } },
                awaitNetwork = { awaitNetwork(app.applicationContext, 10 * 60_000L) },
            ).run(t0.source ?: error("No source"), t0.parent, t0.move)
            finishOk(id)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            fail(id, e.friendlyOrNull() ?: e.message ?: "Copy failed")
        }
    }

    private suspend fun processArchive(t0: Transfer) {
        val id = t0.id
        val work = File(LocalCache.dir(app.applicationContext, "archive"), "$id")
        try {
            step(id) { it.copy(state = TransferState.PREPARING, done = 0, startedAt = maxOf(it.startedAt, System.currentTimeMillis())) }
            val runner = ArchiveRunner(
                app, work,
                setTotal = { total -> step(id) { it.copy(size = total, state = TransferState.UPLOADING, done = 0) } },
                setDone = { done -> step(id) { it.copy(done = done) } },
                deliver = { local, dest ->
                    step(id) { it.copy(done = 0, state = TransferState.PREPARING) }
                    CopyRunner(
                        app,
                        setTotal = { total -> step(id) { it.copy(size = total, state = TransferState.UPLOADING, done = 0) } },
                        setDone = { done -> step(id) { it.copy(done = done) } },
                        setWaiting = { w -> step(id) { if (w) it.copy(state = TransferState.WAITING_NETWORK, error = "Waiting for network…") else it.copy(state = TransferState.UPLOADING, error = null) } },
                        awaitNetwork = { awaitNetwork(app.applicationContext, 10 * 60_000L) },
                    ).run(local, dest, move = false)
                },
            )
            when (t0.kind) {
                TransferKind.ZIP -> runner.zip(t0.sources, t0.parent, t0.name)
                TransferKind.ADDZIP -> {
                    // Sources are either staged copies of picked documents or ordinary items from any storage.
                    val files = t0.sources.map { sid ->
                        val local = if (sid.scheme == "local") File(sid.key).takeIf { it.isFile } else null
                        if (local != null) local to local.name
                        else {
                            val item = app.router.stat(sid) ?: throw java.io.IOException("A file to add is no longer there.")
                            File(app.router.materialize(item).path) to item.name
                        }
                    }
                    runner.addToZip(t0.source ?: error("No archive"), files, t0.prefix)
                }
                else -> runner.unzip(t0.source ?: error("No archive"), t0.parent)
            }
            finishOk(id)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            fail(id, e.friendlyOrNull() ?: e.message ?: "Couldn't ${when (t0.kind) { TransferKind.ZIP -> "compress"; TransferKind.ADDZIP -> "update the zip"; else -> "extract" }}")
        } finally {
            withContext(kotlinx.coroutines.NonCancellable) { work.deleteRecursively() }
        }
    }

    private fun finishOk(id: Long, uploaded: app.filenfiles.core.FileItem? = null, note: String? = null) {
        store.ledgerFile(id).delete()
        // An app that created a file knows exactly what is in it: its next save builds on this upload.
        find(id)?.takeIf { it.kind == TransferKind.UPLOAD && it.docId != null && it.writer >= 0 }?.let { t ->
            uploaded?.lineage?.let { store.noteRead(t.writer, it, uploaded.id.key) }
        }
        // Receipt first: a provider listing between "no longer pending" and "visible in Filen" must still show the file.
        if (uploaded != null) find(id)?.let { recentUploads.add(it.parent, uploaded); it.docId?.let { d -> docIds.put(d, uploaded.id.value) } }
        store.finish(id, note)
        app.notifyChanged()
        find(id)?.let { app.docsChanged(it.parent) }
    }

    /** Suspends until a validated network is available; false after [timeoutMs]. */
    private suspend fun awaitNetwork(ctx: Context, timeoutMs: Long): Boolean {
        val cm = ctx.getSystemService(ConnectivityManager::class.java)
        fun online() = cm.getNetworkCapabilities(cm.activeNetwork)?.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED) == true
        if (online()) { kotlinx.coroutines.delay(2_000); return true } // transient blip: brief pause, then retry
        return withTimeoutOrNull(timeoutMs) {
            suspendCancellableCoroutine<Boolean> { cont ->
                val cb = object : ConnectivityManager.NetworkCallback() {
                    override fun onCapabilitiesChanged(n: Network, caps: NetworkCapabilities) {
                        if (caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED) && cont.isActive) cont.resume(true) {}
                    }
                }
                cm.registerDefaultNetworkCallback(cb)
                cont.invokeOnCancellation { runCatching { cm.unregisterNetworkCallback(cb) } }
            }
        } == true
    }

    /**
     * Keeps the process alive while work is pending. When the process was started in the background (for example by
     * another app writing through the documents provider) Android refuses a foreground service; fall back to an
     * expedited WorkManager job that drives the same persisted queue to the end.
     */
    private fun startService() {
        val ctx = app.applicationContext
        try {
            ContextCompat.startForegroundService(ctx, Intent(ctx, TransferService::class.java))
        } catch (e: Throwable) {
            val refused = e is IllegalStateException || (android.os.Build.VERSION.SDK_INT >= 31 && e is android.app.ForegroundServiceStartNotAllowedException)
            android.util.Log.w("FilenDebug", "Foreground service start refused (${e.javaClass.simpleName}: ${e.message}); using WorkManager (refused=$refused)")
            TransferResumeWorker.enqueue(ctx)
        }
    }

}
