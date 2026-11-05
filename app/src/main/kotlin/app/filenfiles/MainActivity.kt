package app.filenfiles

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import app.filenfiles.ui.AppRoot
import app.filenfiles.ui.FilesTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val app = application as FilesApp
        setContent { FilesTheme { AppRoot(app) } }
    }

    override fun onStart() {
        super.onStart()
        (application as FilesApp).warmUpSearchIndex()
    }
}
