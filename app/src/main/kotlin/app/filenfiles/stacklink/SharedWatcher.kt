package app.filenfiles.stacklink

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.DocumentsContract
import androidx.work.*
import app.filenfiles.FilesApp
import app.filenfiles.core.ItemId
import app.filenfiles.docs.FilenDocumentsProvider
import app.filenfiles.filen.FilenSession
import app.filenfiles.filen.StackLink
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import uniffi.filen_sdk_rs.SocketEvent
import uniffi.filen_sdk_rs.SocketEventListener
import java.security.MessageDigest
import java.util.concurrent.TimeUnit

/**
 * Change notice for Stack partner folders (I4 F5). For every tree (drive, shared with me, shared by me) a pinned app holds a
 * persisted read grant on (authority app.filenfiles.documents, document id `filen:`, `filen-in:` or `filen-out:`) it compares a fingerprint of the tree
 * (names, sizes, times) with the last one and, when it changed, calls notifyChange on the tree's child-documents URI and
 * sends the explicit broadcast [ACTION] (extra [EXTRA_TREE_URI], a String) to Stack's package only. It only notifies:
 * nothing is shared, read or copied. The first sight of a tree just records a baseline (Stack imports at its own start).
 * Triggers: Filen's socket while the app runs (debounced), the app coming to the foreground, and a 6-hour worker.
 */
object SharedWatcher {
    const val ACTION = "app.filenfiles.action.SHARED_CHANGED"
    const val EXTRA_TREE_URI = "app.filenfiles.extra.TREE_URI"
    private const val MAX_ITEMS = 3000
    private const val MAX_DEPTH = 8

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var debounce: Job? = null
    private var handle: AutoCloseable? = null
    private val lock = Any()

    /** Pinned callers (Stack, or the test package of a checks build) that are installed: the only ones ever told. */
    private fun pinnedTargets(ctx: Context): List<Pair<String, Int>> =
        setOf(StackLink.STACK_PACKAGE, StackLink.testPackage).filter { it.isNotEmpty() }.mapNotNull { pkg ->
            runCatching { pkg to ctx.packageManager.getPackageUid(pkg, 0) }.getOrNull()
        }

    /** Persisted read grants on a drive, shared-with-me or shared-by-me tree that a pinned caller holds; the packages holding each. */
    private fun grantedTrees(ctx: Context): Map<Uri, List<String>> {
        val targets = pinnedTargets(ctx)
        if (targets.isEmpty()) return emptyMap()
        val out = LinkedHashMap<Uri, List<String>>()
        for (g in ctx.contentResolver.outgoingPersistedUriPermissions) {
            val u = g.uri
            if (!g.isReadPermission || u.authority != FilenDocumentsProvider.AUTHORITY || !DocumentsContract.isTreeUri(u) || u in out) continue
            val id = runCatching { DocumentsContract.getTreeDocumentId(u) }.getOrNull() ?: continue
            if (!(id.startsWith("filen:") || id.startsWith("filen-in:") || id.startsWith("filen-out:"))) continue
            val holders = targets.filter { (_, uid) ->
                ctx.checkUriPermission(u, -1, uid, Intent.FLAG_GRANT_READ_URI_PERMISSION) == android.content.pm.PackageManager.PERMISSION_GRANTED
            }.map { it.first }
            if (holders.isNotEmpty()) out[u] = holders
        }
        return out
    }

    private suspend fun fingerprint(app: FilesApp, root: ItemId): String? {
        val md = MessageDigest.getInstance("SHA-256")
        var count = 0
        suspend fun walk(id: ItemId, depth: Int): Boolean {
            val l = try { app.router.list(id) } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (_: Exception) { return false }
            for (i in l.items.sortedBy { it.id.value }) {
                if (++count > MAX_ITEMS) return false
                md.update("${i.id.value}|${i.name}|${i.size}|${i.modified}\n".toByteArray())
                if (i.isDirectory && depth < MAX_DEPTH && !walk(i.id, depth + 1)) return false
            }
            return true
        }
        return if (walk(root, 0)) md.digest().joinToString("") { "%02x".format(it) } else null
    }

