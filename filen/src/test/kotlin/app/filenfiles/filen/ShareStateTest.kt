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
