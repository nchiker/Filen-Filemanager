package app.filenfiles.core

/**
 * "Stop sharing" beats a save in progress. A save over a file shared on its own has to share the new version again; it
 * takes a [mark] when it notes who has the file, and must not share if the owner stopped that file ([stoppedSince]) in
 * the meantime. In memory only: nothing is ever re-shared later than the save itself.
 */
class ShareStops {
    private var count = 0L
    private val stopped = HashMap<String, Long>()

    @Synchronized fun mark(): Long = count
    /** [ids]: every id the stopped file is known by (the version's uuid, its whole-life id). */
    @Synchronized fun stop(ids: Collection<String>) { count++; ids.forEach { stopped[it.lowercase()] = count } }
    @Synchronized fun stoppedSince(mark: Long, ids: Collection<String>): Boolean = ids.any { (stopped[it.lowercase()] ?: 0L) > mark }
}
