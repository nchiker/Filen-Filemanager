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
}
