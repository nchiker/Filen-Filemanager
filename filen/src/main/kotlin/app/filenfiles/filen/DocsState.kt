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

/**
 * A folder listing as last seen from Filen, kept on disk. [savedAt] is epoch millis. [complete] is false when the listing
 * was cut at the storage cap: it is only ever an offline (not FRESH) answer and never proof that an item is absent.
 */
class StoredListing(val items: List<FileItem>, val savedAt: Long, val complete: Boolean = true)

/**
 * What the documents provider must remember across a process death (docs/FOLDER_SHARING_INTERFACES.md I4 F1/F2):
 * the child -> parent map (so "is X inside the granted folder" resolves at any depth without asking Filen) and the last
 * listing of each folder (so offline answers can be given, marked not fresh). Tied to one account: a file written for
 * another account reads as empty and is replaced. Pure JVM; saves are atomic (temp file + rename) and a truncated or
 * corrupt file reads as "nothing known". Holds decrypted names, so it lives in the app's private files and is wiped
 * by [clear] at sign-out / expiry.
 *
 * Bounds (a hostile sharer can list many items): the parent map and the "gone" marks drop their least recently used
 * entries when full; a listing over [maxItems] is stored cut and marked incomplete; names over [MAX_NAME_BYTES] are
 * cut for storage. A stale link is removed when a fresh listing of its folder no longer has the child.
 */
