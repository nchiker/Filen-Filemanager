package app.filenfiles.backup

import android.content.Context
import android.os.Environment
import android.webkit.MimeTypeMap
import androidx.work.*
import app.filenfiles.FilesApp
import app.filenfiles.core.BackupRules
import app.filenfiles.core.ItemId
import app.filenfiles.core.Names
import app.filenfiles.filen.FailureKind
import app.filenfiles.filen.FilenSession
import app.filenfiles.filen.failureKind
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * Backs up new camera photos (and optionally videos/screenshots) to a Filen folder, in the background, on a schedule
 * WorkManager keeps across restarts. Files go into per-month folders ("2026-11"). By default only files taken after
 * the feature was switched on are uploaded; nothing is ever deleted from the phone.
 *
 * Already-uploaded files are remembered by path+size+timestamp in a small state file bound to the destination folder,
 * and the destination listing is checked before every upload, so interrupted runs never create duplicates.
 */
object CameraBackup {
    private const val PERIODIC = "camera-backup"
    private const val NOW = "camera-backup-now"
    private const val MAX_FAILS = 3

    /** Persisted bookkeeping: which files are done, how often one failed, and which destination this belongs to. */
    class State(val folder: String, val done: MutableSet<String>, val fails: MutableMap<String, Int>)

    private fun stateFile(ctx: Context) = File(ctx.filesDir, "camera_backup.json")

    fun loadState(ctx: Context, folder: String): State {
        val f = stateFile(ctx)
        val empty = State(folder, HashSet(), HashMap())
        if (!f.isFile) return empty
        return runCatching {
            val o = JSONObject(f.readText())
            if (o.getString("folder") != folder) return empty // destination changed: start over
            val done = o.getJSONArray("done").let { a -> (0 until a.length()).mapTo(HashSet()) { a.getString(it) } }
            val fails = o.getJSONObject("fails").let { j -> j.keys().asSequence().associateWithTo(HashMap()) { j.getInt(it) } }
            State(folder, done, fails)
        }.getOrDefault(empty)
    }

    fun saveState(ctx: Context, s: State) {
        runCatching {
            val tmp = File(ctx.filesDir, "camera_backup.json.part")
            tmp.writeText(JSONObject().put("folder", s.folder).put("done", JSONArray(s.done.toList())).put("fails", JSONObject(s.fails as Map<*, *>)).toString())
            if (!tmp.renameTo(stateFile(ctx))) tmp.delete()
        }
    }

    fun forget(ctx: Context) { stateFile(ctx).delete() }

    // ---------- scheduling ----------

    private fun constraints(app: FilesApp) = Constraints.Builder()
        .setRequiredNetworkType(if (app.prefs.backupWifiOnly) NetworkType.UNMETERED else NetworkType.CONNECTED)
        .setRequiresCharging(app.prefs.backupCharging)
        .setRequiresBatteryNotLow(true)
        .build()

