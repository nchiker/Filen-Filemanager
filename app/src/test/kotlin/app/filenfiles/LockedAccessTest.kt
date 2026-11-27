package app.filenfiles

import kotlin.test.*

class LockedAccessTest {
    private val inRoot = "content://app.filenfiles.documents/tree/filen-in%3Aroot"
    private val outRoot = "content://app.filenfiles.documents/tree/filen-out%3Aroot"
    private val drive = "content://app.filenfiles.documents/tree/filen%3Aroot"
    private val scans = "content://app.filenfiles.documents/tree/filen%3Aabc"

    @Test fun allowingTwoTreesDoesNotOpenTheDriveGrant() {
        val allowed = setOf(LockedAccess.entry("stack", inRoot), LockedAccess.entry("stack", outRoot))
        assertTrue(LockedAccess.allows(allowed, listOf("stack"), inRoot))
        assertTrue(LockedAccess.allows(allowed, listOf("stack"), outRoot))
        assertFalse(LockedAccess.allows(allowed, listOf("stack"), drive)) // the whole-drive grant stays refused
        assertFalse(LockedAccess.allows(allowed, listOf("other"), inRoot)) // another package is not covered
    }
    @Test fun aNotNowOnOneTreeDoesNotBlockOrOverrideAnother() {
        val asked = setOf(LockedAccess.entry("stack", drive)) // "Not now" for the drive
        val allowed = setOf(LockedAccess.entry("stack", inRoot))
        assertTrue(LockedAccess.allows(allowed, listOf("stack"), inRoot))
        assertFalse(LockedAccess.allows(allowed, listOf("stack"), drive))
        assertEquals(null, LockedAccess.firstUnasked(asked, listOf("stack"), listOf(drive)))
        assertEquals(LockedAccess.entry("stack", scans), LockedAccess.firstUnasked(asked, listOf("stack"), listOf(drive, scans)))
    }
    @Test fun entryRoundTripsAndScopeIsInWords() {
        val e = LockedAccess.entry("p.k", scans)
        assertEquals("p.k", LockedAccess.pkgOf(e)); assertEquals(scans, LockedAccess.treeOf(e))
        assertEquals("your whole Filen Drive", LockedAccess.scope(drive))
        assertEquals("“Shared with me”", LockedAccess.scope(inRoot))
        assertEquals("one folder", LockedAccess.scope(scans))
    }

    private fun tree(u: String) = "content://docs/tree/filen%3A$u"

    @Test fun rebuildFromF11AndGrantedFolders() {
        val r = LockedAccess.rebuildWriters(mapOf("stack" to "u1"), mapOf("stack" to setOf(tree("b1")), "ami" to setOf(tree("b2")))) { tree(it) }
        assertEquals(setOf(LockedAccess.entry("stack", tree("u1")), LockedAccess.entry("stack", tree("b1")), LockedAccess.entry("ami", tree("b2"))), r)
        assertTrue(LockedAccess.rebuildWriters(emptyMap(), emptyMap()) { tree(it) }.isEmpty()) // nothing recorded: nothing invented
    }

    @Test fun refusedWriteIsRememberedAndAskedOnce() {
        val t = tree("u1"); val e = LockedAccess.entry("stack", t)
        var pending = LockedAccess.noteRefusedWrites(emptySet(), emptySet(), emptySet(), listOf("stack"), listOf(t))
        assertEquals(setOf(e), pending)
        assertEquals(e, LockedAccess.nextWriteAsk(pending, emptySet()))
        assertEquals(null, LockedAccess.nextWriteAsk(pending, setOf(e))) // answered: never again
        assertEquals(emptySet<String>(), LockedAccess.noteRefusedWrites(emptySet(), emptySet(), setOf(e), listOf("stack"), listOf(t))) // "Not now" is not re-remembered
        assertEquals(emptySet<String>(), LockedAccess.noteRefusedWrites(emptySet(), setOf(e), emptySet(), listOf("stack"), listOf(t))) // already allowed
        pending = LockedAccess.noteRefusedWrites(pending, emptySet(), emptySet(), listOf("stack"), listOf(t))
        assertEquals(1, pending.size) // repeated refusals stay one entry
        assertEquals(2, LockedAccess.noteRefusedWrites(pending, emptySet(), emptySet(), listOf("stack"), listOf(tree("u2"))).size) // another tree is its own entry
    }
}
