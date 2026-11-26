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
}
