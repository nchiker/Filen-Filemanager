package app.filenfiles.filen

import app.filenfiles.core.FileItem
import app.filenfiles.core.ItemId
import app.filenfiles.core.Origin
import java.io.File
import kotlin.test.*

class DocsStateTest {
    private val dir = kotlin.io.path.createTempDirectory("docs-state").toFile()
    private val file = File(dir, "s.bin")
    private var acct: String? = "acct-1"
    private fun state() = DocsState(file, { acct }, autosave = false)
    private fun item(id: String, name: String = "n") = FileItem(ItemId(id), name, false, 5, 7, "text/plain", Origin.FILEN_SHARED_WITH_ME, lineage = "L")
    @AfterTest fun cleanup() { dir.deleteRecursively() }

    @Test fun parentsAndListingsSurviveANewProcess() {
        val a = state()
        a.setParent("filen-in:c", "filen-in:b"); a.setParent("filen-in:b", "filen-in:root")
        a.putListing("filen-in:b", listOf(item("filen-in:c", "report.sam")))
        a.flush()
        val b = state() // new object = process restart
        assertEquals("filen-in:b", b.parent("filen-in:c"))
        assertEquals("filen-in:root", b.parent("filen-in:b"))
        val l = b.listing("filen-in:b")!!
        assertEquals("report.sam", l.items.single().name)
        assertEquals("L", l.items.single().lineage)
        assertEquals("report.sam", b.storedItem("filen-in:c")?.name)
    }

    @Test fun anotherAccountSeesNothing() {
        state().apply { setParent("x", "y"); flush() }
        acct = "acct-2"
        assertNull(state().parent("x"))
        acct = null
        assertNull(state().parent("x"))
    }

    @Test fun corruptOrTruncatedFileReadsAsNothing() {
        state().apply { setParent("x", "y"); putListing("f", listOf(item("filen-in:1"))); flush() }
        file.writeBytes(file.readBytes().copyOf(20))
        val s = state()
        assertNull(s.parent("x")); assertNull(s.listing("f"))
        file.writeBytes(ByteArray(50) { 7 })
        assertNull(state().parent("x"))
    }

    @Test fun pendingIdsAreNeverWritten() {
        state().apply { setParent("filen-pending:1", "filen:root"); setParent("filen:a", "filen:root"); flush() }
        val s = state()
        assertNull(s.parent("filen-pending:1")); assertEquals("filen:root", s.parent("filen:a"))
    }

    @Test fun boundsDropOldestListingsOnly() {
        val s = DocsState(file, { acct }, autosave = false, maxFolders = 2)
        s.putListing("a", listOf(item("filen-in:1"))); s.putListing("b", emptyList()); s.putListing("c", emptyList())
        assertNull(s.listing("a")); assertNotNull(s.listing("b")); assertNotNull(s.listing("c"))
    }

    @Test fun clearDeletesTheFile() {
        val s = state(); s.setParent("x", "y"); s.flush(); assertTrue(file.exists())
        s.clear(); assertFalse(file.exists()); assertNull(state().parent("x"))
    }

    // ---- finding 1: stale links, gone marks ----

    @Test fun freshListingDropsLinksOfChildrenItNoLongerHas() {
        val s = state()
        s.putListing("d", listOf(item("filen-in:a"), item("filen-in:b")))
        s.setParent("filen-in:a", "d"); s.setParent("filen-in:b", "d")
        s.putListing("d", listOf(item("filen-in:b")))
        assertNull(s.parent("filen-in:a")); assertEquals("d", s.parent("filen-in:b"))
    }

    @Test fun aChildListedElsewhereKeepsItsLink() {
        val s = state()
        s.putListing("d", listOf(item("filen-in:a"))); s.setParent("filen-in:a", "d")
        s.putListing("e", listOf(item("filen-in:a")))
        s.putListing("d", emptyList())
        assertEquals("d", s.parent("filen-in:a"))
    }

    @Test fun aLinkPointingAtAnotherFolderIsNotTouched() {
        val s = state()
        s.putListing("d", listOf(item("filen-in:a")))
        s.setParent("filen-in:a", "moved-to") // learned from the new folder's listing
        s.putListing("d", emptyList())
        assertEquals("moved-to", s.parent("filen-in:a"))
    }

