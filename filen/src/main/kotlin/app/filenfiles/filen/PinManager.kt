package app.filenfiles.filen

import app.filenfiles.core.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File as JFile

/** What the pin logic needs from Filen; the real one is [FilenPinRemote], tests use a fake. */
interface PinRemote {
    /** The file with this stable key or version uuid; null when it does not exist. [RemoteLookup.trashed] = it is in the trash. */
    suspend fun file(key: String): RemoteLookup?
    /** Every file below the folder, recursively (trashed ones excluded); null when the folder is gone or in the trash. */
    suspend fun folder(uuid: String): List<RemoteFile>?
    /** Downloads exactly this version to [dest] (a temp path; the caller verifies and renames). */
    suspend fun download(file: RemoteFile, dest: JFile)
}

data class RemoteLookup(val file: RemoteFile, val trashed: Boolean)

/**
 * Offline copies of personal-drive files ("Available offline"). Decisions live in [Pins] (pure, unit-tested);
 * this class does the I/O: the state file, the downloads, the local renames/deletes.
 *
 * Copies live in `filesDir/pinned/files/<key>/<name>` (allowBackup is off) - not in `cache/` - so the 1 GiB LRU cap, the 7-day
 * age limit and the OS cache clearing never touch them; they are decrypted at rest, so they are wiped on sign-out
 * and session expiry like every other decrypted copy. The real file name is the leaf so Open With shows it.
 * The state file records only the user's choices plus what has arrived; every sync recomputes the rest.
 */
