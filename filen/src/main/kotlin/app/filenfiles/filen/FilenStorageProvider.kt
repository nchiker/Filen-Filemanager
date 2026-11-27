package app.filenfiles.filen

import android.content.Context
import android.webkit.MimeTypeMap
import app.filenfiles.core.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.ensureActive
import java.security.MessageDigest
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import uniffi.filen_sdk_rs.SharedRootDirsAndFiles
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
import uniffi.filen_sdk_rs.PasswordState
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
class FilenStorageProvider(private val context: Context, private val session: FilenSession) : StorageProvider, WritableStorage, TrashStorage, FavoriteStorage, SharingStorage, VersionStorage, CleanupStorage, QuotaStorage, PinStorage, LinkStorage {

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
    /**
     * What the documents provider keeps across a process death: child -> parent ids (all namespaces) and the last
     * listing of each folder. Nested shared folders resolve cold from this, without the user opening Filen (I4 F1/F2).
     */
    val docState = DocsState(JFile(context.filesDir, "docs-state/state.bin"), { session.accountId })
    /** The saved partner's address for the signed-in account (set by the app). "Shared by me" for other apps lists only shares with them. */
    @Volatile var partnerEmail: () -> String? = { null }
    /** A full top-down search for one id runs at most once per 10 minutes; see [CrawlGuard]. */
    private val crawlGuard = CrawlGuard()
    /** At most 6 full crawls per hour across all ids; then "cannot tell" (never "gone"). */
    private val crawlBudget = CrawlBudget()
    /** Outgoing ids another app may reach: only what a partner-scoped listing showed (I4 F4/F2). */
    private val outScope = OutScope()
    /** Parent links Filen confirmed in the last 60 s (reads under a persisted tree grant). */
    val verifiedParents = VerifiedParents()
    /** Guards only the in-flight table (never held across a network call). */
    private val registryLock = Any()
    private val inflight = HashMap<String, kotlinx.coroutines.CompletableDeferred<Boolean>>()
    private val crawlSlots = kotlinx.coroutines.sync.Semaphore(2)
    /** Opaque stable id of the signed-in account (I4 F6); null when signed out. */
    val accountId: String? get() = if (session.isSignedIn) session.accountId else null
    /** child ItemId -> parent folder ItemId, kept in [docState]. */
    private val sharedParents = object {
        operator fun get(id: String): String? = docState.parent(id)
        operator fun set(id: String, parent: String) = docState.setParent(id, parent)
    }

    private val client get() = session.client

    // ---------- offline pins ----------
    val pins = PinManager(
        JFile(context.filesDir, "pinned"),
        { (session.state.value as? FilenSession.State.LoggedIn)?.email },
        FilenPinRemote(session),
        { LocalCache.freeBytes(context) },
        { session.noteError(it) },
    )
    override val revision get() = pins.revision
    override val syncing get() = pins.syncing
    override val lastError get() = pins.lastError
    override fun isPinned(item: FileItem) = pins.isPinned(item)
    override fun isOffline(item: FileItem) = pins.isOffline(item)
    override fun isDirect(file: PinnedFile) = pins.isDirect(file)
    override val orphaned get() = pins.orphaned
    override fun canPin(item: FileItem) = item.origin == Origin.FILEN_PERSONAL && item.id.key != ROOT
    override suspend fun sizeOf(items: List<FileItem>) = pins.sizeOf(items)
    override suspend fun pin(items: List<FileItem>) { pins.pin(items) }
    override suspend fun unpin(items: List<FileItem>) { pins.unpin(items) }
    override fun offline() = pins.offline()
    override fun pinnedFolders() = pins.folders()
    override fun localFile(file: PinnedFile) = pins.localFile(file)
    override suspend fun sync() = pins.sync()

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
            mimeOf(i.name, i.mime), Origin.FILEN_PERSONAL, lineage = f.stableUuid,
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

    /** What other apps see: the "Shared by me" root lists only items shared with the saved partner (I4 F4); nothing without one. */
    suspend fun listForProvider(folder: ItemId): Listing = io {
        if (folder.scheme == P) listPersonal(folder) else listShared(folder, partnerOnly = true)
    }

    private suspend fun listShared(folder: ItemId, partnerOnly: Boolean = false): Listing {
        val scheme = folder.scheme
        if (folder.key == ROOT) {
            val res = timed("sharedRoot($scheme)") {
                if (scheme == IN) client.listInSharedRoot()
                else if (!partnerOnly) client.listOutShared(null)
                else {
                    val all = client.getContacts()
                    val contact = Partner.contactFor(partnerEmail(), all.map { Person(it.email, null) })
                        ?.let { p -> all.firstOrNull { it.email == p.email } }
                    if (contact == null) SharedRootDirsAndFiles(emptyList(), emptyList()) else client.listOutShared(contact)
                }
            }
            var items = res.dirs.map { d ->
                sharedDirItem(AnySharedDir.Root(d), d.inner.uuid, d.inner.meta, d.inner.timestamp, d.sharingRole,
                    d.writeAccess, scheme, "$scheme:$ROOT")
            } + res.files.map { f ->
                sharedFileItem(AnyFile.Shared(f), f.uuid, f.size, f.timestamp, f.meta, f.sharingRole, false, scheme, "$scheme:$ROOT")
            }
            if (scheme == OUT && !partnerOnly) {
                // One folder shared with several people comes back once per recipient: show it once (recipients
                // joined) so list keys stay unique, and remember every underlying share so it can be revoked.
                outRoots.clear()
                res.dirs.forEach { d -> outRoots.merge("$OUT:${d.inner.uuid}", listOf<SharedRootItem>(SharedRootItem.Dir(d))) { a, b -> a + b } }
                res.files.forEach { f -> outRoots.merge("$OUT:${f.uuid}", listOf<SharedRootItem>(SharedRootItem.File(f))) { a, b -> a + b } }
                items = items.groupBy { it.id }.map { (_, same) ->
                    same.first().copy(sharedWith = same.mapNotNull { it.sharedWith }.distinct().joinToString(", ").ifEmpty { null })
                }
            }
            if (scheme == OUT && partnerOnly) outScope.noteRoot(items.map { it.id.value })
            val title = rootTitle(scheme)
            return Listing(Folder(folder, title, originOf(scheme)), items, listOf(Crumb(folder, title)))
        }
        // After a process restart nested shared dirs are unknown: find them again (a confirmed miss = no longer shared).
        // A partner-scoped caller may only open an outgoing folder the partner listing showed.
        val scoped = scheme == OUT && partnerOnly
        val node = (sharedDirs[folder.value]?.takeIf { !scoped || outScope.allows(folder.value) }
            ?: run { ensureShared(folder); sharedDirs[folder.value]?.takeIf { !scoped || outScope.allows(folder.value) } })
            ?: throw java.io.FileNotFoundException("This folder is no longer shared")
        val res = timed("listSharedDir") { client.listSharedDir(node.any, node.role) }
        val items = res.dirs.map { d ->
            sharedDirItem(AnySharedDir.Dir(d), d.inner.uuid, d.inner.meta, d.inner.timestamp, node.role,
                node.write, scheme, folder.value)
        } + res.files.map { f ->
            sharedFileItem(AnyFile.File(f), f.uuid, f.size, f.timestamp, f.meta, node.role, node.write, scheme, folder.value)
        }
        if (scoped) outScope.noteChildren(folder.value, items.map { it.id.value })
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
        // Outgoing shares are only ever registered by the partner-scoped listing here (a caller who knows an id must not
        // be able to make this register shares with other contacts).
        val po = target.scheme == OUT
        listShared(ItemId(rootId), partnerOnly = po)
        for (dir in chain) { if (sharedDirs[dir] == null) return; listShared(ItemId(dir), partnerOnly = po) }
        val parent = sharedParents[target.value]
        if (parent != null && parent != rootId && sharedDirs[target.value] == null && sharedFiles[target.value] == null && sharedDirs[parent] != null) {
            listShared(ItemId(parent), partnerOnly = po)
        }
    }

