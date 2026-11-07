package app.filenfiles

import android.os.Bundle
import androidx.fragment.app.FragmentActivity
import androidx.activity.compose.setContent
import androidx.activity.SystemBarStyle
import androidx.activity.enableEdgeToEdge
import app.filenfiles.ui.AppRoot
import app.filenfiles.ui.FilesTheme
import app.filenfiles.ui.LockScreen

class MainActivity : FragmentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val app = application as FilesApp
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
                }
                androidx.compose.runtime.LaunchedEffect(app.prefs.secureScreen) {
                    // Blocks screenshots and blanks the preview in the recent-apps list.
                    if (app.prefs.secureScreen) window.addFlags(android.view.WindowManager.LayoutParams.FLAG_SECURE)
                    else window.clearFlags(android.view.WindowManager.LayoutParams.FLAG_SECURE)
                }
            }
        }
    }

    override fun onStart() {
        super.onStart()
        val app = application as FilesApp
        app.appLock.onStart()
        app.warmUpSearchIndex()
        app.checkForUpdate()
    }

    override fun onStop() {
        super.onStop()
        (application as FilesApp).appLock.onStop()
    }
}