class PinManager(
    /** `<filesDir>/pinned`. */
    private val base: JFile,
    /** The signed-in account, or null when signed out. */
    private val account: () -> String?,
    private val remote: PinRemote,
    private val freeBytes: () -> Long,
    private val noteError: (Throwable) -> Unit = {},
) {
    /** Guards [state] and the state file (short, never held across a download or a network call). */
    private val stateLock = Any()
    /** One sync at a time; pin/unpin never wait for it. */
    private val syncLock = Mutex()
    /** Bumped by [wipe]: a sync that started before it must not write anything afterwards. */
    private val generation = java.util.concurrent.atomic.AtomicInteger()
    private val storeFile get() = JFile(base, "pins.bin")
    private val filesRoot get() = JFile(base, "files")
    @Volatile private var state: PinState? = null
    /** version uuid -> stable key, learned from listings, so a file with a newer version than our copy is still recognised. */
    private val versionKeys = java.util.concurrent.ConcurrentHashMap<String, String>()
    /** Keys reachable through a pinned folder as of the last sync (null until one ran in this process). */
    @Volatile private var lastFolderKeys: Set<String>? = null
    private val _revision = MutableStateFlow(0)
    /** Bumps whenever pins or what is on the device changed, so badges and the Offline list refresh. */
    val revision: StateFlow<Int> = _revision
    private val _syncing = MutableStateFlow(false)
    val syncing: StateFlow<Boolean> = _syncing
    @Volatile var lastError: String? = null; private set
    @Volatile var orphaned: Set<String> = emptySet(); private set


    // Callers hold stateLock.
    private fun currentLocked(): PinState? {
        val acct = account() ?: return null
        state?.takeIf { it.account == acct }?.let { return it }
        return (PinStore.load(storeFile, acct) ?: PinState(acct)).also { state = it }
    }

    private fun current(): PinState? = synchronized(stateLock) { currentLocked() }

    /** Applies [f] to the latest state and saves it. Skipped when [gen] is stale (a wipe happened since the sync began). */
    private fun mutate(gen: Int = generation.get(), f: (PinState) -> PinState): Boolean = synchronized(stateLock) {
        if (gen != generation.get()) return false
        val cur = currentLocked() ?: return false
        val next = f(cur)
        state = next
        try { PinStore.save(storeFile, next) } catch (e: Exception) { lastError = "Couldn't save the offline list: ${e.message}" }
        _revision.value++
        true
    }

    private suspend inline fun <T> io(crossinline block: suspend () -> T): T = withContext(Dispatchers.IO) {
        try { block() } catch (e: Throwable) { noteError(e); throw e }
    }

    // ---------- queries (memory only: they work offline) ----------

    /** The stable key of [uuid]: from a copy we hold, from a listing we saw, else [uuid] itself (a pin made before its first sync). */
    private fun keyForUuid(s: PinState, uuid: String) =
        s.files.firstOrNull { it.version == uuid }?.key ?: versionKeys[uuid] ?: uuid

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
    fun isDirect(f: PinnedFile) = current()?.directKeys?.containsKey(f.key) == true

    fun localFile(f: PinnedFile) = JFile(JFile(filesRoot, safe(f.key)), safeName(f.name))

    /** The on-device copy of the current version of [uuid], if it is complete. */
    fun copyOf(uuid: String): Pair<PinnedFile, JFile>? {
        val f = current()?.files?.firstOrNull { it.version == uuid } ?: return null
        val file = localFile(f)
        return if (file.isFile && file.length() == f.size) f to file else null
    }

    private fun safe(s: String) = s.replace(Regex("[^A-Za-z0-9_-]"), "_")
    private fun safeName(n: String) = n.replace('/', '_').ifBlank { "file" }

    // ---------- changes (no network: they work offline and never wait for a sync) ----------

    /** Records the choice; the files arrive when [sync] runs (a background job, so it survives the app being closed). */
    suspend fun pin(items: List<FileItem>) = withContext(Dispatchers.IO) {
        mutate { s0 ->
            var s = s0
            for (it in items) {
                if (it.origin != Origin.FILEN_PERSONAL) continue
                s = if (it.isDirectory) {
                    if (s.folders.any { f -> f.uuid == it.id.key }) s else s.copy(folders = s.folders + PinnedFolder(it.id.key, it.name))
                } else s.copy(directKeys = s.directKeys + (keyForUuid(s, it.id.key) to it.name)) // a pre-sync pin holds the version uuid; sync swaps in the stable key
            }
            s
        }
        Unit
    }

    /**
     * Removes the choice and deletes what is certainly no longer wanted right away, with no network: a file that is not
     * reachable through a pinned folder (as far as we know) is wiped now; anything uncertain waits for the next sync.
     */
    suspend fun unpin(items: List<FileItem>) = withContext(Dispatchers.IO) {
        val doomed = ArrayList<PinnedFile>()
        mutate { s0 ->
            var s = s0
            for (it in items) {
                s = if (it.isDirectory) s.copy(folders = s.folders.filter { f -> f.uuid != it.id.key })
                else s.copy(directKeys = s.directKeys - keyForUuid(s, it.id.key))
            }
            val viaFolders = if (s.folders.isEmpty()) emptySet() else lastFolderKeys
            if (viaFolders != null) {
                doomed += s.files.filter { it.key !in s.directKeys && it.key !in viaFolders }
                s = s.copy(files = s.files - doomed.toSet())
            }
            s
        }
        doomed.forEach { JFile(filesRoot, safe(it.key)).deleteRecursively() }
    }

    /** Total bytes a pin of [items] would download, from a recursive listing; for the confirmation. */
    suspend fun sizeOf(items: List<FileItem>): Long = io {
        var total = 0L
        for (it in items) {
            if (it.origin != Origin.FILEN_PERSONAL) continue
            total += if (it.isDirectory) remote.folder(it.id.key)?.sumOf { f -> f.size } ?: 0L else it.size
        }
        total
    }

    // ---------- sync ----------

    /**
     * Brings the device in line with the pins: new and changed files are downloaded, files that left a pinned folder or
     * were unpinned are deleted, renames are done locally. A listing that fails changes nothing. Returns the number of
     * files that could not be downloaded (they are tried again next time). Pins made while it runs are picked up by the
     * next run (the worker queues one behind this one).
     */
    suspend fun sync(): Int = io {
        syncLock.withLock {
            val gen = generation.get()
            val s0 = current() ?: return@withLock 0
            _syncing.value = true
            try { doSync(s0, gen) } finally { _syncing.value = false }
        }
    }

    private suspend fun doSync(s0: PinState, gen: Int): Int {
        lastError = null
        val wanted = LinkedHashMap<String, RemoteFile>()
        val missing = HashSet<String>()
        val resolved = HashMap<String, String>() // pinned key as stored -> stable key
        for (key in s0.directKeys.keys) {
            val f = remote.file(key)
            if (f == null || f.trashed) missing.add(key)
            else { resolved[key] = f.file.key; wanted.putIfAbsent(f.file.key, f.file) }
        }
        val liveFolders = ArrayList<PinnedFolder>()
        val viaFolders = HashSet<String>()
        for (folder in s0.folders) {
            val files = remote.folder(folder.uuid) ?: continue // gone: drops from the pins, its copies are deleted below
            liveFolders.add(folder)
            files.forEach { viaFolders.add(it.key); wanted.putIfAbsent(it.key, it) }
        }
        wanted.values.forEach { versionKeys[it.version] = it.key }
        lastFolderKeys = viaFolders
        // Plan against the latest state (pins may have changed during the listing), apply to it atomically.
        val plan = Pins.reconcile(current() ?: return 0, wanted.values, missing)
        orphaned = plan.orphaned

        val deleted = plan.delete.map { it.key }.toSet()
        // A pin made before its first sync holds a version uuid; swap in the stable key now that we know it.
        if (!mutate(gen) { cur ->
                cur.copy(
                    directKeys = cur.directKeys.entries.associate { (resolved[it.key] ?: it.key) to it.value },
                    folders = cur.folders.filter { f -> liveFolders.any { it.uuid == f.uuid } },
                    files = cur.files.filter { it.key !in deleted },
                )
            }) return 0
        for (f in plan.delete) JFile(filesRoot, safe(f.key)).deleteRecursively()
        for ((f, newName) in plan.rename) {
            val from = localFile(f); val to = JFile(from.parentFile, safeName(newName))
            if (from.isFile && from.renameTo(to)) mutate(gen) { cur -> cur.copy(files = cur.files.map { if (it.key == f.key) it.copy(name = newName) else it }) }
        }
        current()?.let { sweepStrays(it) }

        var failed = 0
        for (r in plan.download) {
            if (gen != generation.get()) return failed
            try {
                download(r)
                val done = PinnedFile(r.key, r.version, r.name, r.size, r.modified, r.mime)
                if (!mutate(gen) { cur -> cur.copy(files = cur.files.filter { it.key != r.key } + done) }) {
                    JFile(filesRoot, safe(r.key)).deleteRecursively() // wiped meanwhile: leave nothing behind
                    return failed
                }
            } catch (e: kotlinx.coroutines.CancellationException) { throw e
            } catch (e: Exception) {
                failed++; lastError = e.friendlyOrNull() ?: e.message
                if (e.failureKind() == FailureKind.AUTH || e.failureKind() == FailureKind.DISK_FULL) break
            }
        }
        return failed
    }

    private suspend fun download(r: RemoteFile) {
        val dir = JFile(filesRoot, safe(r.key)).apply { mkdirs() }
        if (Pins.tooBig(r.size, freeBytes(), LocalCache.FREE_MARGIN)) throw java.io.IOException("No space left on device (need ${r.size shr 20} MB, ${freeBytes() shr 20} MB free)")
        val target = JFile(dir, safeName(r.name)); val tmp = JFile(dir, safeName(r.name) + ".part")
        try {
            tmp.delete() // a half-written file is never reused
            remote.download(r, tmp)
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

    /**
     * Sign-out / session expiry: nothing decrypted may stay behind. Bumping [generation] first stops a running sync from
     * writing again; the copies of an expired session go too, the user simply pins again after signing in.
     */
    fun wipe() {
        synchronized(stateLock) {
            generation.incrementAndGet()
            state = null; lastFolderKeys = null; orphaned = emptySet(); versionKeys.clear()
            base.deleteRecursively()
        }
        _revision.value++
    }
}