    /** Makes the schedule match the current settings (call on app start and after any backup setting changes). */
    fun apply(app: FilesApp) {
        val wm = WorkManager.getInstance(app)
        if (!app.prefs.backupEnabled) { wm.cancelUniqueWork(PERIODIC); wm.cancelUniqueWork(NOW); return }
        val req = PeriodicWorkRequestBuilder<BackupWorker>(30, TimeUnit.MINUTES).setConstraints(constraints(app))
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 5, TimeUnit.MINUTES).build()
        wm.enqueueUniquePeriodicWork(PERIODIC, ExistingPeriodicWorkPolicy.UPDATE, req)
    }

    /** "Back up now": one run as soon as the constraints are met. */
    fun runNow(app: FilesApp) {
        if (!app.prefs.backupEnabled) return
        val req = OneTimeWorkRequestBuilder<BackupWorker>().setConstraints(constraints(app)).build()
        WorkManager.getInstance(app).enqueueUniqueWork(NOW, ExistingWorkPolicy.REPLACE, req)
    }

    /** Signed out: the backup belongs to that account. Switch it off and forget what it knew. */
    fun disableAndForget(app: FilesApp) {
        app.prefs.updateBackupEnabled(false)
        forget(app)
        apply(app)
    }

    // ---------- the work ----------

    fun candidates(app: FilesApp, now: Long = System.currentTimeMillis()): List<File> {
        val p = app.prefs
        val roots = buildList {
            add(File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DCIM), "Camera"))
            if (p.backupScreenshots) add(File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES), "Screenshots"))
        }
        return roots.filter { it.isDirectory }.flatMap { r ->
            r.walkTopDown().maxDepth(3).filter { f ->
                f.isFile && f.length() > 0 && BackupRules.isBackupCandidate(f.name, p.backupVideos) &&
                    BackupRules.isSettled(f.lastModified(), now) && f.lastModified() >= p.backupSince
            }.toList()
        }.sortedBy { it.lastModified() }
    }

    /** What a run reports back to the worker. */
    enum class Outcome { DONE, RETRY }

    /**
     * One backup pass. [progress] is called with (done, total) after each file so the worker can show it.
     * Never throws except for cancellation (WorkManager stopping the job).
     */
    suspend fun run(app: FilesApp, progress: suspend (Int, Int) -> Unit): Outcome {
        val p = app.prefs
        if (!p.backupEnabled) return Outcome.DONE
        withTimeoutOrNull(30_000) { app.session.state.first { it !is FilenSession.State.Unknown } }
        if (app.session.state.value !is FilenSession.State.LoggedIn) return Outcome.DONE // signed out/expired: try again later
        val destStr = p.backupFolder ?: return Outcome.DONE
        val dest = ItemId(destStr)
        val storage = app.router.writable(dest)?.takeIf { it.canWrite(dest) }
        if (storage == null) { p.updateBackupStatus("The backup folder isn't available."); return Outcome.DONE }

        val state = loadState(app, destStr)
        val todo = candidates(app).filter { f ->
            val k = BackupRules.key(f.absolutePath, f.length(), f.lastModified())
            k !in state.done && (state.fails[k] ?: 0) < MAX_FAILS
        }
        if (todo.isEmpty()) { p.updateBackupStatus("Up to date"); return Outcome.DONE }

        try {
            val months = HashMap<String, ItemId>()
            val monthFiles = HashMap<ItemId, MutableList<Pair<String, Long>>>()
            val top = app.router.list(dest).items
            top.filter { it.isDirectory }.forEach { months[it.name] = it.id }
            var uploaded = 0; var failed = 0
            todo.forEachIndexed { i, f ->
                progress(i, todo.size)
                val key = BackupRules.key(f.absolutePath, f.length(), f.lastModified())
                val month = BackupRules.monthFolder(f.lastModified())
                val monthId = months.getOrPut(month) { storage.createFolder(dest, month) }
                val existing = monthFiles.getOrPut(monthId) { app.router.list(monthId).items.filter { !it.isDirectory }.map { it.name to it.size }.toMutableList() }
                if (existing.any { it.first == f.name && it.second == f.length() }) { state.done += key; return@forEachIndexed } // already there
                val name = Names.unique(existing.map { it.first }, f.name)
                val mime = MimeTypeMap.getSingleton().getMimeTypeFromExtension(f.extension.lowercase())
                try {
                    storage.upload(monthId, f.absolutePath, name, mime) {}
                    existing += name to f.length()
                    state.done += key; uploaded++
                    if (uploaded % 5 == 0) saveState(app, state)
                } catch (e: CancellationException) { throw e
                } catch (e: Throwable) {
                    if (e.failureKind() == FailureKind.NETWORK || e.failureKind() == FailureKind.AUTH) {
                        saveState(app, state); p.updateBackupStatus("Waiting for a connection"); return Outcome.RETRY
                    }
                    state.fails[key] = (state.fails[key] ?: 0) + 1; failed++
                }
            }
            saveState(app, state)
            app.docsChanged(dest)
            app.notifyChanged()
            p.updateBackupStatus(if (failed == 0) "Backed up $uploaded file${if (uploaded == 1) "" else "s"}" else "Backed up $uploaded, $failed couldn't be uploaded")
            return Outcome.DONE
        } catch (e: CancellationException) {
            saveState(app, state); throw e
        } catch (e: Throwable) {
            saveState(app, state)
            p.updateBackupStatus("Backup stopped: ${e.message ?: e.javaClass.simpleName}")
            return if (e.failureKind() == FailureKind.NETWORK) Outcome.RETRY else Outcome.DONE
        }
    }
}
