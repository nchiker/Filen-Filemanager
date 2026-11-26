package app.filenfiles.filen

import kotlinx.coroutines.withTimeoutOrNull

/**
 * Remembers when a full top-down search for a shared item last ran, per item id, so a sharer who lists many ids that do
 * not exist cannot make this phone crawl again and again. A search that finished and did not find the item
 * ([Outcome.NOT_FOUND]) answers "gone" for [minGapMs]; one that was cut off ([Outcome.INCOMPLETE]) answers "cannot tell"
 * for [minGapMs]. Bounded: the least recently used ids are forgotten when full. Thread-safe; [now] is injected for tests.
 */
class CrawlGuard(
    private val minGapMs: Long = 10 * 60_000L,
    private val maxEntries: Int = 2_000,
    private val now: () -> Long = System::currentTimeMillis,
) {
    enum class Outcome { NOT_FOUND, INCOMPLETE }
    private class Entry(val at: Long, val outcome: Outcome)
    private val map = LinkedHashMap<String, Entry>(16, 0.75f, true)

    /** The outcome of a search for [id] that ran less than the minimum gap ago, else null (a new search may run). */
    @Synchronized fun recent(id: String): Outcome? = map[id]?.takeIf { now() - it.at in 0 until minGapMs }?.outcome

    @Synchronized fun note(id: String, outcome: Outcome) {
        map.remove(id); map[id] = Entry(now(), outcome)
        val it = map.entries.iterator()
        while (map.size > maxEntries && it.hasNext()) { it.next(); it.remove() }
    }

    @Synchronized fun forget(id: String) { map.remove(id) }
    @Synchronized fun clear() = map.clear()
    @Synchronized fun size() = map.size
}

/** Top-down search of shared folders, bounded in folders and in wall time. */
object SharedCrawl {
    /** One entry of a listing: its id and whether it is a folder. */
    class Child(val id: String, val isDir: Boolean)

    sealed interface Result {
        /** [parent] is the folder whose listing contains the target. */
        data class Found(val parent: String) : Result
        /** Every folder was listed without error and the target is in none of them. */
        data object NotFound : Result
        /** Cut off by the folder count or the wall time: nothing is known. */
        data object Incomplete : Result
    }

    /**
     * Lists folders breadth first from [root] until a listing contains [target]. [list] gives the entries of one folder
     * (it throws when it cannot: the error propagates, nothing is concluded). Stops with [Result.Incomplete] after
     * [maxFolders] listings or [maxMillis] of wall time (checked with [now] between listings, and a hard timeout around
     * a listing that hangs).
     */
    suspend fun search(
        root: String, target: String, maxFolders: Int, maxMillis: Long,
        now: () -> Long = System::currentTimeMillis, list: suspend (String) -> List<Child>,
    ): Result {
        val start = now()
        val queue = ArrayDeque<String>().apply { add(root) }
        val seen = HashSet<String>().apply { add(root) }
        var listed = 0
        val r = withTimeoutOrNull(maxMillis) {
            while (queue.isNotEmpty()) {
                if (listed >= maxFolders || now() - start >= maxMillis) return@withTimeoutOrNull Result.Incomplete
                listed++
                val folder = queue.removeFirst()
                val children = list(folder)
                if (children.any { it.id == target }) return@withTimeoutOrNull Result.Found(folder)
                children.forEach { if (it.isDir && seen.add(it.id)) queue.add(it.id) }
            }
            Result.NotFound
        }
        return r ?: Result.Incomplete
    }
}

/**
 * Write-mode calls under a persisted tree grant do not trust the stored parent map: the chain from the item up to a
 * granted folder is confirmed one step at a time ([step] asks Filen) and the call is refused when it cannot be
 * confirmed (a [step] that throws, for example offline, propagates: the write is refused, never allowed on stale data).
 */
object ChainCheck {
    sealed interface Step {
        data class Up(val parent: String) : Step
        /** The item is a root: nothing above it. */
        data object Top : Step
        /** The server confirms the item or its share is gone. */
        data object Gone : Step
    }
    enum class Verdict { INSIDE, OUTSIDE, GONE }

    /** INSIDE when [start] is one of [trees] or reaches one going up; OUTSIDE at a root or after [maxSteps]. */
    inline fun verify(start: String, trees: Set<String>, maxSteps: Int = 64, step: (String) -> Step): Verdict {
        var cur = start
        repeat(maxSteps) {
            if (cur in trees) return Verdict.INSIDE
            when (val s = step(cur)) {
                is Step.Up -> cur = s.parent
                Step.Top -> return Verdict.OUTSIDE
                Step.Gone -> return Verdict.GONE
            }
        }
        return if (cur in trees) Verdict.INSIDE else Verdict.OUTSIDE
    }
}
