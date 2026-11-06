package app.filenfiles.filen

import app.filenfiles.core.*
import java.io.File
import kotlin.test.*

class SearchIndexStoreTest {
    private val dir = kotlin.io.path.createTempDirectory("idx").toFile()
    private val file = File(dir, "search.idx")
    @AfterTest fun cleanup() { dir.deleteRecursively() }

    private fun entry(id: String, parent: String?, dir: Boolean = false, by: String? = null) = IndexEntry(
        FileItem(ItemId(id), "näme ✓ $id", dir, 42, 1_700_000_000_000, if (dir) null else "image/png",
            Origin.FILEN_SHARED_WITH_ME, Permission.READ_ONLY, by, null), parent)

    @Test fun roundTrip() {
        val snap = IndexSnapshot("a@b.c", 10, 20, listOf(entry("filen:1", "filen:root"), entry("filen:2", null, true)),
            listOf(entry("filen-in:9", "filen-in:root", by = "x@y.z")))
        SearchIndexStore.save(file, snap)
        val got = assertNotNull(SearchIndexStore.load(file, "a@b.c"))
        assertEquals(10, got.personalAt); assertEquals(20, got.sharedAt)
        assertEquals(listOf("filen:1", "filen:2"), got.personal.map { it.item.id.value })
        assertEquals("filen:root", got.personal[0].parent); assertNull(got.personal[1].parent)
        assertEquals(snap.personal[0].item, got.personal[0].item)
        assertEquals(snap.shared[0].item, got.shared[0].item)
        assertEquals("filen-in:root", got.shared[0].parent)
    }

    @Test fun otherAccountOrCorruptReadsAsNothing() {
        SearchIndexStore.save(file, IndexSnapshot("a@b.c", 1, 1, listOf(entry("filen:1", null)), emptyList()))
        assertNull(SearchIndexStore.load(file, "someone@else"))
        file.writeBytes(file.readBytes().copyOf(30)) // truncated
        assertNull(SearchIndexStore.load(file, "a@b.c"))
        assertNull(SearchIndexStore.load(File(dir, "missing"), "a@b.c"))
    }
}
