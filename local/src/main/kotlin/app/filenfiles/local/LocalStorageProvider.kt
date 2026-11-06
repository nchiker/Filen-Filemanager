package app.filenfiles.local

import android.content.Context
import android.os.Environment
import android.provider.MediaStore
import android.webkit.MimeTypeMap
import app.filenfiles.core.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/** Local Android storage via java.io.File (requires All-files access to see anything but app dirs). */
class LocalStorageProvider(private val context: Context) : StorageProvider, WritableStorage {

    private val internalRoot: File = Environment.getExternalStorageDirectory()

    override fun owns(id: ItemId) = id.scheme == SCHEME

    fun hasAccess(): Boolean = Environment.isExternalStorageManager()

    private fun id(f: File) = ItemId("$SCHEME:${f.absolutePath}")

    override suspend fun roots(): List<Folder> {
        val named = listOf(
            "Internal storage" to internalRoot,
            "Downloads" to Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),
            "Documents" to Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOCUMENTS),
            "Pictures" to Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES),
            "Camera" to Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DCIM),
            "Music" to Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MUSIC),
            "Movies" to Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MOVIES),
        )
        return named.filter { it.second.exists() }.map { Folder(id(it.second), it.first, Origin.LOCAL) }
    }

    private fun toItem(f: File) = FileItem(
        id = id(f),
        name = f.name,
        isDirectory = f.isDirectory,
        size = if (f.isDirectory) 0 else f.length(),
        modified = f.lastModified(),
        mime = if (f.isDirectory) null else mimeOf(f.name),
        origin = Origin.LOCAL,
        permission = if (f.canWrite()) Permission.READ_WRITE else Permission.READ_ONLY,
    )

    override suspend fun list(folder: ItemId): Listing = withContext(Dispatchers.IO) {
        val dir = File(folder.key)
        require(dir.isDirectory) { "Not a folder: ${dir.path}" }
        val children = dir.listFiles() ?: throw SecurityException("Cannot read ${dir.path} (grant All files access)")
        val trail = ArrayList<Crumb>()
        var cur: File? = dir
        while (cur != null && cur.path.startsWith(internalRoot.path)) {
            trail.add(0, Crumb(id(cur), if (cur == internalRoot) "Internal storage" else cur.name))
            if (cur == internalRoot) break
            cur = cur.parentFile
        }
        Listing(Folder(id(dir), if (dir == internalRoot) "Internal storage" else dir.name.ifEmpty { "Internal storage" }, Origin.LOCAL), children.map(::toItem), trail)
    }

    override suspend fun stat(id: ItemId): FileItem? = withContext(Dispatchers.IO) {
        File(id.key).takeIf { it.exists() }?.let(::toItem)
    }

    override suspend fun search(query: String, limit: Int): List<FileItem> = withContext(Dispatchers.IO) {
        val q = query.trim().lowercase()
        if (q.isEmpty()) return@withContext emptyList()
        val out = ArrayList<FileItem>()
        var visited = 0
        val stack = ArrayDeque<File>().apply { add(internalRoot) }
        while (stack.isNotEmpty() && out.size < limit && visited < MAX_SCAN) {
            val d = stack.removeLast()
            val kids = d.listFiles() ?: continue
            for (k in kids) {
                visited++
                if (k.name.lowercase().contains(q)) out.add(toItem(k))
                // skip Android's private data trees and hidden dirs
                if (k.isDirectory && !k.name.startsWith(".") && k.name != "Android") stack.add(k)
            }
        }
        out
    }

    override suspend fun recent(limit: Int): List<FileItem> = withContext(Dispatchers.IO) {
        val out = ArrayList<FileItem>()
        val uri = MediaStore.Files.getContentUri("external")
        val proj = arrayOf(MediaStore.Files.FileColumns.DATA)
        val sel = "${MediaStore.Files.FileColumns.MEDIA_TYPE} != ${MediaStore.Files.FileColumns.MEDIA_TYPE_NONE} OR " +
            "${MediaStore.Files.FileColumns.MIME_TYPE} IS NOT NULL"
        context.contentResolver.query(
            uri, proj, sel, null, "${MediaStore.Files.FileColumns.DATE_MODIFIED} DESC",
        )?.use { c ->
            while (c.moveToNext() && out.size < limit) {
                val f = File(c.getString(0) ?: continue)
                if (f.isFile && !f.name.startsWith(".")) out.add(toItem(f))
            }
        }
        out
    }

    override suspend fun materialize(item: FileItem, onProgress: (Long, Long) -> Unit): MaterializedFile =
        MaterializedFile(File(item.id.key).absolutePath, item.name, item.mime)

    // ---------- write ----------

    override fun canWrite(folder: ItemId) = folder.scheme == SCHEME && File(folder.key).let { it.isDirectory && it.canWrite() }

    override suspend fun createFolder(parent: ItemId, name: String): ItemId = withContext(Dispatchers.IO) {
        val d = File(parent.key, name)
        check(!d.exists()) { "“$name” already exists" }
        check(d.mkdirs()) { "Could not create the folder" }
        id(d)
    }

    /** For local storage "upload" means copy a file into the folder. */
    override suspend fun upload(parent: ItemId, localPath: String, name: String, mime: String?, onProgress: (Long) -> Unit): FileItem =
        withContext(Dispatchers.IO) {
            val dst = File(parent.key, name)
            check(!dst.exists()) { "“$name” already exists" }
            File(localPath).copyTo(dst); onProgress(dst.length()); toItem(dst)
        }

    override fun canModify(item: FileItem) = item.origin == Origin.LOCAL && File(item.id.key).let { it.exists() && it.canWrite() }

    override suspend fun rename(item: FileItem, newName: String): FileItem = withContext(Dispatchers.IO) {
        val src = File(item.id.key)
        val dst = File(src.parentFile, newName)
        check(!dst.exists()) { "“$newName” already exists" }
        check(src.renameTo(dst)) { "Could not rename" }
        toItem(dst)
    }

    override suspend fun move(item: FileItem, newParent: ItemId): FileItem = withContext(Dispatchers.IO) {
        val src = File(item.id.key)
        val dst = File(newParent.key, src.name)
        check(!dst.exists()) { "“${src.name}” already exists in that folder" }
        check(!dst.absolutePath.startsWith(src.absolutePath + "/")) { "Cannot move a folder into itself" }
        check(src.renameTo(dst)) { "Could not move (different storage volume?)" }
        toItem(dst)
    }

    /** Local deletion is permanent: no undo. */
    override suspend fun trash(item: FileItem): (suspend () -> Unit)? = withContext(Dispatchers.IO) {
        check(File(item.id.key).deleteRecursively()) { "Could not delete" }
        null
    }

    companion object {
        const val SCHEME = "local"
        private const val MAX_SCAN = 60_000
        fun mimeOf(name: String): String? =
            MimeTypeMap.getSingleton().getMimeTypeFromExtension(name.substringAfterLast('.', "").lowercase())
    }
}
