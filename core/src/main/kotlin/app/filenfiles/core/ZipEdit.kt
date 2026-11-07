package app.filenfiles.core

import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream

/** Pure zip helpers (no Android, no storage): listing an archive like folders, and writing an archive with files added. */
object ZipEdit {
    /** One entry as stored in the archive: [path] is the entry name ("docs/a.txt", or "docs/" for a folder entry). */
    class Entry(val path: String, val isDir: Boolean, val size: Long, val time: Long)

    /** One row of an archive folder. [path] is the entry name, or "docs/" for a folder (which may have no entry of its own). */
    class Row(val name: String, val path: String, val isDir: Boolean, val size: Long, val time: Long)

    /** Direct children of the archive folder [cwd] ("" = top level, otherwise "a/b/"): folders first, then by name. */
    fun children(entries: List<Entry>, cwd: String): List<Row> {
        val out = LinkedHashMap<String, Row>()
        for (e in entries) {
            if (!e.path.startsWith(cwd)) continue
            val rest = e.path.removePrefix(cwd)
            if (rest.isEmpty()) continue
            val seg = rest.substringBefore('/')
            if (seg.isEmpty()) continue
            if (rest.contains('/')) { // inside a sub-folder (which may have no entry of its own)
                if (out[seg]?.isDir != true) out[seg] = Row(seg, "$cwd$seg/", true, 0, e.time)
            } else if (e.isDir) out[seg] = Row(seg, "$cwd$seg/", true, 0, e.time)
            else out[seg] = Row(seg, e.path, false, e.size, e.time)
        }
        return out.values.sortedWith(compareBy({ !it.isDir }, { it.name.lowercase() }))
    }

    /** Reads the entries of [zip], skipping any whose path would escape the archive root. */
    fun entries(zip: File): List<Entry> = ZipFile(zip).use { zf ->
        zf.entries().asSequence().filter { Names.safeEntryPath(it.name) != null }
            .map { Entry(it.name, it.isDirectory, it.size, it.time) }.toList()
    }

    /**
     * Writes a copy of [src] to [out] with [files] (local file + the name it should get) added inside the archive folder
     * [prefix] ("" or "a/b/"). Existing entries are copied unchanged; a name already used in [prefix] gets a numbered suffix
     * ("a (1).txt"). [src] is never modified. [onBytes] gets the byte count of each copied chunk; [check] is called between
     * entries and may throw (e.g. to cancel). Returns the names the added files received.
     */
    fun addFiles(src: File, out: File, files: List<Pair<File, String>>, prefix: String,
                 onBytes: (Int) -> Unit = {}, check: () -> Unit = {}): List<String> {
        val given = ArrayList<String>()
        ZipFile(src).use { zf ->
            ZipOutputStream(out.outputStream().buffered(1 shl 16)).use { zos ->
                val seen = HashSet<String>()
                for (e in zf.entries()) {
                    check()
                    if (!seen.add(e.name)) continue // a damaged zip may repeat a name; ZipOutputStream would refuse it
                    zos.putNextEntry(ZipEntry(e.name).apply { if (e.time > 0) time = e.time })
                    if (!e.isDirectory) try { zf.getInputStream(e).use { copy(it, zos, onBytes) } } catch (ex: java.util.zip.ZipException) {
                        throw IOException("Couldn't read “${e.name}”: the zip is damaged or password protected (not supported).")
                    }
                    zos.closeEntry()
                }
                // Names already used in the target archive folder (files and sub-folders), for numbering.
                val used = seen.filter { it.startsWith(prefix) }.map { it.removePrefix(prefix).substringBefore('/') }.filter { it.isNotEmpty() }.toMutableList()
                for ((f, entryName) in files) {
                    check()
                    val name = Names.unique(used, entryName.replace('/', '_')); used += name; given += name
                    zos.putNextEntry(ZipEntry("$prefix$name").apply { time = f.lastModified().takeIf { it > 0 } ?: System.currentTimeMillis() })
                    f.inputStream().buffered(1 shl 16).use { copy(it, zos, onBytes) }
                    zos.closeEntry()
                }
            }
        }
        return given
    }

    private fun copy(input: InputStream, out: OutputStream, onBytes: (Int) -> Unit) {
        val buf = ByteArray(1 shl 16)
        while (true) {
            val n = input.read(buf); if (n < 0) break
            out.write(buf, 0, n); onBytes(n)
        }
    }
}
