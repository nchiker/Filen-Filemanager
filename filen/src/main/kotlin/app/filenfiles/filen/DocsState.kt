package app.filenfiles.filen

import app.filenfiles.core.FileItem
import app.filenfiles.core.ItemId
import app.filenfiles.core.Origin
import app.filenfiles.core.Permission
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.io.IOException
import java.security.MessageDigest
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit

/** A folder listing as last seen from Filen, kept on disk. [savedAt] is epoch millis. */
class StoredListing(val items: List<FileItem>, val savedAt: Long)

/**
 * What the documents provider must remember across a process death (docs/FOLDER_SHARING_INTERFACES.md I4 F1/F2):
 * the child -> parent map (so "is X inside the granted folder" resolves at any depth without asking Filen) and the last
 * listing of each folder (so offline answers can be given, marked not fresh). Tied to one account: a file written for
 * another account reads as empty and is replaced. Pure JVM; saves are atomic (temp file + rename) and a truncated or
 * corrupt file reads as "nothing known". Holds decrypted names, so it lives in the app's private files and is wiped
 * by [clear] at sign-out / expiry.
 */
class DocsState(
    private val file: File,
    private val account: () -> String?,
    private val autosave: Boolean = true,
    private val maxParents: Int = 150_000,
    private val maxFolders: Int = 300,
    private val maxItems: Int = 30_000,
    private val now: () -> Long = System::currentTimeMillis,
) {
    private val lock = Any()
    private var loadedFor: String? = null
    private val parents = HashMap<String, String>()
    private val listings = object : LinkedHashMap<String, StoredListing>(16, 0.75f, true) {}
    private var dirty = false
    private var pendingSave: java.util.concurrent.ScheduledFuture<*>? = null

    private val scheduler: ScheduledExecutorService by lazy {
        Executors.newSingleThreadScheduledExecutor { r -> Thread(r, "docs-state").apply { isDaemon = true } }
    }

    /** Loads the file for the signed-in account (once); returns false when nobody is signed in. Call under [lock]. */
    private fun ensure(): Boolean {
        val acct = account() ?: return false
        if (loadedFor == acct) return true
        parents.clear(); listings.clear(); dirty = false
        loadedFor = acct
        runCatching { read(acct) }
        return true
    }

    fun parent(id: String): String? = synchronized(lock) { if (ensure()) parents[id] else null }

    fun setParent(id: String, parent: String) {
        if (id.startsWith(TRANSIENT)) return
        synchronized(lock) {
            if (!ensure() || parents[id] == parent) return
            if (parents.size >= maxParents && id !in parents) return // bounded: unknown parents are looked up again
            parents[id] = parent; changed()
        }
    }

    fun removeParent(id: String): String? = synchronized(lock) {
        if (!ensure()) null else parents.remove(id)?.also { changed() }
    }

    /** Remembers [items] as the current content of [folder] (call only with a listing that came from Filen just now). */
    fun putListing(folder: String, items: List<FileItem>) {
        synchronized(lock) {
            if (!ensure()) return
            listings[folder] = StoredListing(items, now())
            var total = listings.values.sumOf { it.items.size }
            val it = listings.entries.iterator()
            while ((listings.size > maxFolders || total > maxItems) && listings.size > 1 && it.hasNext()) {
                val e = it.next(); if (e.key == folder) continue
                total -= e.value.items.size; it.remove()
            }
            changed()
        }
    }

    fun listing(folder: String): StoredListing? = synchronized(lock) { if (ensure()) listings[folder] else null }

    /** The row for [id] in the stored listing of its known parent (null when either is unknown). */
    fun storedItem(id: String): FileItem? = synchronized(lock) {
        if (!ensure()) return null
        val p = parents[id] ?: return null
        listings[p]?.items?.firstOrNull { it.id.value == id }
    }

    /** Forget everything (sign-out, expiry) and delete the file. */
    fun clear() {
        synchronized(lock) {
            pendingSave?.cancel(false); pendingSave = null
            parents.clear(); listings.clear(); loadedFor = null; dirty = false
            file.delete(); File(file.parentFile, file.name + ".part").delete()
        }
    }

    /** Writes now when something changed (tests, and anything that wants durability before a risky step). */
    fun flush() { synchronized(lock) { pendingSave?.cancel(false); pendingSave = null; save() } }

    private fun changed() {
        dirty = true
        if (!autosave || pendingSave != null) return
        pendingSave = scheduler.schedule({ synchronized(lock) { pendingSave = null; save() } }, 3, TimeUnit.SECONDS)
    }

    private fun save() {
        if (!dirty) return
        val acct = loadedFor ?: return
        file.parentFile?.mkdirs()
        val tmp = File(file.parentFile, file.name + ".part")
        try {
            DataOutputStream(tmp.outputStream().buffered(1 shl 16)).use { out ->
                out.writeInt(MAGIC); out.writeInt(VERSION); out.writeUTF(acct)
                out.writeInt(parents.size)
                parents.forEach { (k, v) -> out.writeUTF(k); out.writeUTF(v) }
                out.writeInt(listings.size)
                listings.forEach { (folder, l) ->
                    out.writeUTF(folder); out.writeLong(l.savedAt); out.writeInt(l.items.size)
                    l.items.forEach { writeItem(out, it) }
                }
            }
            if (!tmp.renameTo(file)) throw IOException("could not replace ${file.name}")
            dirty = false
        } catch (_: Exception) {
            // Keep working from memory; the next change tries again.
        } finally { tmp.delete() }
    }

    private fun read(acct: String) {
        if (!file.isFile) return
        try {
            DataInputStream(file.inputStream().buffered(1 shl 16)).use { inp ->
                if (inp.readInt() != MAGIC || inp.readInt() != VERSION || inp.readUTF() != acct) return
                val p = HashMap<String, String>()
                repeat(inp.readInt().also { if (it < 0 || it > maxParents * 2) throw IOException() }) { p[inp.readUTF()] = inp.readUTF() }
                val l = LinkedHashMap<String, StoredListing>()
                repeat(inp.readInt().also { if (it < 0 || it > maxFolders * 2) throw IOException() }) {
                    val folder = inp.readUTF(); val at = inp.readLong()
                    val n = inp.readInt().also { if (it < 0 || it > maxItems * 2) throw IOException() }
                    l[folder] = StoredListing(List(n) { readItem(inp) }, at)
                }
                parents.putAll(p); listings.putAll(l)
            }
        } catch (_: Exception) { parents.clear(); listings.clear() } // truncated or corrupt: nothing known
    }

    private fun writeItem(out: DataOutputStream, i: FileItem) {
        out.writeUTF(i.id.value); out.writeUTF(i.name); out.writeBoolean(i.isDirectory)
        out.writeLong(i.size); out.writeLong(i.modified)
        writeOpt(out, i.mime); out.writeUTF(i.origin.name); out.writeUTF(i.permission.name)
        writeOpt(out, i.sharedBy); writeOpt(out, i.sharedWith); writeOpt(out, i.lineage)
    }

    private fun readItem(inp: DataInputStream) = FileItem(
        ItemId(inp.readUTF()), inp.readUTF(), inp.readBoolean(), inp.readLong(), inp.readLong(),
        readOpt(inp), Origin.valueOf(inp.readUTF()), Permission.valueOf(inp.readUTF()),
        readOpt(inp), readOpt(inp), readOpt(inp),
    )

    private fun writeOpt(out: DataOutputStream, s: String?) { out.writeBoolean(s != null); if (s != null) out.writeUTF(s) }
    private fun readOpt(inp: DataInputStream): String? = if (inp.readBoolean()) inp.readUTF() else null

    companion object {
        private const val MAGIC = 0x46445354 // "FDST"
        private const val VERSION = 1
        /** Ids that live only in this process (uploads in progress) are never written down. */
        private const val TRANSIENT = "filen-pending:"
    }
}

