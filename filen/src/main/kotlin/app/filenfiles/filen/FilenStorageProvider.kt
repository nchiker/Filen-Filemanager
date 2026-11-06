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
import uniffi.filen_sdk_rs.CreatedTime
import uniffi.filen_sdk_rs.Dir
import uniffi.filen_sdk_rs.DirectoryMetaChanges
import uniffi.filen_sdk_rs.FileMetaChanges
import uniffi.filen_sdk_rs.DirMeta
import uniffi.filen_sdk_rs.FileMeta
import uniffi.filen_sdk_rs.FileBuilderParamsOptionalName
import uniffi.filen_sdk_rs.JsFileDownloadCallback
import uniffi.filen_sdk_rs.JsFileUploadCallback
import uniffi.filen_sdk_rs.MakeThumbnailInMemoryParams
import uniffi.filen_sdk_rs.MakeThumbnailInMemoryResult
import uniffi.filen_sdk_rs.ManagedFuture
import uniffi.filen_sdk_rs.NonRootDir
import uniffi.filen_sdk_rs.NonRootNormalItem
import uniffi.filen_sdk_rs.Contact
import uniffi.filen_sdk_rs.SharedRootItem
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
class FilenStorageProvider(private val context: Context, private val session: FilenSession) : StorageProvider, WritableStorage, TrashStorage, FavoriteStorage, SharingStorage {

    private class DirEntry(val name: String, val parent: String?)

    /** A directory inside a shared namespace, with what the SDK needs to list it again. */
    private class SharedDirNode(
        val any: AnySharedDir, val role: SharingRole, val write: Boolean,
        val scheme: String, val name: String, val parent: String?,
    )

    private class SharedFileNode(val any: AnyFile, val item: FileItem)

    private val dirCache = ConcurrentHashMap<String, DirEntry>()
    private val personalFiles = ConcurrentHashMap<String, FFile>()
    private val personalDirObjs = ConcurrentHashMap<String, Dir>()
    private val sharedDirs = ConcurrentHashMap<String, SharedDirNode>()   // key = full ItemId value
    private val sharedFiles = ConcurrentHashMap<String, SharedFileNode>() // key = full ItemId value
    /** child ItemId -> parent folder ItemId for shared items; survives in the persisted index so nested folders resolve cold. */
    private val sharedParents = ConcurrentHashMap<String, String>()

    private val client get() = session.client

    /** IO-dispatcher wrapper that reports SDK failures to the session (so a rejected login is noticed). */
    private suspend inline fun <T> io(crossinline block: suspend () -> T): T = withContext(Dispatchers.IO) {
        try { block() } catch (e: Throwable) { session.noteError(e); throw e }
    }

