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
import app.filenfiles.core.ItemId
import app.filenfiles.core.Names
import app.filenfiles.core.RecentUploads
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
 * whole app, can be cancelled, waits for connectivity after a network failure and never leaves duplicates
 * behind when a retry follows an upload that actually reached the server. A foreground service keeps the
 * process alive while work is pending.
 */
@OptIn(FlowPreview::class)
class TransferManager(private val app: FilesApp) {
    enum class State { QUEUED, PREPARING, UPLOADING, WAITING_NETWORK, DONE, FAILED, CANCELLED }

    /** UPLOAD sends a picked document/staged file to [Transfer.parent]; COPY copies/moves [Transfer.source] (file or folder) there. */
    enum class Kind { UPLOAD, COPY, ZIP, UNZIP,
        /** ADDZIP: [Transfer.source] is the zip, [Transfer.sources] the staged local files to add, [Transfer.prefix] the folder inside it. */
        ADDZIP }

    data class Transfer(
        val id: Long, val name: String, val uri: Uri, val parent: ItemId, val mime: String?,
        val size: Long, val state: State = State.QUEUED, val done: Long = 0, val error: String? = null, val attempt: Int = 1,
        /** Already-staged source (documents-provider writes): uploaded in place and deleted when finished/cancelled/dismissed. */
        val file: File? = null,
        /** Epoch millis the current/last attempt began (0 = never ran); lets a restart detect an upload that landed before the kill. */
        val startedAt: Long = 0,
        /** True once the picked Uri has been fully copied to the staging dir (so a restart doesn't need the Uri any more). */
        val staged: Boolean = false,
        val kind: Kind = Kind.UPLOAD,
        /** COPY only: what to copy, and whether to delete it once everything has been copied. */
        val source: ItemId? = null,
        val move: Boolean = false,
        /** ZIP only: the items to compress ([name] is the archive's file name). UNZIP uses [source] for the archive. */
        val sources: List<ItemId> = emptyList(),
        /** Documents-provider writes: the `filen-pending:N` id the other app was given, kept so its row survives a restart. */
        val docId: String? = null,
        /** ADDZIP only: the folder inside the archive the files go to ("" or "a/b/"). */
        val prefix: String = "",
    ) {
        /** Present-tense label for the panel/notification. */
        val verb: String get() = when {
            kind == Kind.UPLOAD -> "Uploading"
            kind == Kind.ZIP -> "Compressing"
            kind == Kind.UNZIP -> "Extracting"
            kind == Kind.ADDZIP -> "Updating zip"
            move -> "Moving"
            source != null && source.scheme != "local" && parent.scheme == "local" -> "Saving"
            else -> "Copying"
        }
        val active get() = state == State.QUEUED || state == State.PREPARING || state == State.UPLOADING || state == State.WAITING_NETWORK
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val ids = AtomicLong(1)
    private val queue = Channel<Long>(Channel.UNLIMITED)
    private val jobs = HashMap<Long, Job>()
    private val _items = MutableStateFlow<List<Transfer>>(emptyList())
    val items: StateFlow<List<Transfer>> = _items

    /** False until [resume]/[clearAll] ran after the session state is known: the service must not stop before that. */
    private val _settled = MutableStateFlow(false)
    val settled: StateFlow<Boolean> = _settled
    private val scheduled = HashSet<Long>() // ids already handed to the worker queue
    private val storeFile = File(app.filesDir, "transfers.json")

    init {
        loadPersisted()
        sweepOrphanStaging()
        // Persist when the *shape* of the list changes (not on every progress tick).
        scope.launch {
            _items.map { l -> l.filter { it.active || it.state == State.FAILED }.map { "${it.id}|${it.state}|${it.attempt}|${it.size}|${it.error}|${it.startedAt}|${it.staged}|${it.kind}" } }
                .distinctUntilChanged().debounce(300).collect { savePersisted() }
        }
        scope.launch {
            for (id in queue) {
                val t = find(id) ?: continue
                if (t.state != State.QUEUED) continue // cancelled while waiting
                val job = scope.launch { process(id) }
                synchronized(jobs) { jobs[id] = job }
                job.join()
                synchronized(jobs) { jobs.remove(id) }
            }
        }
    }

    private fun find(id: Long) = _items.value.firstOrNull { it.id == id }
    private fun update(id: Long, f: (Transfer) -> Transfer) = _items.update { l -> l.map { if (it.id == id) f(it) else it } }

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
            Transfer(ids.getAndIncrement(), name, uri, parent, ctx.contentResolver.getType(uri), size)
        }
        _items.update { l -> l.filter { it.active || it.state == State.FAILED } + added } // new batch: forget completed/cancelled ones
        startService()
        added.forEach { schedule(it.id) }
    }

    private fun schedule(id: Long) { synchronized(scheduled) { scheduled.add(id) }; queue.trySend(id) }

    /** Queues a copy (or move, which deletes the original after a complete copy) of [source] into the folder [dest]. */
    fun enqueueCopy(source: app.filenfiles.core.FileItem, dest: ItemId, move: Boolean): Long {
        val t = Transfer(ids.getAndIncrement(), source.name, Uri.EMPTY, dest, source.mime, source.size,
            kind = Kind.COPY, source = source.id, move = move)
        _items.update { l -> l.filter { it.active || it.state == State.FAILED } + t }
        startService()
        schedule(t.id)
        return t.id
    }

    /** Queues compressing [items] into a new .zip inside the folder [dest]. */
    fun enqueueZip(items: List<app.filenfiles.core.FileItem>, dest: ItemId): Long {
        val t = Transfer(ids.getAndIncrement(), ArchiveRunner.archiveName(items), Uri.EMPTY, dest, "application/zip", -1,
            kind = Kind.ZIP, sources = items.map { it.id })
        _items.update { l -> l.filter { it.active || it.state == State.FAILED } + t }
        startService(); schedule(t.id); return t.id
    }

    /** Queues extracting the .zip [archive] into a new folder inside [dest]. */
    fun enqueueUnzip(archive: app.filenfiles.core.FileItem, dest: ItemId): Long {
        val t = Transfer(ids.getAndIncrement(), archive.name, Uri.EMPTY, dest, archive.mime, archive.size, kind = Kind.UNZIP, source = archive.id)
        _items.update { l -> l.filter { it.active || it.state == State.FAILED } + t }
        startService(); schedule(t.id); return t.id
    }

    /**
     * Queues adding the picked documents to the zip [zip] (inside [prefix], an archive folder like "docs/"). The files
     * are copied to private staging first, so the job survives the picker's Uri grants going away.
     */
    fun enqueueAddToZip(zip: app.filenfiles.core.FileItem, uris: List<Uri>, prefix: String) {
        val ctx = app.applicationContext
        val id = ids.getAndIncrement()
        val parent = app.router.parentOf(zip.id) ?: ItemId("none:")
        val t = Transfer(id, zip.name, Uri.EMPTY, parent, "application/zip", -1, state = State.PREPARING,
            kind = Kind.ADDZIP, source = zip.id, prefix = prefix)
        _items.update { l -> l.filter { it.active || it.state == State.FAILED } + t }
        startService()
        scope.launch {
            val dir = File(LocalCache.dir(ctx, LocalCache.UPLOAD), "$id").apply { mkdirs() }
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
                update(id) { if (it.state == State.PREPARING) it.copy(state = State.QUEUED, sources = staged) else it }
                schedule(id)
            } catch (e: CancellationException) { throw e
            } catch (e: Throwable) {
                dir.deleteRecursively()
                fail(id, e.friendlyOrNull() ?: e.message ?: "Couldn't read the files to add")
            }
        }
    }

    /** Queues an already-local file (written by another app through the documents provider). Takes ownership of [file]. */
    fun enqueueFile(parent: ItemId, file: File, name: String, mime: String?, docId: String? = null): Long {
        val t = Transfer(ids.getAndIncrement(), name, Uri.fromFile(file), parent, mime, file.length(), file = file, docId = docId)
        _items.update { l -> l.filter { it.active || it.state == State.FAILED } + t }
        startService()
        schedule(t.id)
        return t.id
    }

    /** Uploads that finished in the last couple of minutes (the Filen listing can lag behind them). */
    val recentUploads = RecentUploads()

    /** Whether anything is queued, running, or waiting for the network. */
    val hasActive: Boolean get() = _items.value.any { it.active }

    /** Documents-provider writes that are queued, running, waiting or failed (not yet dismissed), oldest first. */
    fun providerUploads(): List<Transfer> = _items.value.filter { it.kind == Kind.UPLOAD && it.docId != null && (it.active || it.state == State.FAILED) }

    fun byDocId(docId: String): Transfer? = _items.value.firstOrNull { it.docId == docId }

    /** True while the transfer is still pending (queued, running, waiting or failed-but-retryable). */
    fun isPending(id: Long) = find(id)?.let { it.state != State.DONE && it.state != State.CANCELLED } == true

    private fun discardSource(t: Transfer?) {
        t?.file?.parentFile?.deleteRecursively()
        if (t?.kind == Kind.ADDZIP) File(LocalCache.dir(app.applicationContext, LocalCache.UPLOAD), "${t.id}").deleteRecursively()
    }

    fun cancel(id: Long) {
        val t = find(id) ?: return
        if (!t.active) return
        update(id) { it.copy(state = State.CANCELLED, error = null) }
        synchronized(jobs) { jobs[id] }?.cancel()
        discardSource(t)
    }

    fun cancelAll() = _items.value.filter { it.active }.forEach { cancel(it.id) }

    fun retry(id: Long) {
        val t = find(id) ?: return
        if (t.active || t.state == State.DONE) return
        // A retried copy resumes (skips what already arrived); a retried upload starts over.
        update(id) { it.copy(state = State.QUEUED, done = 0, error = null, attempt = 1, startedAt = if (it.kind == Kind.COPY) it.startedAt else 0) }
        startService()
        schedule(id)
    }

    fun dismiss(id: Long) {
        find(id)?.takeIf { !it.active }?.let(::discardSource)
        _items.update { l -> l.filterNot { it.id == id && !it.active } }
    }
    fun clearFinished() = _items.update { l -> l.filter { it.active } }

    // ---------- persistence / lifecycle ----------

    /** Called once the session state is known and the user is signed in: starts everything that was left queued. */
    fun resume() {
        val pending = _items.value.filter { it.state == State.QUEUED }.map { it.id }
            .filter { synchronized(scheduled) { it !in scheduled } }
        if (pending.isNotEmpty()) startService()
        pending.forEach(::schedule)
        _settled.value = true
    }

    /** Signed out: pending work belongs to the account that left. Cancels it and forgets everything. */
    fun clearAll() {
        _items.value.forEach { if (it.active) cancel(it.id) else discardSource(it) }
        _items.value = emptyList()
        File(LocalCache.dir(app.applicationContext, LocalCache.UPLOAD).path).deleteRecursively()
        storeFile.delete()
        _settled.value = true
    }

    private fun savePersisted() {
        runCatching {
            val a = JSONArray()
            _items.value.filter { it.active || it.state == State.FAILED }.forEach { t ->
                a.put(JSONObject().put("id", t.id).put("name", t.name).put("uri", t.uri.toString()).put("parent", t.parent.value)
                    .put("mime", t.mime ?: JSONObject.NULL).put("size", t.size).put("state", if (t.active) "QUEUED" else "FAILED")
                    .put("error", t.error ?: JSONObject.NULL).put("file", t.file?.absolutePath ?: JSONObject.NULL)
                    .put("startedAt", t.startedAt).put("staged", t.staged)
                    .put("kind", t.kind.name).put("source", t.source?.value ?: JSONObject.NULL).put("move", t.move)
                    .put("sources", JSONArray(t.sources.map { it.value })).put("docId", t.docId ?: JSONObject.NULL).put("prefix", t.prefix))
            }
            if (a.length() == 0) { storeFile.delete(); return }
            val tmp = File(storeFile.parentFile, storeFile.name + ".part")
            tmp.writeText(JSONObject().put("v", 1).put("items", a).toString())
            if (!tmp.renameTo(storeFile)) tmp.delete()
        }
    }

    /** Anything that was pending when the process died comes back as QUEUED (a failed one stays failed, retryable). */
    private fun loadPersisted() {
        if (!storeFile.isFile) return
        val list = runCatching {
            val a = JSONObject(storeFile.readText()).getJSONArray("items")
            (0 until a.length()).mapNotNull { i ->
                val o = a.getJSONObject(i)
                val file = o.optString("file").takeIf { !o.isNull("file") && it.isNotEmpty() }?.let(::File)
                if (file != null && !file.isFile) return@mapNotNull null // staged source vanished: nothing to resume
                val failed = o.getString("state") == "FAILED"
                Transfer(
                    o.getLong("id"), o.getString("name"), Uri.parse(o.getString("uri")), ItemId(o.getString("parent")),
                    o.optString("mime").takeIf { !o.isNull("mime") }, o.getLong("size"),
                    state = if (failed) State.FAILED else State.QUEUED, error = o.optString("error").takeIf { failed && !o.isNull("error") },
                    file = file, startedAt = o.optLong("startedAt"), staged = o.optBoolean("staged"),
                    kind = runCatching { Kind.valueOf(o.getString("kind")) }.getOrDefault(Kind.UPLOAD),
                    source = o.optString("source").takeIf { !o.isNull("source") && it.isNotEmpty() }?.let(::ItemId),
                    move = o.optBoolean("move"),
                    sources = o.optJSONArray("sources")?.let { a -> (0 until a.length()).map { ItemId(a.getString(it)) } } ?: emptyList(),
                    docId = o.optString("docId").takeIf { !o.isNull("docId") && it.isNotEmpty() },
                    prefix = o.optString("prefix", ""),
                )
            }
        }.getOrNull() ?: return
        _items.value = list
        ids.set((list.maxOfOrNull { it.id } ?: 0L) + 1)
    }

    /** Staging dirs of transfers that no longer exist are leftovers (the old behaviour wiped all of them at startup). */
    private fun sweepOrphanStaging() {
        val keep = _items.value.flatMap { listOfNotNull(it.id.toString(), it.file?.parentFile?.name) }.toSet()
        LocalCache.dir(app.applicationContext, LocalCache.UPLOAD).listFiles()?.filter { it.name !in keep }?.forEach { it.deleteRecursively() }
        // Archive scratch space is rebuilt from scratch by a resumed job, so whatever is there is leftover.
        listOf("archive", "extract").forEach { LocalCache.dir(app.applicationContext, it).deleteRecursively() }
    }

    // ---------- worker ----------

    private suspend fun process(id: Long) {
        val ctx = app.applicationContext
        val t0 = find(id) ?: return
        if (t0.kind == Kind.COPY) { processCopy(t0); return }
        if (t0.kind == Kind.ZIP || t0.kind == Kind.UNZIP || t0.kind == Kind.ADDZIP) { processArchive(t0); return }
        val storage = app.router.writable(t0.parent)
        if (storage == null) { fail(id, "This folder doesn't accept uploads."); return }
        val staging = File(LocalCache.dir(ctx, LocalCache.UPLOAD), "$id").apply { mkdirs() }
        val staged = t0.file ?: File(staging, t0.name.replace('/', '_').ifBlank { "upload" })
        try {
            update(id) { it.copy(state = State.PREPARING, done = 0) }
            if (t0.file == null && !(t0.staged && staged.isFile)) {
                // Stage a private copy: the SDK uploads from a path, and the picked Uri may stop being readable.
                if (t0.size >= 0) LocalCache.requireSpace(ctx, t0.size)
                val input = try { ctx.contentResolver.openInputStream(t0.uri)!! } catch (e: Exception) {
                    if (e is SecurityException || e is java.io.FileNotFoundException || e is NullPointerException)
                        throw java.io.IOException("The original file is no longer available. Pick it again to upload it.")
                    throw e
                }
                input.use { i -> staged.outputStream().use { o -> i.copyTo(o) } }
                update(id) { it.copy(staged = true) }
            }
            if (t0.file != null && !t0.file.isFile) throw java.io.IOException("The file to upload is gone.")
            val size = staged.length()
            update(id) { it.copy(size = size) }
            // Resumed after a restart: the interrupted attempt may have reached the server already.
            if (t0.startedAt > 0) findUploaded(t0, size, t0.startedAt)?.let { finishOk(id, it); return }

            val maxAttempts = 4
            var attempt = 1
            while (true) {
                update(id) { it.copy(state = State.UPLOADING, done = 0, attempt = attempt, error = null) }
                val started = System.currentTimeMillis()
                update(id) { it.copy(startedAt = started) }
                try {
                    val uploaded = storage.upload(t0.parent, staged.absolutePath, t0.name, t0.mime) { sent -> update(id) { it.copy(done = sent) } }
                    finishOk(id, uploaded); return
                } catch (e: CancellationException) { throw e
                } catch (e: Throwable) {
                    if (e.failureKind() != FailureKind.NETWORK || attempt >= maxAttempts) { fail(id, e.friendlyOrNull() ?: e.message ?: "Upload failed"); return }
                    update(id) { it.copy(state = State.WAITING_NETWORK, error = "Waiting for network…") }
                    if (!awaitNetwork(ctx, 10 * 60_000L)) { fail(id, "No network connection. Retry when you're back online."); return }
                    // The failed attempt may have finished server-side before the connection dropped.
                    findUploaded(t0, size, started)?.let { finishOk(id, it); return }
                    attempt++
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            fail(id, e.friendlyOrNull() ?: e.message ?: "Upload failed")
        } finally {
            withContext(kotlinx.coroutines.NonCancellable) {
                staging.deleteRecursively()
                update(id) { it.copy(staged = false) }
                if (find(id)?.state == State.DONE) discardSource(t0) // failed file transfers keep their source for Retry
            }
        }
    }

    private suspend fun processCopy(t0: Transfer) {
        val id = t0.id
        try {
            update(id) { it.copy(state = State.PREPARING, done = 0, startedAt = maxOf(it.startedAt, System.currentTimeMillis())) }
            CopyRunner(
                app, resumed = t0.startedAt > 0,
                // `it.active` guards: a late progress tick must not resurrect a transfer that was just cancelled.
                setTotal = { total -> update(id) { if (it.active) it.copy(size = total, state = State.UPLOADING) else it } },
                setDone = { done -> update(id) { if (it.active) it.copy(done = done) else it } },
                setWaiting = { w -> update(id) { if (!it.active) it else if (w) it.copy(state = State.WAITING_NETWORK, error = "Waiting for network…") else it.copy(state = State.UPLOADING, error = null) } },
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
            update(id) { it.copy(state = State.PREPARING, done = 0, startedAt = maxOf(it.startedAt, System.currentTimeMillis())) }
            val active: (Transfer) -> Boolean = { it.active }
            val runner = ArchiveRunner(
                app, work,
                setTotal = { total -> update(id) { if (active(it)) it.copy(size = total, state = State.UPLOADING, done = 0) else it } },
                setDone = { done -> update(id) { if (active(it)) it.copy(done = done) else it } },
                deliver = { local, dest ->
                    update(id) { if (active(it)) it.copy(done = 0, state = State.PREPARING) else it }
                    CopyRunner(
                        app, resumed = false,
                        setTotal = { total -> update(id) { if (active(it)) it.copy(size = total, state = State.UPLOADING, done = 0) else it } },
                        setDone = { done -> update(id) { if (active(it)) it.copy(done = done) else it } },
                        setWaiting = { w -> update(id) { if (!it.active) it else if (w) it.copy(state = State.WAITING_NETWORK, error = "Waiting for network…") else it.copy(state = State.UPLOADING, error = null) } },
                        awaitNetwork = { awaitNetwork(app.applicationContext, 10 * 60_000L) },
                    ).run(local, dest, move = false)
                },
            )
            when (t0.kind) {
                Kind.ZIP -> runner.zip(t0.sources, t0.parent, t0.name)
                Kind.ADDZIP -> {
                    val files = t0.sources.map { File(it.key) }
                    if (files.any { !it.isFile }) throw java.io.IOException("The files to add are gone. Pick them again.")
                    runner.addToZip(t0.source ?: error("No archive"), files, t0.prefix)
                }
                else -> runner.unzip(t0.source ?: error("No archive"), t0.parent)
            }
            finishOk(id)
            if (t0.kind == Kind.ADDZIP) discardSource(t0)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            fail(id, e.friendlyOrNull() ?: e.message ?: "Couldn't ${when (t0.kind) { Kind.ZIP -> "compress"; Kind.ADDZIP -> "update the zip"; else -> "extract" }}")
        } finally {
            withContext(kotlinx.coroutines.NonCancellable) { work.deleteRecursively() }
        }
    }

    private fun finishOk(id: Long, uploaded: app.filenfiles.core.FileItem? = null) {
        // Receipt first: a provider listing between "no longer pending" and "visible in Filen" must still show the file.
        if (uploaded != null) find(id)?.let { recentUploads.add(it.parent, uploaded) }
        update(id) { it.copy(state = State.DONE, done = it.size, error = null) }
        app.notifyChanged()
        find(id)?.let { app.docsChanged(it.parent) }
    }

    private fun fail(id: Long, msg: String) = update(id) { it.copy(state = State.FAILED, error = msg) }

    private suspend fun findUploaded(t: Transfer, size: Long, since: Long): app.filenfiles.core.FileItem? = runCatching {
        app.router.list(t.parent).items.firstOrNull { !it.isDirectory && it.name == t.name && it.size == size && it.modified >= since - 60_000 }
    }.getOrNull()

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
