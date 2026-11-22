package app.filenfiles.filen

import android.content.Context
import app.filenfiles.core.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import uniffi.filen_sdk_rs.AnyDirWithContext
import uniffi.filen_sdk_rs.AnyFile
import uniffi.filen_sdk_rs.AnyNormalDir
import uniffi.filen_sdk_rs.FileMeta
import uniffi.filen_sdk_rs.JsFileDownloadCallback
import uniffi.filen_sdk_rs.ManagedFuture
import uniffi.filen_types.ParentUuid
import java.io.File as JFile
import uniffi.filen_sdk_rs.File as FFile

/**
 * Offline copies of personal-drive files ("Available offline"). Decisions live in [Pins] (pure, unit-tested);
 * this class does the I/O: the state file, the downloads, the local renames/deletes.
 *
 * Copies live in `filesDir/pinned/files/<key>/<name>` (allowBackup is off) - not in `cache/` - so the 1 GiB LRU cap, the 7-day
 * age limit and the OS cache clearing never touch them; they are decrypted at rest, so they are wiped on sign-out
 * and session expiry like every other decrypted copy. The real file name is the leaf so Open With shows it.
 * The state file records only the user's choices plus what has arrived; every sync recomputes the rest.
 */
class PinManager(private val context: Context, private val session: FilenSession) {
    private val lock = Mutex()
    private val base get() = JFile(context.filesDir, "pinned")
    private val storeFile get() = JFile(base, "pins.bin")
    private val filesRoot get() = JFile(base, "files")
    @Volatile private var state: PinState? = null
    private val _revision = MutableStateFlow(0)
    /** Bumps whenever pins or what is on the device changed, so badges and the Offline list refresh. */
    val revision: StateFlow<Int> = _revision
    private val _syncing = MutableStateFlow(false)
    val syncing: StateFlow<Boolean> = _syncing
    @Volatile var lastError: String? = null; private set
    @Volatile var orphaned: Set<String> = emptySet(); private set

    private val account get() = (session.state.value as? FilenSession.State.LoggedIn)?.email
    private val client get() = session.client

    private fun current(): PinState? {
        val acct = account ?: return null
        state?.takeIf { it.account == acct }?.let { return it }
        return (PinStore.load(storeFile, acct) ?: PinState(acct)).also { state = it }
    }

    private fun commit(s: PinState) {
        state = s
        runCatching { PinStore.save(storeFile, s) }
        _revision.value++
    }

    private suspend inline fun <T> io(crossinline block: suspend () -> T): T = withContext(Dispatchers.IO) {
        try { block() } catch (e: Throwable) { session.noteError(e); throw e }
    }

    // ---------- queries (memory only: they work offline) ----------

    private fun keyForUuid(s: PinState, uuid: String) = s.files.firstOrNull { it.version == uuid }?.key ?: uuid

    fun isPinned(item: FileItem): Boolean {
        val s = current() ?: return false
        return if (item.isDirectory) s.folders.any { it.uuid == item.id.key } else keyForUuid(s, item.id.key) in s.directKeys
    }

    /** Has a copy on the device (pinned directly or through a pinned folder). */
    fun isOffline(item: FileItem): Boolean {
        val s = current() ?: return false
        return !item.isDirectory && s.files.any { it.version == item.id.key }
    }

    fun offline(): List<PinnedFile> = current()?.files.orEmpty()
    fun folders(): List<PinnedFolder> = current()?.folders.orEmpty()
    fun directNames(): Map<String, String> = current()?.directKeys.orEmpty()

    fun localFile(f: PinnedFile) = JFile(JFile(filesRoot, safe(f.key)), safeName(f.name))

    /** The on-device copy of the current version of [uuid], if it is complete. */
    fun copyOf(uuid: String): Pair<PinnedFile, JFile>? {
        val f = current()?.files?.firstOrNull { it.version == uuid } ?: return null
        val file = localFile(f)
        return if (file.isFile && file.length() == f.size) f to file else null
    }

    private fun safe(s: String) = s.replace(Regex("[^A-Za-z0-9_-]"), "_")
    private fun safeName(n: String) = n.replace('/', '_').ifBlank { "file" }

    // ---------- changes ----------

    /** Records the choice; the files arrive when [sync] runs (a background job, so it survives the app being closed). */
    suspend fun pin(items: List<FileItem>) = io {
        lock.withLock {
            var s = current() ?: return@withLock
            for (it in items) {
                if (it.origin != Origin.FILEN_PERSONAL) continue
                if (it.isDirectory) {
                    if (s.folders.none { f -> f.uuid == it.id.key }) s = s.copy(folders = s.folders + PinnedFolder(it.id.key, it.name))
                } else {
                    val f = client.getFileOptional(it.id.key) ?: continue
                    s = s.copy(directKeys = s.directKeys + (keyOf(f) to it.name))
                }
            }
            commit(s)
        }
    }

    suspend fun unpin(items: List<FileItem>) = io {
        lock.withLock {
            var s = current() ?: return@withLock
            for (it in items) {
                s = if (it.isDirectory) s.copy(folders = s.folders.filter { f -> f.uuid != it.id.key })
                else s.copy(directKeys = s.directKeys - keyForUuid(s, it.id.key))
            }
            commit(s)
        }
    }

    private fun keyOf(f: FFile) = f.stableUuid ?: f.uuid

