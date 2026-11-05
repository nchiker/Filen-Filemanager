package app.filenfiles.filen

import android.content.Context
import android.webkit.MimeTypeMap
import app.filenfiles.core.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong
import uniffi.filen_sdk_rs.AnyDirWithContext
import uniffi.filen_sdk_rs.AnyFile
import uniffi.filen_sdk_rs.AnyNormalDir
import uniffi.filen_sdk_rs.AnySharedDir
import uniffi.filen_sdk_rs.Dir
import uniffi.filen_sdk_rs.DirMeta
import uniffi.filen_sdk_rs.FileMeta
import uniffi.filen_sdk_rs.JsFileDownloadCallback
import uniffi.filen_sdk_rs.ManagedFuture
import uniffi.filen_sdk_rs.NonRootDir
import uniffi.filen_sdk_rs.SharingRole
import uniffi.filen_types.ParentUuid
import java.io.File as JFile
import uniffi.filen_sdk_rs.File as FFile

/**
 * Filen storage on top of the official Rust SDK (UniFFI). Three namespaces, never mixed:
 *  - `filen:<uuid>`     personal drive (`filen:root` is the drive root)
 *  - `filen-in:<uuid>`  shared WITH me (`filen-in:root` lists received shares)
 *  - `filen-out:<uuid>` shared BY me   (`filen-out:root` lists my outgoing shares)
 * Shared items keep their sharing context (role, write access) so permissions are respected.
 */
class FilenStorageProvider(private val context: Context, private val session: FilenSession) : StorageProvider {

    private class DirEntry(val name: String, val parent: String?)

    /** A directory inside a shared namespace, with what the SDK needs to list it again. */
    private class SharedDirNode(
        val any: AnySharedDir, val role: SharingRole, val write: Boolean,
        val scheme: String, val name: String, val parent: String?,
    )

    private class SharedFileNode(val any: AnyFile, val item: FileItem)

    private val dirCache = ConcurrentHashMap<String, DirEntry>()
    private val personalFiles = ConcurrentHashMap<String, FFile>()
    private val sharedDirs = ConcurrentHashMap<String, SharedDirNode>()   // key = full ItemId value
    private val sharedFiles = ConcurrentHashMap<String, SharedFileNode>() // key = full ItemId value

    private val client get() = session.client

    private suspend inline fun <T> timed(label: String, block: () -> T): T {
        val t = System.nanoTime()
        try { return block() } finally { android.util.Log.d("FilenPerf", "$label took ${(System.nanoTime() - t) / 1_000_000} ms") }
    }
    private val rootUuid get() = client.root().uuid

    override fun owns(id: ItemId) = id.scheme == P || id.scheme == IN || id.scheme == OUT

    override suspend fun roots(): List<Folder> =
        if (!session.isSignedIn) emptyList() else listOf(
            Folder(ItemId("$P:$ROOT"), "Filen Drive", Origin.FILEN_PERSONAL),
            Folder(ItemId("$IN:$ROOT"), "Shared with me", Origin.FILEN_SHARED_WITH_ME),
            Folder(ItemId("$OUT:$ROOT"), "Shared by me", Origin.FILEN_SHARED_BY_ME),
        )

    // ---------- mapping ----------

    private fun dirName(m: DirMeta) = (m as? DirMeta.Decoded)?.v1?.name ?: "(undecryptable folder)"

    private class FileInfo(val name: String, val mime: String?, val modified: Long?)

    private fun fileInfo(m: FileMeta, uuid: String): FileInfo = (m as? FileMeta.Decoded)?.v1
        ?.let { FileInfo(it.name, it.mime.takeIf(String::isNotBlank), it.modified) }
        ?: FileInfo("(undecryptable file $uuid)", null, null)

    private fun mimeOf(name: String, declared: String?): String? =
        declared?.takeIf { it != "application/octet-stream" }
            ?: MimeTypeMap.getSingleton().getMimeTypeFromExtension(name.substringAfterLast('.', "").lowercase())
            ?: declared

    private fun personalDirItem(d: Dir): FileItem {
        val name = dirName(d.meta)
        dirCache[d.uuid] = DirEntry(name, (d.parent as? ParentUuid.Uuid)?.v1)
        return FileItem(ItemId("$P:${d.uuid}"), name, true, 0, d.timestamp, null, Origin.FILEN_PERSONAL)
    }

