package app.filenfiles.filen

import kotlinx.coroutines.runBlocking
import kotlin.test.*

class CrawlTest {
    private var t = 0L
    private fun guard() = CrawlGuard(minGapMs = 600_000, maxEntries = 3, now = { t })

    @Test fun aSearchedIdIsNotCrawledAgainWithinTenMinutes() {
        val g = guard()
        assertNull(g.recent("x"))
        g.note("x", CrawlGuard.Outcome.NOT_FOUND)
        t = 599_999; assertEquals(CrawlGuard.Outcome.NOT_FOUND, g.recent("x"))
        t = 600_000; assertNull(g.recent("x"))
    }
    @Test fun incompleteIsRememberedToo() {
        val g = guard(); g.note("x", CrawlGuard.Outcome.INCOMPLETE)
        assertEquals(CrawlGuard.Outcome.INCOMPLETE, g.recent("x")); g.forget("x"); assertNull(g.recent("x"))
    }
    @Test fun theNegativeCacheIsBoundedLeastRecentlyUsedFirst() {
        val g = guard()
        listOf("a", "b", "c").forEach { g.note(it, CrawlGuard.Outcome.NOT_FOUND) }
        g.recent("a"); g.note("d", CrawlGuard.Outcome.NOT_FOUND)
        assertEquals(3, g.size()); assertNull(g.recent("b")); assertNotNull(g.recent("a")); assertNotNull(g.recent("d"))
    }

    private fun tree(vararg e: Pair<String, List<SharedCrawl.Child>>): suspend (String) -> List<SharedCrawl.Child> {
        val m = e.toMap(); return { m[it] ?: emptyList() }
    }
    private fun d(id: String) = SharedCrawl.Child(id, true)
    private fun f(id: String) = SharedCrawl.Child(id, false)

    @Test fun findsTheFolderHoldingTheTarget() = runBlocking {
        val r = SharedCrawl.search("root", "t", 10, 20_000, { t }, tree("root" to listOf(d("a"), d("b")), "b" to listOf(d("c")), "c" to listOf(f("t"))))
        assertEquals(SharedCrawl.Result.Found("c"), r)
    }
    @Test fun confirmedAbsentOnlyWhenEveryFolderWasListed() = runBlocking {
        assertEquals(SharedCrawl.Result.NotFound, SharedCrawl.search("root", "t", 10, 20_000, { t }, tree("root" to listOf(d("a")), "a" to listOf(f("x")))))
    }
    @Test fun folderCountCapGivesIncompleteNotAbsent() = runBlocking {
        val many = (1..50).map { d("d$it") }
        assertEquals(SharedCrawl.Result.Incomplete, SharedCrawl.search("root", "t", 5, 20_000, { t }, tree("root" to many)))
    }
    @Test fun wallTimeCapStopsTheCrawl() = runBlocking {
        var listed = 0
        val r = SharedCrawl.search("root", "t", 1000, 20_000, { t }) { _ -> listed++; t += 8_000; listOf(d("n$listed")) }
        assertEquals(SharedCrawl.Result.Incomplete, r)
        assertEquals(3, listed) // 0, 8 s, 16 s listed; at 24 s it stops
    }
    @Test fun aListingThatHangsIsCutOffByTheHardTimeout() = runBlocking {
        val r = SharedCrawl.search("root", "t", 10, 100, System::currentTimeMillis) { kotlinx.coroutines.delay(5_000); emptyList() }
        assertEquals(SharedCrawl.Result.Incomplete, r)
    }
    @Test fun aFailingFolderPropagatesInsteadOfConcludingAbsent() {
        assertFailsWith<java.io.IOException> {
            runBlocking { SharedCrawl.search("root", "t", 10, 20_000, { t }) { throw java.io.IOException("offline") } }
        }
    }
    @Test fun aFolderListedTwiceIsVisitedOnce() = runBlocking {
        var n = 0
        SharedCrawl.search("root", "t", 100, 20_000, { t }) { f -> n++; if (f == "root") listOf(d("a"), d("a")) else listOf(d("root")) }
        assertEquals(2, n)
    }

    // ---- write re-verification ----
    private fun chain(vararg e: Pair<String, String>): (String) -> ChainCheck.Step { val m = e.toMap(); return { m[it]?.let(ChainCheck.Step::Up) ?: ChainCheck.Step.Top } }

    @Test fun writeIsInsideWhenTheFreshChainReachesAGrantedFolder() {
        assertEquals(ChainCheck.Verdict.INSIDE, ChainCheck.verify("c", setOf("t"), step = chain("c" to "b", "b" to "t")))
        assertEquals(ChainCheck.Verdict.INSIDE, ChainCheck.verify("t", setOf("t"), step = chain()))
    }
    @Test fun anItemMovedOutsideTheGrantIsOutside() {
        assertEquals(ChainCheck.Verdict.OUTSIDE, ChainCheck.verify("c", setOf("t"), step = chain("c" to "other")))
    }
    @Test fun goneIsReportedAndOfflineRefuses() {
        assertEquals(ChainCheck.Verdict.GONE, ChainCheck.verify("c", setOf("t")) { ChainCheck.Step.Gone })
        assertFailsWith<java.io.IOException> { ChainCheck.verify("c", setOf("t")) { throw java.io.IOException("offline") } }
    }
    @Test fun aLoopEndsOutside() {
        assertEquals(ChainCheck.Verdict.OUTSIDE, ChainCheck.verify("a", setOf("t"), 10, chain("a" to "b", "b" to "a")))
    }
}
