package app.filenfiles

import android.app.Application
import app.filenfiles.core.StorageRouter
import app.filenfiles.filen.FilenSession
import app.filenfiles.filen.FilenStorageProvider
import app.filenfiles.filen.LocalCache
import app.filenfiles.local.LocalStorageProvider
import app.filenfiles.transfer.TransferManager
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlin.concurrent.thread
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/** Process-wide wiring. Storage back ends are independent modules; the UI only sees [router]. */
class FilesApp : Application() {
    lateinit var session: FilenSession; private set
    lateinit var local: LocalStorageProvider; private set
    lateinit var router: StorageRouter; private set
    lateinit var filen: FilenStorageProvider; private set
    /** Set at the end of [onCreate]; content-provider calls can arrive before that on a cold process start. */
    @Volatile var ready = false; private set
    private val warmedUp = java.util.concurrent.atomic.AtomicBoolean(false)

    /** Blocks the caller (never the main thread) until the app is wired up. */
    fun awaitReady(timeoutMs: Long = 10_000) {
        val end = System.currentTimeMillis() + timeoutMs
        while (!ready && System.currentTimeMillis() < end) Thread.sleep(20)
        check(ready) { "App is still starting" }
    }

    /**
     * Builds the Filen search index in the background. Only the UI asks for this (a few seconds after it opens):
     * the crawl saturates the CPU for a minute, which would starve picker/provider requests served by a process
     * that was started just to answer them. Search starts the index on demand anyway.
     */
    fun warmUpSearchIndex() {
        if (!warmedUp.compareAndSet(false, true)) return
        scope.launch {
            kotlinx.coroutines.delay(3_000)
            session.state.first { it !is FilenSession.State.Unknown }
            if (session.isSignedIn) runCatching { filen.warmUp() }
        }
    }
    lateinit var transfers: TransferManager; private set

    /**
     * Last listing per folder, in memory only: re-entering a folder shows it immediately while the fresh one loads
     * (cold Filen listings take seconds). Bounded LRU; dropped on sign-out/expiry.
     */
    val listingCache: MutableMap<String, app.filenfiles.core.Listing> = java.util.Collections.synchronizedMap(
        object : LinkedHashMap<String, app.filenfiles.core.Listing>(16, 0.75f, true) {
            override fun removeEldestEntry(e: MutableMap.MutableEntry<String, app.filenfiles.core.Listing>) = size > 48
        })

    // ---- Filen favorites live on the server; this is the app's view of them (ids of starred personal items) ----
    var serverFavIds by mutableStateOf<Set<String>>(emptySet()); private set
    /** Toggles made in the last 30 s win over a (possibly lagging) server listing. */
    private val recentFavToggles = java.util.concurrent.ConcurrentHashMap<String, Pair<Boolean, Long>>()

    /** Feed in a fresh server listing of favorites; recent local toggles are layered on top. */
    fun applyServerFavorites(ids: Set<String>) {
        val now = System.currentTimeMillis()
        recentFavToggles.entries.removeIf { now - it.value.second > 30_000 }
        serverFavIds = (ids.filter { recentFavToggles[it]?.first != false } + recentFavToggles.filter { it.value.first }.keys).toSet()
    }

    fun isStarred(id: app.filenfiles.core.ItemId) = id.value in serverFavIds || prefs.isFavorite(id)

    /** Stars/unstars [item] on the server when it supports that, else locally. Throws on server failure (state is rolled back). */
    suspend fun setStarred(item: app.filenfiles.core.FileItem, star: Boolean) {
        val server = router.favoriteStorage(item)
        if (server == null) { prefs.setFavorite(item.id, star); notifyChanged(); return }
        val id = item.id.value
        val before = serverFavIds
        recentFavToggles[id] = star to System.currentTimeMillis()
        serverFavIds = if (star) before + id else before - id
        try {
            server.setFavorite(item, star)
            prefs.setFavorite(item.id, false) // an older local star on the same item is now redundant
            notifyChanged()
        } catch (e: Throwable) {
            recentFavToggles.remove(id); serverFavIds = before
            throw e
        }
    }
    /** Bumped after any rename/move/delete so visible lists reload. */
    var changeCounter by mutableIntStateOf(0); private set
    fun notifyChanged() { changeCounter++ }

