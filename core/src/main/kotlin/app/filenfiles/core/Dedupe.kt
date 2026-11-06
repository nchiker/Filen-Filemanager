package app.filenfiles.core

import kotlinx.coroutines.ensureActive
import kotlin.coroutines.coroutineContext

/** A file found by a storage scan. [hash] is set when the storage already knows the content hash (Filen); [path] is for display. */
data class ScanFile(val item: FileItem, val path: String, val hash: String? = null)

/** Cheap-to-expensive content fingerprints for files whose hash isn't known up front (device storage). */
interface ContentHasher {
    /** Fingerprint of a few KB (start + end) of the file; equal files always match, different files almost never do. */
    suspend fun quick(item: FileItem): String?
    /** Fingerprint of the whole content. Two files with equal full hashes (and sizes) are treated as identical. */
    suspend fun full(item: FileItem): String?
}

/** Implemented by providers that can enumerate all their files for cleanup tools. */
interface CleanupStorage {
    /** Shown in the scope picker: "This device", "Filen Drive". */
    val label: String
    /** Every file (not folders), with its display path; [onProgress] gets the count so far. Hashes are filled in when free. */
    suspend fun scanFiles(onProgress: (Int) -> Unit = {}): List<ScanFile>
    val hasher: ContentHasher
}

/** Identical files: same size and same content. */
data class DupGroup(val files: List<ScanFile>, val size: Long) {
    val reclaimable: Long get() = size * (files.size - 1)
}

enum class KeepRule(val label: String) { OLDEST("Keep the oldest"), NEWEST("Keep the newest"), SHORTEST_PATH("Keep the shortest path") }

object Dedupe {
    /**
     * Groups identical files. Stages narrow the candidates cheaply: same size -> same quick fingerprint -> same full
     * hash. Files with a known [ScanFile.hash] skip the I/O stages. Groups come back largest-savings first.
     * [onProgress] receives (stage description, done, total).
     */
    suspend fun find(
        files: List<ScanFile>, hasher: ContentHasher, minSize: Long = 1,
        onProgress: (String, Int, Int) -> Unit = { _, _, _ -> },
    ): List<DupGroup> {
        val bySize = files.filter { !it.item.isDirectory && it.item.size >= minSize && it.item.size > 0 }
            .groupBy { it.item.size }.values.filter { it.size > 1 }
        val candidates = bySize.flatten()
        var done = 0

        // Stage 1: quick fingerprint (skipped for files that already carry a content hash).
        val quickGroups = ArrayList<List<ScanFile>>()
        for (sameSize in bySize) {
            val m = HashMap<String, MutableList<ScanFile>>()
            for (f in sameSize) {
                coroutineContext.ensureActive()
                val key = f.hash?.let { "h:$it" } ?: hasher.quick(f.item)?.let { "q:$it" }
                if (key != null) m.getOrPut(key) { ArrayList() } += f
                onProgress("Comparing files", ++done, candidates.size)
            }
            quickGroups += m.values.filter { it.size > 1 }
        }

        // Stage 2: confirm with a full hash where the content hash wasn't already known.
        val toConfirm = quickGroups.sumOf { g -> g.count { it.hash == null } }
        var confirmed = 0
        val result = ArrayList<DupGroup>()
        for (g in quickGroups) {
            val m = HashMap<String, MutableList<ScanFile>>()
            for (f in g) {
                coroutineContext.ensureActive()
                val key = f.hash?.let { "h:$it" } ?: hasher.full(f.item)?.let { "f:$it" }
                if (key != null) m.getOrPut(key) { ArrayList() } += f
                if (f.hash == null) onProgress("Checking contents", ++confirmed, toConfirm)
            }
            m.values.filter { it.size > 1 }.forEach { result += DupGroup(it, it.first().item.size) }
        }
        return result.sortedByDescending { it.reclaimable }
    }

    /** Which files of [group] to delete so that exactly one survives, according to [rule]. */
    fun suggestDeletes(group: DupGroup, rule: KeepRule): List<ScanFile> {
        val keep = when (rule) {
            KeepRule.OLDEST -> group.files.minWith(compareBy<ScanFile> { it.item.modified.takeIf { m -> m > 0 } ?: Long.MAX_VALUE }.thenBy { it.path.length }.thenBy { it.path })
            KeepRule.NEWEST -> group.files.maxWith(compareBy<ScanFile> { it.item.modified }.thenByDescending { it.path.length }.thenByDescending { it.path })
            KeepRule.SHORTEST_PATH -> group.files.minWith(compareBy<ScanFile> { it.path.length }.thenBy { it.item.modified }.thenBy { it.path })
        }
        return group.files.filter { it !== keep }
    }

    /** The [count] largest files at least [minSize] bytes, biggest first. */
    fun largest(files: List<ScanFile>, minSize: Long, count: Int = 100): List<ScanFile> =
        files.filter { !it.item.isDirectory && it.item.size >= minSize }.sortedByDescending { it.item.size }.take(count)
}
