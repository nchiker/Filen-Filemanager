package app.filenfiles.filen

import app.filenfiles.core.FileItem
import app.filenfiles.core.ItemId
import app.filenfiles.core.Origin
import app.filenfiles.core.Permission
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.io.IOException

/** One searchable item plus the id of its parent folder (null when unknown), so subtrees can be dropped or resolved. */
class IndexEntry(val item: FileItem, val parent: String?)

/** What was last crawled for [account]; `personalAt`/`sharedAt` are epoch millis (0 = never). */
class IndexSnapshot(
    val account: String,
    val personalAt: Long,
    val sharedAt: Long,
    val personal: List<IndexEntry>,
    val shared: List<IndexEntry>,
)

/**
 * Plain-file persistence for the search index so a process start doesn't need a crawl. Contains decrypted
 * names, so it lives in the cache (wiped on sign-out/expiry) and is tied to one account. Pure JVM I/O;
 * writes go through a temp file + rename, and a truncated/foreign/corrupt file reads as "no snapshot".
 */
object SearchIndexStore {
    private const val MAGIC = 0x46494458 // "FIDX"
    private const val VERSION = 1

    fun save(file: File, s: IndexSnapshot) {
        file.parentFile?.mkdirs()
        val tmp = File(file.parentFile, file.name + ".part")
        try {
            DataOutputStream(tmp.outputStream().buffered(1 shl 16)).use { out ->
                out.writeInt(MAGIC); out.writeInt(VERSION)
                out.writeUTF(s.account); out.writeLong(s.personalAt); out.writeLong(s.sharedAt)
                writeEntries(out, s.personal); writeEntries(out, s.shared)
            }
            if (!tmp.renameTo(file)) throw IOException("could not replace ${file.name}")
        } finally { tmp.delete() }
    }

    /** Null when absent, unreadable, from another version, or for a different [account]. */
    fun load(file: File, account: String): IndexSnapshot? {
        if (!file.isFile) return null
        return try {
            DataInputStream(file.inputStream().buffered(1 shl 16)).use { inp ->
                if (inp.readInt() != MAGIC || inp.readInt() != VERSION) return null
                val acc = inp.readUTF()
                if (acc != account) return null
                val pAt = inp.readLong(); val sAt = inp.readLong()
                IndexSnapshot(acc, pAt, sAt, readEntries(inp), readEntries(inp))
            }
        } catch (e: Exception) { null } // truncated or corrupt: just crawl again
    }

    private fun writeEntries(out: DataOutputStream, list: List<IndexEntry>) {
        out.writeInt(list.size)
        for (e in list) {
            val i = e.item
            out.writeUTF(i.id.value); out.writeUTF(i.name); out.writeBoolean(i.isDirectory)
            out.writeLong(i.size); out.writeLong(i.modified)
            writeOpt(out, i.mime)
            out.writeByte(i.origin.ordinal); out.writeByte(i.permission.ordinal)
            writeOpt(out, i.sharedBy); writeOpt(out, i.sharedWith); writeOpt(out, e.parent)
        }
    }

    private fun readEntries(inp: DataInputStream): List<IndexEntry> {
        val n = inp.readInt()
        require(n in 0..10_000_000)
        val origins = Origin.entries; val perms = Permission.entries
        val out = ArrayList<IndexEntry>(n)
        repeat(n) {
            val id = inp.readUTF(); val name = inp.readUTF(); val dir = inp.readBoolean()
            val size = inp.readLong(); val mod = inp.readLong(); val mime = readOpt(inp)
            val origin = origins[inp.readUnsignedByte()]; val perm = perms[inp.readUnsignedByte()]
            val by = readOpt(inp); val with = readOpt(inp); val parent = readOpt(inp)
            out.add(IndexEntry(FileItem(ItemId(id), name, dir, size, mod, mime, origin, perm, by, with), parent))
        }
        return out
    }

    private fun writeOpt(out: DataOutputStream, s: String?) {
        out.writeBoolean(s != null); if (s != null) out.writeUTF(s)
    }
    private fun readOpt(inp: DataInputStream): String? = if (inp.readBoolean()) inp.readUTF() else null
}
