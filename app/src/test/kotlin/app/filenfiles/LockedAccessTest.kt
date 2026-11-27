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

    @Test fun rebuildNeverOverridesAnAnswer() {
        val a = LockedAccess.entry("stack", tree("a")); val b = LockedAccess.entry("stack", tree("b")); val c = LockedAccess.entry("stack", tree("c")); val d = LockedAccess.entry("stack", tree("d"))
        val r = LockedAccess.rebuildUnanswered(setOf(a, b, c, d), readers = setOf(a), readersAsked = setOf(b), writeAsked = setOf(c))
        assertEquals(setOf(d), r)
    }
    @Test fun writePromptOnlyForRecordedNarrowTrees() {
        val rec = setOf(LockedAccess.entry("stack", tree("u1")), LockedAccess.entry("stack", drive), LockedAccess.entry("stack", inRoot))
        assertEquals(listOf(tree("u1")), LockedAccess.askableTrees(rec, listOf("stack"), listOf(tree("u1"), drive, inRoot, outRoot, tree("u2"))))
        assertTrue(LockedAccess.askableTrees(rec, listOf("other"), listOf(tree("u1"))).isEmpty())
    }
    @Test fun allowWriteKeepsAnEarlierNotNowAndRemoveKeepsOtherReading() {
        val e = LockedAccess.entry("stack", tree("u1"))
        // never answered: reader created by the write, marked
        val (r1, a1, o1) = LockedAccess.allowWrite(emptySet(), emptySet(), emptySet(), e)
        assertEquals(setOf(e), r1); assertEquals(setOf(e), a1); assertEquals(setOf(e), o1)
        assertEquals(emptySet<String>(), LockedAccess.removeWrite(r1, o1, e).first) // created by the write: dropped
        // earlier "Not now" for reading: stays off
        val (r2, _, o2) = LockedAccess.allowWrite(emptySet(), setOf(e), emptySet(), e)
        assertTrue(r2.isEmpty() && o2.isEmpty())
        // reading allowed elsewhere (not by the write): Remove leaves it
        val (r3, _, o3) = LockedAccess.allowWrite(setOf(e), setOf(e), emptySet(), e)
        assertEquals(setOf(e), LockedAccess.removeWrite(r3, o3, e).first)
    }
    @Test fun partnerStatusAnsweredWhileLocked() {
        assertTrue(LockedAccess.answeredWhileLocked("partner_status")); assertTrue(LockedAccess.answeredWhileLocked("contract"))
        assertFalse(LockedAccess.answeredWhileLocked("locate")); assertFalse(LockedAccess.answeredWhileLocked("account"))
    }
}