    private fun personalFileItem(f: FFile): FileItem {
        personalFiles[f.uuid] = f
        val i = fileInfo(f.meta, f.uuid)
        return FileItem(
            ItemId("$P:${f.uuid}"), i.name, false, f.size.toLong(), i.modified ?: f.timestamp,
            mimeOf(i.name, i.mime), Origin.FILEN_PERSONAL,
        )
    }

    private fun originOf(scheme: String) = if (scheme == IN) Origin.FILEN_SHARED_WITH_ME else Origin.FILEN_SHARED_BY_ME

    private fun roleEmail(r: SharingRole) = when (r) {
        is SharingRole.Sharer -> r.v1.email
        is SharingRole.Receiver -> r.v1.email
    }

    private fun schemeFor(r: SharingRole) = if (r is SharingRole.Receiver) IN else OUT

    /** Registers + converts a shared directory. */
    private fun sharedDirItem(
        any: AnySharedDir, uuid: String, meta: DirMeta, ts: Long, role: SharingRole,
        write: Boolean, scheme: String, parent: String?,
    ): FileItem {
        val id = ItemId("$scheme:$uuid")
        val name = dirName(meta)
        sharedDirs[id.value] = SharedDirNode(any, role, write, scheme, name, parent)
        val who = roleEmail(role)
        return FileItem(
            id, name, true, 0, ts, null, originOf(scheme),
            if (write) Permission.READ_WRITE else Permission.READ_ONLY,
            sharedBy = if (scheme == IN) who else null, sharedWith = if (scheme == OUT) who else null,
        )
    }

    private fun sharedFileItem(
        any: AnyFile, uuid: String, size: ULong, ts: Long, meta: FileMeta, role: SharingRole,
        write: Boolean, scheme: String,
    ): FileItem {
        val i = fileInfo(meta, uuid)
        val id = ItemId("$scheme:$uuid")
        val who = roleEmail(role)
        val item = FileItem(
            id, i.name, false, size.toLong(), i.modified ?: ts, mimeOf(i.name, i.mime), originOf(scheme),
            if (write) Permission.READ_WRITE else Permission.READ_ONLY,
            sharedBy = if (scheme == IN) who else null, sharedWith = if (scheme == OUT) who else null,
        )
        sharedFiles[id.value] = SharedFileNode(any, item)
        return item
    }

    // ---------- listing ----------

    override suspend fun list(folder: ItemId): Listing = withContext(Dispatchers.IO) {
        when (folder.scheme) {
            P -> listPersonal(folder)
            else -> listShared(folder)
        }
    }

    private suspend fun listPersonal(folder: ItemId): Listing {
        val root = rootUuid
        val uuid = if (folder.key == ROOT) root else folder.key
        val any = if (uuid == root) AnyNormalDir.Root(client.root()) else AnyNormalDir.Dir(client.getDir(uuid))
        val res = timed("listDir") { client.listDir(any) }
        val items = res.dirs.map(::personalDirItem) + res.files.map(::personalFileItem)
        val name = if (uuid == root) "Filen Drive" else dirCache[uuid]?.name ?: "Folder"
        val trail = personalTrail(uuid, root)
        return Listing(Folder(ItemId("$P:${if (uuid == root) ROOT else uuid}"), name, Origin.FILEN_PERSONAL), items, trail)
    }

    private suspend fun personalTrail(uuid: String, root: String): List<Crumb> {
        val out = ArrayDeque<Crumb>()
        var cur: String? = uuid
        var guard = 0
        while (cur != null && cur != root && guard++ < 64) {
            val c = cur
            val e = dirCache[c] ?: run { personalDirItem(client.getDir(c)); dirCache[c]!! }
            out.addFirst(Crumb(ItemId("$P:$c"), e.name))
            cur = e.parent
        }
        out.addFirst(Crumb(ItemId("$P:$ROOT"), "Filen Drive"))
        return out
    }

