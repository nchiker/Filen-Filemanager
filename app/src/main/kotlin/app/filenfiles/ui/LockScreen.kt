package app.filenfiles.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.fragment.app.FragmentActivity
import app.filenfiles.FilesApp
import app.filenfiles.docs.FilenDocumentsProvider

/** Opaque cover shown while the app lock is on; asks for authentication as soon as it appears. */
@Composable
fun LockScreen(app: FilesApp, activity: FragmentActivity) {
    var prompting by remember { mutableStateOf(false) }
    fun ask() {
        if (prompting) return
        prompting = true
        authenticate(activity, "Unlock Filen Files") { ok ->
            prompting = false
            if (ok) {
                app.appLock.unlock()
                // The picker may have been told "locked" meanwhile: let it re-read the roots.
                runCatching {
                    activity.contentResolver.notifyChange(
                        android.provider.DocumentsContract.buildRootsUri(FilenDocumentsProvider.AUTHORITY), null)
                }
            }
        }
    }
    LaunchedEffect(Unit) { ask() }
    // Swallows every touch so nothing underneath can be used.
    Column(
        Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).clickable(enabled = true, onClick = {}),
        Arrangement.Center, Alignment.CenterHorizontally,
    ) {
        Icon(Icons.Filled.Lock, null, Modifier.size(56.dp), tint = MaterialTheme.colorScheme.primary)
        Spacer(Modifier.height(12.dp))
        Text("Filen Files is locked", style = MaterialTheme.typography.titleLarge)
        Spacer(Modifier.height(16.dp))
        Button({ ask() }) { Text("Unlock") }
    }
}
