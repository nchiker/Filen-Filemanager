package app.filenfiles

import android.os.Bundle
import androidx.fragment.app.FragmentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import app.filenfiles.ui.AppRoot
import app.filenfiles.ui.FilesTheme
import app.filenfiles.ui.LockScreen

class MainActivity : FragmentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val app = application as FilesApp
        setContent {
            FilesTheme {
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