    /** Known to this process, and for an outgoing id also inside the partner's scope (see [OutScope]). */
    private fun registered(id: ItemId) =
        (sharedDirs[id.value] != null || sharedFiles[id.value] != null) && (id.scheme != OUT || outScope.allows(id.value))

    /**
     * Makes sure the shared item [target] is registered (so it can be listed or opened) after a process restart.
     * First the remembered chain of parents is re-listed; if that does not find it (the chain is unknown or stale),
     * the shares are searched top-down from the root, listing folders until the item turns up (bounded in folders and
     * wall time, at most once per 10 minutes per id, one search at a time per id: see [crawl]). The SDK has no call
     * that asks the server for a received item's parent (get-by-uuid only knows the own drive), so this is the
     * server lookup. Returns true when found. Returns false ONLY after every folder was listed from the server without
     * error and the item is not in any of them (the share is gone); anything else (offline, a failing folder, too
     * many folders, out of time) throws, which callers report as "unknown".
     */
    private suspend fun ensureShared(target: ItemId): Boolean {
        if (registered(target)) return true
        while (true) {
            // The table is only touched under the lock; the network work below runs outside it.
            val mine = kotlinx.coroutines.CompletableDeferred<Boolean>()
            val other = synchronized(registryLock) { inflight[target.value] ?: run { inflight[target.value] = mine; null } }
            if (other != null) {
                try { return other.await() } catch (e: kotlinx.coroutines.CancellationException) {
                    // The searcher was cancelled, not this caller: look again (or become the searcher).
                    kotlin.coroutines.coroutineContext.ensureActive(); continue
                }
            }
            try {
                val r = findShared(target)
                mine.complete(r); return r
            } catch (e: Throwable) {
                mine.completeExceptionally(e); throw e
            } finally { synchronized(registryLock) { if (inflight[target.value] === mine) inflight.remove(target.value) } }
        }
    }

    private suspend fun findShared(target: ItemId): Boolean {
        if (registered(target)) return true
        try { resolveShared(target) } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (_: Exception) { /* chain stale: search below */ }
        if (registered(target)) return true
        return crawl(target) != null
    }

    /**
     * The full top-down search for [target]. Returns the folder whose listing holds it, null when every folder was
     * listed from the server and it is in none; throws [java.io.IOException] when it could not be finished or was
     * searched too recently (the answer then is "cannot tell", never "gone").
     */
    private suspend fun crawl(target: ItemId): String? {
        when (crawlGuard.recent(target.value)) {
            CrawlGuard.Outcome.NOT_FOUND -> return null
            CrawlGuard.Outcome.INCOMPLETE -> throw java.io.IOException("Searched a moment ago without finishing; try again later")
            null -> {}
        }
        val rootId = "${target.scheme}:$ROOT"
        return crawlSlots.withPermit {
            if (!crawlBudget.tryTake()) throw java.io.IOException("Searched too often; try again later") // cannot tell, never gone
            var failed = false
            val r = try {
                SharedCrawl.search(rootId, target.value, MAX_SEARCH_FOLDERS, MAX_SEARCH_MS) { f ->
                    try { listShared(ItemId(f), partnerOnly = true).items.map { SharedCrawl.Child(it.id.value, it.isDirectory) } }
                    catch (e: kotlinx.coroutines.CancellationException) { throw e }
                    catch (e: java.io.FileNotFoundException) { failed = true; emptyList() } // a folder that vanished mid-search: carry on, but the result is not clean
                    catch (e: Exception) { failed = true; throw e }
                }
            } catch (e: kotlinx.coroutines.CancellationException) { throw e
            } catch (e: Exception) { crawlGuard.note(target.value, CrawlGuard.Outcome.INCOMPLETE, clean = false); throw e }
            when (r) {
                is SharedCrawl.Result.Found -> { crawlGuard.forget(target.value); r.parent }
                SharedCrawl.Result.NotFound -> {
                    if (failed) { crawlGuard.note(target.value, CrawlGuard.Outcome.INCOMPLETE, clean = false); throw java.io.IOException("A shared folder could not be read; cannot tell") }
                    crawlGuard.note(target.value, CrawlGuard.Outcome.NOT_FOUND, clean = true); null
                }
                SharedCrawl.Result.Incomplete -> {
                    crawlGuard.note(target.value, CrawlGuard.Outcome.INCOMPLETE, clean = false)
                    throw java.io.IOException("Too many shared folders to search")
                }
            }
        }
    }

    /** Like [stat] for a shared item, but throws when it cannot be told; null only when the share is confirmed gone. */
    suspend fun statShared(id: ItemId): FileItem? = io {
        if (!ensureShared(id)) { docState.markGone(id.value); return@io null } // the server confirmed: no longer there / shared
        sharedFiles[id.value]?.item ?: sharedDirs[id.value]?.let { n ->
            FileItem(id, n.name, true, 0, 0, null, originOf(n.scheme), if (n.write) Permission.READ_WRITE else Permission.READ_ONLY)
        }
    }

    /** I4 F1: where an item sits. */
    sealed interface ParentLookup {
        data class Found(val parent: ItemId) : ParentLookup
        /** The item is a root. */
        data object Top : ParentLookup
        /** The server confirms the item (or its share) is gone. */
        data object Gone : ParentLookup
    }

    /** The folder of [id] from the persisted map or the server. Throws (never answers Gone) when it cannot be told. */
    suspend fun parentLookup(id: ItemId): ParentLookup = io {
        if (id.key == ROOT) return@io ParentLookup.Top
        if (id.scheme == P) {
            val file = client.getFileOptional(id.key)?.let { headOf(it) ?: return@io ParentLookup.Gone } // an older version's uuid names the current file
            val p = if (file != null) file.parent else (client.getDirOptional(id.key) ?: return@io ParentLookup.Gone).parent
            val pid = personalParentId(p) ?: return@io ParentLookup.Gone // in the trash or not in the drive
            return@io ParentLookup.Found(ItemId(pid))
        }
        sharedParents[id.value]?.let { return@io ParentLookup.Found(ItemId(it)) }
        if (!ensureShared(id)) { docState.markGone(id.value); return@io ParentLookup.Gone }
        sharedParents[id.value]?.let { ParentLookup.Found(ItemId(it)) } ?: ParentLookup.Gone
    }

