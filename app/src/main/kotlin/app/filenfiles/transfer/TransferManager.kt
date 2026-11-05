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
class TransferManager(private val app: FilesApp) {
    enum class State { QUEUED, PREPARING, UPLOADING, WAITING_NETWORK, DONE, FAILED, CANCELLED }

    data class Transfer(
        val id: Long, val name: String, val uri: Uri, val parent: ItemId, val mime: String?,
        val size: Long, val state: State = State.QUEUED, val done: Long = 0, val error: String? = null, val attempt: Int = 1,
    ) {
        val active get() = state == State.QUEUED || state == State.PREPARING || state == State.UPLOADING || state == State.WAITING_NETWORK
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val ids = AtomicLong(1)
    private val queue = Channel<Long>(Channel.UNLIMITED)
    private val jobs = HashMap<Long, Job>()
    private val _items = MutableStateFlow<List<Transfer>>(emptyList())
    val items: StateFlow<List<Transfer>> = _items

    init {
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
            Transfer(ids.getAndIncrement(), name, uri, parent, ctx.contentResolver.getType(uri), size)
        }
        _items.update { l -> l.filter { it.active || it.state == State.FAILED } + added } // new batch: forget completed/cancelled ones
        startService()
        added.forEach { queue.trySend(it.id) }
    }

    fun cancel(id: Long) {
        val t = find(id) ?: return
        if (!t.active) return
        update(id) { it.copy(state = State.CANCELLED, error = null) }
        synchronized(jobs) { jobs[id] }?.cancel()
    }

    fun cancelAll() = _items.value.filter { it.active }.forEach { cancel(it.id) }

    fun retry(id: Long) {
        val t = find(id) ?: return
        if (t.active || t.state == State.DONE) return
        update(id) { it.copy(state = State.QUEUED, done = 0, error = null, attempt = 1) }
        startService()
        queue.trySend(id)
    }

    fun dismiss(id: Long) = _items.update { l -> l.filterNot { it.id == id && !it.active } }
    fun clearFinished() = _items.update { l -> l.filter { it.active } }

    // ---------- worker ----------

    private suspend fun process(id: Long) {
        val ctx = app.applicationContext
        val t0 = find(id) ?: return
        val storage = app.router.writable(t0.parent)
        if (storage == null) { fail(id, "This folder doesn't accept uploads."); return }
        val staging = File(LocalCache.dir(ctx, LocalCache.UPLOAD), "$id").apply { mkdirs() }
        val staged = File(staging, t0.name.replace('/', '_').ifBlank { "upload" })
        try {
            update(id) { it.copy(state = State.PREPARING, done = 0) }
            // Stage a private copy: the SDK uploads from a path, and the picked Uri may stop being readable.
            if (t0.size >= 0) LocalCache.requireSpace(ctx, t0.size)
            ctx.contentResolver.openInputStream(t0.uri)!!.use { i -> staged.outputStream().use { o -> i.copyTo(o) } }
            val size = staged.length()
            update(id) { it.copy(size = size) }

            val maxAttempts = 4
            var attempt = 1
            while (true) {
                update(id) { it.copy(state = State.UPLOADING, done = 0, attempt = attempt, error = null) }
                val started = System.currentTimeMillis()
                try {
                    storage.upload(t0.parent, staged.absolutePath, t0.name, t0.mime) { sent -> update(id) { it.copy(done = sent) } }
                    finishOk(id); return
                } catch (e: CancellationException) { throw e
                } catch (e: Throwable) {
                    if (e.failureKind() != FailureKind.NETWORK || attempt >= maxAttempts) { fail(id, e.friendlyOrNull() ?: e.message ?: "Upload failed"); return }
                    update(id) { it.copy(state = State.WAITING_NETWORK, error = "Waiting for network…") }
                    if (!awaitNetwork(ctx, 10 * 60_000L)) { fail(id, "No network connection. Retry when you're back online."); return }
                    // The failed attempt may have finished server-side before the connection dropped.
                    if (alreadyUploaded(t0, size, started)) { finishOk(id); return }
                    attempt++
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            fail(id, e.friendlyOrNull() ?: e.message ?: "Upload failed")
        } finally {
            withContext(kotlinx.coroutines.NonCancellable) { staging.deleteRecursively() }
        }
    }

    private fun finishOk(id: Long) {
        update(id) { it.copy(state = State.DONE, done = it.size, error = null) }
        app.notifyChanged()
    }

    private fun fail(id: Long, msg: String) = update(id) { it.copy(state = State.FAILED, error = msg) }

    private suspend fun alreadyUploaded(t: Transfer, size: Long, since: Long): Boolean = runCatching {
        app.router.list(t.parent).items.any { !it.isDirectory && it.name == t.name && it.size == size && it.modified >= since - 60_000 }
    }.getOrDefault(false)

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

    private fun startService() {
        val ctx = app.applicationContext
        runCatching { ContextCompat.startForegroundService(ctx, Intent(ctx, TransferService::class.java)) }
    }
}
