package app.filenfiles.core

/** What other apps (through the documents provider) are told about a file that is still on its way to Filen. */
object UploadStatus {
    const val UPLOADING = "Uploading…"
    const val WAITING_NETWORK = "Waiting for network…"
    private const val FAILED = "Upload failed"

    /** One line for COLUMN_SUMMARY; failures carry a short reason so the other app can show it to the user. */
    fun summary(waitingNetwork: Boolean, failed: Boolean, error: String?): String = when {
        failed -> {
            val reason = error.orEmpty().replace(Regex("\\s+"), " ").trim().take(120)
            if (reason.isEmpty()) FAILED else "$FAILED: $reason"
        }
        waitingNetwork -> WAITING_NETWORK
        else -> UPLOADING
    }

    fun isFailure(summary: String?) = summary?.startsWith(FAILED) == true
}

/**
 * Remembers files that finished uploading for a short while. Filen's listing can lag a few seconds behind an upload, so
 * the documents provider adds these to a folder's listing until the real listing includes them - no gap between the
 * "uploading" row going away and the real row appearing.
 */
class RecentUploads(private val ttlMs: Long = 2 * 60_000L) {
    private class Entry(val parent: ItemId, val item: FileItem, val at: Long)
    private val entries = ArrayList<Entry>()

    @Synchronized fun add(parent: ItemId, item: FileItem, now: Long = System.currentTimeMillis()) {
        entries.removeAll { it.item.id == item.id || now - it.at > ttlMs }
        entries.add(Entry(parent, item, now))
    }

    /** Uploads into [parent] that finished less than the TTL ago and are not already present in [listed]. */
    @Synchronized fun missingFrom(parent: ItemId, listed: Collection<ItemId>, now: Long = System.currentTimeMillis()): List<FileItem> {
        entries.removeAll { now - it.at > ttlMs }
        val have = listed.toSet()
        return entries.filter { it.parent == parent && it.item.id !in have }.map { it.item }
    }
}
