package app.filenfiles.core

import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.withLock

/** Where an item lives. Shared namespaces are never merged into personal storage. */
enum class Origin { LOCAL, FILEN_PERSONAL, FILEN_SHARED_WITH_ME, FILEN_SHARED_BY_ME }

/**
 * Stable, storage-qualified identity of a file or folder, e.g. `local:/storage/emulated/0/Download`
 * or `filen:<uuid>`. Used as favorites key and navigation argument.
 */
@JvmInline
value class ItemId(val value: String) {
    val scheme: String get() = value.substringBefore(':')
    val key: String get() = value.substringAfter(':')
}

enum class Permission { READ_ONLY, READ_WRITE }

data class FileItem(
    val id: ItemId,
    val name: String,
    val isDirectory: Boolean,
    val size: Long,
    /** epoch millis, 0 when unknown */
    val modified: Long,
    val mime: String?,
    val origin: Origin,
    val permission: Permission = Permission.READ_WRITE,
    /** For items inside a shared tree: who shared it (email), when known. */
    val sharedBy: String? = null,
    val sharedWith: String? = null,
)

/** One entry of a breadcrumb trail. */
data class Crumb(val id: ItemId, val name: String)

/** A browsable place: a root plus the breadcrumb prefix that leads to it. */
data class Folder(val id: ItemId, val name: String, val origin: Origin)

/** A directory listing together with the trail that leads to it. */
data class Listing(val folder: Folder, val items: List<FileItem>, val trail: List<Crumb>)

/** A file made available to a viewer or to Open-With: always a local path/Uri. */
data class MaterializedFile(val path: String, val name: String, val mime: String?)

sealed interface Result2<out T> {
    data class Ok<T>(val value: T) : Result2<T>
    data class Err(val message: String, val cause: Throwable? = null) : Result2<Nothing>
}

/** Implemented once per storage back end (Filen, local). The UI only talks to this. */
interface StorageProvider {
    /** Roots this provider contributes to the Files tab. */
    suspend fun roots(): List<Folder>

    suspend fun list(folder: ItemId): Listing

    suspend fun search(query: String, limit: Int = 100): List<FileItem>

    suspend fun recent(limit: Int = 50): List<FileItem>

    /** Items favorited on the storage itself (e.g. Filen's server-side favorites). */
    suspend fun favorites(): List<FileItem> = emptyList()

    /** Resolve one item (used by favorites). */
    suspend fun stat(id: ItemId): FileItem?

    /** Make the file readable locally (downloading+decrypting into cache if needed). */
    suspend fun materialize(item: FileItem, onProgress: (Long, Long) -> Unit = { _, _ -> }): MaterializedFile

    /**
     * Path of a small cached WebP/JPEG thumbnail for an image [item], or null when this provider has none
     * (the UI then loads the file directly for local items, or shows the type icon).
     */
    suspend fun thumbnail(item: FileItem, sizePx: Int): String? = null

    /**
     * Writes [item]'s content to [dest] (a new file; its folder exists). [onProgress] gets the bytes written so far
     * for this file. The default goes through [materialize] and a copy; providers that can write straight to the
     * destination (Filen) override it to avoid a second copy in the cache.
     */
    suspend fun download(item: FileItem, dest: java.io.File, onProgress: (Long) -> Unit = {}) {
        val m = materialize(item) { done, _ -> onProgress(done) }
        java.io.File(m.path).copyTo(dest, overwrite = false)
        onProgress(item.size)
    }

    fun owns(id: ItemId): Boolean
}

/** Implemented by providers that can create folders and accept uploads. */
interface WritableStorage {
    /** True when [folder] accepts new content (personal drive folders; never received shares). */
    fun canWrite(folder: ItemId): Boolean
    suspend fun createFolder(parent: ItemId, name: String): ItemId
    suspend fun upload(parent: ItemId, localPath: String, name: String, mime: String?, onProgress: (Long) -> Unit = {}): FileItem

    /** True when [item] may be renamed, moved or deleted (never true for received shares). */
    fun canModify(item: FileItem): Boolean
    suspend fun rename(item: FileItem, newName: String): FileItem
    suspend fun move(item: FileItem, newParent: ItemId): FileItem

    /**
     * Deletes [item]. Returns an undo action when the deletion is recoverable (Filen trash),
     * or null when it is permanent (local storage).
     */
    suspend fun trash(item: FileItem): (suspend () -> Unit)?
}

/** Implemented by providers with a recoverable trash (Filen). Items are top-level trashed entries. */
interface TrashStorage {
    suspend fun listTrash(): List<FileItem>
    /** Puts [item] back where it came from. */
    suspend fun restore(item: FileItem)
    suspend fun deletePermanently(item: FileItem)
    suspend fun emptyTrash()
}