class DocsState(
    private val file: File,
    private val account: () -> String?,
    private val autosave: Boolean = true,
    private val maxParents: Int = 150_000,
    private val maxFolders: Int = 300,
    private val maxItems: Int = 30_000,
    private val maxGone: Int = 5_000,
    private val now: () -> Long = System::currentTimeMillis,
) {
    private val lock = Any()
    private val saveLock = Any()
    private var loadedFor: String? = null
    private val parents = LinkedHashMap<String, String>(16, 0.75f, true) // access order: eldest = least recently used
    private val listings = object : LinkedHashMap<String, StoredListing>(16, 0.75f, true) {}
    /** Items the server last said are gone or no longer shared (id -> when). Oldest dropped when full. */
    private val gone = LinkedHashMap<String, Long>()
    private var dirty = false
    private var generation = 0
    private var pendingSave: java.util.concurrent.ScheduledFuture<*>? = null

    private val scheduler: ScheduledExecutorService by lazy {
        Executors.newSingleThreadScheduledExecutor { r -> Thread(r, "docs-state").apply { isDaemon = true } }
    }

    /** Loads the file for the signed-in account (once); returns false when nobody is signed in. Call under [lock]. */
    private fun ensure(): Boolean {
        val acct = account() ?: return false
        if (loadedFor == acct) return true
        parents.clear(); listings.clear(); gone.clear(); dirty = false
        loadedFor = acct
        runCatching { read(acct) }
        return true
    }

    fun parent(id: String): String? = synchronized(lock) { if (ensure()) parents[id] else null }

    fun setParent(id: String, parent: String) {
        if (id.startsWith(TRANSIENT)) return
        synchronized(lock) {
            if (!ensure()) return
            if (gone.remove(id) != null) changed() // a fresh server answer put it back in a folder
            if (parents[id] == parent) return
            parents[id] = parent; trimParents(); changed()
        }
    }

    private fun trimParents() {
        if (parents.size <= maxParents) return
        val it = parents.entries.iterator()
        while (parents.size > maxParents && it.hasNext()) { it.next(); it.remove() } // least recently used first
    }

    fun removeParent(id: String): String? = synchronized(lock) {
        if (!ensure()) null else parents.remove(id)?.also { changed() }
    }

    /**
     * Remembers [items] as the current content of [folder] (call only with a listing that came from Filen just now).
     * Children the previous stored listing had and this one lacks lose their parent link, unless another stored listing
     * has them (a moved item). Items seen here are no longer marked gone.
     */
    fun putListing(folder: String, items: List<FileItem>) {
        synchronized(lock) {
            if (!ensure()) return
            val before = listings[folder]
            val ids = HashSet<String>(items.size * 2)
            items.forEach { ids.add(it.id.value) }
            if (before != null) {
                for (old in before.items) {
                    val id = old.id.value
                    if (id in ids || parents[id] != folder) continue
                    if (listings.any { (k, l) -> k != folder && l.items.any { it.id.value == id } }) continue
                    parents.remove(id)
                }
            }
            ids.forEach { gone.remove(it) }
            val cut = items.size > maxItems
            listings[folder] = StoredListing(if (cut) items.subList(0, maxItems).toList() else items, now(), !cut)
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

    /**
     * The row for [id] in the stored listing of its known parent (null when either is unknown, or when the last fresh
     * check said the item is gone or no longer shared: a stale row must not answer for it).
     */
    fun storedItem(id: String): FileItem? = synchronized(lock) {
        if (!ensure() || id in gone) return null
        val p = parents[id] ?: return null
        listings[p]?.items?.firstOrNull { it.id.value == id }
    }

    /** The server confirmed [id] is gone or no longer shared. Its parent link goes too. */
    fun markGone(id: String) {
        if (id.startsWith(TRANSIENT)) return
        synchronized(lock) {
            if (!ensure()) return
            gone.remove(id); gone[id] = now()
            val it = gone.entries.iterator()
            while (gone.size > maxGone && it.hasNext()) { it.next(); it.remove() }
            parents.remove(id)
            changed()
        }
    }

    fun isGone(id: String): Boolean = synchronized(lock) { ensure() && id in gone }

    /** A fresh server answer found [id] again (it was shared again, or restored). */
    fun clearGone(id: String) { synchronized(lock) { if (ensure() && gone.remove(id) != null) changed() } }

    /** Forget everything (sign-out, expiry) and delete the file. */
    fun clear() {
        synchronized(lock) {
            pendingSave?.cancel(false); pendingSave = null
            parents.clear(); listings.clear(); gone.clear(); loadedFor = null; dirty = false; generation++
            file.delete(); File(file.parentFile, file.name + ".part").delete()
        }
    }

    /** Writes now when something changed (tests, and anything that wants durability before a risky step). */
    fun flush() { synchronized(lock) { pendingSave?.cancel(false); pendingSave = null }; save() }

    private fun changed() {
        dirty = true
        if (!autosave || pendingSave != null) return
        pendingSave = scheduler.schedule({ synchronized(lock) { pendingSave = null }; save() }, 3, TimeUnit.SECONDS)
    }

    private class Snapshot(
        val acct: String, val gen: Int, val parents: List<Pair<String, String>>,
        val listings: List<Pair<String, StoredListing>>, val gone: List<Pair<String, Long>>,
    )

    /** Copies the state under [lock]; the slow encoding and disk write then happen outside it. */
    private fun snapshot(): Snapshot? = synchronized(lock) {
        if (!dirty) return null
        val acct = loadedFor ?: return null
        dirty = false
        Snapshot(acct, generation, parents.entries.map { it.key to it.value },
            listings.entries.map { it.key to it.value }, gone.entries.map { it.key to it.value })
    }

    private fun save() = synchronized(saveLock) {
        val snap = snapshot() ?: return@synchronized
        val tmp = File(file.parentFile, file.name + ".part")
        var ok = false
        try {
            file.parentFile?.mkdirs()
            // A bad entry (a string the format cannot hold) is skipped; it never stops the rest from being saved.
            val parentRows = snap.parents.mapNotNull { (k, v) -> rowBytes { it.writeUTF(k); it.writeUTF(v) } }
            val goneRows = snap.gone.mapNotNull { (k, at) -> rowBytes { it.writeUTF(k); it.writeLong(at) } }
            val listingRows = snap.listings.mapNotNull { (folder, l) ->
                val items = l.items.mapNotNull { i -> rowBytes { writeItem(it, i) } }
                rowBytes { out ->
                    out.writeUTF(folder); out.writeLong(l.savedAt); out.writeBoolean(l.complete); out.writeInt(items.size)
                    items.forEach { out.write(it) }
                }
            }
            DataOutputStream(tmp.outputStream().buffered(1 shl 16)).use { out ->
                out.writeInt(MAGIC); out.writeInt(VERSION); out.writeUTF(snap.acct)
                out.writeInt(parentRows.size); parentRows.forEach { out.write(it) }
                out.writeInt(goneRows.size); goneRows.forEach { out.write(it) }
                out.writeInt(listingRows.size); listingRows.forEach { out.write(it) }
            }
            synchronized(lock) {
                if (generation != snap.gen) return@synchronized // cleared meanwhile (sign-out): never bring the file back
                if (!tmp.renameTo(file)) throw IOException("could not replace ${file.name}")
                ok = true
            }
            if (!ok) return@synchronized
        } catch (_: Exception) {
            synchronized(lock) { if (generation == snap.gen) dirty = true } // keep working from memory; the next change tries again
        } finally { tmp.delete() }
    }

    private inline fun rowBytes(write: (DataOutputStream) -> Unit): ByteArray? = try {
        val b = java.io.ByteArrayOutputStream()
        DataOutputStream(b).use(write)
        b.toByteArray()
    } catch (_: Exception) { null }

    private fun read(acct: String) {
        if (!file.isFile) return
        try {
            DataInputStream(file.inputStream().buffered(1 shl 16)).use { inp ->
                if (inp.readInt() != MAGIC || inp.readInt() != VERSION || inp.readUTF() != acct) return
                val p = LinkedHashMap<String, String>()
                repeat(inp.readInt().also { if (it < 0 || it > maxParents * 2) throw IOException() }) { p[inp.readUTF()] = inp.readUTF() }
                val g = LinkedHashMap<String, Long>()
                repeat(inp.readInt().also { if (it < 0 || it > maxGone * 2) throw IOException() }) { g[inp.readUTF()] = inp.readLong() }
                val l = LinkedHashMap<String, StoredListing>()
                repeat(inp.readInt().also { if (it < 0 || it > maxFolders * 2) throw IOException() }) {
                    val folder = inp.readUTF(); val at = inp.readLong(); val complete = inp.readBoolean()
                    val n = inp.readInt().also { if (it < 0 || it > maxItems * 2) throw IOException() }
                    l[folder] = StoredListing(List(n) { readItem(inp) }, at, complete)
                }
                parents.putAll(p); gone.putAll(g); listings.putAll(l)
                trimParents()
            }
        } catch (_: Exception) { parents.clear(); listings.clear(); gone.clear() } // truncated or corrupt: nothing known
    }

    private fun writeItem(out: DataOutputStream, i: FileItem) {
        out.writeUTF(i.id.value); out.writeUTF(truncateUtf8(i.name, MAX_NAME_BYTES)); out.writeBoolean(i.isDirectory)
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
        private const val VERSION = 2
        const val MAX_NAME_BYTES = 1_000
        /** Ids that live only in this process (uploads in progress) are never written down. */
        private const val TRANSIENT = "filen-pending:"

        /** [s] cut to at most [maxBytes] UTF-8 bytes, never in the middle of a character. */
        fun truncateUtf8(s: String, maxBytes: Int): String {
            if (s.length <= maxBytes / 3 || s.toByteArray(Charsets.UTF_8).size <= maxBytes) return s
            var bytes = 0; var i = 0
            while (i < s.length) {
                val cp = s.codePointAt(i); val n = Character.charCount(cp)
                val w = when { cp < 0x80 -> 1; cp < 0x800 -> 2; cp < 0x10000 -> 3; else -> 4 }
                if (bytes + w > maxBytes) break
                bytes += w; i += n
            }
            return s.substring(0, i)
        }
    }
}

/** I4 F2 / F6: the pure rules behind the documents provider's answers. */
object DocsRules {
    private val SCHEMES = setOf("filen", "filen-in", "filen-out")

    /**
     * The ids from [parent] (or, when null, from the drive root) down to [child], for findDocumentPath. [parentOf] answers the
     * parent id, null = the item sits at the top of its drive; it may throw when the chain cannot be verified. Null result =
     * [child] is not below [parent], is in no known drive, or the chain is too deep: the provider answers "not found".
     */
    fun documentPath(parent: String?, child: String, parentOf: (String) -> String?): List<String>? {
        fun scheme(id: String) = id.substringBefore(':')
        fun isRoot(id: String) = id.substringAfter(':', "") == "root"
        if (scheme(child) !in SCHEMES) return null
        val up = ArrayList<String>().apply { add(child) }
        var cur = child
        repeat(64) {
            if (parent != null && cur == parent) return up.reversed()
            if (isRoot(cur)) return if (parent == null) up.reversed() else null
            val p = parentOf(cur) ?: (scheme(cur) + ":root")
            up.add(p); cur = p
        }
        return null
    }

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
