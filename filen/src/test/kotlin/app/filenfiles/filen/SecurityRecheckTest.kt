package app.filenfiles.filen

import app.filenfiles.core.FileItem
import app.filenfiles.core.ItemId
import app.filenfiles.core.Origin
import java.io.File
import kotlin.test.*

class SecurityRecheckTest {
    // F1: verified-parent TTL
    @Test fun verifiedParentsExpireAfterTtl() {
        var t = 0L
        val v = VerifiedParents(ttlMs = 60_000, now = { t })
        v.put("a", "b")
        t = 59_999; assertEquals("b", v.get("a"))
        t = 60_000; assertNull(v.get("a"))
        assertNull(v.get("unknown"))
    }

    // F1: offline rule
    @Test fun offlineRule() {
        val day = OfflineRule.MAX_AGE_MS
        assertTrue(OfflineRule.linkTrusted(1_000, 1_000 + day, gone = false))
        assertFalse(OfflineRule.linkTrusted(1_000, 1_001 + day, gone = false))
        assertFalse(OfflineRule.linkTrusted(1_000, 2_000, gone = true))
        assertFalse(OfflineRule.linkTrusted(null, 2_000, gone = false))
        assertFalse(OfflineRule.linkTrusted(5_000, 2_000, gone = false)) // clock went back
    }

    private val dir = kotlin.io.path.createTempDirectory("sec-recheck").toFile()
    @AfterTest fun cleanup() { dir.deleteRecursively() }
    private fun item(id: String) = FileItem(ItemId(id), "n", false, 5, 7, "text/plain", Origin.FILEN_SHARED_WITH_ME)

    @Test fun freshParentNeedsFreshListingContainingItAndNotGone() {
        var t = 1_000L
        val s = DocsState(File(dir, "s.bin"), { "acct" }, autosave = false, now = { t })
        s.setParent("filen-in:c", "filen-in:b")
        assertNull(s.freshParent("filen-in:c")) // link without a listing of the parent
        s.putListing("filen-in:b", listOf(item("filen-in:c")))
        assertEquals("filen-in:b", s.freshParent("filen-in:c"))
        t += OfflineRule.MAX_AGE_MS + 1
        assertNull(s.freshParent("filen-in:c")) // listing older than 24 h
        t = 2_000
        s.markGone("filen-in:c")
        assertNull(s.freshParent("filen-in:c"))
    }

    // F2: partner scope
    @Test fun outScopeOnlyFromPartnerListings() {
        val o = OutScope()
        assertFalse(o.allows("filen-out:x"))
        o.noteRoot(listOf("filen-out:p"))
        assertTrue(o.allows("filen-out:p")); assertFalse(o.allows("filen-out:other"))
        o.noteChildren("filen-out:other", listOf("filen-out:k")) // folder not in scope: children are not
        assertFalse(o.allows("filen-out:k"))
        o.noteChildren("filen-out:p", listOf("filen-out:q"))
        assertTrue(o.allows("filen-out:q"))
        o.noteRoot(emptyList()) // partner changed / none
        assertFalse(o.allows("filen-out:p")); assertFalse(o.allows("filen-out:q"))
    }

    // F3: global budget and cache lifetimes
    @Test fun crawlBudgetSixPerHour() {
        var t = 0L
        val b = CrawlBudget(6, 3_600_000, { t })
        repeat(6) { assertTrue(b.tryTake()); t += 1000 }
        assertFalse(b.tryTake())
        t = 3_600_000 - 1; assertFalse(b.tryTake())
        t = 3_600_000; assertTrue(b.tryTake()) // the first one aged out
        assertFalse(b.tryTake())
    }

    @Test fun onlyCleanCrawlCachedForFullGap() {
        var t = 0L
        val g = CrawlGuard(minGapMs = 600_000, now = { t })
        g.note("clean", CrawlGuard.Outcome.NOT_FOUND)
        g.note("dirty", CrawlGuard.Outcome.NOT_FOUND, clean = false)
        g.note("cut", CrawlGuard.Outcome.INCOMPLETE, clean = false)
        t = 119_999
        assertNotNull(g.recent("dirty")); assertNotNull(g.recent("cut"))
        t = 120_000
        assertNull(g.recent("dirty")); assertNull(g.recent("cut"))
        assertEquals(CrawlGuard.Outcome.NOT_FOUND, g.recent("clean"))
        t = 600_000; assertNull(g.recent("clean"))
    }

    // F4: many children moved between big listings
    @Test fun putListingKeepsMovedItemsAndIsFastWithManyListings() {
        val s = DocsState(File(dir, "q.bin"), { "acct" }, autosave = false)
        val n = 3000
        s.putListing("d", (0 until n).map { item("filen-in:$it") })
        (0 until n).forEach { s.setParent("filen-in:$it", "d") }
        s.putListing("e", (0 until n step 2).map { item("filen-in:$it") }) // even ones also listed in e
        s.putListing("d", emptyList())
        assertNull(s.parent("filen-in:1")) // gone from d, nowhere else: link dropped
        assertEquals("d", s.parent("filen-in:2")) // listed in e too: kept
    }
}