    private suspend fun listShared(folder: ItemId): Listing {
        val scheme = folder.scheme
        if (folder.key == ROOT) {
            val res = timed("sharedRoot($scheme)") { if (scheme == IN) client.listInSharedRoot() else client.listOutShared(null) }
            val items = res.dirs.map { d ->
                sharedDirItem(AnySharedDir.Root(d), d.inner.uuid, d.inner.meta, d.inner.timestamp, d.sharingRole,
                    d.writeAccess, scheme, "$scheme:$ROOT")
            } + res.files.map { f ->
                sharedFileItem(AnyFile.Shared(f), f.uuid, f.size, f.timestamp, f.meta, f.sharingRole, false, scheme)
            }
            val title = rootTitle(scheme)
            return Listing(Folder(folder, title, originOf(scheme)), items, listOf(Crumb(folder, title)))
        }
        val node = sharedDirs[folder.value] ?: run {
            // After a process restart nested shared dirs are unknown: repopulate from the roots.
            listShared(ItemId("$scheme:$ROOT"))
            sharedDirs[folder.value]
        } ?: error("This shared folder is no longer in memory; reopen it from the Shared tab.")
        val res = timed("listSharedDir") { client.listSharedDir(node.any, node.role) }
        val items = res.dirs.map { d ->
            sharedDirItem(AnySharedDir.Dir(d), d.inner.uuid, d.inner.meta, d.inner.timestamp, node.role,
                node.write, scheme, folder.value)
        } + res.files.map { f ->
            sharedFileItem(AnyFile.File(f), f.uuid, f.size, f.timestamp, f.meta, node.role, node.write, scheme)
        }
        return Listing(Folder(folder, node.name, originOf(scheme)), items, sharedTrail(folder.value, scheme))
    }

    private fun rootTitle(scheme: String) = if (scheme == IN) "Shared with me" else "Shared by me"

    private fun sharedTrail(id: String, scheme: String): List<Crumb> {
        val out = ArrayDeque<Crumb>()
        var cur: String? = id
        var guard = 0
        while (cur != null && guard++ < 64) {
            val n = sharedDirs[cur] ?: break
            out.addFirst(Crumb(ItemId(cur), n.name))
            cur = n.parent
        }
        out.addFirst(Crumb(ItemId("$scheme:$ROOT"), rootTitle(scheme)))
        return out
    }

    // ---------- stat / favorites / recents ----------

    override suspend fun stat(id: ItemId): FileItem? = withContext(Dispatchers.IO) {
        if (id.scheme == P) {
            if (id.key == ROOT) return@withContext null
            personalFiles[id.key]?.let { return@withContext personalFileItem(it) }
            client.getFileOptional(id.key)?.let { return@withContext personalFileItem(it) }
            client.getDirOptional(id.key)?.let { return@withContext personalDirItem(it) }
            return@withContext null
        }
        if (sharedDirs[id.value] == null && sharedFiles[id.value] == null) {
            runCatching { listShared(ItemId("${id.scheme}:$ROOT")) }
        }
        sharedFiles[id.value]?.item ?: sharedDirs[id.value]?.let { n ->
            FileItem(id, n.name, true, 0, 0, null, originOf(n.scheme),
                if (n.write) Permission.READ_WRITE else Permission.READ_ONLY)
        }
    }

    override suspend fun favorites(): List<FileItem> = withContext(Dispatchers.IO) {
        val r = client.listFavorites()
        r.dirs.map(::personalDirItem) + r.files.map(::personalFileItem)
    }

    override suspend fun recent(limit: Int): List<FileItem> = withContext(Dispatchers.IO) {
        val personal = runCatching { timed("listRecents") { client.listRecents() }.files.map(::personalFileItem) }.getOrDefault(emptyList())
        // Received shares appear in recents too, flagged by their origin so the UI can badge them.
        val received = runCatching {
            client.listInSharedRoot().files.map { f ->
                sharedFileItem(AnyFile.Shared(f), f.uuid, f.size, f.timestamp, f.meta, f.sharingRole, false, IN)
            }
        }.getOrDefault(emptyList())
        (personal + received).sortedByDescending { it.modified }.take(limit)
    }

    // ---------- search (client-side index; the SDK exposes no server search) ----------

