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
    lateinit var prefs: Prefs; private set

    override fun onCreate() {
        super.onCreate()
        session = FilenSession(this)
        local = LocalStorageProvider(this)
        filen = FilenStorageProvider(this, session)
        router = StorageRouter(listOf(filen, local))
        prefs = Prefs(this)
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
                    // Decrypted copies of cloud files must not outlive the session.
                    is FilenSession.State.LoggedOut, is FilenSession.State.Expired -> runCatching { LocalCache.wipeDecrypted(this@FilesApp) }
                    else -> Unit
                }
            }
        }
        ready = true
    }
}