/** I4 F2 / F6: the pure rules behind the documents provider's answers. */
object DocsRules {
    enum class ListOutcome { FRESH, STALE, NOT_FOUND, AUTH, IO }

    /**
     * What a folder listing answers. [failure] null = the listing came from Filen just now. [confirmedGone]: the server
     * confirmed the folder (or its share) no longer exists; null = could not be asked. A stale answer is only ever
     * given when nothing is known to be wrong, and never says "gone": removal needs a fresh listing (I6).
     */
    fun decideList(failure: FailureKind?, confirmedGone: Boolean?, haveStored: Boolean): ListOutcome = when {
        failure == null -> ListOutcome.FRESH
        confirmedGone == true -> ListOutcome.NOT_FOUND
        failure == FailureKind.AUTH -> ListOutcome.AUTH
        haveStored -> ListOutcome.STALE
        else -> ListOutcome.IO
    }

    /** The `app.filenfiles.extra.FRESH` value for an outcome. */
    fun isFresh(o: ListOutcome) = o == ListOutcome.FRESH

    /** F3: changes whenever the content of a file changes (Filen gives a saved-over file a new uuid; size and time back that up). */
    fun version(item: FileItem): String? = if (item.isDirectory) null else "${item.id.key}.${item.size}.${item.modified}"

    /** F6: opaque, stable per account, not the email. */
    fun accountId(userId: ULong): String {
        val h = MessageDigest.getInstance("SHA-256").digest("filen-account:$userId".toByteArray())
        return h.take(16).joinToString("") { "%02x".format(it) }
    }
}