    private fun remote(f: FFile): RemoteFile {
        val m = (f.meta as? FileMeta.Decoded)?.v1
        val name = m?.name ?: "file-${f.uuid}"
        return RemoteFile(keyOf(f), f.uuid, name, f.size.toLong(), m?.modified ?: f.timestamp, m?.mime?.takeIf { it.isNotBlank() })
    }

    /** Total bytes a pin of [items] would download, from a recursive listing; for the confirmation. */
    suspend fun sizeOf(items: List<FileItem>): Long = io {
        var total = 0L
        for (it in items) {
            if (it.origin != Origin.FILEN_PERSONAL) continue
            if (it.isDirectory) {
                val dir = client.getDirOptional(it.id.key) ?: continue
                total += client.listDirRecursive(AnyDirWithContext.Normal(AnyNormalDir.Dir(dir)), null).files.sumOf { f -> f.size.toLong() }
            } else total += it.size
        }
        total
    }

    // ---------- sync ----------

    /**
     * Brings the device in line with the pins: new and changed files are downloaded, files that left a pinned folder or
     * were unpinned are deleted, renames are done locally. A listing that fails changes nothing. Returns the number of
     * files that could not be downloaded (they are tried again next time).
     */
    suspend fun sync(): Int = io {
        lock.withLock {
            val s0 = current() ?: return@withLock 0
            _syncing.value = true
            try { doSync(s0) } finally { _syncing.value = false }
        }
    }

    private suspend fun doSync(s0: PinState): Int {
        lastError = null
        val wanted = LinkedHashMap<String, RemoteFile>()
        val missing = HashSet<String>()
        for (key in s0.directKeys.keys) {
            val f = client.getFileByStableUuidOptional(key) ?: client.getFileOptional(key)
            if (f == null || f.parent is ParentUuid.Trash) missing.add(key) else wanted.putIfAbsent(keyOf(f), remote(f))
        }
        val liveFolders = ArrayList<PinnedFolder>()
        for (folder in s0.folders) {
            val dir = client.getDirOptional(folder.uuid)
            if (dir == null || dir.parent is ParentUuid.Trash) continue // gone: drops from the pins, its copies are deleted below
            liveFolders.add(folder)
            client.listDirRecursive(AnyDirWithContext.Normal(AnyNormalDir.Dir(dir)), null).files
                .filter { it.parent !is ParentUuid.Trash }.forEach { wanted.putIfAbsent(keyOf(it), remote(it)) }
        }
        var s = s0.copy(folders = liveFolders)
        val plan = Pins.reconcile(s, wanted.values, missing)
        orphaned = plan.orphaned

        for (f in plan.delete) { JFile(filesRoot, safe(f.key)).deleteRecursively() }
        s = s.copy(files = s.files.filter { f -> plan.delete.none { it.key == f.key } })
        for ((f, newName) in plan.rename) {
            val from = localFile(f); val to = JFile(from.parentFile, safeName(newName))
            if (from.isFile && from.renameTo(to)) s = s.copy(files = s.files.map { if (it.key == f.key) it.copy(name = newName) else it })
        }
        commit(s)
        sweepStrays(s)

        var failed = 0
        for (r in plan.download) {
            try {
                download(r)
                val done = PinnedFile(r.key, r.version, r.name, r.size, r.modified, r.mime)
                s = s.copy(files = s.files.filter { it.key != r.key } + done)
                commit(s)
            } catch (e: kotlinx.coroutines.CancellationException) { throw e
            } catch (e: Exception) {
                failed++; lastError = e.friendlyOrNull() ?: e.message
                if (e.failureKind() == FailureKind.AUTH || e.failureKind() == FailureKind.DISK_FULL) break
            }
        }
        // Folder pins that no longer have a folder, and files only reachable through them, are gone from the state already.
        return failed
    }

    private suspend fun download(r: RemoteFile) {
        val dir = JFile(filesRoot, safe(r.key)).apply { mkdirs() }
        LocalCache.requireSpace(context, r.size)
        val file = client.getFileOptional(r.version) ?: error("File is no longer available")
        val target = JFile(dir, safeName(r.name)); val tmp = JFile(dir, safeName(r.name) + ".part")
        try {
            retryNetwork { attempt ->
                tmp.delete()
                val cb = object : JsFileDownloadCallback { override fun onUpdate(downloadedBytes: ULong) {} }
                client.downloadFileToPath(AnyFile.File(file), tmp.absolutePath, cb, ManagedFuture(null, null))
            }
            check(tmp.length() == r.size) { "Download was incomplete (${tmp.length()} of ${r.size} bytes)." }
            // Replace the stale copy (maybe under another name) only now that the new one is complete.
            dir.listFiles()?.filter { it != tmp }?.forEach { it.delete() }
            check(tmp.renameTo(target)) { "Could not finalise download" }
        } finally {
            tmp.delete(); dir.listFiles()?.filter(LocalCache::isTemp)?.forEach { it.delete() }
        }
    }

    /** Deletes copies nothing in the state refers to (left by a crash, another account, or a lost state file). */
    private fun sweepStrays(s: PinState) {
        val keep = s.files.map { safe(it.key) }.toSet()
        filesRoot.listFiles()?.forEach { d ->
            if (d.name !in keep && !d.isFile) d.deleteRecursively()
            else if (d.isDirectory) d.listFiles()?.filter(LocalCache::isTemp)?.forEach { it.delete() }
        }
    }

    /** Sign-out / session expiry: nothing decrypted may stay behind. */
    fun wipe() {
        state = null
        base.deleteRecursively()
        _revision.value++
    }
}
