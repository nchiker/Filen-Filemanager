package app.filenfiles.filen

import android.content.Context
import java.io.File

/**
 * Everything the app writes to disk that is a decrypted copy of cloud data (opened files, thumbnails)
 * All of it is disposable: it is size-capped, swept at startup and wiped on sign-out / session expiry.
 * Content waiting to be uploaded is NOT disposable and lives in [outbox], outside the cache.
 */
object LocalCache {
    const val OPEN = "open"
    const val THUMBS = "thumbs"
    /** Where pending uploads used to be staged; only swept now (a queue saved by an older build may still point there). */
    const val UPLOAD = "upload"
    /** Persisted search index (decrypted names): small, account-bound, wiped with the rest. */
    const val INDEX = "index"
    private const val OPEN_CAP = 1L shl 30        // 1 GiB of opened/decrypted files
    private const val THUMB_CAP = 100L shl 20     // 100 MiB of thumbnails
    private const val MAX_AGE_MS = 7L * 24 * 3600 * 1000
    /** Free space that must remain after staging a download or upload. */
    const val FREE_MARGIN = 64L shl 20

    fun dir(ctx: Context, name: String) = File(ctx.cacheDir, name)
    /** Content on its way to Filen. In files/, so neither Android nor "Clear cache" deletes it; the transfer queue owns it. */
    fun outbox(ctx: Context) = File(ctx.filesDir, "outbox")
    fun freeBytes(ctx: Context) = ctx.cacheDir.usableSpace

    /** Throws an out-of-space error if [bytes] (plus a safety margin) won't fit. */
    fun requireSpace(ctx: Context, bytes: Long) {
        val free = freeBytes(ctx)
        if (free < bytes + FREE_MARGIN) {
            throw java.io.IOException("No space left on device (need ${bytes shr 20} MB, ${free shr 20} MB free)")
        }
    }

    /** At process start: no transfer is running, so partial downloads are leftovers. (Upload staging is swept by TransferManager, which knows what is still queued.) */
    fun sweepAtStartup(ctx: Context) {
        for (n in listOf(OPEN, THUMBS, INDEX)) dir(ctx, n).walkBottomUp().filter { it.isFile && isTemp(it) }.forEach { it.delete() }
        trim(ctx)
    }

    /** Partial-download leftovers: ours (`.part`) and the SDK's own (`.filendl`). */
    fun isTemp(f: File) = f.name.endsWith(".part") || f.name.endsWith(".filendl")

    /** Evicts files older than a week, then least-recently-used files until each cache is under its cap. */
    fun trim(ctx: Context, keep: File? = null) {
        trimDir(dir(ctx, OPEN), OPEN_CAP, keep)
        trimDir(dir(ctx, THUMBS), THUMB_CAP, keep)
    }

    private fun trimDir(root: File, cap: Long, keep: File?) {
        if (!root.isDirectory) return
        val now = System.currentTimeMillis()
        val files = root.walkTopDown().filter { it.isFile && it != keep }.sortedBy { it.lastModified() }.toMutableList()
        var total = root.walkTopDown().filter { it.isFile }.sumOf { it.length() }
        val it = files.iterator()
        while (it.hasNext()) {
            val f = it.next()
            if (total <= cap && now - f.lastModified() < MAX_AGE_MS) break // sorted oldest-first: nothing else is eligible
            total -= f.length(); f.delete()
        }
        root.walkBottomUp().filter { it.isDirectory && it != root && it.list().isNullOrEmpty() }.forEach { it.delete() }
    }

    /** Removes decrypted data on sign-out or session expiry. */
    fun wipeDecrypted(ctx: Context) {
        dir(ctx, OPEN).deleteRecursively(); dir(ctx, THUMBS).deleteRecursively(); dir(ctx, INDEX).deleteRecursively()
    }
}