    /**
     * Like [parentLookup], but never from the stored map: the parent chain is confirmed by Filen now. For the own drive
     * that is the lookup itself. For a shared item, the claimed parent is listed from the server and must contain the
     * item; if it does not (a stale link), the link is dropped and the shares are searched (bounded, see [crawl]).
     * Throws when it cannot be told (offline); [ParentLookup.Gone] only when the server confirms.
     */
    suspend fun verifiedParent(id: ItemId): ParentLookup = io {
        if (id.key == ROOT) return@io ParentLookup.Top
        if (id.scheme == P) return@io parentLookup(id)
        val claimed = docState.parent(id.value)
        if (claimed != null) {
            val listed = try {
                listShared(ItemId(claimed), partnerOnly = true).items.any { it.id == id }
            } catch (_: java.io.FileNotFoundException) { false } // the claimed parent is no longer shared
            if (listed) return@io ParentLookup.Found(ItemId(claimed))
            docState.removeParent(id.value)
        }
        val parent = crawl(id)
        if (parent == null) { docState.markGone(id.value); return@io ParentLookup.Gone }
        sharedParents[id.value] = parent
        ParentLookup.Found(ItemId(parent))
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

    // ---------- cleanup scan (duplicates / large files) ----------

    override val label = "Filen Drive"

    /** Filen stores a content hash in each file's metadata, so duplicates need no downloading. Older files may lack it. */
    override val hasher = object : ContentHasher {
        override suspend fun quick(item: FileItem): String? = null
        override suspend fun full(item: FileItem): String? = null
    }

    override suspend fun scanFiles(onProgress: (Int) -> Unit): List<ScanFile> = io {
        val all = timed("cleanup.scan") { client.listDirRecursive(AnyDirWithContext.Normal(AnyNormalDir.Root(client.root())), null) }
        val dirs = HashMap<String, Pair<String, String?>>() // uuid -> (name, parent uuid)
        all.dirs.forEach { (it as? NonRootDir.Normal)?.v1?.let { d -> dirs[d.uuid] = dirName(d.meta) to (d.parent as? ParentUuid.Uuid)?.v1 } }
        fun folderPath(start: String?): String {
            val parts = ArrayList<String>(); var cur = start; var guard = 0
            while (cur != null && guard++ < 64) { val d = dirs[cur] ?: break; parts.add(0, d.first); cur = d.second }
            return parts.joinToString("/")
        }
        val out = all.files.map { f ->
            val item = personalFileItem(f)
            val hash = (f.meta as? FileMeta.Decoded)?.v1?.hash?.joinToString("") { "%02x".format(it) }
            val folder = folderPath((f.parent as? ParentUuid.Uuid)?.v1)
            ScanFile(item, if (folder.isEmpty()) item.name else "$folder/${item.name}", hash)
        }
        onProgress(out.size)
        out
    }

    // ---------- file version history ----------

    private val versionObjs = ConcurrentHashMap<String, uniffi.filen_sdk_rs.FileVersion>()

    override fun canVersion(item: FileItem) = item.id.scheme == P && !item.isDirectory && item.id.key != ROOT

    override suspend fun versions(item: FileItem): List<VersionInfo> = io {
        require(canVersion(item)) { "No version history for this item" }
        val file = fileObj(item.id)
        // The SDK lists the current content too; only earlier versions are useful here.
        val list = client.listFileVersions(file).filter { it.uuid != file.uuid }
        list.forEach { versionObjs[it.uuid] = it }
        list.sortedByDescending { it.timestamp }.map { v ->
            VersionInfo(v.uuid, v.size.toLong(), v.timestamp, (v.metadata as? FileMeta.Decoded)?.v1?.name)
        }
    }

    override suspend fun restoreVersion(item: FileItem, version: VersionInfo): FileItem = io {
        val v = versionObjs[version.id] ?: error("Version list changed; reopen the history")
        val parent = indexParentOf(item.id)
        val f = client.restoreFileVersion(fileObj(item.id), v)
        indexRemoveSubtree(item.id) // the restored file may come back under a new id
        personalFileItem(f).also { indexUpsert(it, personalParentId(f.parent) ?: parent) }
    }

    override suspend fun deleteVersion(item: FileItem, version: VersionInfo): Unit = io {
        val v = versionObjs[version.id] ?: error("Version list changed; reopen the history")
        client.deleteFileVersion(v); versionObjs.remove(version.id)
    }

    override suspend fun quota(): Quota = io { client.getUserInfo().let { Quota(it.storageUsed.toLong(), it.maxStorage.toLong()) } }
    override suspend fun versioningEnabled(): Boolean = io { client.getUserInfo().versioningEnabled }
    override suspend fun setVersioningEnabled(enabled: Boolean): Unit = io { client.setVersioningEnabled(enabled) }

    private fun indexParentOf(id: ItemId): String? = synchronized(indexLock) { personalIndex.firstOrNull { it.item.id == id }?.parent }

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

    override suspend fun addContact(email: String) { io { client.sendContactRequest(email.trim()) } }

    // ---------- partner (Stack folder sharing: I4 F7-F9; contacts are the channel) ----------

    class ContactRequest(val uuid: String, val email: String, val nick: String? = null) {
        val person get() = Person(email, nick)
    }
    enum class Contacted { CONNECTED, REQUESTED }

    /** Everything the partner screens and `partner_status` need, from the server. */
    class PartnerLists(val contacts: List<Person>, val outgoing: List<Person>, val incoming: List<ContactRequest>)

    suspend fun incomingRequests(): List<ContactRequest> = io {
        client.listIncomingContactRequests().map { ContactRequest(it.uuid, it.email, it.nickName.takeIf(String::isNotBlank)) }
    }

    suspend fun partnerLists(): PartnerLists = io {
        PartnerLists(
            client.getContacts().map { Person(it.email, it.nickName?.takeIf(String::isNotBlank)) },
            client.listOutgoingContactRequests().map { Person(it.email, it.nickName.takeIf(String::isNotBlank)) },
            client.listIncomingContactRequests().map { ContactRequest(it.uuid, it.email, it.nickName.takeIf(String::isNotBlank)) },
        )
    }

    /** [saved] is the partner chosen earlier (see [Partner.decide]). */
    suspend fun partnerStatus(saved: String?): PartnerStatus = partnerLists().let { l ->
        Partner.decide(saved, l.contacts, l.outgoing, l.incoming.map { it.person })
    }

    suspend fun acceptRequest(request: ContactRequest): Unit = io { client.acceptContactRequest(request.uuid) }

    /** Make [email] a contact: accepts their pending request, or sends ours (once). */
    suspend fun connectTo(email: String): Contacted = io {
        val want = email.trim()
        require(want.isNotEmpty() && '@' in want) { "That is not an email address" }
        if (client.getContacts().any { it.email.equals(want, true) }) return@io Contacted.CONNECTED
        client.listIncomingContactRequests().firstOrNull { it.email.equals(want, true) }?.let {
            client.acceptContactRequest(it.uuid); return@io Contacted.CONNECTED
        }
        if (client.listOutgoingContactRequests().none { it.email.equals(want, true) }) client.sendContactRequest(want)
        Contacted.REQUESTED
    }

    private val outShareCache = OutShareCache()

    /**
     * I4 F10: is the item [uuid] of the own drive shared with the partner [savedEmail]? Throws when it cannot be told
     * (offline); [ShareAnswer.NotFound] only when Filen says the item is not in the drive or is in the trash.
     */
    suspend fun shareState(uuid: String, savedEmail: String?): ShareAnswer = io {
        val file = client.getFileOptional(uuid)?.let { headOf(it) ?: return@io ShareAnswer.NotFound }
        val dir = if (file == null) client.getDirOptional(uuid) else null
        val parent = (file?.parent ?: dir?.parent) ?: return@io ShareAnswer.NotFound
        if (parent !is ParentUuid.Uuid) return@io ShareAnswer.NotFound
        val id = file?.uuid ?: uuid
        if (savedEmail == null) return@io ShareAnswer.Known(false)
        val ids = outShareCache.get(savedEmail) ?: run {
            val contact = client.getContacts().firstOrNull { it.email.equals(savedEmail.trim(), true) } ?: return@io ShareAnswer.Known(false)
            val out = client.listOutShared(contact)
            (out.dirs.map { it.inner.uuid } + out.files.map { it.uuid }).map(String::lowercase).toSet().also { outShareCache.put(savedEmail, it) }
        }
        if (ShareRules.isShared(id, ids)) return@io ShareAnswer.Known(true)
        if (ids.isEmpty()) return@io ShareAnswer.Known(false)
        // Not shared itself: is a folder above it? (bounded walk; the folders' names and parents are cached)
        val start = (parent as ParentUuid.Uuid).v1.takeIf { it != rootUuid }
        val via = Ancestors.nearestShared(start, ids) { u ->
            val e = dirCache[u] ?: run { personalDirItem(client.getDir(u)); dirCache[u]!! }
            e.name to e.parent?.takeIf { it != rootUuid }
        }
        ShareAnswer.Known(false, via)
    }

    /** What F11 would do for [name] and [email]; [recorded] is the uuid of the folder F11 made earlier for this caller (see [FolderReuse]). */
    suspend fun planSharedFolder(name: String, email: String, recorded: String?): FolderPlan = io {
        val contact = client.getContacts().firstOrNull { it.email.equals(email.trim(), true) } ?: error("That person is not a contact yet")
        val dirs = client.listDir(AnyNormalDir.Root(client.root())).dirs.map { personalDirItem(it) }
        val sharedIds = client.listOutShared(contact).dirs.map { it.inner.uuid }.toSet()
        FolderReuse.plan(name, recorded, dirs.map { it.id.key to it.name }, sharedIds)
    }

    class SharedFolder(val id: ItemId, val name: String, val created: Boolean)

    /**
     * I4 F11: the folder in the drive root shared with [email]. Only a folder this caller's earlier F11 call created
     * ([recorded]) is reused, and only while it is still in the drive root and shared with them; anything else, including
     * an existing folder that merely has the same name, gets a new folder ("[name] 2"... when the name is taken). An
     * existing private folder is never shared by this call.
     */
    suspend fun ensureSharedFolder(name: String, email: String, recorded: String?): SharedFolder = io {
        when (val plan = planSharedFolder(name, email, recorded)) {
            is FolderPlan.Reuse -> SharedFolder(ItemId("$P:${plan.uuid}"), plan.name, false)
            is FolderPlan.Create -> {
                val id = createFolder(ItemId("$P:$ROOT"), plan.name)
                outShareCache.clear()
                sharePartner(id, email, false)
                SharedFolder(id, plan.name, true)
            }
        }
    }

    /**
     * Shares the personal file or folder [id] with the contact [email] (once), or with [stop] stops sharing it with them.
     * Returns whether it is shared with them afterwards. The partner must already be a contact.
     */
    suspend fun sharePartner(id: ItemId, email: String, stop: Boolean): Boolean = io {
        outShareCache.clear()
        require(id.scheme == P && id.key != ROOT) { "Only an item in your own drive can be shared" }
        val contact = client.getContacts().firstOrNull { it.email.equals(email.trim(), true) } ?: error("That person is not a contact yet")
        val out = client.listOutShared(contact)
        val dirShared = out.dirs.any { it.inner.uuid == id.key }
        val fileShared = out.files.any { it.uuid == id.key }
        if (stop) {
            out.dirs.filter { it.inner.uuid == id.key }.forEach { client.removeSharedItem(SharedRootItem.Dir(it)) }
            out.files.filter { it.uuid == id.key }.forEach { client.removeSharedItem(SharedRootItem.File(it)) }
            outRoots.remove("$OUT:${id.key}")
            if (fileShared) stackEvent { if (!stillShared(id.key)) StackLink.unshared(context, id.key) }
            return@io false
        }
        if (!dirShared && !fileShared) {
            if (client.getDirOptional(id.key) != null) client.shareDir(dirObj(id), contact, null) else client.shareFile(fileObj(id), contact)
        }
        true
    }

    // ---------- public links ----------
    // The link key is part of the URL: it is handed to the UI for copying but never logged.

    override fun canLink(item: FileItem) = item.origin == Origin.FILEN_PERSONAL && item.id.key != ROOT

    private fun expiryOf(e: uniffi.filen_types.PublicLinkExpiration) = LinkExpiry.valueOf(e.name)
    private fun sdkExpiry(e: LinkExpiry) = uniffi.filen_types.PublicLinkExpiration.valueOf(e.name)
    private fun passwordOf(p: PasswordState, change: PasswordChange): PasswordState = when (change) {
        PasswordChange.Keep -> p
        PasswordChange.Remove -> PasswordState.None
        is PasswordChange.Set -> PasswordState.Known(change.password)
    }
    private fun hasPassword(p: PasswordState) = p !is PasswordState.None

    private fun fileLinkInfo(f: FFile, l: uniffi.filen_sdk_rs.FilePublicLink) = LinkInfo(
        url = (f.meta as? FileMeta.Decoded)?.v1?.key?.let { LinkUrl.build(false, l.linkUuid, it) },
        expiry = expiryOf(l.expiration), expiresAt = l.expiresAt, hasPassword = hasPassword(l.password), downloadable = l.downloadable,
    )

    private fun dirLinkInfo(l: uniffi.filen_sdk_rs.DirPublicLinkRw) = LinkInfo(
        url = l.linkKey?.let { LinkUrl.build(true, l.linkUuid, it) },
        expiry = expiryOf(l.expiration), expiresAt = l.expiresAt, hasPassword = hasPassword(l.password), downloadable = l.enableDownload,
    )

    override suspend fun linkOf(item: FileItem): LinkInfo? = io {
        require(canLink(item)) { "This item cannot have a public link" }
        if (item.isDirectory) client.getDirLinkStatus(dirObj(item.id))?.let(::dirLinkInfo)
        else fileObj(item.id).let { f -> client.getFileLinkStatus(f)?.let { fileLinkInfo(f, it) } }
    }

    override suspend fun saveLink(item: FileItem, settings: LinkSettings): LinkInfo = io {
        require(canLink(item)) { "This item cannot have a public link" }
        if (item.isDirectory) {
            val d = dirObj(item.id)
            val link = client.getDirLinkStatus(d) ?: client.publicLinkDir(d, null)
            link.expiration = sdkExpiry(settings.expiry); link.password = passwordOf(link.password, settings.password)
            link.enableDownload = settings.downloadable
            dirLinkInfo(client.updateDirLink(d, link))
        } else {
            val f = fileObj(item.id)
            val link = client.getFileLinkStatus(f) ?: client.publicLinkFile(f)
            link.expiration = sdkExpiry(settings.expiry); link.password = passwordOf(link.password, settings.password)
            link.downloadable = settings.downloadable
            fileLinkInfo(f, client.updateFileLink(f, link))
        }
    }

    override suspend fun removeLink(item: FileItem): Unit = io {
        require(canLink(item)) { "This item cannot have a public link" }
        if (item.isDirectory) client.removeDirLink(dirObj(item.id))
        else { val f = fileObj(item.id); client.getFileLinkStatus(f)?.let { client.removeFileLink(f, it) } }
        if (!item.isDirectory) stackEvent { if (!stillShared(item.id.key)) StackLink.unshared(context, item.id.key) }
    }

    override suspend fun links(): List<LinkedItem> = io {
        val all = client.listLinkedItems()
        buildList {
            for (d in all.dirs) client.getDirLinkStatus(d)?.let { add(LinkedItem(personalDirItem(d), dirLinkInfo(it))) }
            for (f in all.files) client.getFileLinkStatus(f)?.let { add(LinkedItem(personalFileItem(f), fileLinkInfo(f, it))) }
        }
    }

    override suspend fun unshare(item: FileItem): Unit = io {
        val roots = outRoots[item.id.value] ?: error("This share is no longer listed; reload and try again")
        roots.forEach { client.removeSharedItem(it) }
        outRoots.remove(item.id.value); sharedDirs.remove(item.id.value); sharedFiles.remove(item.id.value)
        if (roots.any { it is SharedRootItem.File }) stackEvent { if (!stillShared(item.id.key)) StackLink.unshared(context, item.id.key) }
    }

    // ---------- stat / favorites / recents ----------

    /**
     * The folder an item lives in, asked from Filen when not cached (null for a root or an unknown item). Lets the
     * documents provider check "is X inside the folder an app was given" after a cold start, at any depth.
     */
    suspend fun parentOf(id: ItemId): ItemId? = io {
        if (id.key == ROOT) return@io null
        if (id.scheme == P) {
            val p = personalFiles[id.key]?.parent ?: client.getFileOptional(id.key)?.parent ?: client.getDirOptional(id.key)?.parent
            return@io p?.let(::personalParentId)?.let(::ItemId)
        }
        if (sharedParents[id.value] == null) runCatching { resolveShared(id) }
        sharedParents[id.value]?.let(::ItemId)
    }

    override suspend fun stat(id: ItemId): FileItem? = io {
        if (id.scheme == P) {
            if (id.key == ROOT) return@io null
            personalFiles[id.key]?.let { return@io personalFileItem(it) }
            // An offline copy answers from the pin store: no network round trip (the DocumentsProvider stats before it opens).
            pins.offline().firstOrNull { it.version == id.key }?.let { p ->
                return@io FileItem(id, p.name, false, p.size, p.modified, mimeOf(p.name, p.mime), Origin.FILEN_PERSONAL)
            }
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

    override suspend fun freshStat(id: ItemId): FileItem? {
        if (id.scheme != P || id.key == ROOT) return stat(id)
        return io {
            val f = client.getFileOptional(id.key)
            if (f == null) return@io client.getDirOptional(id.key)?.takeIf { it.parent !is ParentUuid.Trash }?.let(::personalDirItem)
            // A replaced version still answers to its old uuid, so ask for the file's current one.
            val head = f.stableUuid?.let { client.getFileByStableUuidOptional(it) } ?: f
            head.takeIf { it.uuid == id.key && it.parent !is ParentUuid.Trash }?.let(::personalFileItem)
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
        listOf(dirCache, personalFiles, personalDirObjs, sharedDirs, sharedFiles, trashedFiles, trashedDirs, outRoots, contactMap).forEach { it.clear() }
        docState.clear(); outShareCache.clear(); crawlGuard.clear(); crawlBudget.clear(); outScope.clear(); verifiedParents.clear()
        indexFile().delete()
        pins.wipe()
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

    // ---------- Stack link (docs/STACK_LINK_CONTRACT.md): events out, lookups in ----------

    private val stackScope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob() + Dispatchers.IO)

    /** Runs [block] off the caller's path; any failure is swallowed so a file operation never depends on Stack. */
    private fun stackEvent(block: suspend () -> Unit) { stackScope.launch { runCatching { block() } } }

    private fun stackNames(item: FileItem) = !item.isDirectory && item.id.scheme == P && item.id.key != ROOT

    /** "/Folder/Sub/name" of a personal file after an operation (null if unknown). Folders are never named to Stack. */
    private suspend fun filePath(uuid: String): String? {
        val f = personalFiles[uuid] ?: return null
        val parent = (f.parent as? ParentUuid.Uuid)?.v1 ?: return null
        val crumbs = personalTrail(parent, rootUuid)
        return "/" + (crumbs.drop(1).map { it.name } + fileInfo(f.meta, f.uuid).name).joinToString("/")
    }

    private fun notifyMoved(item: FileItem) {
        if (!stackNames(item)) return
        stackEvent { filePath(item.id.key)?.let { StackLink.moved(context, item.id.key, it) } }
    }

    private val MAX_FILES = 300
    private val MAX_FOLDERS = 80

    /** UUIDs of the files under [dir] (cached as a side effect), capped: at most [MAX_FILES] files and [MAX_FOLDERS] folder listings. */
    private suspend fun filesUnder(dir: Dir, out: MutableList<String> = ArrayList()): List<String> {
        val queue = ArrayDeque<Dir>().apply { add(dir) }
        var lists = 0
        while (queue.isNotEmpty() && lists++ < MAX_FOLDERS && out.size < MAX_FILES) {
            val res = runCatching { client.listDir(AnyNormalDir.Dir(queue.removeFirst())) }.getOrNull() ?: continue
            res.files.forEach { if (out.size < MAX_FILES) { personalFileItem(it); out.add(it.uuid) } }
            res.dirs.forEach { personalDirItem(it); queue.add(it) }
        }
        return out
    }

    /** A folder moved, renamed or restored: its files' paths changed, so tell Stack for each (capped, one after another). */
    private fun notifyFolderMoved(folder: FileItem) {
        if (!folder.isDirectory || folder.id.scheme != P || !StackLink.available(context)) return
        stackEvent {
            val d = personalDirObjs[folder.id.key] ?: return@stackEvent
            for (uuid in filesUnder(d)) { filePath(uuid)?.let { StackLink.moved(context, uuid, it) }; kotlinx.coroutines.yield() }
        }
    }

    /**
     * The files under a folder, listed BEFORE it is trashed (a trashed folder can no longer be listed). Bounded to a few
     * seconds so a slow network never holds up the trash; whatever was found by then is used, the rest is caught by Stack's next stat.
     */
    private suspend fun filesBeforeTrash(folder: FileItem): List<String> {
        if (!folder.isDirectory || folder.id.scheme != P || !StackLink.available(context)) return emptyList()
        val d = personalDirObjs[folder.id.key] ?: return emptyList()
        val found = ArrayList<String>()
        kotlinx.coroutines.withTimeoutOrNull(3_000) { runCatching { filesUnder(d, found) } }
        return found.toList()
    }

    private fun notifyRemoved(item: FileItem, reason: String) {
        if (item.id.scheme == P && !item.isDirectory) stackEvent { StackLink.removed(context, item.id.key, reason) }
    }

    /** Whether others can still reach a personal item: a public link, or an outgoing share to a contact. */
    private suspend fun stillShared(uuid: String): Boolean {
        client.getFileOptional(uuid)?.let { f ->
            if (client.getFileLinkStatus(f) != null) return true
        }
        val out = client.listOutShared(null)
        return out.files.any { it.uuid == uuid } || out.dirs.any { it.inner.uuid == uuid }
    }

    /** What Stack's `lookup(uuid)` answers. [path] is null for a trashed or missing item. */
    class StackLookup(val exists: Boolean, val path: String?, val trashed: Boolean, val shared: Boolean)

    suspend fun stackLookup(uuid: String): StackLookup = io {
        // A uuid Stack stored before the file was saved again names an older version: answer for the file as it is now.
        val f = headOf(client.getFileOptional(uuid) ?: return@io StackLookup(false, null, false, false))
            ?: return@io StackLookup(false, null, false, false)
        personalFileItem(f)
        if (f.parent is ParentUuid.Trash) return@io StackLookup(true, null, true, false)
        StackLookup(true, filePath(f.uuid), false, runCatching { stillShared(f.uuid) }.getOrDefault(false))
    }

    /** The current version of [f]'s file (itself if it is current); null when the file is gone. Asked of Filen. */
    private suspend fun headOf(f: FFile): FFile? {
        val stable = f.stableUuid ?: return f
        return client.getFileByStableUuidOptional(stable)
    }

    /** What a document id names now: the current version of its file, whichever version's uuid the id holds. */
    class Head(val item: FileItem, val trashed: Boolean)

    /** [id] resolved through its file's versions to the current one, asked of Filen (no cache); null when gone. */
    suspend fun currentHead(id: ItemId): Head? = io {
        if (id.scheme != P || id.key == ROOT) return@io null
        val row = client.getFileOptional(id.key)
        // A folder's id is never looked up as a file's whole-life id: how the server answers that is unknown.
        if (row == null && client.getDirOptional(id.key) != null) return@io null
        // The first version's uuid IS the whole-life id, so an id still resolves after that version's row is gone.
        val head = (if (row != null) headOf(row) else client.getFileByStableUuidOptional(id.key)) ?: return@io null
        Head(personalFileItem(head), head.parent is ParentUuid.Trash)
    }

    /** The current version of the file whose whole-life id is [lineage]; null only when Filen no longer knows it. */
    suspend fun headOfLineage(lineage: String): Head? = io {
        client.getFileByStableUuidOptional(lineage)?.let { Head(personalFileItem(it), it.parent is ParentUuid.Trash) }
    }

    /** Folder of the current version of [id]'s file (a superseded version's row may not say). */
    suspend fun currentParent(id: ItemId): ItemId? = io {
        val head = client.getFileOptional(id.key)?.let { headOf(it) } ?: return@io parentOf(id)
        (head.parent as? ParentUuid.Uuid)?.v1?.let { if (it == rootUuid) ItemId("$P:$ROOT") else ItemId("$P:$it") }
    }

    /**
     * Uploads [localPath] as [name] into [parent] so that it REPLACES the file holding that name there (Filen keeps the
     * replaced content as an earlier version when versioning is on). Only for saving over a file: callers check the
     * file and versioning first ([app.filenfiles.core.SaveOver]). Returns the new version and the version it replaced
     * per the version list (null when that can't be told).
     */
    suspend fun uploadVersion(parent: ItemId, localPath: String, name: String, mime: String?, onProgress: (Long) -> Unit): Pair<FileItem, String?> = io {
        var sent = 0L
        val cb = object : JsFileUploadCallback {
            override fun onUpdate(uploadedBytes: ULong) { sent += uploadedBytes.toLong(); onProgress(sent) }
        }
        val f = client.uploadFile(FileBuilderParamsOptionalName(personalDir(parent), name, null, null, mime), localPath, cb, ManagedFuture(null, null))
        val item = personalFileItem(f).also { indexUpsert(it, parent.normalizedParent()) }
        val previous = runCatching {
            client.listFileVersions(f).filter { it.uuid != f.uuid }.maxByOrNull { it.timestamp }?.uuid
        }.getOrNull()
        item to previous
    }


    /** `locate(uri)`: the uuid and path of a Filen document id (`filen:<uuid>` or `filen-in:<uuid>`). */
    suspend fun stackPath(id: ItemId): String? = io {
        if (id.scheme != P) return@io null
        client.getFileOptional(id.key)?.let { personalFileItem(it) }
        filePath(id.key)
    }

    // ---------- write (personal drive only: received shares are never written to) ----------

    override fun canWrite(folder: ItemId) = folder.scheme == P

    override fun nameProblem(name: String): String? = FilenNames.problem(name)

    private suspend fun personalDir(id: ItemId): AnyNormalDir {
        require(id.scheme == P) { "Uploads are only supported into your personal Filen drive" }
        val root = rootUuid
        return if (id.key == ROOT || id.key == root) AnyNormalDir.Root(client.root()) else AnyNormalDir.Dir(client.getDir(id.key))
    }

    override suspend fun createFolder(parent: ItemId, name: String): ItemId = io {
        val d = client.createDir(personalDir(parent), name)
        personalDirItem(d).also { indexUpsert(it, parent.normalizedParent()) }.id
    }

    /** Uploads under their temporary name that were not yet named (on disk: a crash or kill must not strand them). */
    private val journal by lazy { UploadJournal(JFile(context.filesDir, "upload-names")) }
    private val uploadLocks = ConcurrentHashMap<String, Mutex>()
    private fun account(): String = (session.state.value as? FilenSession.State.LoggedIn)?.email ?: ""

    /**
     * Always creates a NEW file. Filen replaces a same-name file on upload (for good with versioning off), so the
     * content goes up under a name of its own and is then renamed; a taken name becomes "name (1)" (see [SafeCreate]).
     * The returned item carries the name it really got. If the rename fails, the file stays under its upload name
     * until a retry of the same upload reuses that name, or [recoverUploads] names it.
     */
    override suspend fun upload(parent: ItemId, localPath: String, name: String, mime: String?, onProgress: (Long) -> Unit): FileItem =
        io {
            FilenNames.problem(name)?.let { throw java.io.IOException("Filen can't store “$name”. $it") } // before any upload
            val tempName = SafeCreate.tempName(uploadDigest(parent.normalizedParent(), name, JFile(localPath)), name)
            uploadLocks.getOrPut(tempName) { Mutex() }.withLock {
                val f = renamedEarlier(tempName) ?: createUnder(tempName, parent, localPath, name, mime, onProgress)
                journal.remove(tempName)
                personalFileItem(f).also { indexUpsert(it, parent.normalizedParent()) }
            }
        }

    private suspend fun createUnder(tempName: String, parent: ItemId, localPath: String, name: String, mime: String?, onProgress: (Long) -> Unit): FFile {
        var sent = 0L
        val cb = object : JsFileUploadCallback {
            // `uploadedBytes` is a delta since the previous call
            override fun onUpdate(uploadedBytes: ULong) { sent += uploadedBytes.toLong(); onProgress(sent) }
        }
        val dir = personalDir(parent)
        val params = FileBuilderParamsOptionalName(dir, tempName, null, null, mime)
        // Written first: if the process dies after the server stored the file but before we hear of it, it can be found.
        val entry = UploadJournal.Entry(tempName, parent.value, name, account())
        journal.put(entry)
        val uploaded = client.uploadFile(params, localPath, cb, ManagedFuture(null, null))
        journal.put(entry.copy(uuid = uploaded.uuid))
        // The content is up: a cancel now must not leave it under the upload name, so the naming always finishes.
        return withContext(NonCancellable) { claim(uploaded, name, dir) }
    }

    private suspend fun claim(f: FFile, name: String, dir: AnyNormalDir): FFile = SafeCreate.claimName(
        name,
        takenNames = { client.listDir(dir).files.map { fileInfo(it.meta, it.uuid).name } },
        isTaken = { it.isNameTaken() },
    ) { candidate -> client.updateFileMetadata(f, FileMetaChanges(name = candidate, created = CreatedTime.Keep)) }

    /** The file an earlier try uploaded as [tempName], if its rename did reach Filen although we saw a failure. */
    private suspend fun renamedEarlier(tempName: String): FFile? {
        val e = journal[tempName]?.takeIf { it.account == account() } ?: return null
        val f = client.getFileOptional(e.uuid ?: return null) ?: return null
        val current = fileInfo(f.meta, f.uuid).name
        if (f.parent is ParentUuid.Trash || current == tempName) return null
        // The failure may have come after the rename but before shares and links were told the new name: say it again.
        return runCatching { client.updateFileMetadata(f, FileMetaChanges(name = current, created = CreatedTime.Keep)) }.getOrDefault(f)
    }

    /**
     * Gives files left under their temporary upload name (after a crash, a kill, a cancel during the upload or a failed
     * rename) the name they were uploaded for, "name (1)" if it is taken. Never deletes anything: a file that can't be
     * named now stays as it is and is tried again next time. Call when no upload is running. Returns how many were named.
     */
    suspend fun recoverUploads(): Int = io {
        val me = account().ifEmpty { return@io 0 }
        var named = 0
        for (e0 in journal.all().filter { it.account == me }) {
            runCatching {
                uploadLocks.getOrPut(e0.tempName) { Mutex() }.withLock {
                    val e = journal[e0.tempName] ?: return@withLock
                    val dir = personalDir(ItemId(e.parent))
                    val uuid = e.uuid
                    val f = if (uuid != null) client.getFileOptional(uuid)
                    else client.listDir(dir).files.firstOrNull { fileInfo(it.meta, it.uuid).name == e.tempName }
                    // Gone, trashed, or already named (by the user or a lost reply): nothing left to do.
                    if (f == null || f.parent is ParentUuid.Trash || fileInfo(f.meta, f.uuid).name != e.tempName) { journal.remove(e.tempName); return@withLock }
                    val out = withContext(NonCancellable) { claim(f, e.name, dir) }
                    // The entry stays (now pointing at a named file) so a Retry of that transfer in this session reuses
                    // the file instead of uploading a "(1)"; the next pass drops it.
                    personalFileItem(out).also { indexUpsert(it, ItemId(e.parent).normalizedParent()) }
                    named++
                }
            }.onFailure { android.util.Log.w("FilenUpload", "Couldn't name a leftover upload yet: ${it.message}") }
        }
        named
    }

    /** Per install, so two devices uploading the same file to the same place don't share an upload name. */
    private val uploadSalt: String by lazy {
        val p = context.getSharedPreferences("upload_names", Context.MODE_PRIVATE)
        p.getString("salt", null) ?: java.util.UUID.randomUUID().toString().also { p.edit().putString("salt", it).commit() }
    }

    private suspend fun uploadDigest(parent: String, name: String, file: JFile): ByteArray {
        val digest = MessageDigest.getInstance("SHA-256")
        digest.update("$uploadSalt\u0000$parent\u0000$name\u0000".toByteArray())
        file.inputStream().use { input ->
            val buf = ByteArray(1 shl 16)
            while (true) { kotlin.coroutines.coroutineContext.ensureActive(); val n = input.read(buf); if (n < 0) break; digest.update(buf, 0, n) }
        }
        return digest.digest()
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
        indexUpsert(out, null); notifyMoved(out); notifyFolderMoved(out); out
    }

    override suspend fun move(item: FileItem, newParent: ItemId): FileItem = io {
        require(canModify(item)) { "This item cannot be moved" }
        val parent = personalDir(newParent)
        if (!item.isDirectory) {
            // Filen does not refuse this: the file already there would stop being current (gone with versioning off).
            // It refuses folders itself. Another device could still add the name between this check and the move.
            val now = client.getFile(item.id.key) // the cached name may be older than a rename made elsewhere
            val name = fileInfo(now.meta, now.uuid).name
            val clash = client.listDir(parent).files.any { it.uuid != item.id.key && fileInfo(it.meta, it.uuid).name.lowercase() == name.lowercase() }
            if (clash) throw java.io.IOException("“$name” already exists in that folder. Rename one of them first.")
        }
        val out = if (item.isDirectory) personalDirItem(client.moveDir(dirObj(item.id), parent))
        else personalFileItem(client.moveFile(fileObj(item.id), parent))
        indexUpsert(out, newParent.normalizedParent()); notifyMoved(out); notifyFolderMoved(out); out
    }

    override suspend fun trash(item: FileItem): (suspend () -> Unit)? = io {
        require(canModify(item)) { "This item cannot be deleted" }
        val inside = filesBeforeTrash(item)
        val undo: suspend () -> Unit = if (item.isDirectory) {
            val t = client.trashDir(dirObj(item.id))
            ({ io { checkRestorable(dirName(t.meta), t.parent); val di = personalDirItem(client.restoreDir(t)); indexUpsert(di, null); indexMarkDirty(); notifyFolderMoved(di) } })
        } else {
            val t = client.trashFile(fileObj(item.id))
            ({ io { checkRestorable(fileInfo(t.meta, t.uuid).name, t.parent); val f = client.restoreFile(t); val fi = personalFileItem(f); indexUpsert(fi, personalParentId(f.parent)); notifyMoved(fi) } })
        }
        indexRemoveSubtree(item.id)
        notifyRemoved(item, "trashed")
        if (inside.isNotEmpty()) stackEvent { inside.forEach { StackLink.removed(context, it, "trashed"); kotlinx.coroutines.yield() } }
        undo
    }

    // ---------- trash ----------

    private val trashedFiles = ConcurrentHashMap<String, FFile>()
    private val trashedDirs = ConcurrentHashMap<String, Dir>()

    /**
     * Restore puts an item back in the folder it came from. What the server does when that folder now holds an item of
     * the same name (ignoring case) is unknown, and it might replace it, so such a restore is refused, as is one whose
     * folder can't be checked. A race with another device between this check and the restore remains.
     */
    private suspend fun checkRestorable(name: String, parent: ParentUuid) {
        val from = (parent as? ParentUuid.Trash)?.v1?.takeIf { it.isNotEmpty() && it != NIL_UUID }
            ?: throw java.io.IOException("Couldn't tell which folder “$name” came from, so it was not restored (to be safe). Restore it in the Filen web app.")
        val dir = if (from == rootUuid) AnyNormalDir.Root(client.root())
        else AnyNormalDir.Dir(client.getDirOptional(from)?.takeIf { it.parent !is ParentUuid.Trash }
            ?: throw java.io.IOException("The folder “$name” came from is gone or in the trash. Restore that folder first."))
        val l = client.listDir(dir)
        val key = name.lowercase()
        if (l.files.any { fileInfo(it.meta, it.uuid).name.lowercase() == key } || l.dirs.any { dirName(it.meta).lowercase() == key }) {
            throw java.io.IOException("“$name” was not restored: its folder now holds an item with that name. Rename or move that one first.")
        }
    }

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
            val t = trashedDirs[item.id.key] ?: error("Trash changed; reload it")
            checkRestorable(dirName(t.meta), t.parent)
            val d = client.restoreDir(t)
            val di = personalDirItem(d); indexUpsert(di, personalParentId(d.parent)); indexMarkDirty() /* its subtree comes back too */; notifyFolderMoved(di)
        } else {
            val t = trashedFiles[item.id.key] ?: error("Trash changed; reload it")
            checkRestorable(fileInfo(t.meta, t.uuid).name, t.parent)
            val f = client.restoreFile(t)
            val fi = personalFileItem(f); indexUpsert(fi, personalParentId(f.parent)); notifyMoved(fi)
        }
        trashedDirs.remove(item.id.key); trashedFiles.remove(item.id.key)
    }

    override suspend fun deletePermanently(item: FileItem): Unit = io {
        if (item.isDirectory) client.deleteDirPermanently(trashedDirs[item.id.key] ?: error("Trash changed; reload it"))
        else client.deleteFilePermanently(trashedFiles[item.id.key] ?: error("Trash changed; reload it"))
        trashedDirs.remove(item.id.key); trashedFiles.remove(item.id.key)
        notifyRemoved(item, "deleted")
    }

    override suspend fun emptyTrash(): Unit = io {
        val gone = trashedFiles.keys.toList() // as of the last trash listing; anything else is caught by Stack's next stat
        client.emptyTrash(); trashedDirs.clear(); trashedFiles.clear()
        stackEvent { gone.forEach { StackLink.removed(context, it, "deleted") } }
    }

    // ---------- download ----------

    override suspend fun materialize(item: FileItem, onProgress: (Long, Long) -> Unit): MaterializedFile =
        io {
            if (item.id.scheme == P) pins.copyOf(item.id.key)?.let { (_, f) -> return@io MaterializedFile(f.absolutePath, item.name, item.mime) }
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

    /** Downloads straight into [dest] (no cache copy): `.part` file, size check, atomic rename, cleanup on failure. */
    override suspend fun download(item: FileItem, dest: JFile, onProgress: (Long) -> Unit): Unit = io {
        val dir = dest.absoluteFile.parentFile!!.apply { mkdirs() }
        if (dir.usableSpace < item.size + LocalCache.FREE_MARGIN) {
            throw java.io.IOException("No space left on device (need ${item.size shr 20} MB, ${dir.usableSpace shr 20} MB free)")
        }
        val any: AnyFile = if (item.id.scheme == P) {
            AnyFile.File(personalFiles[item.id.key] ?: client.getFile(item.id.key))
        } else {
            (sharedFiles[item.id.value] ?: run { stat(item.id); sharedFiles[item.id.value] })?.any
                ?: error("Shared file is no longer available; reopen it from the Shared tab.")
        }
        val tmp = JFile(dir, dest.name + ".part")
        try {
            retryNetwork { attempt ->
                tmp.delete() // the SDK restarts from byte 0
                var done = 0L
                if (attempt > 0) onProgress(0)
                val cb = object : JsFileDownloadCallback {
                    override fun onUpdate(downloadedBytes: ULong) { done += downloadedBytes.toLong(); onProgress(done) }
                }
                client.downloadFileToPath(any, tmp.absolutePath, cb, ManagedFuture(null, null))
            }
            check(tmp.length() == item.size) { "Download was incomplete (${tmp.length()} of ${item.size} bytes). Try again." }
            check(!dest.exists()) { "“${dest.name}” appeared while downloading. Try again." }
            check(tmp.renameTo(dest)) { "Could not finalise download" }
        } finally {
            tmp.delete()
            dir.listFiles()?.filter { it.name.startsWith(dest.name) && LocalCache.isTemp(it) }?.forEach { it.delete() } // SDK scratch for THIS file only
        }
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
        private const val NIL_UUID = "00000000-0000-0000-0000-000000000000"
        /** Own edits patch the index in place, so these only bound drift from changes made elsewhere (web, other devices). */
        private const val PERSONAL_TTL = 6 * 3_600_000L
        private const val SHARED_TTL = 24 * 3_600_000L
        private const val SHARED_CRAWL_BUDGET = 3000
        private const val SHARED_CRAWL_MAX_MS = 120_000L
        private const val MAX_SEARCH_FOLDERS = 400
        /** Wall time cap of one top-down search for a shared item. */
        private const val MAX_SEARCH_MS = 20_000L
        private const val SEARCH_WAIT_MS = 5_000L
    }
}
