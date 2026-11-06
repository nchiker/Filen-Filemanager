package app.filenfiles.transfer

import app.filenfiles.FilesApp
import app.filenfiles.core.FileItem
import app.filenfiles.core.ItemId
import app.filenfiles.core.Names
import app.filenfiles.core.Origin
import app.filenfiles.filen.LocalCache
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import java.io.File
import java.io.IOException
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream

/**
 * Zips items from any storage into a .zip, or extracts a .zip into a folder, with the result delivered to any
 * storage. Work happens in a private temp folder first and is then handed to [deliver] (a CopyRunner), which takes
 * care of name clashes, Filen uploads and network retries - so a half-finished archive never shows up at the
 * destination. Extraction refuses entries that would escape the output folder (zip slip).
 */
class ArchiveRunner(
    private val app: FilesApp,
    private val workDir: File,
    private val setTotal: (Long) -> Unit,
    private val setDone: (Long) -> Unit,
    /** Copies a finished local result into the destination folder. */
    private val deliver: suspend (local: ItemId, dest: ItemId) -> Unit,
) {
    private val router get() = app.router
    private var done = 0L

    suspend fun zip(sources: List<ItemId>, dest: ItemId, archiveName: String) {
        val items = sources.map { router.stat(it) ?: throw IOException("An item to compress is no longer there.") }
        val total = items.sumOf { scan(it) }
        setTotal(total)
        LocalCache.requireSpace(app.applicationContext, total) // worst case: nothing compresses
        workDir.deleteRecursively(); workDir.mkdirs()
        val out = File(workDir, archiveName)
        ZipOutputStream(out.outputStream().buffered(1 shl 16)).use { zos ->
            items.forEach { addNode(zos, it, "") }
        }
        deliver(ItemId("local:${out.absolutePath}"), dest)
        workDir.deleteRecursively()
    }

    suspend fun unzip(source: ItemId, dest: ItemId) {
        val item = router.stat(source) ?: throw IOException("The archive is no longer there.")
        val path = if (item.origin == Origin.LOCAL) item.id.key else router.materialize(item).path
        val folderName = item.name.replace(Regex("(?i)\\.zip$"), "").ifBlank { "Extracted" }
        workDir.deleteRecursively()
        val root = File(workDir, folderName).apply { mkdirs() }
        try {
            ZipFile(path).use { zf ->
                val entries = zf.entries().asSequence().toList()
                val total = entries.sumOf { if (it.isDirectory) 0L else maxOf(it.size, 0L) }
                setTotal(total)
                LocalCache.requireSpace(app.applicationContext, total)
                val rootPath = root.canonicalPath + File.separator
                for (e in entries) {
                    currentCoroutineContext().ensureActive()
                    val rel = Names.safeEntryPath(e.name) ?: throw IOException("The archive contains an unsafe path (“${e.name}”).")
                    val target = File(root, rel)
                    if (!target.canonicalPath.startsWith(rootPath)) throw IOException("The archive contains an unsafe path (“${e.name}”).")
                    if (e.isDirectory) { target.mkdirs(); continue }
                    target.parentFile?.mkdirs()
                    try {
                        zf.getInputStream(e).use { input -> target.outputStream().buffered(1 shl 16).use { o -> copy(input, o) } }
                    } catch (ex: java.util.zip.ZipException) {
                        throw IOException("Couldn't read “${e.name}”: the archive is damaged or password protected (not supported).")
                    }
                }
            }
            // A zip that already wraps everything in one top-level folder shouldn't be wrapped a second time.
            val only = root.listFiles()?.singleOrNull()
            deliver(ItemId("local:${(only?.takeIf { it.isDirectory } ?: root).absolutePath}"), dest)
        } finally {
            workDir.deleteRecursively()
        }
    }

    // ---- helpers ----

    private suspend fun scan(item: FileItem): Long =
        if (item.isDirectory) router.list(item.id).items.sumOf { scan(it) } else item.size

    private suspend fun addNode(zos: ZipOutputStream, item: FileItem, prefix: String) {
        currentCoroutineContext().ensureActive()
        if (item.isDirectory) {
            val dir = "$prefix${item.name}/"
            zos.putNextEntry(ZipEntry(dir)); zos.closeEntry()
            router.list(item.id).items.forEach { addNode(zos, it, dir) }
        } else {
            val path = if (item.origin == Origin.LOCAL) item.id.key else router.materialize(item).path
            zos.putNextEntry(ZipEntry("$prefix${item.name}").apply { time = item.modified.takeIf { it > 0 } ?: System.currentTimeMillis() })
            File(path).inputStream().buffered(1 shl 16).use { copy(it, zos) }
            zos.closeEntry()
        }
    }

    private fun copy(input: java.io.InputStream, out: java.io.OutputStream) {
        val buf = ByteArray(1 shl 16)
        while (true) {
            val n = input.read(buf); if (n < 0) break
            out.write(buf, 0, n); done += n; setDone(done)
        }
    }

    companion object {
        /** "photo.jpg" -> "photo.zip", folder "Trip" -> "Trip.zip"; several items -> "Archive.zip". */
        fun archiveName(items: List<FileItem>): String =
            if (items.size == 1) (items[0].name.let { n -> if (items[0].isDirectory || n.lastIndexOf('.') <= 0) n else n.substring(0, n.lastIndexOf('.')) } + ".zip")
            else "Archive.zip"
    }
}