    private suspend inline fun <T> timed(label: String, block: () -> T): T {
        val t = System.nanoTime()
        try { return block() } finally { android.util.Log.d("FilenPerf", "$label took ${(System.nanoTime() - t) / 1_000_000} ms") }
    }
    @Volatile private var rootCache: String? = null
    private val rootUuid: String get() = rootCache ?: client.root().uuid.also { rootCache = it }

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
        personalDirObjs[d.uuid] = d
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
        if (parent != null) sharedParents[id.value] = parent
        val who = roleEmail(role)
        return FileItem(
            id, name, true, 0, ts, null, originOf(scheme),
            if (write) Permission.READ_WRITE else Permission.READ_ONLY,
            sharedBy = if (scheme == IN) who else null, sharedWith = if (scheme == OUT) who else null,
        )
    }

    private fun sharedFileItem(
        any: AnyFile, uuid: String, size: ULong, ts: Long, meta: FileMeta, role: SharingRole,
        write: Boolean, scheme: String, parent: String,
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
        sharedParents[id.value] = parent
        return item
    }

    // ---------- listing ----------

    override suspend fun list(folder: ItemId): Listing = io {
        when (folder.scheme) {
            P -> listPersonal(folder)
            else -> listShared(folder)
        }
    }

    private suspend fun listPersonal(folder: ItemId): Listing {
        val root = rootUuid
        val uuid = if (folder.key == ROOT) root else folder.key
        val any = if (uuid == root) AnyNormalDir.Root(client.root()) else AnyNormalDir.Dir(client.getDir(uuid))
        val res = timed("listDir") { retryNetwork { client.listDir(any) } }
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
            var items = res.dirs.map { d ->
                sharedDirItem(AnySharedDir.Root(d), d.inner.uuid, d.inner.meta, d.inner.timestamp, d.sharingRole,
                    d.writeAccess, scheme, "$scheme:$ROOT")
            } + res.files.map { f ->
                sharedFileItem(AnyFile.Shared(f), f.uuid, f.size, f.timestamp, f.meta, f.sharingRole, false, scheme, "$scheme:$ROOT")
            }
            if (scheme == OUT) {
                // One folder shared with several people comes back once per recipient: show it once (recipients
                // joined) so list keys stay unique, and remember every underlying share so it can be revoked.
                outRoots.clear()
                res.dirs.forEach { d -> outRoots.merge("$OUT:${d.inner.uuid}", listOf<SharedRootItem>(SharedRootItem.Dir(d))) { a, b -> a + b } }
                res.files.forEach { f -> outRoots.merge("$OUT:${f.uuid}", listOf<SharedRootItem>(SharedRootItem.File(f))) { a, b -> a + b } }
                items = items.groupBy { it.id }.map { (_, same) ->
                    same.first().copy(sharedWith = same.mapNotNull { it.sharedWith }.distinct().joinToString(", ").ifEmpty { null })
                }
            }
            val title = rootTitle(scheme)
            return Listing(Folder(folder, title, originOf(scheme)), items, listOf(Crumb(folder, title)))
        }
        val node = sharedDirs[folder.value] ?: run {
            // After a process restart nested shared dirs are unknown: re-list the path down to them.
            resolveShared(folder)
            sharedDirs[folder.value]
        } ?: error("This shared folder is no longer in memory; reopen it from the Shared tab.")
        val res = timed("listSharedDir") { client.listSharedDir(node.any, node.role) }
        val items = res.dirs.map { d ->
            sharedDirItem(AnySharedDir.Dir(d), d.inner.uuid, d.inner.meta, d.inner.timestamp, node.role,
                node.write, scheme, folder.value)
        } + res.files.map { f ->
            sharedFileItem(AnyFile.File(f), f.uuid, f.size, f.timestamp, f.meta, node.role, node.write, scheme, folder.value)
        }
        return Listing(Folder(folder, node.name, originOf(scheme)), items, sharedTrail(folder.value, scheme))
    }

    /**
     * Re-registers a shared item after a process restart. The SDK needs a parent listing to hand out a usable
     * handle, so list the received-share root and then each remembered ancestor (parents come from the persisted
     * index) down to the item's own parent. Without any hint this degrades to listing the root.
     */
    private suspend fun resolveShared(target: ItemId) {
        ensureLoaded()
        val rootId = "${target.scheme}:$ROOT"
        val chain = ArrayDeque<String>()
        var cur = sharedParents[target.value]
        var guard = 0
        while (cur != null && cur != rootId && sharedDirs[cur] == null && guard++ < 64) { chain.addFirst(cur); cur = sharedParents[cur] }
        listShared(ItemId(rootId))
        for (dir in chain) { if (sharedDirs[dir] == null) return; listShared(ItemId(dir)) }
        val parent = sharedParents[target.value]
        if (parent != null && parent != rootId && sharedDirs[target.value] == null && sharedFiles[target.value] == null && sharedDirs[parent] != null) {
            listShared(ItemId(parent))
        }
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

    // ---------- sharing with other people ----------

    private val outRoots = ConcurrentHashMap<String, List<SharedRootItem>>() // my top-level outgoing shares, by id
    private val contactMap = ConcurrentHashMap<String, Contact>()

    override fun canShare(item: FileItem) = item.id.scheme == P && item.id.key != ROOT
    override fun canUnshare(item: FileItem) = item.id.scheme == OUT && outRoots.containsKey(item.id.value)

    override suspend fun contacts(): List<ContactInfo> = io {
        val all = client.getContacts()
        contactMap.clear(); all.forEach { contactMap[it.uuid] = it }
        all.map { ContactInfo(it.uuid, it.email, it.nickName?.takeIf(String::isNotBlank)) }.sortedBy { (it.name ?: it.email).lowercase() }
    }

    override suspend fun share(item: FileItem, contact: ContactInfo): Unit = io {
        require(canShare(item)) { "This item cannot be shared" }
        val c = contactMap[contact.id] ?: client.getContacts().also { l -> l.forEach { contactMap[it.uuid] = it } }
            .firstOrNull { it.uuid == contact.id } ?: error("That contact is no longer available")
        if (item.isDirectory) client.shareDir(dirObj(item.id), c, null) else client.shareFile(fileObj(item.id), c)
    }

    override suspend fun addContact(email: String): Unit = io { client.sendContactRequest(email.trim()); Unit }

    override suspend fun unshare(item: FileItem): Unit = io {
        val roots = outRoots[item.id.value] ?: error("This share is no longer listed; reload and try again")
        roots.forEach { client.removeSharedItem(it) }
        outRoots.remove(item.id.value); sharedDirs.remove(item.id.value); sharedFiles.remove(item.id.value)
    }

    // ---------- stat / favorites / recents ----------

    override suspend fun stat(id: ItemId): FileItem? = io {
        if (id.scheme == P) {
            if (id.key == ROOT) return@io null
            personalFiles[id.key]?.let { return@io personalFileItem(it) }
            client.getFileOptional(id.key)?.let { return@io personalFileItem(it) }
            client.getDirOptional(id.key)?.let { return@io personalDirItem(it) }
            return@io null
        }
        if (sharedDirs[id.value] == null && sharedFiles[id.value] == null) {
            runCatching { resolveShared(id) }
        }
        sharedFiles[id.value]?.item ?: sharedDirs[id.value]?.let { n ->
            FileItem(id, n.name, true, 0, 0, null, originOf(n.scheme),
                if (n.write) Permission.READ_WRITE else Permission.READ_ONLY)
        }
    }

    override suspend fun favorites(): List<FileItem> = io {
        val r = client.listFavorites()
        r.dirs.map(::personalDirItem) + r.files.map(::personalFileItem)
    }

    override fun canFavorite(item: FileItem) = item.id.scheme == P && item.id.key != ROOT

    override suspend fun setFavorite(item: FileItem, favorite: Boolean): Unit = io {
        require(canFavorite(item)) { "This item cannot be starred on Filen" }
        if (item.isDirectory) {
            val d = (client.setFavorite(NonRootNormalItem.Dir(dirObj(item.id)), favorite) as NonRootNormalItem.Dir).v1
            personalDirItem(d)
        } else {
            val f = (client.setFavorite(NonRootNormalItem.File(fileObj(item.id)), favorite) as NonRootNormalItem.File).v1
            personalFileItem(f)
        }
    }

    override suspend fun recent(limit: Int): List<FileItem> = io {
        val personal = runCatching { timed("listRecents") { client.listRecents() }.files.map(::personalFileItem) }.getOrDefault(emptyList())
        // Received shares appear in recents too, flagged by their origin so the UI can badge them.
        val received = runCatching {
            client.listInSharedRoot().files.map { f ->
                sharedFileItem(AnyFile.Shared(f), f.uuid, f.size, f.timestamp, f.meta, f.sharingRole, false, IN, "$IN:$ROOT")
            }
        }.getOrDefault(emptyList())
        (personal + received).sortedByDescending { it.modified }.take(limit)
    }

    // ---------- search (client-side index; the SDK exposes no server search) ----------
    //
    // The index is persisted (SearchIndexStore, in the cache dir, wiped on sign-out) so a process start answers
    // searches immediately instead of crawling. Our own edits patch it in place. A full re-crawl happens only when
    // it is missing, marked dirty (a restored folder), or older than its TTL - and a TTL-driven refresh is skipped
    // on metered networks / battery saver, because the SDK burns a lot of CPU and data on it.

    private val indexLock = Any()
    @Volatile private var personalIndex: List<IndexEntry> = emptyList()
    @Volatile private var sharedIndex: List<IndexEntry> = emptyList()
    @Volatile private var personalAt = 0L
    @Volatile private var sharedAt = 0L
    @Volatile private var personalDirty = false
    @Volatile private var mutations = 0
    @Volatile private var loadedAccount: String? = null
    private val generation = java.util.concurrent.atomic.AtomicInteger(0)
    private val buildScope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob() + Dispatchers.IO)
    @Volatile private var building: kotlinx.coroutines.Job? = null
    private var saveJob: kotlinx.coroutines.Job? = null

    private fun indexFile() = JFile(LocalCache.dir(context, LocalCache.INDEX), "search.idx")
    private val account get() = (session.state.value as? FilenSession.State.LoggedIn)?.email

    /** Loads the persisted index for the signed-in account once (also restores shared-parent hints). */
    @Synchronized
    private fun ensureLoaded() {
        val acct = account ?: return
        if (loadedAccount == acct) return
        loadedAccount = acct
        val snap = SearchIndexStore.load(indexFile(), acct) ?: return
        synchronized(indexLock) {
            if (personalAt != 0L || sharedAt != 0L) return // a crawl already finished first
            personalIndex = snap.personal; sharedIndex = snap.shared
            personalAt = snap.personalAt; sharedAt = snap.sharedAt
        }
        snap.shared.forEach { e -> e.parent?.let { sharedParents[e.item.id.value] = it } }
        android.util.Log.d("FilenPerf", "index loaded: ${snap.personal.size} personal + ${snap.shared.size} shared")
    }

    private fun saveNow() {
        val acct = loadedAccount ?: return
        runCatching {
            val (p, s, pa, sa) = synchronized(indexLock) { Quad(personalIndex, sharedIndex, personalAt, sharedAt) }
            if (pa == 0L && sa == 0L) return
            SearchIndexStore.save(indexFile(), IndexSnapshot(acct, pa, sa, p, s))
        }
    }
    private data class Quad(val p: List<IndexEntry>, val s: List<IndexEntry>, val pa: Long, val sa: Long)

    @Synchronized
    private fun scheduleSave() {
        saveJob?.cancel()
        saveJob = buildScope.launch { kotlinx.coroutines.delay(3_000); saveNow() }
    }

    /** Whether a background refresh is cheap for the device right now: unmetered network and no battery saver. */
    private fun cheapToRefresh(): Boolean {
        val cm = context.getSystemService(android.net.ConnectivityManager::class.java)
        val caps = cm?.getNetworkCapabilities(cm.activeNetwork)
        val unmetered = caps?.hasCapability(android.net.NetworkCapabilities.NET_CAPABILITY_NOT_METERED) == true
        val saver = context.getSystemService(android.os.PowerManager::class.java)?.isPowerSaveMode == true
        return unmetered && !saver
    }

    /** Starts a (single) background crawl if something is missing, dirty or past its TTL. Safe to call often. */
    @Synchronized
    private fun ensureIndexing(): kotlinx.coroutines.Job? {
        ensureLoaded()
        building?.takeIf { it.isActive }?.let { return it }
        val now = System.currentTimeMillis()
        val needPersonal = personalAt == 0L || personalDirty || now - personalAt > PERSONAL_TTL
        val needShared = sharedAt == 0L || now - sharedAt > SHARED_TTL
        if (!needPersonal && !needShared) return null
        // Having an index already, a purely time-based refresh waits for a cheap moment.
        val urgent = personalAt == 0L || personalDirty
        if (!urgent && !cheapToRefresh()) return null
        val gen = generation.get()
        return buildScope.launch { rebuildIndex(needPersonal, needShared, gen) }.also { building = it }
    }

    /** Warm the index in the background (called from the UI) so the first search is fast. */
    fun warmUp() { if (session.isSignedIn) ensureIndexing() }

    /** Forget everything account-specific (sign-out / expiry): memory, registries and the persisted index. */
    fun onSessionEnded() {
        generation.incrementAndGet()
        building?.cancel(); building = null
        synchronized(indexLock) {
            personalIndex = emptyList(); sharedIndex = emptyList(); personalAt = 0; sharedAt = 0; personalDirty = false
        }
        loadedAccount = null; rootCache = null
        listOf(dirCache, personalFiles, personalDirObjs, sharedDirs, sharedFiles, sharedParents, trashedFiles, trashedDirs, outRoots, contactMap).forEach { it.clear() }
        indexFile().delete()
    }

    override suspend fun search(query: String, limit: Int): List<FileItem> = io {
        val q = query.trim().lowercase()
        if (q.isEmpty() || !session.isSignedIn) return@io emptyList()
        val job = ensureIndexing()
        // Only wait for a crawl when there is nothing (or something known-wrong) to answer from.
        if (job != null && (personalAt == 0L || personalDirty)) kotlinx.coroutines.withTimeoutOrNull(SEARCH_WAIT_MS) { job.join() }
        val (p, s) = synchronized(indexLock) { personalIndex to sharedIndex }
        buildList {
            for (e in p) if (e.item.name.lowercase().contains(q)) { add(e.item); if (size >= limit) return@buildList }
            for (e in s) if (e.item.name.lowercase().contains(q)) { add(e.item); if (size >= limit) return@buildList }
        }
    }

    private fun personalParentId(p: ParentUuid): String? = (p as? ParentUuid.Uuid)?.v1?.let { if (it == rootUuid) "$P:$ROOT" else "$P:$it" }
    private fun personalEntry(d: Dir) = IndexEntry(personalDirItem(d), personalParentId(d.parent))
    private fun personalEntry(f: FFile) = IndexEntry(personalFileItem(f), personalParentId(f.parent))

    private suspend fun rebuildIndex(doPersonal: Boolean, doShared: Boolean, gen: Int) {
        val seenMutations = mutations
        if (doPersonal) {
            val list = ArrayList<IndexEntry>()
            val ok = runCatching {
                val all = timed("index.personalRecursive") { client.listDirRecursive(AnyDirWithContext.Normal(AnyNormalDir.Root(client.root())), null) }
                all.dirs.forEach { (it as? NonRootDir.Normal)?.let { n -> list.add(personalEntry(n.v1)) } }
                all.files.forEach { list.add(personalEntry(it)) }
            }.isSuccess
            if (gen != generation.get()) return
            if (ok) synchronized(indexLock) {
                personalIndex = list; personalAt = System.currentTimeMillis()
                if (mutations == seenMutations) personalDirty = false // else an edit raced the crawl: stay dirty
            }
        }
        if (doShared && gen == generation.get()) crawlShared(gen)
        if (gen != generation.get()) return
        android.util.Log.d("FilenPerf", "index built: ${personalIndex.size} personal + ${sharedIndex.size} shared")
        saveNow()
    }

    /**
     * Shared trees: bounded breadth-first crawl, one listing at a time (concurrent listings made the SDK back
     * off and were slower overall), publishing as it goes so searches can use a partial index. If a top-level
     * listing fails (network) the previous shared index is kept as it was.
     */
    private suspend fun crawlShared(gen: Int) {
        val acc = ArrayList<IndexEntry>()
        val hadData = sharedIndex.isNotEmpty()
        if (!hadData) synchronized(indexLock) { sharedIndex = acc }
        val deadline = System.currentTimeMillis() + SHARED_CRAWL_MAX_MS
        var rootFailed = false
        timed("index.shared") {
            for (scheme in listOf(IN, OUT)) {
                val queue = ArrayDeque<ItemId>().apply { add(ItemId("$scheme:$ROOT")) }
                var budget = SHARED_CRAWL_BUDGET
                while (queue.isNotEmpty() && budget > 0 && System.currentTimeMillis() < deadline && gen == generation.get()) {
                    val dir = queue.removeFirst()
                    val l = runCatching { listShared(dir) }.getOrNull()
                    if (l == null) { if (dir.key == ROOT) rootFailed = true; continue }
                    budget -= l.items.size
                    synchronized(indexLock) { l.items.forEach { acc.add(IndexEntry(it, dir.value)) } }
                    l.items.filter { it.isDirectory }.forEach { queue.add(it.id) }
                }
            }
        }
        if (gen != generation.get()) return
        synchronized(indexLock) {
            if (!rootFailed) { sharedIndex = acc; sharedAt = System.currentTimeMillis() }
            else if (!hadData) sharedIndex = emptyList()
        }
    }

    // In-place edits keep the index right after our own changes, so they don't each trigger a re-crawl.

    private fun indexUpsert(item: FileItem, parent: String?) {
        synchronized(indexLock) {
            val old = personalIndex.firstOrNull { it.item.id == item.id }
            personalIndex = personalIndex.filter { it.item.id != item.id } + IndexEntry(item, parent ?: old?.parent)
            mutations++
        }
        scheduleSave()
    }

    /** Drops [id] and everything below it. */
    private fun indexRemoveSubtree(id: ItemId) {
        synchronized(indexLock) {
            val children = personalIndex.groupBy { it.parent }
            val gone = HashSet<String>().apply { add(id.value) }
            val stack = ArrayDeque<String>().apply { add(id.value) }
            while (stack.isNotEmpty()) children[stack.removeLast()]?.forEach { if (gone.add(it.item.id.value)) stack.add(it.item.id.value) }
            personalIndex = personalIndex.filter { it.item.id.value !in gone }
            mutations++
        }
        scheduleSave()
    }

    /** Something changed that the in-place edits can't express (a restored folder brings its subtree back). */
    private fun indexMarkDirty() { synchronized(indexLock) { personalDirty = true; mutations++ } }

    // ---------- write (personal drive only: received shares are never written to) ----------

    override fun canWrite(folder: ItemId) = folder.scheme == P

    private suspend fun personalDir(id: ItemId): AnyNormalDir {
        require(id.scheme == P) { "Uploads are only supported into your personal Filen drive" }
        val root = rootUuid
        return if (id.key == ROOT || id.key == root) AnyNormalDir.Root(client.root()) else AnyNormalDir.Dir(client.getDir(id.key))
    }

    override suspend fun createFolder(parent: ItemId, name: String): ItemId = io {
        val d = client.createDir(personalDir(parent), name)
        personalDirItem(d).also { indexUpsert(it, parent.normalizedParent()) }.id
    }

    override suspend fun upload(parent: ItemId, localPath: String, name: String, mime: String?, onProgress: (Long) -> Unit): FileItem =
        io {
            var sent = 0L
            val cb = object : JsFileUploadCallback {
                // `uploadedBytes` is a delta since the previous call
                override fun onUpdate(uploadedBytes: ULong) { sent += uploadedBytes.toLong(); onProgress(sent) }
            }
            val params = FileBuilderParamsOptionalName(personalDir(parent), name, null, null, mime)
            val f = client.uploadFile(params, localPath, cb, ManagedFuture(null, null))
            personalFileItem(f).also { indexUpsert(it, parent.normalizedParent()) }
        }

    private suspend fun fileObj(id: ItemId) = personalFiles[id.key] ?: client.getFile(id.key)
    private suspend fun dirObj(id: ItemId) = personalDirObjs[id.key] ?: client.getDir(id.key)
    /** Index id of a personal folder: the drive root is always `filen:root`. */
    private fun ItemId.normalizedParent() = if (key == ROOT || key == rootUuid) "$P:$ROOT" else value

    override fun canModify(item: FileItem) = item.origin == Origin.FILEN_PERSONAL && item.id.scheme == P && item.id.key != ROOT

    override suspend fun rename(item: FileItem, newName: String): FileItem = io {
        require(canModify(item)) { "This item cannot be renamed" }
        val out = if (item.isDirectory)
            personalDirItem(client.updateDirMetadata(dirObj(item.id), DirectoryMetaChanges(name = newName, created = CreatedTime.Keep)))
        else
            personalFileItem(client.updateFileMetadata(fileObj(item.id), FileMetaChanges(name = newName, created = CreatedTime.Keep)))
        indexUpsert(out, null); out
    }

    override suspend fun move(item: FileItem, newParent: ItemId): FileItem = io {
        require(canModify(item)) { "This item cannot be moved" }
        val parent = personalDir(newParent)
        val out = if (item.isDirectory) personalDirItem(client.moveDir(dirObj(item.id), parent))
        else personalFileItem(client.moveFile(fileObj(item.id), parent))
        indexUpsert(out, newParent.normalizedParent()); out
    }

    override suspend fun trash(item: FileItem): (suspend () -> Unit)? = io {
        require(canModify(item)) { "This item cannot be deleted" }
        val undo: suspend () -> Unit = if (item.isDirectory) {
            val t = client.trashDir(dirObj(item.id))
            ({ io { indexUpsert(personalDirItem(client.restoreDir(t)), null); indexMarkDirty() } })
        } else {
            val t = client.trashFile(fileObj(item.id))
            ({ io { val f = client.restoreFile(t); indexUpsert(personalFileItem(f), personalParentId(f.parent)) } })
        }
        indexRemoveSubtree(item.id)
        undo
    }

    // ---------- trash ----------

    private val trashedFiles = ConcurrentHashMap<String, FFile>()
    private val trashedDirs = ConcurrentHashMap<String, Dir>()

    override suspend fun listTrash(): List<FileItem> = io {
        val res = timed("listTrash") { client.listTrash() }
        trashedFiles.clear(); trashedDirs.clear()
        res.dirs.forEach { trashedDirs[it.uuid] = it }
        res.files.forEach { trashedFiles[it.uuid] = it }
        val dirs = res.dirs.map { d -> FileItem(ItemId("$P:${d.uuid}"), dirName(d.meta), true, 0, d.timestamp, null, Origin.FILEN_PERSONAL) }
        val files = res.files.map { f ->
            val i = fileInfo(f.meta, f.uuid)
            FileItem(ItemId("$P:${f.uuid}"), i.name, false, f.size.toLong(), i.modified ?: f.timestamp, mimeOf(i.name, i.mime), Origin.FILEN_PERSONAL)
        }
        (dirs + files).sortedByDescending { it.modified }
    }

    override suspend fun restore(item: FileItem): Unit = io {
        if (item.isDirectory) {
            val d = client.restoreDir(trashedDirs[item.id.key] ?: error("Trash changed; reload it"))
            indexUpsert(personalDirItem(d), personalParentId(d.parent)); indexMarkDirty() // its subtree comes back too
        } else {
            val f = client.restoreFile(trashedFiles[item.id.key] ?: error("Trash changed; reload it"))
            indexUpsert(personalFileItem(f), personalParentId(f.parent))
        }
        trashedDirs.remove(item.id.key); trashedFiles.remove(item.id.key)
    }

    override suspend fun deletePermanently(item: FileItem): Unit = io {
        if (item.isDirectory) client.deleteDirPermanently(trashedDirs[item.id.key] ?: error("Trash changed; reload it"))
        else client.deleteFilePermanently(trashedFiles[item.id.key] ?: error("Trash changed; reload it"))
        trashedDirs.remove(item.id.key); trashedFiles.remove(item.id.key)
    }

    override suspend fun emptyTrash(): Unit = io {
        client.emptyTrash(); trashedDirs.clear(); trashedFiles.clear()
    }

    // ---------- download ----------

    override suspend fun materialize(item: FileItem, onProgress: (Long, Long) -> Unit): MaterializedFile =
        io {
            val safe = item.name.replace('/', '_').ifBlank { "file" }
            val dir = JFile(LocalCache.dir(context, LocalCache.OPEN), "${item.id.scheme}/${item.id.key}-${item.size}").apply { mkdirs() }
            val target = JFile(dir, safe)
            if (target.isFile && target.length() == item.size) {
                target.setLastModified(System.currentTimeMillis()) // LRU: a hit keeps it from being evicted
                return@io MaterializedFile(target.absolutePath, item.name, item.mime)
            }
            LocalCache.requireSpace(context, item.size)
            val any: AnyFile = if (item.id.scheme == P) {
                AnyFile.File(personalFiles[item.id.key] ?: client.getFile(item.id.key))
            } else {
                (sharedFiles[item.id.value] ?: run { stat(item.id); sharedFiles[item.id.value] })?.any
                    ?: error("Shared file is no longer available; reopen it from the Shared tab.")
            }
            val tmp = JFile(dir, "$safe.part")
            try {
                retryNetwork { attempt ->
                    tmp.delete() // the SDK restarts from byte 0, so a half-written file is never reused
                    var done = 0L
                    if (attempt > 0) onProgress(0, item.size)
                    val cb = object : JsFileDownloadCallback {
                        // `downloadedBytes` is a delta since the previous call
                        override fun onUpdate(downloadedBytes: ULong) {
                            done += downloadedBytes.toLong(); onProgress(done, item.size)
                        }
                    }
                    client.downloadFileToPath(any, tmp.absolutePath, cb, ManagedFuture(null, null))
                }
                // A truncated file must never be cached as complete.
                check(tmp.length() == item.size) { "Download was incomplete (${tmp.length()} of ${item.size} bytes). Try again." }
                check(tmp.renameTo(target)) { "Could not finalise download" }
            } finally {
                tmp.delete() // no-op after a successful rename; removes partial data on failure or cancellation
                dir.listFiles()?.filter(LocalCache::isTemp)?.forEach { it.delete() } // SDK scratch files (.filendl)
            }
            LocalCache.trim(context, target)
            MaterializedFile(target.absolutePath, item.name, item.mime)
        }

    /** SDK-made WebP thumbnail, cached on disk per file+size. Null for non-images or when the SDK can't make one. */
    override suspend fun thumbnail(item: FileItem, sizePx: Int): String? = io {
        if (item.isDirectory || item.mime?.startsWith("image/") != true) return@io null
        val target = JFile(LocalCache.dir(context, LocalCache.THUMBS), "${item.id.scheme}/${item.id.key.replace('/', '_')}-${item.size}-$sizePx.webp")
        if (target.isFile && target.length() > 0) return@io target.absolutePath
        val any: AnyFile = if (item.id.scheme == P) {
            AnyFile.File(personalFiles[item.id.key] ?: client.getFile(item.id.key))
        } else {
            (sharedFiles[item.id.value] ?: run { stat(item.id); sharedFiles[item.id.value] })?.any ?: return@io null
        }
        val res = client.makeThumbnailInMemory(MakeThumbnailInMemoryParams(any, sizePx.toUInt(), sizePx.toUInt(), 80u))
        if (res !is MakeThumbnailInMemoryResult.Thumbnail) return@io null
        target.parentFile?.mkdirs()
        val tmp = JFile(target.parentFile, target.name + ".part")
        tmp.writeBytes(res.thumbnail.webpData)
        if (tmp.renameTo(target)) { LocalCache.trim(context, target); target.absolutePath } else null
    }

    companion object {
        const val P = "filen"
        const val IN = "filen-in"
        const val OUT = "filen-out"
        const val ROOT = "root"
        /** Own edits patch the index in place, so these only bound drift from changes made elsewhere (web, other devices). */
        private const val PERSONAL_TTL = 6 * 3_600_000L
        private const val SHARED_TTL = 24 * 3_600_000L
        private const val SHARED_CRAWL_BUDGET = 3000
        private const val SHARED_CRAWL_MAX_MS = 120_000L
        private const val SEARCH_WAIT_MS = 5_000L
    }
}
