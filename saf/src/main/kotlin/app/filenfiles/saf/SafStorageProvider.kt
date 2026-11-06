package app.filenfiles.saf

import android.content.Context
import android.content.Intent
import android.database.Cursor
import android.graphics.Bitmap
import android.graphics.Point
import android.net.Uri
import android.provider.DocumentsContract
import android.provider.DocumentsContract.Document
import android.webkit.MimeTypeMap
import app.filenfiles.core.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap

/**
 * Cloud and other storage reached through Android's document providers (Storage Access Framework): the user picks a
 * folder once in the system picker - for example "My Drive" of the Google Drive app, or a Dropbox/OneDrive/Nextcloud
 * folder - and this app keeps a persistable permission for that tree. No accounts, keys or Google/Dropbox APIs are
 * involved; the provider's own app does the talking.
 *
 * Item id: `saf:<tree uri, url-encoded>|<document id, url-encoded>`.
 * Google Docs/Sheets/Slides are "virtual documents": they have no file content, only exports, so they are opened as a
 * PDF (or the provider's first export type) and are read-only.
 */
class SafStorageProvider(
    private val context: Context,
    /** Called after a file was copied into the app's cache, so the caller can trim the cache to its size limit. */
    private val onCached: (File) -> Unit = {},
) : StorageProvider, WritableStorage {

    data class Root(val tree: Uri, val name: String)

    private val resolver get() = context.contentResolver
    private val prefs = context.getSharedPreferences("saf", Context.MODE_PRIVATE)
    @Volatile private var roots: List<Root> = loadRoots()

    private val flags = ConcurrentHashMap<String, Int>()
    private val parents = ConcurrentHashMap<String, String>()
    private val names = ConcurrentHashMap<String, String>()

    // ---------- ids ----------

    private fun id(tree: Uri, docId: String) = ItemId("$SCHEME:${Uri.encode(tree.toString())}|${Uri.encode(docId)}")
    private fun treeOf(id: ItemId): Uri = Uri.parse(Uri.decode(id.key.substringBefore('|')))
    private fun docIdOf(id: ItemId): String = Uri.decode(id.key.substringAfter('|'))
    private fun docUri(id: ItemId): Uri = DocumentsContract.buildDocumentUriUsingTree(treeOf(id), docIdOf(id))
    private fun rootId(tree: Uri) = id(tree, DocumentsContract.getTreeDocumentId(tree))
    private fun isRoot(id: ItemId) = docIdOf(id) == DocumentsContract.getTreeDocumentId(treeOf(id))

    override fun owns(id: ItemId) = id.scheme == SCHEME

    // ---------- roots (the folders the user connected) ----------

    private fun loadRoots(): List<Root> {
        val granted = context.contentResolver.persistedUriPermissions.filter { it.isReadPermission }.map { it.uri }.toSet()
        return runCatching {
            val a = JSONArray(prefs.getString("roots", "[]"))
            (0 until a.length()).map { a.getJSONObject(it) }.map { Root(Uri.parse(it.getString("tree")), it.getString("name")) }
                .filter { it.tree in granted } // permission revoked in system settings: forget it
        }.getOrDefault(emptyList())
    }

    private fun saveRoots() {
        prefs.edit().putString("roots", JSONArray(roots.map { JSONObject().put("tree", it.tree.toString()).put("name", it.name) }).toString()).apply()
    }

    /** Connects the folder tree [tree] returned by the system picker (ACTION_OPEN_DOCUMENT_TREE). Returns the new root's id. */
    suspend fun addRoot(tree: Uri): ItemId = withContext(Dispatchers.IO) {
        runCatching { resolver.takePersistableUriPermission(tree, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION) }
            .recoverCatching { resolver.takePersistableUriPermission(tree, Intent.FLAG_GRANT_READ_URI_PERMISSION) }.getOrThrow()
        val docId = DocumentsContract.getTreeDocumentId(tree)
        val display = queryOne(tree, docId)?.name ?: "Cloud storage"
        val app = runCatching { context.packageManager.resolveContentProvider(tree.authority!!, 0)?.loadLabel(context.packageManager)?.toString() }.getOrNull()
        val name = if (app != null && !display.contains(app, ignoreCase = true)) "$app – $display" else display
        if (roots.none { it.tree == tree }) { roots = roots + Root(tree, name); saveRoots() }
        names[rootId(tree).value] = name
        rootId(tree)
    }

    /** Disconnects a root; the files stay where they are, this app just loses access. */
    fun removeRoot(rootId: ItemId) {
        val tree = treeOf(rootId)
        runCatching { resolver.releasePersistableUriPermission(tree, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION) }
        roots = roots.filter { it.tree != tree }; saveRoots()
        listOf(flags, parents, names).forEach { m -> m.keys.filter { it.startsWith("$SCHEME:${Uri.encode(tree.toString())}|") }.forEach(m::remove) }
    }

    override suspend fun roots(): List<Folder> = withContext(Dispatchers.IO) {
        roots.map { r ->
            val rid = rootId(r.tree)
            names[rid.value] = r.name
            // Learn whether the root accepts new files (drives the New folder / Upload buttons).
            runCatching { queryOne(r.tree, DocumentsContract.getTreeDocumentId(r.tree)) }
            Folder(rid, r.name, Origin.EXTERNAL)
        }
    }

    // ---------- querying ----------

    private val PROJECTION = arrayOf(Document.COLUMN_DOCUMENT_ID, Document.COLUMN_DISPLAY_NAME, Document.COLUMN_MIME_TYPE,
        Document.COLUMN_SIZE, Document.COLUMN_LAST_MODIFIED, Document.COLUMN_FLAGS)

    private fun toItem(tree: Uri, c: Cursor): FileItem {
        val docId = c.getString(0)
        val mime = c.getString(2)
        val isDir = mime == Document.MIME_TYPE_DIR
        val fl = c.getInt(5)
        val virtual = fl and Document.FLAG_VIRTUAL_DOCUMENT != 0
        var name = c.getString(1) ?: docId
        val shownMime = if (virtual) exportMime(tree, docId) else mime
        // An exported Google Doc needs an extension so other apps recognise what they get.
        if (virtual && !name.contains('.')) MimeTypeMap.getSingleton().getExtensionFromMimeType(shownMime)?.let { name = "$name.$it" }
        val item = FileItem(
            id(tree, docId), name, isDir, if (isDir || c.isNull(3)) 0 else c.getLong(3), if (c.isNull(4)) 0 else c.getLong(4),
            if (isDir) null else shownMime?.takeIf { it != "application/octet-stream" } ?: MimeTypeMap.getSingleton().getMimeTypeFromExtension(name.substringAfterLast('.', "").lowercase()) ?: shownMime,
            Origin.EXTERNAL,
            // Exports of Google's own documents can't be written back.
            if (virtual || fl and (Document.FLAG_SUPPORTS_WRITE or Document.FLAG_DIR_SUPPORTS_CREATE) == 0) Permission.READ_ONLY else Permission.READ_WRITE,
        )
        flags[item.id.value] = fl
        names[item.id.value] = name
        return item
    }

    private fun exportMime(tree: Uri, docId: String): String {
        val uri = DocumentsContract.buildDocumentUriUsingTree(tree, docId)
        val types = runCatching { resolver.getStreamTypes(uri, "*/*") }.getOrNull().orEmpty()
        return types.firstOrNull { it == "application/pdf" } ?: types.firstOrNull() ?: "application/pdf"
    }

    private fun queryOne(tree: Uri, docId: String): FileItem? =
        resolver.query(DocumentsContract.buildDocumentUriUsingTree(tree, docId), PROJECTION, null, null, null)?.use { c ->
            if (c.moveToFirst()) toItem(tree, c) else null
        }

    override suspend fun stat(id: ItemId): FileItem? = withContext(Dispatchers.IO) { runCatching { queryOne(treeOf(id), docIdOf(id)) }.getOrNull() }

    override suspend fun list(folder: ItemId): Listing = withContext(Dispatchers.IO) {
        val tree = treeOf(folder); val docId = docIdOf(folder)
        val uri = DocumentsContract.buildChildDocumentsUriUsingTree(tree, docId)
        var items: List<FileItem> = emptyList()
        // Providers may answer with "still loading"; give them a few seconds to fill the listing.
        for (attempt in 0..10) {
            val cursor = resolver.query(uri, PROJECTION, null, null, null) ?: throw IOException("The storage app didn't respond. Is it installed and signed in?")
            var loading = false
            items = cursor.use { c ->
                loading = c.extras?.getBoolean(DocumentsContract.EXTRA_LOADING, false) == true
                buildList { while (c.moveToNext()) add(toItem(tree, c)) }
            }
            if (!loading || items.isNotEmpty()) break
            delay(500)
        }
        items.forEach { parents[it.id.value] = folder.value }
        val self = if (isRoot(folder)) null else names[folder.value] ?: queryOne(tree, docId)?.name
        val title = if (isRoot(folder)) names[folder.value] ?: roots.firstOrNull { it.tree == tree }?.name ?: "Cloud storage" else self ?: "Folder"
        Listing(Folder(folder, title, Origin.EXTERNAL), items.sortedWith(compareBy({ !it.isDirectory }, { it.name.lowercase() })), trail(folder, title))
    }

    private fun trail(folder: ItemId, title: String): List<Crumb> {
        val out = ArrayDeque<Crumb>()
        var cur: ItemId? = folder; var guard = 0
        while (cur != null && guard++ < 64) {
            val c = cur
            out.addFirst(Crumb(c, if (c == folder) title else names[c.value] ?: "Folder"))
            cur = if (isRoot(c)) null else parents[c.value]?.let(::ItemId)
        }
        // A folder opened cold (no remembered parents): fall back to root > folder.
        if (!isRoot(out.first().id)) out.addFirst(Crumb(rootId(treeOf(folder)), roots.firstOrNull { it.tree == treeOf(folder) }?.name ?: "Cloud storage"))
        return out
    }

    // Provider-side search needs a root id this tree doesn't have; cloud storage is browsed, not searched.
    override suspend fun search(query: String, limit: Int): List<FileItem> = emptyList()
    override suspend fun recent(limit: Int): List<FileItem> = emptyList()

    // ---------- reading ----------

    private fun isVirtual(id: ItemId) = (flags[id.value] ?: 0) and Document.FLAG_VIRTUAL_DOCUMENT != 0

    private fun openForRead(item: FileItem): java.io.InputStream {
        val uri = docUri(item.id)
        val stream = if (isVirtual(item.id)) {
            resolver.openTypedAssetFileDescriptor(uri, exportMime(treeOf(item.id), docIdOf(item.id)), null)?.createInputStream()
        } else resolver.openInputStream(uri)
        return stream ?: throw IOException("Couldn't open “${item.name}”.")
    }

    private fun copyOut(item: FileItem, dest: File, onProgress: (Long) -> Unit) {
        dest.parentFile?.mkdirs()
        val tmp = File(dest.parentFile, dest.name + ".part")
        try {
            openForRead(item).use { input ->
                tmp.outputStream().buffered(1 shl 16).use { out ->
                    val buf = ByteArray(1 shl 16); var done = 0L
                    while (true) { val n = input.read(buf); if (n < 0) break; out.write(buf, 0, n); done += n; onProgress(done) }
                }
            }
            if (!isVirtual(item.id) && item.size > 0) check(tmp.length() == item.size) { "The download was incomplete (${tmp.length()} of ${item.size} bytes). Try again." }
            check(tmp.renameTo(dest)) { "Couldn't finish saving the file." }
        } finally { tmp.delete() }
    }

    override suspend fun materialize(item: FileItem, onProgress: (Long, Long) -> Unit): MaterializedFile = withContext(Dispatchers.IO) {
        val safe = item.name.replace('/', '_').ifBlank { "file" }
        val key = Integer.toHexString(item.id.value.hashCode())
        val dir = File(context.cacheDir, "open/saf/$key-${item.size}").apply { mkdirs() }
        val target = File(dir, safe)
        val reusable = !isVirtual(item.id) && target.isFile && target.length() == item.size
        if (reusable) { target.setLastModified(System.currentTimeMillis()) }
        else {
            if (dir.usableSpace < item.size + (64L shl 20)) throw IOException("No space left on device.")
            copyOut(item, target) { onProgress(it, item.size) }
            onCached(target)
        }
        MaterializedFile(target.absolutePath, item.name, item.mime)
    }

    override suspend fun download(item: FileItem, dest: File, onProgress: (Long) -> Unit) {
        withContext(Dispatchers.IO) { copyOut(item, dest, onProgress) }
    }

    override suspend fun thumbnail(item: FileItem, sizePx: Int): String? = withContext(Dispatchers.IO) {
        if (item.isDirectory || (flags[item.id.value] ?: 0) and Document.FLAG_SUPPORTS_THUMBNAIL == 0) return@withContext null
        val target = File(context.cacheDir, "thumbs/saf/${Integer.toHexString(item.id.value.hashCode())}-${item.size}-$sizePx.webp")
        if (target.isFile && target.length() > 0) return@withContext target.absolutePath
        val bmp = runCatching { DocumentsContract.getDocumentThumbnail(resolver, docUri(item.id), Point(sizePx, sizePx), null) }.getOrNull()
            ?: return@withContext null
        target.parentFile?.mkdirs()
        val tmp = File(target.parentFile, target.name + ".part")
        tmp.outputStream().use { bmp.compress(if (android.os.Build.VERSION.SDK_INT >= 30) Bitmap.CompressFormat.WEBP_LOSSY else @Suppress("DEPRECATION") Bitmap.CompressFormat.WEBP, 80, it) }
        if (tmp.renameTo(target)) { onCached(target); target.absolutePath } else null
    }

    // ---------- writing ----------

    override fun canWrite(folder: ItemId) =
        folder.scheme == SCHEME && (flags[folder.value] ?: 0) and Document.FLAG_DIR_SUPPORTS_CREATE != 0

    override suspend fun createFolder(parent: ItemId, name: String): ItemId = withContext(Dispatchers.IO) {
        val uri = DocumentsContract.createDocument(resolver, docUri(parent), Document.MIME_TYPE_DIR, name)
            ?: throw IOException("The storage app couldn't create the folder “$name”.")
        id(treeOf(parent), DocumentsContract.getDocumentId(uri)).also { parents[it.value] = parent.value; names[it.value] = name }
    }

    override suspend fun upload(parent: ItemId, localPath: String, name: String, mime: String?, onProgress: (Long) -> Unit): FileItem =
        withContext(Dispatchers.IO) {
            val type = mime ?: MimeTypeMap.getSingleton().getMimeTypeFromExtension(name.substringAfterLast('.', "").lowercase()) ?: "application/octet-stream"
            val uri = DocumentsContract.createDocument(resolver, docUri(parent), type, name)
                ?: throw IOException("The storage app couldn't create “$name”.")
            try {
                File(localPath).inputStream().buffered(1 shl 16).use { input ->
                    (resolver.openOutputStream(uri, "w") ?: throw IOException("Couldn't write “$name”.")).buffered(1 shl 16).use { out ->
                        val buf = ByteArray(1 shl 16); var sent = 0L
                        while (true) { val n = input.read(buf); if (n < 0) break; out.write(buf, 0, n); sent += n; onProgress(sent) }
                    }
                }
            } catch (e: Throwable) {
                runCatching { DocumentsContract.deleteDocument(resolver, uri) } // never leave a half-written file behind
                throw e
            }
            val tree = treeOf(parent)
            val item = queryOne(tree, DocumentsContract.getDocumentId(uri)) ?: throw IOException("Uploaded, but couldn't read the file back.")
            parents[item.id.value] = parent.value
            item
        }

    override fun canModify(item: FileItem) =
        item.origin == Origin.EXTERNAL && !isRoot(item.id) &&
            (flags[item.id.value] ?: 0) and (Document.FLAG_SUPPORTS_DELETE or Document.FLAG_SUPPORTS_RENAME or Document.FLAG_SUPPORTS_MOVE) != 0

    override suspend fun rename(item: FileItem, newName: String): FileItem = withContext(Dispatchers.IO) {
        require((flags[item.id.value] ?: 0) and Document.FLAG_SUPPORTS_RENAME != 0) { "This storage doesn't allow renaming here." }
        val uri = DocumentsContract.renameDocument(resolver, docUri(item.id), newName) ?: throw IOException("Couldn't rename “${item.name}”.")
        val out = queryOne(treeOf(item.id), DocumentsContract.getDocumentId(uri)) ?: throw IOException("Renamed, but couldn't read the item back.")
        parents[item.id.value]?.let { parents[out.id.value] = it }
        out
    }

    override suspend fun move(item: FileItem, newParent: ItemId): FileItem = withContext(Dispatchers.IO) {
        require((flags[item.id.value] ?: 0) and Document.FLAG_SUPPORTS_MOVE != 0) { "This storage doesn't allow moving here." }
        require(treeOf(newParent).authority == treeOf(item.id).authority) { "Items can only be moved within the same cloud storage." }
        val oldParent = parents[item.id.value]?.let(::ItemId) ?: throw IOException("Open the folder first, then move the item.")
        val uri = DocumentsContract.moveDocument(resolver, docUri(item.id), docUri(oldParent), docUri(newParent))
            ?: throw IOException("Couldn't move “${item.name}”.")
        val out = queryOne(treeOf(newParent), DocumentsContract.getDocumentId(uri)) ?: throw IOException("Moved, but couldn't read the item back.")
        parents[out.id.value] = newParent.value
        out
    }

    /** Deletes through the provider; whether the service keeps it in a trash is up to the service. No undo is offered. */
    override suspend fun trash(item: FileItem): (suspend () -> Unit)? = withContext(Dispatchers.IO) {
        check(DocumentsContract.deleteDocument(resolver, docUri(item.id))) { "Couldn't delete “${item.name}”." }
        null
    }

    companion object { const val SCHEME = "saf" }
}
