package app.filenfiles.stacklink

import kotlin.test.*

class WatchRulesTest {
    @Test fun quietWaitUnlessTheMaximumIsNear() {
        assertEquals(5_000, WatchRules.delayFor(now = 1_000, firstAt = 1_000, quietMs = 5_000, maxWaitMs = 60_000))
        assertEquals(5_000, WatchRules.delayFor(now = 50_000, firstAt = 1_000, quietMs = 5_000, maxWaitMs = 60_000))
        assertEquals(2_000, WatchRules.delayFor(now = 59_000, firstAt = 1_000, quietMs = 5_000, maxWaitMs = 60_000))
        assertEquals(0, WatchRules.delayFor(now = 90_000, firstAt = 1_000, quietMs = 5_000, maxWaitMs = 60_000))
    }
    @Test fun aSteadyStreamOfEventsStillFiresWithinTheMaximum() {
        var t = 0L; val first = 0L; var fired = -1L
        while (t < 200_000) { // an event every second
            val d = WatchRules.delayFor(t, first, 5_000, 60_000)
            if (t + d <= t + 1_000) { fired = t + d; break } // would fire before the next event
            t += 1_000
        }
        assertTrue(fired in 0..60_000, "fired at $fired")
    }
    @Test fun fingerprintCoversAPrefixPastTheCapAndDetectsChangesInIt() {
        fun fp(lines: List<String>, cap: Int): Pair<String, Boolean> {
            val f = TreeFingerprint(cap); for (l in lines) if (!f.add(l)) break; return f.digest() to f.capped
        }
        val a = (1..10).map { "i$it" }
        val (d1, c1) = fp(a, 5); val (d2, _) = fp(a.take(4) + "changed" + a.drop(5), 5)
        assertTrue(c1); assertNotEquals(d1, d2) // change inside the prefix is seen
        assertEquals(d1, fp(a.take(5) + "later-change" + a.drop(6), 5).first) // beyond the cap: not seen (documented)
        assertNotEquals(fp(a.take(5), 5).first, d1) // capped differs from exactly-5
        assertEquals(fp(a, 20).first, fp(a, 20).first)
        assertFalse(fp(a, 20).second)
    }
}
