package app.filenfiles

import android.os.Bundle
import androidx.fragment.app.FragmentActivity
import androidx.activity.compose.setContent
import androidx.activity.SystemBarStyle
import androidx.activity.enableEdgeToEdge
import app.filenfiles.stacklink.SharedWatcher
import app.filenfiles.ui.AppRoot
import app.filenfiles.ui.FilesTheme
import app.filenfiles.ui.LockScreen
import app.filenfiles.ui.LockedReaderDialog

class MainActivity : FragmentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val app = application as FilesApp
        handleOpenShared(intent)
        app.finishAfterSignIn = intent.getBooleanExtra(EXTRA_FINISH_AFTER_SIGN_IN, false)
        setContent {
            val dark = when (app.prefs.themeMode) {
                ThemeMode.SYSTEM -> androidx.compose.foundation.isSystemInDarkTheme()
                ThemeMode.LIGHT -> false
                ThemeMode.DARK -> true
            }
            // Status/navigation bar icons must match the chosen theme, not the system's.
            androidx.compose.runtime.SideEffect {
                val bar = if (dark) SystemBarStyle.dark(android.graphics.Color.TRANSPARENT)
                    else SystemBarStyle.light(android.graphics.Color.TRANSPARENT, android.graphics.Color.TRANSPARENT)
                enableEdgeToEdge(statusBarStyle = bar, navigationBarStyle = bar)
            }
            FilesTheme(dark) {
                // Keep the app composed under the lock so unlocking returns to the same place; the lock covers it fully.
                androidx.compose.foundation.layout.Box {
                    AppRoot(app)
                    if (app.appLock.isLocked) LockScreen(app, this@MainActivity)
                    else app.appLock.askReader?.let { LockedReaderDialog(app, it) }
                }
                androidx.compose.runtime.LaunchedEffect(app.prefs.secureScreen) {
                    // Blocks screenshots and blanks the preview in the recent-apps list.
                    if (app.prefs.secureScreen) window.addFlags(android.view.WindowManager.LayoutParams.FLAG_SECURE)
                    else window.clearFlags(android.view.WindowManager.LayoutParams.FLAG_SECURE)
                }
            }
        }
    }

    override fun onNewIntent(intent: android.content.Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleOpenShared(intent)
    }

    /** `app.filenfiles.action.OPEN_SHARED` with `app.filenfiles.extra.UUID` (required; PATH is only a hint and unused). Navigation only. */
    private fun handleOpenShared(intent: android.content.Intent?) {
        if (intent?.action != ACTION_OPEN_SHARED) return
        val uuid = intent.getStringExtra(EXTRA_UUID)?.trim()?.lowercase()
        if (uuid != null && UUID.matches(uuid)) (application as FilesApp).pendingOpenShared = uuid
        intent.action = null // a rotation or recreate must not reopen it
    }

    override fun onStart() {
        super.onStart()
        val app = application as FilesApp
        app.appLock.onStart()
        app.warmUpSearchIndex()
        app.checkForUpdate()
        SharedWatcher.requestCheck(app, 2_000)
    }

    override fun onStop() {
        super.onStop()
        (application as FilesApp).appLock.onStop()
    }

    companion object {
        const val ACTION_OPEN_SHARED = "app.filenfiles.action.OPEN_SHARED"
        const val EXTRA_UUID = "app.filenfiles.extra.UUID"
        /** Set by GrantFolderActivity: close this screen as soon as the person has signed in, so they land back in the grant dialog. */
        const val EXTRA_FINISH_AFTER_SIGN_IN = "app.filenfiles.extra.FINISH_AFTER_SIGN_IN"
        private val UUID = Regex("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}")
    }
}
