package app.filenfiles.core

import java.io.File
import java.net.URLDecoder
import java.net.URLEncoder

/**
 * What one copy transfer has itself created, so a resumed run can tell its own finished work from a file that merely
 * has the same name. [size] and [modified] describe the source when it was copied; [destSize] and [destModified] the
 * file that was created. A folder entry only uses [destId]. Appended line by line to [file] (null: memory only), so a
 * killed process keeps what was finished; an unreadable line is ignored, which only means copying that file again.
 */
class CopyLedger(private val file: File? = null) {
    data class Entry(val destId: ItemId, val size: Long = 0, val modified: Long = 0, val destSize: Long = 0, val destModified: Long = 0)

    private val entries = HashMap<Pair<String, String>, Entry>()

    init {
        file?.takeIf { it.isFile }?.forEachLine { line ->
            runCatching {
                val p = line.split('\t')
                require(p.size == 8 && p[7] == END)
                entries[dec(p[0]) to dec(p[1])] = Entry(ItemId(dec(p[2])), p[3].toLong(), p[4].toLong(), p[5].toLong(), p[6].toLong())
            }
        }
    }

    /** Keyed by source AND destination folder: the same source copied elsewhere is different work. */
    operator fun get(source: ItemId, destFolder: ItemId): Entry? = entries[source.value to destFolder.value]

    fun put(source: ItemId, destFolder: ItemId, e: Entry) {
        entries[source.value to destFolder.value] = e
        val f = file ?: return
        f.parentFile?.mkdirs()
        // The leading newline ends a line a killed process left unfinished.
        f.appendText("\n" + listOf(enc(source.value), enc(destFolder.value), enc(e.destId.value), e.size, e.modified, e.destSize, e.destModified, END).joinToString("\t"))
    }

    private fun enc(s: String) = URLEncoder.encode(s, "UTF-8")
    private fun dec(s: String) = URLDecoder.decode(s, "UTF-8")

    private companion object { const val END = "." }
}
