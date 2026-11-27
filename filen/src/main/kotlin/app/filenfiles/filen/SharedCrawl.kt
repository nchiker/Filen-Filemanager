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
    private class Entry(val at: Long, val outcome: Outcome, val ttl: Long)
    private val map = LinkedHashMap<String, Entry>(16, 0.75f, true)

    /** The outcome of a search for [id] that ran less than the minimum gap ago, else null (a new search may run). */
    @Synchronized fun recent(id: String): Outcome? = map[id]?.takeIf { now() - it.at in 0 until it.ttl }?.outcome

    /**
     * Remembers a search result. Only a clean, complete crawl ([clean], the default for NOT_FOUND) is kept for the full
     * gap; anything cut off, or any crawl that hit a failing folder (clean = false), is kept at most [SHORT_MS].
     */
    @Synchronized fun note(id: String, outcome: Outcome, clean: Boolean = outcome == Outcome.NOT_FOUND) {
        map.remove(id); map[id] = Entry(now(), outcome, if (clean) minGapMs else minOf(minGapMs, SHORT_MS))
        val it = map.entries.iterator()
        while (map.size > maxEntries && it.hasNext()) { it.next(); it.remove() }
    }

    @Synchronized fun forget(id: String) { map.remove(id) }

    companion object { const val SHORT_MS = 2 * 60_000L }
    @Synchronized fun clear() = map.clear()
    @Synchronized fun size() = map.size
}

/**
 * A global budget of full crawls: at most [max] per [windowMs] across all ids, so many different made-up ids cannot keep
 * this phone crawling. When it is spent the answer is "cannot tell", never "gone".
 */
class CrawlBudget(
    private val max: Int = 6,
    private val windowMs: Long = 60 * 60_000L,
    private val now: () -> Long = System::currentTimeMillis,
) {
    private val starts = ArrayDeque<Long>()
    /** True (and counted) when a crawl may start now. */
    @Synchronized fun tryTake(): Boolean {
        val t = now()
        while (starts.isNotEmpty() && (t - starts.first() >= windowMs || t < starts.first())) starts.removeFirst()
        if (starts.size >= max) return false
        starts.addLast(t); return true
    }
    @Synchronized fun clear() = starts.clear()
}

/**
 * Which outgoing ("Shared by me") ids another app may reach: only what a partner-scoped listing showed, from the root
 * down. An id from the full own-use listing (shares with other contacts) is never in it.
 */
class OutScope {
    private val ids = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()
    /** The partner-scoped root listing: replaces everything (the partner may have changed). */
    fun noteRoot(children: Collection<String>) { ids.clear(); ids.addAll(children) }
    /** A partner-scoped listing of [folder]: its children are in scope only when the folder is. */
    fun noteChildren(folder: String, children: Collection<String>) { if (folder in ids) ids.addAll(children) }
    fun allows(id: String) = id in ids
    fun clear() = ids.clear()
}

/** Short-lived memory of parent links that Filen confirmed just now (I4 F1: reads under a persisted tree grant). */
class VerifiedParents(
    private val ttlMs: Long = 60_000L,
    private val maxEntries: Int = 2_000,
    private val now: () -> Long = System::currentTimeMillis,
) {
    private class E(val parent: String, val at: Long)
    private val map = LinkedHashMap<String, E>(16, 0.75f, true)
    @Synchronized fun get(id: String): String? = map[id]?.takeIf { now() - it.at in 0 until ttlMs }?.parent
    @Synchronized fun put(id: String, parent: String) {
        map.remove(id); map[id] = E(parent, now())
        val it = map.entries.iterator()
        while (map.size > maxEntries && it.hasNext()) { it.next(); it.remove() }
    }
    @Synchronized fun clear() = map.clear()
}

/** I4 F1 offline rule: a stored parent link may stand in for a check only when it came from a fresh listing and is not gone. */
object OfflineRule {
    const val MAX_AGE_MS = 24 * 60 * 60_000L
    fun linkTrusted(listingSavedAt: Long?, now: Long, gone: Boolean, maxAgeMs: Long = MAX_AGE_MS): Boolean =
        !gone && listingSavedAt != null && now - listingSavedAt in 0..maxAgeMs
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
