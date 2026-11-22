package app.filenfiles.core

import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.io.IOException

/**
 * Offline pinning, pure logic. The user's choices are [PinState.directKeys] (files) and [PinState.folders]; which
 * files must exist on the device is recomputed on every sync from the remote listing ([reconcile]), so a folder
 * pin follows files added to, moved out of or removed from the folder.
 *
 * A file's identity is [RemoteFile.key] (Filen's stable id, which survives edits and version restores; the
 * version-specific uuid is only a fallback when the server reports none). [RemoteFile.version] is the current
 * version uuid: a pinned copy is stale when it differs from what was downloaded.
 */
data class RemoteFile(
    val key: String, val version: String, val name: String, val size: Long, val modified: Long, val mime: String?,
)

/** A copy that is on the device. [version] is the version uuid it was downloaded from. */
data class PinnedFile(
    val key: String, val version: String, val name: String, val size: Long, val modified: Long, val mime: String?,
)

data class PinnedFolder(val uuid: String, val name: String)

data class PinState(
    val account: String,
    /** Files pinned on their own (key -> last known name, so the Offline list can show something if the file is gone). */
    val directKeys: Map<String, String> = emptyMap(),
    val folders: List<PinnedFolder> = emptyList(),
    /** What is on the device right now. */
    val files: List<PinnedFile> = emptyList(),
)

/** What a sync has to do. [orphaned] = pinned files that no longer exist (or are in the trash) remotely; their copy is kept. */
data class PinPlan(
    val download: List<RemoteFile>,
    val rename: List<Pair<PinnedFile, String>>,
    val delete: List<PinnedFile>,
    val orphaned: Set<String>,
)

object Pins {
    /**
     * [wanted] = every remote file that is pinned directly or lives in a pinned folder (already de-duplicated by key).
     * [missingDirect] = keys of direct pins the server no longer has (deleted or trashed).
     */
    fun reconcile(state: PinState, wanted: Collection<RemoteFile>, missingDirect: Set<String>): PinPlan {
        val have = state.files.associateBy { it.key }
        val wantedKeys = HashSet<String>()
        val download = ArrayList<RemoteFile>()
        val rename = ArrayList<Pair<PinnedFile, String>>()
        for (w in wanted) {
            if (!wantedKeys.add(w.key)) continue
            val h = have[w.key]
            when {
                h == null || h.version != w.version -> download.add(w)
                h.name != w.name -> rename.add(h to w.name)
            }
        }
        val orphaned = missingDirect.filter { it in have }.toSet()
        val delete = state.files.filter { it.key !in wantedKeys && it.key !in orphaned }
        return PinPlan(download, rename, delete, orphaned)
    }

    /** True when pinning [bytes] more would leave less than [margin] free. */
    fun tooBig(bytes: Long, free: Long, margin: Long) = free < bytes + margin

    /** Size above which pinning asks for confirmation first. */
    const val WARN_BYTES = 2L shl 30

    /** Folder uuids already covered by a pinned ancestor are redundant; used to avoid listing the same subtree twice. */
    fun withoutNested(folders: List<PinnedFolder>, isAncestor: (ancestor: String, child: String) -> Boolean): List<PinnedFolder> =
        folders.filter { f -> folders.none { o -> o.uuid != f.uuid && isAncestor(o.uuid, f.uuid) } }
}

/**
 * Plain-file persistence of [PinState]: temp file + rename, and a truncated/foreign/corrupt file reads as "no pins"
 * (a lost store only costs a re-pin; the files themselves are swept because nothing references them).
 */
object PinStore {
    private const val MAGIC = 0x4650494E // "FPIN"
    private const val VERSION = 1

    fun save(file: File, s: PinState) {
        file.parentFile?.mkdirs()
        val tmp = File(file.parentFile, file.name + ".part")
        try {
            DataOutputStream(tmp.outputStream().buffered()).use { o ->
                o.writeInt(MAGIC); o.writeInt(VERSION); o.writeUTF(s.account)
                o.writeInt(s.directKeys.size); s.directKeys.forEach { (k, n) -> o.writeUTF(k); o.writeUTF(n) }
                o.writeInt(s.folders.size); s.folders.forEach { o.writeUTF(it.uuid); o.writeUTF(it.name) }
                o.writeInt(s.files.size)
                s.files.forEach {
                    o.writeUTF(it.key); o.writeUTF(it.version); o.writeUTF(it.name)
                    o.writeLong(it.size); o.writeLong(it.modified)
                    o.writeBoolean(it.mime != null); if (it.mime != null) o.writeUTF(it.mime)
                }
            }
            if (!tmp.renameTo(file)) throw IOException("could not replace ${file.name}")
        } finally { tmp.delete() }
    }

    /** Null when absent, unreadable, another version or another [account]. */
    fun load(file: File, account: String): PinState? {
        if (!file.isFile) return null
        return try {
            DataInputStream(file.inputStream().buffered()).use { i ->
                if (i.readInt() != MAGIC || i.readInt() != VERSION) return null
                if (i.readUTF() != account) return null
                val direct = LinkedHashMap<String, String>()
                repeat(i.readInt()) { direct[i.readUTF()] = i.readUTF() }
                val folders = List(i.readInt()) { PinnedFolder(i.readUTF(), i.readUTF()) }
                val files = List(i.readInt()) {
                    PinnedFile(i.readUTF(), i.readUTF(), i.readUTF(), i.readLong(), i.readLong(), if (i.readBoolean()) i.readUTF() else null)
                }
                PinState(account, direct, folders, files)
            }
        } catch (e: Exception) { null }
    }
}

/** What the UI and router see of pinning; implemented by the Filen provider. */
interface PinStorage {
    /** Bumps whenever pins or the offline copies change (for recomposition). */
    val revision: kotlinx.coroutines.flow.StateFlow<Int>
    val syncing: kotlinx.coroutines.flow.StateFlow<Boolean>
    /** Whether this item (file or folder) is pinned directly. */
    fun isPinned(item: FileItem): Boolean
    /** Whether a copy of this file is on the device (pinned directly or through a pinned folder). */
    fun isOffline(item: FileItem): Boolean
    /** Bytes a pin of [items] would download (recursive for folders); needs the network. */
    suspend fun sizeOf(items: List<FileItem>): Long
    fun canPin(item: FileItem): Boolean
    suspend fun pin(items: List<FileItem>)
    suspend fun unpin(items: List<FileItem>)
    /** Everything on the device for the Offline screen; works with no network. */
    fun offline(): List<PinnedFile>
    fun pinnedFolders(): List<PinnedFolder>
    fun localFile(file: PinnedFile): File
    /** Sync now (pinned files and folders); no-op when signed out. */
    suspend fun sync(): Int
    /** Why the last sync could not fetch everything (null when fine). */
    val lastError: String?
}
