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

    fun owns(id: ItemId): Boolean
}

/** Implemented by providers that can create folders and accept uploads. */
interface WritableStorage {
    /** True when [folder] accepts new content (personal drive folders; never received shares). */
    fun canWrite(folder: ItemId): Boolean
    suspend fun createFolder(parent: ItemId, name: String): ItemId
    suspend fun upload(parent: ItemId, localPath: String, name: String, mime: String?, onProgress: (Long) -> Unit = {}): FileItem
}

/** Combines providers, routing by [ItemId.scheme]. */
class StorageRouter(private val providers: List<StorageProvider>) {
    fun writable(id: ItemId): WritableStorage? =
        providers.firstOrNull { it.owns(id) } as? WritableStorage

    fun provider(id: ItemId): StorageProvider =
        providers.firstOrNull { it.owns(id) } ?: error("No provider for ${id.value}")

    suspend fun roots(): List<Folder> = providers.flatMap { runCatching { it.roots() }.getOrDefault(emptyList()) }
    suspend fun list(id: ItemId): Listing = provider(id).list(id)
    suspend fun stat(id: ItemId): FileItem? = provider(id).stat(id)
    suspend fun serverFavorites(): List<FileItem> =
        providers.flatMap { runCatching { it.favorites() }.getOrDefault(emptyList()) }
    suspend fun materialize(item: FileItem, onProgress: (Long, Long) -> Unit = { _, _ -> }) =
        provider(item.id).materialize(item, onProgress)

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