    /** Personal drive index (fast) and shared-tree index (slow, grows while crawling). */
    @Volatile private var personalIndex: List<FileItem> = emptyList()
    @Volatile private var sharedIndex: List<FileItem> = emptyList()
    private val indexedAt = AtomicLong(0)
    private val buildScope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob() + Dispatchers.IO)
    @Volatile private var building: kotlinx.coroutines.Job? = null

    private fun stale() = System.currentTimeMillis() - indexedAt.get() > INDEX_TTL

    /** Starts a (single) background index build if the index is missing/stale. Safe to call often. */
    @Synchronized
    private fun ensureIndexing(): kotlinx.coroutines.Job? {
        building?.takeIf { it.isActive }?.let { return it }
        if (!stale()) return null
        return buildScope.launch { rebuildIndex() }.also { building = it }
    }

    /** Warm the index in the background (called after sign-in) so the first search is fast. */
    fun warmUp() { if (session.isSignedIn) ensureIndexing() }

    override suspend fun search(query: String, limit: Int): List<FileItem> = withContext(Dispatchers.IO) {
        val q = query.trim().lowercase()
        if (q.isEmpty() || !session.isSignedIn) return@withContext emptyList()
        // Wait briefly for the index; after that answer from whatever has been indexed so far.
        ensureIndexing()?.let { job -> kotlinx.coroutines.withTimeoutOrNull(SEARCH_WAIT_MS) { job.join() } }
        val shared = synchronized(sharedLock) { sharedIndex.toList() }
        (personalIndex + shared).filter { it.name.lowercase().contains(q) }.take(limit)
    }

    private val sharedLock = Any()

    private suspend fun rebuildIndex() {
        val personal = ArrayList<FileItem>()
        runCatching {
            val all = timed("index.personalRecursive") { client.listDirRecursive(AnyDirWithContext.Normal(AnyNormalDir.Root(client.root())), null) }
            all.dirs.forEach { (it as? NonRootDir.Normal)?.let { n -> personal.add(personalDirItem(n.v1)) } }
            all.files.forEach { personal.add(personalFileItem(it)) }
        }
        personalIndex = personal
        // Shared trees: bounded breadth-first crawl, one listing at a time (concurrent listings made the
        // SDK back off and were slower overall), publishing as it goes so searches can use a partial index.
        val acc = ArrayList<FileItem>()
        synchronized(sharedLock) { if (sharedIndex.isEmpty()) sharedIndex = acc }
        val deadline = System.currentTimeMillis() + SHARED_CRAWL_MAX_MS
        timed("index.shared") {
            for (scheme in listOf(IN, OUT)) {
                val queue = ArrayDeque<ItemId>().apply { add(ItemId("$scheme:$ROOT")) }
                var budget = SHARED_CRAWL_BUDGET
                while (queue.isNotEmpty() && budget > 0 && System.currentTimeMillis() < deadline) {
                    val l = runCatching { listShared(queue.removeFirst()) }.getOrNull() ?: continue
                    budget -= l.items.size
                    synchronized(sharedLock) { acc.addAll(l.items) }
                    l.items.filter { it.isDirectory }.forEach { queue.add(it.id) }
                }
            }
        }
        synchronized(sharedLock) { sharedIndex = acc }
        indexedAt.set(System.currentTimeMillis())
        android.util.Log.d("FilenPerf", "index built: ${personal.size} personal + ${acc.size} shared")
    }

    // ---------- download ----------

    override suspend fun materialize(item: FileItem, onProgress: (Long, Long) -> Unit): MaterializedFile =
        withContext(Dispatchers.IO) {
            val safe = item.name.replace('/', '_').ifBlank { "file" }
            val dir = JFile(context.cacheDir, "open/${item.id.scheme}/${item.id.key}-${item.size}").apply { mkdirs() }
            val target = JFile(dir, safe)
            if (target.isFile && target.length() == item.size) {
                return@withContext MaterializedFile(target.absolutePath, item.name, item.mime)
            }
            val any: AnyFile = if (item.id.scheme == P) {
                AnyFile.File(personalFiles[item.id.key] ?: client.getFile(item.id.key))
            } else {
                (sharedFiles[item.id.value] ?: run { stat(item.id); sharedFiles[item.id.value] })?.any
                    ?: error("Shared file is no longer available; reopen it from the Shared tab.")
            }
            var done = 0L
            val cb = object : JsFileDownloadCallback {
                // `downloadedBytes` is a delta since the previous call
                override fun onUpdate(downloadedBytes: ULong) {
                    done += downloadedBytes.toLong(); onProgress(done, item.size)
                }
            }
            val tmp = JFile(dir, "$safe.part")
            client.downloadFileToPath(any, tmp.absolutePath, cb, ManagedFuture(null, null))
            check(tmp.renameTo(target)) { "Could not finalise download" }
            MaterializedFile(target.absolutePath, item.name, item.mime)
        }

    companion object {
        const val P = "filen"
        const val IN = "filen-in"
        const val OUT = "filen-out"
        const val ROOT = "root"
        private const val INDEX_TTL = 5 * 60_000L
        private const val SHARED_CRAWL_BUDGET = 3000
        private const val SHARED_CRAWL_MAX_MS = 120_000L
        private const val SEARCH_WAIT_MS = 5_000L
    }
}
