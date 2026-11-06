package app.filenfiles.transfer

import app.filenfiles.FilesApp
import app.filenfiles.core.FileItem
import app.filenfiles.core.ItemId
import app.filenfiles.core.Names
import app.filenfiles.core.Origin
import app.filenfiles.filen.FailureKind
import app.filenfiles.filen.failureKind
import kotlinx.coroutines.CancellationException
import java.io.File
import java.io.IOException

/**
 * Copies a file or a whole folder tree between any two storages (Filen, received shares, this device), optionally
 * deleting the original afterwards (move). Only used from [TransferManager], which owns queueing and persistence.
 *
 * Rules: names that already exist at the destination get a numbered suffix ("a (1).txt"); a run that is *resumed*
 * (after a restart or a retry) instead skips files already present with the same size and reuses same-named
 * folders, so it picks up where it stopped without duplicating anything. The original is deleted only after
 * every file has been copied.
 */
class CopyRunner(
    private val app: FilesApp,
    private val resumed: Boolean,
    private val setTotal: (Long) -> Unit,
    private val setDone: (Long) -> Unit,
    private val setWaiting: (Boolean) -> Unit,
    private val awaitNetwork: suspend () -> Boolean,
) {
    private val router get() = app.router
    private var base = 0L // bytes of fully copied files

    suspend fun run(source: ItemId, destFolder: ItemId, move: Boolean) {
        val src = router.stat(source) ?: throw IOException("The original is no longer there.")
        require(src.id != destFolder) { "Can't copy a folder into itself." }
        setTotal(scan(src))
        copyNode(src, destFolder)
        if (move) {
            val m = router.modifier(src) ?: throw IOException("Copied, but the original can't be deleted.")
            m.trash(src)
        }
    }

    private suspend fun scan(item: FileItem): Long =
        if (item.isDirectory) router.list(item.id).items.sumOf { scan(it) } else item.size

    private suspend fun copyNode(item: FileItem, dstFolder: ItemId) {
        val storage = router.writable(dstFolder)?.takeIf { it.canWrite(dstFolder) }
            ?: throw IOException("That folder doesn't accept files.")
        val existing = router.list(dstFolder).items
        if (item.isDirectory) {
            // Snapshot first: when the destination is inside the source, the new folder must not be copied into itself.
            val children = router.list(item.id).items
            val target = existing.firstOrNull { resumed && it.isDirectory && it.name == item.name }?.id
                ?: storage.createFolder(dstFolder, Names.unique(existing.map { it.name }, item.name))
            children.filter { it.id != target }.forEach { copyNode(it, target) }
        } else {
            if (resumed && existing.any { !it.isDirectory && it.name == item.name && it.size == item.size }) {
                base += item.size; setDone(base); return
            }
            transferFile(item, dstFolder, Names.unique(existing.map { it.name }, item.name))
            base += item.size; setDone(base)
        }
    }

    private suspend fun transferFile(item: FileItem, dstFolder: ItemId, name: String) {
        val storage = router.writable(dstFolder)!!
        val toDevice = dstFolder.scheme == "local"
        var attempt = 1
        while (true) {
            try {
                if (toDevice && item.origin != Origin.LOCAL) {
                    router.provider(item.id).download(item, File(dstFolder.key, name)) { setDone(base + it) }
                } else {
                    // Everything else is an upload from a local path (for local destinations the "upload" is a plain copy).
                    val viaCache = item.origin != Origin.LOCAL
                    val path = if (viaCache) router.materialize(item) { d, _ -> setDone(base + d / 2) }.path else item.id.key
                    val offset = if (viaCache) item.size / 2 else 0L
                    val share = if (viaCache) 2 else 1
                    storage.upload(dstFolder, path, name, item.mime) { sent -> setDone(base + offset + sent / share) }
                }
                setWaiting(false)
                return
            } catch (e: CancellationException) { throw e
            } catch (e: Throwable) {
                if (e.failureKind() != FailureKind.NETWORK || attempt >= 4) throw e
                setWaiting(true)
                if (!awaitNetwork()) throw IOException("No network connection. Retry when you're back online.")
                setWaiting(false)
                // The failed attempt may have reached the server before the connection dropped.
                if (!toDevice && runCatching { router.list(dstFolder).items.any { !it.isDirectory && it.name == name && it.size == item.size } }.getOrDefault(false)) return
                attempt++
            }
        }
    }
}
