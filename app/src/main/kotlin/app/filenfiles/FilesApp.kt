package app.filenfiles

import android.app.Application
import app.filenfiles.core.StorageRouter
import app.filenfiles.filen.FilenSession
import app.filenfiles.filen.FilenStorageProvider
import app.filenfiles.local.LocalStorageProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import kotlin.concurrent.thread
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/** Process-wide wiring. Storage back ends are independent modules; the UI only sees [router]. */
class FilesApp : Application() {
    lateinit var session: FilenSession; private set
    lateinit var local: LocalStorageProvider; private set
    lateinit var router: StorageRouter; private set
    /** Bumped after any rename/move/delete so visible lists reload. */
    var changeCounter by mutableIntStateOf(0); private set
    fun notifyChanged() { changeCounter++ }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    lateinit var prefs: Prefs; private set

    override fun onCreate() {
        super.onCreate()
        session = FilenSession(this)
        local = LocalStorageProvider(this)
        val filen = FilenStorageProvider(this, session)
        router = StorageRouter(listOf(filen, local))
        prefs = Prefs(this)
        // Keystore + native init off the main thread; UI observes session.state.
        thread(name = "filen-restore") { runCatching { session.restore() } }
        // Build the Filen search index in the background as soon as a session exists.
        scope.launch {
            session.state.collect { if (it is FilenSession.State.LoggedIn) runCatching { filen.warmUp() } }
        }
    }
}
