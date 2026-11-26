package app.filenfiles.filen

import app.filenfiles.core.FileItem
import app.filenfiles.core.ItemId
import app.filenfiles.core.Origin
import app.filenfiles.filen.DocsRules.ListOutcome.*
import kotlin.test.*

class DocsRulesTest {
    @Test fun freshOnlyWhenListedFromTheServer() {
        assertEquals(FRESH, DocsRules.decideList(null, null, true))
        assertTrue(DocsRules.isFresh(FRESH))
        for (o in listOf(STALE, NOT_FOUND, AUTH, IO)) assertFalse(DocsRules.isFresh(o))
    }

    @Test fun offlineNeverSaysGoneOrFresh() { // removal safety (I6)
        for (k in listOf(FailureKind.NETWORK, FailureKind.OTHER)) {
            assertEquals(STALE, DocsRules.decideList(k, null, true))
            assertEquals(IO, DocsRules.decideList(k, null, false))
            assertEquals(STALE, DocsRules.decideList(k, false, true))
        }
    }

    @Test fun notFoundOnlyWhenTheServerConfirms() {
        assertEquals(NOT_FOUND, DocsRules.decideList(FailureKind.OTHER, true, true))
        assertNotEquals(NOT_FOUND, DocsRules.decideList(FailureKind.NETWORK, null, true))
    }

    @Test fun signedOutIsAuthNotStale() {
        assertEquals(AUTH, DocsRules.decideList(FailureKind.AUTH, null, true))
        assertEquals(AUTH, DocsRules.decideList(FailureKind.AUTH, null, false))
    }

    @Test fun versionChangesWithContent() {
        fun f(id: String, size: Long, mod: Long) = FileItem(ItemId("filen-in:$id"), "a", false, size, mod, null, Origin.FILEN_SHARED_WITH_ME)
        val v = DocsRules.version(f("u1", 5, 10))
        assertEquals(v, DocsRules.version(f("u1", 5, 10)))
        assertNotEquals(v, DocsRules.version(f("u2", 5, 10))) // save-over: new uuid
        assertNotEquals(v, DocsRules.version(f("u1", 6, 10)))
        assertNull(DocsRules.version(f("u1", 5, 10).copy(isDirectory = true)))
    }

    @Test fun accountIdIsStableOpaqueAndDistinct() {
        val a = DocsRules.accountId(42u)
        assertEquals(a, DocsRules.accountId(42u)); assertNotEquals(a, DocsRules.accountId(43u))
        assertEquals(32, a.length); assertFalse(a.contains("42"))
    }
}