    /** Checks every granted tree; returns how many changed. Safe to call any time (does nothing without grants or sign-in). */
    suspend fun check(app: FilesApp): Int {
        if (app.session.state.value !is FilenSession.State.LoggedIn) return 0
        val trees = grantedTrees(app)
        if (trees.isEmpty()) { android.util.Log.d("SharedWatcher", "SharedWatcher: 0 trees, 0 changed"); return 0 }
        val sp = app.getSharedPreferences("shared-watch", Context.MODE_PRIVATE)
        var changed = 0
        for ((tree, holders) in trees) {
            val doc = DocumentsContract.getTreeDocumentId(tree)
            val now = fingerprint(app, ItemId(doc)) ?: continue // unreadable right now: keep the old baseline
            val key = tree.toString()
            val before = sp.getString(key, null)
            if (before == now) continue
            sp.edit().putString(key, now).apply()
            if (before == null) continue // baseline only
            changed++
            notify(app, tree, doc, holders)
        }
        android.util.Log.d("SharedWatcher", "SharedWatcher: ${trees.size} trees, $changed changed") // counts only: no names, ids or addresses
        // Forget trees whose grant is gone.
        val gone = sp.all.keys - trees.keys.map { it.toString() }.toSet()
        if (gone.isNotEmpty()) sp.edit().apply { gone.forEach { remove(it) } }.apply()
        return changed
    }

    private fun notify(app: FilesApp, tree: Uri, doc: String, holders: List<String>) {
        runCatching { app.contentResolver.notifyChange(DocumentsContract.buildChildDocumentsUri(FilenDocumentsProvider.AUTHORITY, doc), null) }
        for (pkg in holders) runCatching {
            app.sendBroadcast(Intent(ACTION).setPackage(pkg).putExtra(EXTRA_TREE_URI, tree.toString()))
        }
    }

    // ---------- triggers ----------

    /** A check soon, but not more than once in a burst of events. */
    fun requestCheck(app: FilesApp, delayMs: Long = 5_000) {
        synchronized(lock) {
            debounce?.cancel()
            debounce = scope.launch { delay(delayMs); runCatching { check(app) } }
        }
    }

    /** While signed in and running: any drive or contact event on the socket asks for a (debounced) check. */
    fun startListening(app: FilesApp) {
        scope.launch {
            runCatching {
                if (grantedTrees(app).isEmpty()) return@launch // nothing to watch; re-evaluated at the next sign-in or start
                stopListening()
                val listener = object : SocketEventListener {
                    override fun onEvent(event: SocketEvent) {
                        if (event is SocketEvent.Drive || event is SocketEvent.Contact) requestCheck(app)
                    }
                }
                handle = app.session.client.addEventListener(listener, null)
            }
        }
    }

    fun stopListening() { runCatching { handle?.close() }; handle = null }

    fun schedule(ctx: Context) {
        val req = PeriodicWorkRequestBuilder<Worker>(6, TimeUnit.HOURS)
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()).build()
        WorkManager.getInstance(ctx).enqueueUniquePeriodicWork("shared-watch-periodic", ExistingPeriodicWorkPolicy.KEEP, req)
    }

    fun cancel(ctx: Context) {
        stopListening()
        WorkManager.getInstance(ctx).cancelUniqueWork("shared-watch-periodic")
        ctx.getSharedPreferences("shared-watch", Context.MODE_PRIVATE).edit().clear().apply()
    }

    class Worker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {
        override suspend fun doWork(): Result {
            val app = applicationContext as FilesApp
            withTimeoutOrNull(30_000) { app.session.state.first { it !is FilenSession.State.Unknown } }
            runCatching { check(app) }
            return Result.success()
        }
    }
}
