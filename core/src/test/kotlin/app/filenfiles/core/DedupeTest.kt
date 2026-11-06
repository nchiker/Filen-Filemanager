package app.filenfiles.core

import kotlinx.coroutines.runBlocking
import kotlin.test.*

class DedupeTest {
    private fun file(path: String, size: Long, modified: Long = 1000, hash: String? = null) =
        ScanFile(FileItem(ItemId("local:$path"), path.substringAfterLast('/'), false, size, modified, null, Origin.LOCAL), path, hash)

    /** Content per path stands in for the file system; counts how often each stage ran. */
    private class FakeHasher(val content: Map<String, String>) : ContentHasher {
        var quicks = 0; var fulls = 0
        override suspend fun quick(item: FileItem): String? { quicks++; return content[item.id.key]?.take(4) }
        override suspend fun full(item: FileItem): String? { fulls++; return content[item.id.key] }
    }

    @Test fun findsIdenticalFilesOnly() = runBlocking {
        val files = listOf(file("/a/x.jpg", 100), file("/b/x copy.jpg", 100), file("/c/other.jpg", 100), file("/d/unique.bin", 55))
        val h = FakeHasher(mapOf("/a/x.jpg" to "AAAA1", "/b/x copy.jpg" to "AAAA1", "/c/other.jpg" to "AAAA2"))
        val groups = Dedupe.find(files, h)
        assertEquals(1, groups.size)
        assertEquals(setOf("/a/x.jpg", "/b/x copy.jpg"), groups[0].files.map { it.path }.toSet())
        assertEquals(100, groups[0].reclaimable)
    }

    @Test fun sameStartDifferentTailIsNotADuplicate() = runBlocking {
        // Equal quick fingerprint ("AAAA") but different full content must be separated by the full hash.
        val files = listOf(file("/a", 10), file("/b", 10))
        val groups = Dedupe.find(files, FakeHasher(mapOf("/a" to "AAAAx", "/b" to "AAAAy")))
        assertTrue(groups.isEmpty())
    }

    @Test fun sizeFilterSkipsSmallAndEmptyFiles() = runBlocking {
        val files = listOf(file("/a", 0), file("/b", 0), file("/c", 5), file("/d", 5))
        val same = FakeHasher(mapOf("/a" to "", "/b" to "", "/c" to "zzzz", "/d" to "zzzz"))
        assertTrue(Dedupe.find(files, same, minSize = 10).isEmpty())
        assertEquals(1, Dedupe.find(files, same, minSize = 1).size) // empty files are never duplicates of interest
    }

    @Test fun knownHashesNeedNoFileReads() = runBlocking {
        val files = listOf(file("/a", 50, hash = "h1"), file("/b", 50, hash = "h1"), file("/c", 50, hash = "h2"))
        val h = FakeHasher(emptyMap())
        val groups = Dedupe.find(files, h)
        assertEquals(1, groups.size)
        assertEquals(0, h.quicks); assertEquals(0, h.fulls)
    }

    @Test fun groupsComeLargestSavingsFirst() = runBlocking {
        val files = listOf(file("/s1", 10), file("/s2", 10), file("/b1", 1000), file("/b2", 1000), file("/b3", 1000))
        val c = mapOf("/s1" to "same", "/s2" to "same", "/b1" to "BIG1", "/b2" to "BIG1", "/b3" to "BIG1")
        val groups = Dedupe.find(files, FakeHasher(c))
        assertEquals(listOf(2000L, 10L), groups.map { it.reclaimable })
    }

    @Test fun suggestDeletesAlwaysKeepsExactlyOne() {
        val g = DupGroup(listOf(file("/a/new.jpg", 9, modified = 300), file("/b/old.jpg", 9, modified = 100), file("/c/mid.jpg", 9, modified = 200)), 9)
        for (rule in KeepRule.entries) assertEquals(2, Dedupe.suggestDeletes(g, rule).size, "$rule")
        assertEquals("/b/old.jpg", g.files.first { it !in Dedupe.suggestDeletes(g, KeepRule.OLDEST) }.path)
        assertEquals("/a/new.jpg", g.files.first { it !in Dedupe.suggestDeletes(g, KeepRule.NEWEST) }.path)
        // equal path lengths: the tie goes to the older file
        assertEquals("/b/old.jpg", g.files.first { it !in Dedupe.suggestDeletes(g, KeepRule.SHORTEST_PATH) }.path)
        val h = DupGroup(listOf(file("/Download/backup/copy of photo.jpg", 9), file("/DCIM/photo.jpg", 9)), 9)
        assertEquals("/DCIM/photo.jpg", h.files.first { it !in Dedupe.suggestDeletes(h, KeepRule.SHORTEST_PATH) }.path)
    }

    @Test fun suggestDeletesBreaksTiesDeterministically() {
        val g = DupGroup(listOf(file("/b/x.jpg", 9, modified = 5), file("/a/x.jpg", 9, modified = 5)), 9)
        val first = Dedupe.suggestDeletes(g, KeepRule.OLDEST).map { it.path }
        val again = Dedupe.suggestDeletes(DupGroup(g.files.reversed(), 9), KeepRule.OLDEST).map { it.path }
        assertEquals(first, again)
    }

    @Test fun largestRespectsMinimumAndCount() {
        val files = (1..10).map { file("/f$it", it * 100L) }
        assertEquals(listOf("/f10", "/f9", "/f8"), Dedupe.largest(files, minSize = 300, count = 3).map { it.path })
        assertEquals(8, Dedupe.largest(files, minSize = 300).size)
    }
}
