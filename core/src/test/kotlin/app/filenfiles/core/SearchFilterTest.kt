package app.filenfiles.core

import kotlin.test.*

class SearchFilterTest {
    private val now = 1_000_000_000_000L
    private fun file(name: String, mime: String?, size: Long = 10, modified: Long = now) =
        FileItem(ItemId("filen:$name"), name, false, size, modified, mime, Origin.FILEN_PERSONAL)
    private val folder = FileItem(ItemId("filen:d"), "d", true, 0, now, null, Origin.FILEN_PERSONAL)

    @Test fun typeFilters() {
        assertTrue(TypeFilter.IMAGES.matches(file("a.png", "image/png")))
        assertFalse(TypeFilter.IMAGES.matches(folder))
        assertTrue(TypeFilter.FOLDERS.matches(folder))
        assertFalse(TypeFilter.FOLDERS.matches(file("a", null)))
        assertTrue(TypeFilter.DOCUMENTS.matches(file("a.pdf", "application/pdf")))
        assertTrue(TypeFilter.DOCUMENTS.matches(file("notes.md", null))) // extension fallback
        assertTrue(TypeFilter.ARCHIVES.matches(file("a.7z", null)))
        assertTrue(TypeFilter.VIDEO.matches(file("a.mp4", "video/mp4")))
        assertTrue(TypeFilter.AUDIO.matches(file("a.mp3", "audio/mpeg")))
        assertTrue(TypeFilter.ANY.matches(folder))
    }

    @Test fun dateFilters() {
        val day = 86_400_000L
        assertTrue(DateFilter.WEEK.matches(file("a", null, modified = now - 6 * day), now))
        assertFalse(DateFilter.WEEK.matches(file("a", null, modified = now - 8 * day), now))
        assertFalse(DateFilter.WEEK.matches(file("a", null, modified = 0), now)) // unknown date
        assertTrue(DateFilter.ANY.matches(file("a", null, modified = 0), now))
    }

    @Test fun sizeFilters() {
        val mb = 1L shl 20
        assertTrue(SizeFilter.SMALL.matches(file("a", null, size = mb - 1)))
        assertFalse(SizeFilter.SMALL.matches(file("a", null, size = mb)))
        assertTrue(SizeFilter.MEDIUM.matches(file("a", null, size = mb)))
        assertTrue(SizeFilter.MEDIUM.matches(file("a", null, size = 100 * mb)))
        assertTrue(SizeFilter.LARGE.matches(file("a", null, size = 100 * mb + 1)))
        assertFalse(SizeFilter.SMALL.matches(folder))
        assertFalse(SizeFilter.SMALL.matches(file("a", null, size = 0))) // unknown size
    }

    @Test fun combinedAndActive() {
        assertFalse(SearchFilters().active)
        val f = SearchFilters(TypeFilter.IMAGES, DateFilter.MONTH, SizeFilter.ANY)
        assertTrue(f.active)
        assertTrue(f.matches(file("a.png", "image/png"), now))
        assertFalse(f.matches(file("a.pdf", "application/pdf"), now))
    }
}

class QuotaTest {
    @Test fun fractionIsClampedAndSafe() {
        assertEquals(0.5f, Quota(50, 100).fraction)
        assertEquals(1f, Quota(150, 100).fraction)
        assertEquals(0f, Quota(10, 0).fraction)
    }
}