    /** Tells document-picker clients that a folder's contents changed. */
    fun docsChanged(folder: app.filenfiles.core.ItemId) {
        runCatching {
            contentResolver.notifyChange(
                android.provider.DocumentsContract.buildChildDocumentsUri(app.filenfiles.docs.FilenDocumentsProvider.AUTHORITY, folder.value), null)
        }
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    /** For user actions that must finish even if the dialog/screen that started them goes away (e.g. sharing a folder). */
    val appScope: CoroutineScope get() = scope
    lateinit var prefs: Prefs; private set
    lateinit var appLock: AppLock; private set

    val installedVersion: String get() = runCatching { packageManager.getPackageInfo(packageName, 0).versionName }.getOrNull() ?: "0"

    /** Asks GitHub for the newest release (at most daily unless [force]); remembers whether it is newer than this build. */
    fun checkForUpdate(force: Boolean = false, onDone: (UpdateChecker.Release?) -> Unit = {}) {
        if (!force && (!prefs.autoUpdateCheck || System.currentTimeMillis() - prefs.lastUpdateCheck < 24 * 3_600_000L)) return
        scope.launch {
            val r = UpdateChecker.latest()
            if (r != null) {
                prefs.lastUpdateCheck = System.currentTimeMillis()
                if (UpdateChecker.isNewer(r, installedVersion)) prefs.setAvailableUpdate(r.version, r.apkUrl ?: r.pageUrl)
                else prefs.setAvailableUpdate(null, null)
            }
            kotlinx.coroutines.withContext(Dispatchers.Main) { onDone(r) }
        }
    }

    override fun onCreate() {
        super.onCreate()
        session = FilenSession(this)
        local = LocalStorageProvider(this)
        filen = FilenStorageProvider(this, session)
        router = StorageRouter(listOf(filen, local))
        prefs = Prefs(this)
        appLock = AppLock(prefs)
        app.filenfiles.backup.CameraBackup.apply(this) // keep the schedule in line with the saved settings
        transfers = TransferManager(this)
        // Partial downloads / upload staging from a previous run are garbage; trim caches to their caps.
        scope.launch { runCatching { LocalCache.sweepAtStartup(this@FilesApp) } }
        // Keystore + native init off the main thread; UI observes session.state.
        thread(name = "filen-restore") { runCatching { session.restore() } }
        // The UI starts the search index (warmUpSearchIndex); the session observer below handles sign-in/out side effects.
        scope.launch {
            session.state.collect {
                // The picker's list of roots depends on whether we're signed in.
                runCatching {
                    contentResolver.notifyChange(
                        android.provider.DocumentsContract.buildRootsUri(app.filenfiles.docs.FilenDocumentsProvider.AUTHORITY), null)
                }
                when (it) {
                    is FilenSession.State.LoggedIn -> transfers.resume()
                    is FilenSession.State.LoggedOut -> { transfers.clearAll(); app.filenfiles.backup.CameraBackup.disableAndForget(this@FilesApp) }
                    else -> Unit
                }
                if (it is FilenSession.State.LoggedIn) runCatching {
                    applyServerFavorites(filen.favorites().map { f -> f.id.value }.toSet())
                }
                when (it) {
                    // Decrypted copies of cloud files must not outlive the session.
                    is FilenSession.State.LoggedOut, is FilenSession.State.Expired -> runCatching {
                        filen.onSessionEnded()
                        serverFavIds = emptySet(); recentFavToggles.clear(); listingCache.clear()
                        LocalCache.wipeDecrypted(this@FilesApp)
                    }
                    else -> Unit
                }
            }
        }
        ready = true
    }
}