    @Test fun goneItemIsNotAnsweredFromTheStoredListing() {
        val s = state()
        s.putListing("d", listOf(item("filen-in:a", "old"))); s.setParent("filen-in:a", "d")
        assertEquals("old", s.storedItem("filen-in:a")?.name)
        s.markGone("filen-in:a")
        assertTrue(s.isGone("filen-in:a")); assertNull(s.storedItem("filen-in:a")); assertNull(s.parent("filen-in:a"))
    }

    @Test fun goneMarkSurvivesAProcessDeathAndClearsWhenSeenAgain() {
        val a = state(); a.markGone("filen-in:a"); a.flush()
        val b = state(); assertTrue(b.isGone("filen-in:a"))
        b.putListing("d", listOf(item("filen-in:a"))) // shared again: a fresh listing has it
        assertFalse(b.isGone("filen-in:a"))
        b.markGone("filen-in:x"); b.setParent("filen-in:x", "d"); assertFalse(b.isGone("filen-in:x"))
    }

    @Test fun goneMarksAreBounded() {
        val s = DocsState(file, { acct }, autosave = false, maxGone = 3)
        (1..5).forEach { s.markGone("filen-in:$it") }
        assertFalse(s.isGone("filen-in:1")); assertFalse(s.isGone("filen-in:2")); assertTrue(s.isGone("filen-in:5"))
    }

    // ---- finding 2: LRU ----

    @Test fun fullParentMapEvictsTheLeastRecentlyUsedNotTheNewcomer() {
        val s = DocsState(file, { acct }, autosave = false, maxParents = 3)
        s.setParent("a", "p"); s.setParent("b", "p"); s.setParent("c", "p")
        assertEquals("p", s.parent("a")) // a is now recent; b is the oldest
        s.setParent("d", "p")
        assertNull(s.parent("b")); assertEquals("p", s.parent("a")); assertEquals("p", s.parent("d"))
    }

    // ---- finding 7: save ----

    @Test fun overCapListingIsStoredCutAndIncomplete() {
        val s = DocsState(file, { acct }, autosave = false, maxItems = 3)
        s.putListing("d", (1..5).map { item("filen-in:$it") }); s.flush()
        val l = state().let { DocsState(file, { acct }, autosave = false, maxItems = 3) }.listing("d")!!
        assertEquals(3, l.items.size); assertFalse(l.complete)
        val small = state(); small.putListing("e", listOf(item("filen-in:1"))); assertTrue(small.listing("e")!!.complete)
    }

    @Test fun cutListingDoesNotUnlinkItsTruncatedChildren() {
        val s = DocsState(file, { acct }, autosave = false, maxItems = 2)
        s.putListing("d", (1..3).map { item("filen-in:$it") }); s.setParent("filen-in:3", "d")
        s.putListing("d", (1..3).map { item("filen-in:$it") })
        assertEquals("d", s.parent("filen-in:3"))
    }

    @Test fun longNamesAreCutForStorageAtACharacterBoundary() {
        val long = "é".repeat(800) // 1600 bytes
        val s = state(); s.putListing("d", listOf(item("filen-in:1", long), item("filen-in:2", "😀".repeat(400)))); s.flush()
        val l = state().listing("d")!!
        l.items.forEach { assertTrue(it.name.toByteArray().size <= DocsState.MAX_NAME_BYTES); assertTrue(it.name.isNotEmpty()) }
        assertEquals(500, l.items[0].name.length)
        assertEquals("short", DocsState.truncateUtf8("short", 1000))
    }

    @Test fun aBadEntryIsSkippedAndTheRestStillSaves() {
        val s = state()
        val hugeId = "filen-in:" + "x".repeat(70_000) // writeUTF cannot hold this
        s.putListing("d", listOf(item("filen-in:ok", "fine"), item(hugeId, "bad"), item("filen-in:ok2", "fine2")))
        s.setParent(hugeId, "d"); s.setParent("filen-in:ok", "d")
        s.flush()
        val r = state()
        assertEquals(listOf("fine", "fine2"), r.listing("d")!!.items.map { it.name })
        assertEquals("d", r.parent("filen-in:ok"))
    }

    @Test fun clearAfterASnapshotNeverBringsTheFileBack() {
        val s = state(); s.setParent("x", "y"); s.flush(); s.clear(); s.flush()
        assertFalse(file.exists())
    }
}
