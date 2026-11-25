package app.filenfiles.core

import java.io.File
import java.net.URLDecoder
import java.net.URLEncoder

/**
 * Uploads that went up (or may have gone up) under a temporary name ([SafeCreate.tempName]) and were not yet given
 * their real name. Kept on disk so a crash, a kill or a failed rename can be finished later: the file is then given its
 * name, never deleted. [uuid] is null until the upload returned.
 */
class UploadJournal(private val file: File?) {
    data class Entry(val tempName: String, val parent: String, val name: String, val account: String, val uuid: String? = null)

    private val entries = LinkedHashMap<String, Entry>()

    init {
        file?.takeIf { it.isFile }?.readLines()?.forEach { line ->
            runCatching {
                val p = line.split('\t').map(::dec)
                require(p.size == 5)
                entries[p[0]] = Entry(p[0], p[1], p[2], p[3], p[4].ifEmpty { null })
            }
        }
    }

    @Synchronized operator fun get(tempName: String): Entry? = entries[tempName]
    @Synchronized fun all(): List<Entry> = entries.values.toList()
    @Synchronized fun put(e: Entry) { entries[e.tempName] = e; write() }
    @Synchronized fun remove(tempName: String) { if (entries.remove(tempName) != null) write() }

    private fun write() {
        val f = file ?: return
        f.parentFile?.mkdirs()
        val tmp = File(f.path + ".part")
        tmp.writeText(entries.values.joinToString("") { e ->
            listOf(e.tempName, e.parent, e.name, e.account, e.uuid ?: "").joinToString("\t", transform = ::enc) + "\n"
        })
        if (!tmp.renameTo(f)) tmp.delete()
    }

    private fun enc(s: String) = URLEncoder.encode(s, "UTF-8")
    private fun dec(s: String) = URLDecoder.decode(s, "UTF-8")
}
