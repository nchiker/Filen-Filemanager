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

    /**
     * Adds [files] (local file + the name it gets inside the zip) to the zip [zip], inside the archive folder [prefix] ("" = top level, otherwise "a/b/").
     * A new zip is written next to the work files first; the original is only replaced once that is complete, so a
     * failure (or cancel) at any point leaves the original untouched. Names that already exist in the archive get a
     * numbered suffix. Device zips are replaced in place; Filen and cloud zips are re-uploaded under a temporary name,
     * the old one is trashed/deleted, and the new one is renamed to the original name.
     */
    suspend fun addToZip(zip: ItemId, files: List<Pair<File, String>>, prefix: String) {
        if (files.isEmpty()) throw IOException("There is nothing to add.")
        val item = router.stat(zip) ?: throw IOException("The zip is no longer there.")
        val storage = router.modifier(item) ?: throw IOException("This zip can't be changed (it is read-only).")
        val parent = router.parentOf(zip) ?: throw IOException("Couldn't tell which folder this zip is in. Open the zip from its folder and try again.")
        val src = if (item.origin == Origin.LOCAL) item.id.key else router.materialize(item).path
        val total = File(src).length() + files.sumOf { it.first.length() }
        setTotal(total)
        LocalCache.requireSpace(app.applicationContext, total)
        workDir.deleteRecursively(); workDir.mkdirs()
        val out = File(workDir, item.name.replace('/', '_'))
        try {
            ZipFile(src).use { zf ->
                ZipOutputStream(out.outputStream().buffered(1 shl 16)).use { zos ->
                    val seen = HashSet<String>()
                    for (e in zf.entries()) {
                        currentCoroutineContext().ensureActive()
                        if (!seen.add(e.name)) continue // a damaged zip may repeat a name; ZipOutputStream would refuse it
                        zos.putNextEntry(ZipEntry(e.name).apply { if (e.time > 0) time = e.time })
                        if (!e.isDirectory) try { zf.getInputStream(e).use { copy(it, zos) } } catch (ex: java.util.zip.ZipException) {
                            throw IOException("Couldn't read “${e.name}”: the zip is damaged or password protected (not supported).")
                        }
                        zos.closeEntry()
                    }
                    // Names already used in the target archive folder (files and sub-folders), for numbering.
                    val used = seen.filter { it.startsWith(prefix) }.map { it.removePrefix(prefix).substringBefore('/') }.filter { it.isNotEmpty() }.toMutableList()
                    for ((f, entryName) in files) {
                        currentCoroutineContext().ensureActive()
                        val name = Names.unique(used, entryName.replace('/', '_')); used += name
                        zos.putNextEntry(ZipEntry("$prefix$name").apply { time = f.lastModified().takeIf { it > 0 } ?: System.currentTimeMillis() })
                        f.inputStream().buffered(1 shl 16).use { copy(it, zos) }
                        zos.closeEntry()
                    }
                }
            }
            if (item.origin == Origin.LOCAL) {
                val target = File(item.id.key)
                val tmp = File(target.parentFile, ".${target.name}.updating")
                try {
                    out.copyTo(tmp, overwrite = true)
                    if (!tmp.renameTo(target)) throw IOException("Couldn't replace “${item.name}”.")
                } finally { tmp.delete() }
            } else {
                val existing = router.list(parent).items.map { it.name }
                val tmpName = Names.unique(existing, item.name.replace(Regex("(?i)\\.zip$"), "") + " (updating).zip")
                val uploaded = storage.upload(parent, out.absolutePath, tmpName, "application/zip") { }
                // Only now is the new zip safely stored; swap it in. If the rename fails the user still has the updated zip.
                storage.trash(item)
                try { storage.rename(uploaded, item.name) }
                catch (e: Throwable) { throw IOException("The updated zip was saved as “$tmpName” (renaming it back failed).") }
            }
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
