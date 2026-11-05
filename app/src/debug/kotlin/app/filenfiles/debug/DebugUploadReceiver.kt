package app.filenfiles.debug

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import app.filenfiles.FilesApp
import app.filenfiles.core.ItemId
import app.filenfiles.core.WritableStorage
import app.filenfiles.filen.FilenSession
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.io.File

/**
 * Debug-only seeding hook for device tests:
 *   adb shell am broadcast -n app.filenfiles/.debug.DebugUploadReceiver \
 *       --es dir /sdcard/Download/seed --es folder "Some folder name"
 * Creates `folder` in the Filen drive root and uploads every file in `dir` (plus one level of subfolders)
 * into it. Results are logged under the tag FilenDebug.
 */
class DebugUploadReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val dir = File(intent.getStringExtra("dir") ?: return)
        val folder = intent.getStringExtra("folder") ?: return
        val app = context.applicationContext as FilesApp
        val pending = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                // A broadcast can start a cold process: wait for the async session restore to settle.
                val st = kotlinx.coroutines.withTimeout(30_000) { app.session.state.first { it !is FilenSession.State.Unknown } }
                check(st is FilenSession.State.LoggedIn) { "Not signed in" }
                val w = app.router.writable(ItemId("filen:root")) as WritableStorage
                val top = w.createFolder(ItemId("filen:root"), folder)
                Log.i(TAG, "created folder")
                suspend fun walk(d: File, into: ItemId, depth: Int) {
                    for (f in d.listFiles().orEmpty().sortedBy { it.name }) {
                        if (f.isDirectory && depth < 1) walk(f, w.createFolder(into, f.name), depth + 1)
                        else if (f.isFile) {
                            val mime = android.webkit.MimeTypeMap.getSingleton()
                                .getMimeTypeFromExtension(f.extension.lowercase())
                            w.upload(into, f.absolutePath, f.name, mime)
                            Log.i(TAG, "uploaded ${f.name} (${f.length()} B)")
                        }
                    }
                }
                walk(dir, top, 0)
                Log.i(TAG, "DONE")
            } catch (e: Throwable) {
                Log.e(TAG, "FAILED: ${e.message ?: e}", e)
            } finally { pending.finish() }
        }
    }
    private companion object { const val TAG = "FilenDebug" }
}
