package app.filenfiles.filen

import kotlin.test.*

class ShareStateTest {
    @Test fun cacheServesWithinTtlThenExpires() {
        var t = 1_000L
        val c = OutShareCache(ttlMs = 100, now = { t })
        assertNull(c.get("a@x.com"))
        c.put("A@x.com", setOf("u1"))
        assertEquals(setOf("u1"), c.get("a@X.com")) // case does not matter
        t += 100; assertNotNull(c.get("a@x.com"))
        t += 1; assertNull(c.get("a@x.com"))
    }
    @Test fun clearDropsEverything() {
        val c = OutShareCache(now = { 0 }); c.put("a@x.com", setOf("u")); c.clear(); assertNull(c.get("a@x.com"))
    }
    @Test fun sharedMeansTheItemItself() {
        assertTrue(ShareRules.isShared("ABC", setOf("abc")))
        assertFalse(ShareRules.isShared("def", setOf("abc")))
        assertFalse(ShareRules.isShared("abc", emptySet()))
    }
}

class FolderNamesAndAncestorsTest {
    @Test fun uniqueName() {
        assertEquals("Scans", FolderNames.unique("Scans", listOf("Other")))
        assertEquals("Scans 2", FolderNames.unique("Scans", listOf("scans")))
        assertEquals("Scans 3", FolderNames.unique("Scans", listOf("Scans", "Scans 2")))
    }
    private val tree = mapOf("c" to ("C" to "b"), "b" to ("B" to "a"), "a" to ("A" to null as String?))
    @Test fun nearestSharedFolderAbove() {
        assertEquals("B", Ancestors.nearestShared("c", setOf("b", "a")) { tree[it] })
        assertEquals("A", Ancestors.nearestShared("c", setOf("a")) { tree[it] })
        assertNull(Ancestors.nearestShared("c", setOf("zzz")) { tree[it] })
        assertNull(Ancestors.nearestShared(null, setOf("a")) { tree[it] })
    }
    @Test fun walkIsBoundedAndStopsOnUnknown() {
        var calls = 0
        assertNull(Ancestors.nearestShared("x", setOf("never"), maxSteps = 5) { calls++; "n" to "x" }) // a loop cannot run away
        assertEquals(5, calls)
        assertNull(Ancestors.nearestShared("q", setOf("q")) { null })
    }
}
