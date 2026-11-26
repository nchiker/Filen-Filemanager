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

    private val tree = mapOf("filen:f" to "filen:d", "filen:d" to null, "filen-in:x" to null)
    private fun parentOf(id: String): String? = tree[id]

    @Test fun pathFromRootAndFromParent() {
        assertEquals(listOf("filen:root", "filen:d", "filen:f"), DocsRules.documentPath(null, "filen:f", ::parentOf))
        assertEquals(listOf("filen:d", "filen:f"), DocsRules.documentPath("filen:d", "filen:f", ::parentOf))
        assertEquals(listOf("filen:root", "filen:d"), DocsRules.documentPath("filen:root", "filen:d", ::parentOf))
        assertEquals(listOf("filen-in:root", "filen-in:x"), DocsRules.documentPath(null, "filen-in:x", ::parentOf))
    }
    @Test fun rootResolvesToItself() {
        assertEquals(listOf("filen:root"), DocsRules.documentPath(null, "filen:root") { error("no lookup") })
        assertEquals(listOf("filen-out:root"), DocsRules.documentPath("filen-out:root", "filen-out:root") { error("no lookup") })
    }
    @Test fun notBelowParentOrUnknownDriveIsNull() {
        assertNull(DocsRules.documentPath("filen:other", "filen:f", ::parentOf))
        assertNull(DocsRules.documentPath("filen:root", "filen-in:x", ::parentOf))
        assertNull(DocsRules.documentPath(null, "evil:a") { null })
        assertNull(DocsRules.documentPath(null, "filen:loop") { "filen:loop2" }) // too deep
    }
    @Test fun unverifiableChainPropagates() {
        assertFailsWith<java.io.IOException> { DocsRules.documentPath(null, "filen:f") { throw java.io.IOException("offline") } }
    }
}