/** Implemented by providers that keep favorites on the server (Filen personal drive). */
interface FavoriteStorage {
    /** True when [item]'s favorite flag lives on the server (never for received shares or local files). */
    fun canFavorite(item: FileItem): Boolean
    suspend fun setFavorite(item: FileItem, favorite: Boolean)
}

/** A person items can be shared with (a Filen contact). */
data class ContactInfo(val id: String, val email: String, val name: String?)

/** Implemented by providers that can share with other users and revoke those shares (Filen). */
interface SharingStorage {
    /** True when [item] can be shared with a contact (personal drive items only). */
    fun canShare(item: FileItem): Boolean
    /** True when [item] is one of my outgoing shares at the top level, so sharing can be stopped. */
    fun canUnshare(item: FileItem): Boolean
    suspend fun contacts(): List<ContactInfo>
    suspend fun share(item: FileItem, contact: ContactInfo)
    /** Sends a contact request; the person must accept before anything can be shared with them. */
    suspend fun addContact(email: String)
    /** Revokes the share for every recipient. */
    suspend fun unshare(item: FileItem)
}

/** One earlier version of a file. */
data class VersionInfo(val id: String, val size: Long, val modified: Long, val name: String?)

/** Implemented by providers that keep earlier versions of files (Filen personal drive). */
interface VersionStorage {
    fun canVersion(item: FileItem): Boolean
    suspend fun versions(item: FileItem): List<VersionInfo>
    /** Makes [version] the current content of [item]; returns the item as it is afterwards (its id may change). */
    suspend fun restoreVersion(item: FileItem, version: VersionInfo): FileItem
    suspend fun deleteVersion(item: FileItem, version: VersionInfo)
    /** Whether the account keeps earlier versions at all (a replaced file is simply overwritten when it doesn't). */
    suspend fun versioningEnabled(): Boolean
    suspend fun setVersioningEnabled(enabled: Boolean)
}

/** Combines providers, routing by [ItemId.scheme]. */
class StorageRouter(private val providers: List<StorageProvider>) {
    fun writable(id: ItemId): WritableStorage? =
        providers.firstOrNull { it.owns(id) } as? WritableStorage

    fun modifier(item: FileItem): WritableStorage? =
        (providers.firstOrNull { it.owns(item.id) } as? WritableStorage)?.takeIf { it.canModify(item) }

    fun favoriteStorage(item: FileItem): FavoriteStorage? =
        (providers.firstOrNull { it.owns(item.id) } as? FavoriteStorage)?.takeIf { it.canFavorite(item) }

    fun versioning(item: FileItem): VersionStorage? =
        (providers.firstOrNull { it.owns(item.id) } as? VersionStorage)?.takeIf { it.canVersion(item) }

    fun cleanupScopes(): List<CleanupStorage> = providers.mapNotNull { it as? CleanupStorage }

    fun sharing(): SharingStorage? = providers.firstNotNullOfOrNull { it as? SharingStorage }

    fun trashStorage(): TrashStorage? = providers.firstNotNullOfOrNull { it as? TrashStorage }

    fun provider(id: ItemId): StorageProvider =
        providers.firstOrNull { it.owns(id) } ?: error("No provider for ${id.value}")

    suspend fun roots(): List<Folder> = providers.flatMap { runCatching { it.roots() }.getOrDefault(emptyList()) }
    suspend fun list(id: ItemId): Listing = provider(id).list(id)
    suspend fun stat(id: ItemId): FileItem? = provider(id).stat(id)
    suspend fun serverFavorites(): List<FileItem> =
        providers.flatMap { runCatching { it.favorites() }.getOrDefault(emptyList()) }
    suspend fun materialize(item: FileItem, onProgress: (Long, Long) -> Unit = { _, _ -> }) =
        provider(item.id).materialize(item, onProgress)

    suspend fun thumbnail(item: FileItem, sizePx: Int): String? = provider(item.id).thumbnail(item, sizePx)

    suspend fun search(query: String): List<FileItem> =
        providers.flatMap { runCatching { it.search(query) }.getOrDefault(emptyList()) }

    /** Searches all providers concurrently; emits the cumulative result list each time one finishes. */
    fun searchProgressive(query: String): kotlinx.coroutines.flow.Flow<List<FileItem>> = kotlinx.coroutines.flow.channelFlow {
        val lock = kotlinx.coroutines.sync.Mutex()
        var all = emptyList<FileItem>()
        providers.forEach { p ->
            launch {
                val found = runCatching { p.search(query) }.getOrDefault(emptyList())
                lock.withLock { all = all + found; send(all) }
            }
        }
    }

    suspend fun recent(limit: Int = 50): List<FileItem> =
        providers.flatMap { runCatching { it.recent(limit) }.getOrDefault(emptyList()) }
            .sortedByDescending { it.modified }.take(limit)
}
