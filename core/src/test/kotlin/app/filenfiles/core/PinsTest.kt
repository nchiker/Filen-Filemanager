package app.filenfiles.core

import java.io.File
import kotlin.test.*

class PinsTest {
    private fun r(key: String, v: String = "v1", name: String = "$key.txt") = RemoteFile(key, v, name, 10, 1, null)
    private fun p(key: String, v: String = "v1", name: String = "$key.txt") = PinnedFile(key, v, name, 10, 1, null)
    private fun st(vararg f: PinnedFile) = PinState("a", files = f.toList())

    @Test fun newFileIsDownloaded() {
        val plan = Pins.reconcile(st(), listOf(r("a")), emptySet())
        assertEquals(listOf("a"), plan.download.map { it.key })
        assertTrue(plan.delete.isEmpty() && plan.rename.isEmpty())
    }

    @Test fun unchangedFileIsLeftAlone() {
        val plan = Pins.reconcile(st(p("a")), listOf(r("a")), emptySet())
        assertTrue(plan.download.isEmpty() && plan.rename.isEmpty() && plan.delete.isEmpty())
    }

    @Test fun changedVersionIsDownloadedAgain() {
        val plan = Pins.reconcile(st(p("a", "v1")), listOf(r("a", "v2")), emptySet())
        assertEquals(listOf("a"), plan.download.map { it.key })
        assertTrue(plan.delete.isEmpty()) // the stale copy stays usable until the new one arrives
    }

    @Test fun renameIsLocalNotADownload() {
        val plan = Pins.reconcile(st(p("a", name = "old.txt")), listOf(r("a", name = "new.txt")), emptySet())
        assertTrue(plan.download.isEmpty())
        assertEquals("new.txt", plan.rename.single().second)
    }

    @Test fun fileNoLongerWantedIsDeleted() { // moved out of a pinned folder, or unpinned
        val plan = Pins.reconcile(st(p("a"), p("b")), listOf(r("a")), emptySet())
        assertEquals(listOf("b"), plan.delete.map { it.key })
    }

    @Test fun duplicateWantedKeyCountsOnce() { // pinned directly and through a folder
        val plan = Pins.reconcile(st(), listOf(r("a"), r("a")), emptySet())
        assertEquals(1, plan.download.size)
    }

    @Test fun missingDirectPinKeepsItsCopy() { // deleted or trashed elsewhere
        val plan = Pins.reconcile(st(p("a")), emptyList(), setOf("a"))
        assertTrue(plan.delete.isEmpty())
        assertEquals(setOf("a"), plan.orphaned)
    }

    @Test fun missingDirectPinWithoutCopyIsNotOrphanedCopy() {
        assertTrue(Pins.reconcile(st(), emptyList(), setOf("a")).orphaned.isEmpty())
    }

    @Test fun freeSpaceCheck() {
        assertTrue(Pins.tooBig(100, 150, 64))
        assertFalse(Pins.tooBig(100, 200, 64))
    }

    @Test fun nestedFoldersAreDropped() {
        val a = PinnedFolder("a", "A"); val b = PinnedFolder("b", "B")
        assertEquals(listOf(a), Pins.withoutNested(listOf(a, b)) { anc, ch -> anc == "a" && ch == "b" })
    }

    @Test fun storeRoundTripAndGuards() {
        val f = File.createTempFile("pins", ".bin"); f.deleteOnExit()
        val s = PinState("acc", mapOf("k" to "n.txt"), listOf(PinnedFolder("d", "Docs")), listOf(p("k"), PinnedFile("m", "v", "m.jpg", 5, 6, "image/jpeg")))
        PinStore.save(f, s)
        assertEquals(s, PinStore.load(f, "acc"))
        assertNull(PinStore.load(f, "other-account"))
        f.writeBytes(f.readBytes().copyOf(20)) // truncated
        assertNull(PinStore.load(f, "acc"))
        assertNull(PinStore.load(File("/nonexistent/pins.bin"), "acc"))
        assertFalse(File(f.parentFile, f.name + ".part").exists())
    }
}
