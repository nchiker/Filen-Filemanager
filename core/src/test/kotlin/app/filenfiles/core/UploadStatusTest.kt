package app.filenfiles.core

import kotlin.test.*

class UploadStatusTest {
    private fun item(id: String) = FileItem(ItemId(id), "f", false, 5, 0, null, Origin.FILEN_PERSONAL)

    @Test fun summariesForEachPhase() {
        assertEquals("Uploading…", UploadStatus.summary(false, false, null))
        assertEquals("Waiting for network…", UploadStatus.summary(true, false, "Waiting for network…"))
        assertEquals("Upload failed: Not enough space", UploadStatus.summary(false, true, "Not enough space"))
        assertEquals("Upload failed", UploadStatus.summary(false, true, "  "))
        assertEquals("Upload failed", UploadStatus.summary(false, true, null))
    }

    @Test fun failureReasonIsOneShortLine() {
        val s = UploadStatus.summary(false, true, "line one\n\n  line   two " + "x".repeat(500))
        assertTrue(s.startsWith("Upload failed: line one line two"))
        assertFalse('\n' in s)
        assertTrue(s.length <= "Upload failed: ".length + 120)
        assertTrue(UploadStatus.isFailure(s)); assertFalse(UploadStatus.isFailure("Uploading…")); assertFalse(UploadStatus.isFailure(null))
    }

    @Test fun receiptsBridgeTheListingGap() {
        val r = RecentUploads(ttlMs = 1000)
        val parent = ItemId("filen:p"); val other = ItemId("filen:q")
        r.add(parent, item("filen:a"), now = 0)
        assertEquals(listOf(ItemId("filen:a")), r.missingFrom(parent, emptyList(), now = 500).map { it.id })
        assertTrue(r.missingFrom(parent, listOf(ItemId("filen:a")), now = 500).isEmpty()) // the real listing has it now
        assertTrue(r.missingFrom(other, emptyList(), now = 500).isEmpty())                  // another folder
        assertTrue(r.missingFrom(parent, emptyList(), now = 1500).isEmpty())                // expired
    }

    @Test fun addingTheSameFileTwiceKeepsOneReceipt() {
        val r = RecentUploads(ttlMs = 1000); val p = ItemId("filen:p")
        r.add(p, item("filen:a"), 0); r.add(p, item("filen:a"), 100)
        assertEquals(1, r.missingFrom(p, emptyList(), 200).size)
    }
}
