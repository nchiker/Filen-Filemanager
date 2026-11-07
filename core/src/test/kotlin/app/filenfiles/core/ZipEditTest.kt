package app.filenfiles.core

import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream
import kotlin.test.*

class ZipEditTest {
    private val tmp = java.nio.file.Files.createTempDirectory("zipedit").toFile()
    @AfterTest fun cleanup() { tmp.deleteRecursively() }

    private fun zip(name: String, vararg entries: Pair<String, String?>): File {
        val f = File(tmp, name)
        ZipOutputStream(f.outputStream()).use { z ->
            for ((n, content) in entries) {
                z.putNextEntry(ZipEntry(n))
                if (content != null) z.write(content.toByteArray())
                z.closeEntry()
            }
        }
        return f
    }
    private fun file(name: String, content: String) = File(tmp, name).also { it.writeText(content) }
    private fun names(z: File) = ZipFile(z).use { zf -> zf.entries().asSequence().map { it.name }.toList() }
    private fun text(z: File, entry: String) = ZipFile(z).use { zf -> zf.getInputStream(zf.getEntry(entry)).readBytes().decodeToString() }

    @Test fun addsFilesAtTopLevelAndKeepsEverythingElse() {
        val src = zip("a.zip", "hello.txt" to "hi", "docs/" to null, "docs/note.txt" to "n")
        val out = File(tmp, "out.zip")
        val given = ZipEdit.addFiles(src, out, listOf(file("x.txt", "extra") to "x.txt"), "")
        assertEquals(listOf("x.txt"), given)
        assertEquals(listOf("hello.txt", "docs/", "docs/note.txt", "x.txt"), names(out))
        assertEquals("hi", text(out, "hello.txt")); assertEquals("n", text(out, "docs/note.txt")); assertEquals("extra", text(out, "x.txt"))
    }

    @Test fun neverModifiesTheSourceZip() {
        val src = zip("a.zip", "hello.txt" to "hi")
        val before = src.readBytes()
        ZipEdit.addFiles(src, File(tmp, "out.zip"), listOf(file("x.txt", "1") to "x.txt"), "")
        assertContentEquals(before, src.readBytes())
    }

    @Test fun numbersNamesThatClashWithEntriesOrWithEachOther() {
        val src = zip("a.zip", "x.txt" to "old", "docs/" to null)
        val given = ZipEdit.addFiles(src, File(tmp, "out.zip"),
            listOf(file("1", "a") to "x.txt", file("2", "b") to "x.txt", file("3", "c") to "docs"), "")
        assertEquals(listOf("x (1).txt", "x (2).txt", "docs (1)"), given) // a folder name counts as taken too
        assertEquals("old", text(File(tmp, "out.zip"), "x.txt"))
    }

    @Test fun addsInsideAnArchiveFolderAndNumbersOnlyThere() {
        val src = zip("a.zip", "x.txt" to "top", "docs/x.txt" to "in docs")
        val out = File(tmp, "out.zip")
        // docs/ already has x.txt, so the new x.txt is numbered; y.txt is free there.
        val given = ZipEdit.addFiles(src, out, listOf(file("1", "a") to "x.txt", file("2", "b") to "y.txt"), "docs/")
        assertEquals(listOf("x (1).txt", "y.txt"), given)
        assertTrue("docs/x (1).txt" in names(out) && "docs/y.txt" in names(out))
        // A name free in docs is NOT numbered just because the top level has it.
        val g2 = ZipEdit.addFiles(zip("b.zip", "z.txt" to "top"), File(tmp, "o2.zip"), listOf(file("3", "c") to "z.txt"), "docs/")
        assertEquals(listOf("z.txt"), g2)
    }

    @Test fun slashesInNamesCannotCreateFoldersOrEscape() {
        val given = ZipEdit.addFiles(zip("a.zip", "q" to "1"), File(tmp, "out.zip"), listOf(file("1", "a") to "../evil/x.txt"), "")
        assertEquals(listOf(".._evil_x.txt"), given)
    }

    @Test fun entriesThatDifferOnlyByCaseAreBothKept() {
        val out = File(tmp, "out.zip")
        ZipEdit.addFiles(zip("a.zip", "A.txt" to "1", "a.txt" to "2"), out, emptyList(), "")
        assertEquals(listOf("A.txt", "a.txt"), names(out))
    }

    @Test fun checkCallbackCanCancelTheRewrite() {
        val src = zip("a.zip", "a" to "1", "b" to "2")
        assertFailsWith<IllegalStateException> { ZipEdit.addFiles(src, File(tmp, "out.zip"), emptyList(), "", check = { error("cancelled") }) }
    }

    @Test fun reportsCopiedBytes() {
        var total = 0L
        ZipEdit.addFiles(zip("a.zip", "a" to "12345"), File(tmp, "out.zip"), listOf(file("1", "678") to "b"), "", onBytes = { total += it })
        assertEquals(8L, total)
    }

    private fun e(path: String, dir: Boolean = false, size: Long = 1) = ZipEdit.Entry(path, dir, size, 0)

    @Test fun childrenListsFoldersFirstAndSynthesisesMissingFolders() {
        val entries = listOf(e("b.txt"), e("docs/a/deep.txt"), e("docs/", true), e("A.txt"), e("zdir/", true))
        val top = ZipEdit.children(entries, "")
        assertEquals(listOf("docs", "zdir", "A.txt", "b.txt"), top.map { it.name })
        assertEquals(listOf(true, true, false, false), top.map { it.isDir })
        val docs = ZipEdit.children(entries, "docs/")
        assertEquals(listOf("a"), docs.map { it.name }) // "a" exists only through "docs/a/deep.txt"
        assertEquals("docs/a/", docs[0].path)
        assertEquals(listOf("deep.txt"), ZipEdit.children(entries, "docs/a/").map { it.name })
        assertTrue(ZipEdit.children(entries, "nothing/").isEmpty())
    }

    @Test fun childrenDoesNotMatchSiblingFoldersWithTheSamePrefix() {
        val entries = listOf(e("docs/a.txt"), e("docs2/b.txt"))
        assertEquals(listOf("a.txt"), ZipEdit.children(entries, "docs/").map { it.name })
    }

    @Test fun entriesSkipsUnsafePaths() {
        val z = zip("a.zip", "ok.txt" to "1", "../evil.txt" to "2", "/abs.txt" to "3")
        assertEquals(listOf("ok.txt"), ZipEdit.entries(z).map { it.path })
    }
}
