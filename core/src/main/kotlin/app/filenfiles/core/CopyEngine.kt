package app.filenfiles.core

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import java.io.File
import java.io.IOException

/**
 * Copies a file or a whole folder tree between any two storages, optionally removing the originals afterwards (move).
 *
 * Names taken at the destination get a numbered suffix. A resumed run skips only files [ledger] says this transfer
 * created AND that are still there unchanged; a matching name and size proves nothing. A move removes only what was
 * copied and has not changed since: anything added or edited meanwhile stays where it is, and the move reports it.
 * Folders in another app's storage are left behind (empty), see [removeCopied].
 */
class CopyEngine(
    private val router: StorageRouter,
    private val ledger: CopyLedger = CopyLedger(),
    private val setTotal: (Long) -> Unit = {},
    private val setDone: (Long) -> Unit = {},
    private val setWaiting: (Boolean) -> Unit = {},
    private val isNetworkError: (Throwable) -> Boolean = { false },
    private val awaitNetwork: suspend () -> Boolean = { false },
    /** How long to wait between asking again for a source listing the other app still reports as loading. */
    private val loadingRetryMs: Long = 2_000,
) {
    private var base = 0L // bytes of fully copied files
    /** Source files that have a verified copy, as they were when copied; and source folders that were walked. */
    private val copiedFiles = HashMap<ItemId, FileItem>()
    private val walkedFolders = HashSet<ItemId>()
    /** Destination folders this run made or reused: never walked as a source (a copy into its own subfolder). */
    private val targets = HashSet<ItemId>()
    /** Items the destination can't take under their name ("a:b.txt" on Filen), with the reason: left out and reported. */
    private val skipped = ArrayList<String>()

    suspend fun run(source: ItemId, destFolder: ItemId, move: Boolean) {
        val src = router.stat(source) ?: throw IOException("The original is no longer there.")
        require(src.id != destFolder) { "Can't copy a folder into itself." }
        setTotal(scan(src))
        copyNode(src, destFolder)
        val kept = if (move) removeOriginals(src) else 0
        if (skipped.isNotEmpty()) {
            val names = skipped.take(3).joinToString("; ") + if (skipped.size > 3) "; and ${skipped.size - 3} more" else ""
            throw IOException("${skipped.size} item(s) were not copied because of their names: $names. Rename them and try again." +
                if (move) " They were left where they were" + (if (kept > skipped.size) ", with ${kept - skipped.size} other item(s) that changed or appeared during the move." else ".") else "")
        }
        if (kept > 0) {
            throw IOException("Copied, but $kept item(s) changed or appeared during the move, or couldn't be deleted. They were left where they were.")
        }
    }

    private suspend fun scan(item: FileItem): Long =
        if (item.isDirectory) listSource(item).items.sumOf { scan(it) } else item.size

    /**
     * A source folder's listing, complete. Another app's cloud storage may say it is still loading; copying from such a
     * listing would silently leave files out, so ask again a few times and otherwise stop with an error.
     */
    private suspend fun listSource(folder: FileItem): Listing {
        repeat(10) {
            val l = router.list(folder.id)
            if (!l.partial) return l
            delay(loadingRetryMs)
        }
        throw IOException("“${folder.name}” is still loading in the app that stores it, so it can't be copied completely. Nothing more was copied; try again later.")
    }

    private suspend fun copyNode(item: FileItem, dstFolder: ItemId) {
        val storage = router.writable(dstFolder)?.takeIf { it.canWrite(dstFolder) }
            ?: throw IOException("That folder doesn't accept files.")
        val existing = router.list(dstFolder).items
        val done = ledger[item.id, dstFolder]
        if (item.isDirectory) {
            // Snapshot first: when the destination is inside the source, the new folder must not be copied into itself.
            val children = listSource(item).items
            val reused = done?.destId?.takeIf { id -> existing.any { it.isDirectory && it.id == id } }
            val name = Names.unique(existing.map { it.name }, item.name)
            if (reused == null) storage.nameProblem(name)?.let { skipped += "“${item.name}” ($it)"; return }
            val target = reused ?: storage.createFolder(dstFolder, name).also { ledger.put(item.id, dstFolder, CopyLedger.Entry(it)) }
            targets += target
            walkedFolders += item.id
            children.filter { it.id !in targets }.forEach { copyNode(it, target) }
            return
        }
        if (done != null && done.size == item.size && done.modified == item.modified &&
            existing.any { !it.isDirectory && it.id == done.destId && it.size == done.destSize && it.modified == done.destModified }
        ) {
            copiedFiles[item.id] = item
        } else {
            val name = Names.unique(existing.map { it.name }, item.name)
            storage.nameProblem(name)?.let { skipped += "“${item.name}” ($it)"; base += item.size; setDone(base); return }
            val copy = transferFile(item, dstFolder, name)
            // Some cloud apps report size 0 right after a write; they check what they can themselves.
            if (copy.size != item.size && !(copy.origin == Origin.EXTERNAL && copy.size == 0L)) {
                throw IOException("The copy of “${item.name}” has the wrong size. The original was kept.")
            }
            // A source that changed while it was read is not "copied": it is neither skipped later nor removed.
            if (unchanged(item)) {
                copiedFiles[item.id] = item
                ledger.put(item.id, dstFolder, CopyLedger.Entry(copy.id, item.size, item.modified, copy.size, copy.modified))
            }
        }
        base += item.size; setDone(base)
    }

    private suspend fun unchanged(item: FileItem): Boolean =
        router.freshStat(item.id)?.let { !it.isDirectory && it.size == item.size && it.modified == item.modified } == true

    private suspend fun transferFile(item: FileItem, dstFolder: ItemId, name: String): FileItem {
        val storage = router.writable(dstFolder)!!
        var attempt = 1
        while (true) {
            try {
                val copy = if (dstFolder.scheme == "local" && item.origin != Origin.LOCAL) {
                    val dest = File(dstFolder.key, name)
                    router.provider(item.id).download(item, dest) { setDone(base + it) }
                    val id = ItemId("local:${dest.absolutePath}")
                    router.stat(id) ?: throw IOException("The copy of “${item.name}” is missing.")
                } else {
                    // Everything else is an upload from a local path (for local destinations the "upload" is a plain copy).
                    val viaCache = item.origin != Origin.LOCAL
                    val path = if (viaCache) router.materialize(item) { d, _ -> setDone(base + d / 2) }.path else item.id.key
                    val offset = if (viaCache) item.size / 2 else 0L
                    val share = if (viaCache) 2 else 1
                    storage.upload(dstFolder, path, name, item.mime) { sent -> setDone(base + offset + sent / share) }
                }
                setWaiting(false)
                return copy
            } catch (e: CancellationException) { throw e
            } catch (e: Throwable) {
                if (!isNetworkError(e) || attempt >= 4) throw e
                setWaiting(true)
                if (!awaitNetwork()) throw IOException("No network connection. Retry when you're back online.")
                setWaiting(false)
                attempt++
            }
        }
    }

    /** Removes the originals that were copied and are unchanged; returns how many items stayed. */
    private suspend fun removeOriginals(src: FileItem): Int {
        val storage = router.modifier(src) ?: throw IOException("Copied, but the original can't be deleted.")
        // Only Filen's own trash is known to be recoverable, so only there is an untouched tree removed as one item (which
        // keeps its structure). The device and other cloud apps delete for good: file by file.
        if (skipped.isEmpty() && src.origin == Origin.FILEN_PERSONAL && stillAsCopied(src)) {
            storage.trash(src)
            return 0
        }
        return removeCopied(src)
    }

    private suspend fun matches(item: FileItem): Boolean =
        copiedFiles[item.id]?.let { it.size == item.size && it.modified == item.modified } == true

    private suspend fun stillAsCopied(item: FileItem): Boolean {
        val now = router.freshStat(item.id) ?: return false
        if (!item.isDirectory) return matches(now)
        return item.id in walkedFolders && router.list(item.id).let { l -> !l.partial && l.items.all { stillAsCopied(it) } }
    }

    /** Removes the copied, unchanged files under [item], then folders left empty. Returns how many items stayed. */
    private suspend fun removeCopied(item: FileItem): Int {
        if (!item.isDirectory) {
            // Looked up again right before the delete.
            // Not found: already gone, or replaced under another id. Either way there is nothing of ours to delete.
            val now = router.freshStat(item.id) ?: return if (router.stat(item.id) == null) 0 else 1
            if (!matches(now)) return 1
            val storage = router.modifier(now) ?: return 1
            return try { storage.trash(now); 0 } catch (e: CancellationException) { throw e } catch (_: Exception) { 1 }
        }
        if (item.id !in walkedFolders) return 1
        val kept = router.list(item.id).items.sumOf { removeCopied(it) }
        if (kept > 0) return kept
        // Another app's listing can be partial and its folder delete takes everything inside: the emptied folder stays.
        if (item.origin == Origin.EXTERNAL) return 0
        val storage = router.modifier(item) ?: return 1
        return try { if (storage.removeEmptyFolder(item) ?: removeIfListedEmpty(storage, item)) 0 else 1 }
        catch (e: CancellationException) { throw e } catch (_: Exception) { 1 }
    }

    private suspend fun removeIfListedEmpty(storage: WritableStorage, folder: FileItem): Boolean {
        if (router.list(folder.id).items.isNotEmpty()) return false
        storage.trash(folder)
        return true
    }
}
