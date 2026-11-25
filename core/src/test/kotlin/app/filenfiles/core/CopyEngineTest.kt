package app.filenfiles.core

import kotlinx.coroutines.runBlocking
import org.junit.Test
import java.io.File
import java.io.IOException
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

class CopyEngineTest {
    private class Node(val dir: Boolean, var size: Long = 0, var modified: Long = 1)

    /** A storage held in memory. Paths look like "root/Docs/a.txt". Upload never replaces: the engine must pick free names. */
    private class Mem(val scheme: String, val atomicRmdir: Boolean) : StorageProvider, WritableStorage {
        val nodes = LinkedHashMap<String, Node>().apply { put("root", Node(true)) }
        val trashed = mutableListOf<String>()
        var uploads = 0
        var onUpload: (String) -> Unit = {}
        var uploadedSize: ((Long) -> Long) = { it }
        var source: Mem? = null // where uploaded sizes are read from
        /** Folders whose listing says "still loading" for this many more asks (and then shows only the first child). */
        val loading = HashMap<String, Int>()
        /** Names this storage refuses (like Filen refusing ':'). */
        var refuses: (String) -> String? = { null }

        fun id(path: String) = ItemId("$scheme:$path")
        fun file(path: String, size: Long, modified: Long = 1) { nodes[path] = Node(false, size, modified) }
        fun folder(path: String) { nodes[path] = Node(true) }
        fun paths() = nodes.keys.filter { it != "root" }.sorted()
        private fun origin() = when (scheme) { "local" -> Origin.LOCAL; "saf" -> Origin.EXTERNAL; else -> Origin.FILEN_PERSONAL }
        /** What [stat] answers from, like a cache that never hears of later edits. */
        var staleStat: FileItem? = null
        private fun item(p: String) = nodes.getValue(p).let { FileItem(id(p), p.substringAfterLast('/'), it.dir, it.size, it.modified, null, origin()) }
        private fun children(p: String) = nodes.keys.filter { it.startsWith("$p/") && '/' !in it.removePrefix("$p/") }

        override suspend fun roots() = emptyList<Folder>()
        override suspend fun list(folder: ItemId): Listing {
            val left = loading[folder.key] ?: 0
            if (left > 0) loading[folder.key] = left - 1
            val all = children(folder.key).map(::item)
            return Listing(Folder(folder, "", origin()), if (left > 0) all.take(1) else all, emptyList(), partial = left > 0)
        }
        override fun nameProblem(name: String) = refuses(name)
        override suspend fun search(query: String, limit: Int) = emptyList<FileItem>()
        override suspend fun recent(limit: Int) = emptyList<FileItem>()
        override suspend fun stat(id: ItemId) = staleStat?.takeIf { it.id == id } ?: if (id.key in nodes) item(id.key) else null
        override suspend fun freshStat(id: ItemId) = if (id.key in nodes) item(id.key) else null
        override suspend fun materialize(item: FileItem, onProgress: (Long, Long) -> Unit) = MaterializedFile(item.id.key, item.name, null)
        override fun owns(id: ItemId) = id.scheme == scheme
        override fun canWrite(folder: ItemId) = true
        override suspend fun createFolder(parent: ItemId, name: String): ItemId {
            val p = "${parent.key}/$name"; check(p !in nodes); folder(p); return id(p)
        }
        override suspend fun upload(parent: ItemId, localPath: String, name: String, mime: String?, onProgress: (Long) -> Unit): FileItem {
            uploads++
            onUpload(localPath)
            val p = "${parent.key}/$name"
            check(p !in nodes) { "would replace $p" }
            file(p, uploadedSize(source!!.nodes.getValue(localPath).size), source!!.nodes.getValue(localPath).modified)
            return item(p)
        }
        override fun canModify(item: FileItem) = true
        override suspend fun rename(item: FileItem, newName: String) = error("unused")
        override suspend fun move(item: FileItem, newParent: ItemId) = error("unused")
        override suspend fun trash(item: FileItem): (suspend () -> Unit)? {
            trashed += item.id.key
            nodes.keys.removeAll { it == item.id.key || it.startsWith(item.id.key + "/") }
            return null
        }
        override suspend fun removeEmptyFolder(folder: FileItem): Boolean? {
            if (!atomicRmdir) return null
            if (children(folder.id.key).isNotEmpty()) return false
            nodes.remove(folder.id.key); return true
        }
    }

    private val phone = Mem("local", atomicRmdir = true)
    private val cloud = Mem("filen", atomicRmdir = false)
    private val router = StorageRouter(listOf(phone, cloud))
    init { cloud.source = phone; phone.source = cloud }

    private fun docs() { phone.folder("root/Docs"); phone.file("root/Docs/a.txt", 3); phone.folder("root/Docs/sub"); phone.file("root/Docs/sub/b.txt", 5) }
    private fun move(ledger: CopyLedger = CopyLedger(), src: Mem = phone, dst: Mem = cloud, path: String = "root/Docs") =
        runBlocking { CopyEngine(router, ledger).run(src.id(path), dst.id("root"), move = true) }

    @Test fun plainMoveFromThePhoneRemovesTheWholeFolder() {
        docs(); move()
        assertEquals(emptyList(), phone.paths())
        assertEquals(listOf("root/Docs", "root/Docs/a.txt", "root/Docs/sub", "root/Docs/sub/b.txt"), cloud.paths())
    }

    @Test fun aFileAddedDuringTheMoveStays() {
        docs()
        cloud.onUpload = { if (it.endsWith("b.txt")) phone.file("root/Docs/new.jpg", 9) }
        assertFailsWith<IOException> { move() }
        assertEquals(listOf("root/Docs", "root/Docs/new.jpg"), phone.paths())
    }

    @Test fun aFileChangedDuringTheMoveStays() {
        docs()
        cloud.onUpload = { if (it.endsWith("a.txt")) phone.nodes.getValue("root/Docs/a.txt").modified = 2 }
        assertFailsWith<IOException> { move() }
        assertEquals(listOf("root/Docs", "root/Docs/a.txt"), phone.paths())
    }

    @Test fun aSameNameSameSizeStrangerIsNeverTakenForTheCopy() {
        docs()
        cloud.folder("root/Docs"); cloud.file("root/Docs/a.txt", 3) // unrelated, same name and size
        move() // a first run and a resumed run behave alike: only the ledger lets anything be skipped
        assertEquals(3, cloud.nodes.keys.count { it.startsWith("root/Docs (1)/") })
        assertEquals(listOf("root/Docs", "root/Docs (1)", "root/Docs (1)/a.txt", "root/Docs (1)/sub", "root/Docs (1)/sub/b.txt", "root/Docs/a.txt"), cloud.paths())
        assertEquals(emptyList(), phone.paths())
    }

    @Test fun aResumeSkipsWhatItCopiedAndReusesItsOwnFolders() {
        docs()
        val ledger = CopyLedger()
        cloud.onUpload = { if (it.endsWith("b.txt")) throw IOException("killed") }
        assertFailsWith<IOException> { move(ledger) }
        assertEquals(4, phone.paths().size) // nothing removed
        cloud.onUpload = {}; cloud.uploads = 0
        move(ledger)
        assertEquals(1, cloud.uploads)
        assertEquals(listOf("root/Docs", "root/Docs/a.txt", "root/Docs/sub", "root/Docs/sub/b.txt"), cloud.paths())
        assertEquals(emptyList(), phone.paths())
    }

    @Test fun aResumeCopiesAgainWhenEitherSideChanged() {
        phone.file("root/a.txt", 3); phone.file("root/b.txt", 3)
        val ledger = CopyLedger()
        fun copy(p: String) = runBlocking { CopyEngine(router, ledger).run(phone.id(p), cloud.id("root"), move = false) }
        copy("root/a.txt"); copy("root/b.txt")
        phone.nodes.getValue("root/a.txt").modified = 7        // source edited
        cloud.nodes.getValue("root/b.txt").modified = 8        // destination replaced
        copy("root/a.txt"); copy("root/b.txt")
        assertEquals(listOf("root/a (1).txt", "root/a.txt", "root/b (1).txt", "root/b.txt"), cloud.paths())
    }

    @Test fun aShortCopyKeepsTheOriginal() {
        phone.file("root/a.txt", 3); cloud.uploadedSize = { 1 }
        assertFailsWith<IOException> { move(path = "root/a.txt") }
        assertEquals(listOf("root/a.txt"), phone.paths())
        assertNull(CopyLedger()[phone.id("root/a.txt"), cloud.id("root")])
    }

    @Test fun aNetworkRetryUploadsAgainEvenIfANamesakeIsThere() {
        phone.file("root/a.txt", 3)
        var calls = 0
        cloud.onUpload = { if (++calls == 1) { cloud.file("root/a.txt", 3); throw IOException("net") } }
        // The namesake appears during the failed try; the retry then finds the name taken (the fake refuses to replace).
        assertFailsWith<IllegalStateException> {
            runBlocking { CopyEngine(router, isNetworkError = { it.message == "net" }, awaitNetwork = { true }).run(phone.id("root/a.txt"), cloud.id("root"), move = true) }
        }
        assertEquals(2, calls)
        assertEquals(listOf("root/a.txt"), phone.paths())
    }

    @Test fun anUntouchedCloudFolderIsTrashedAsOneItem() {
        // Cloud to cloud stands in for cloud to phone, whose download writes real files.
        val other = Mem("saf", atomicRmdir = false).also { it.source = cloud }
        cloud.folder("root/D"); cloud.file("root/D/a", 1)
        runBlocking { CopyEngine(StorageRouter(listOf(cloud, other))).run(cloud.id("root/D"), other.id("root"), move = true) }
        assertEquals(listOf("root/D"), cloud.trashed)
    }

    @Test fun aCloudFolderThatGainedAFileKeepsItAndItsFolder() {
        val other = Mem("saf", atomicRmdir = false).also { it.source = cloud }
        cloud.folder("root/D"); cloud.file("root/D/a", 1)
        other.onUpload = { cloud.file("root/D/late", 2) }
        assertFailsWith<IOException> { runBlocking { CopyEngine(StorageRouter(listOf(cloud, other))).run(cloud.id("root/D"), other.id("root"), move = true) } }
        assertEquals(listOf("root/D", "root/D/late"), cloud.paths())
        assertEquals(listOf("root/D/a"), cloud.trashed)
    }

    @Test fun anotherAppsFolderIsEmptiedFileByFileAndNeverDeletedWhole() {
        val drive = Mem("saf", atomicRmdir = false)
        val other = Mem("filen", atomicRmdir = false).also { it.source = drive }
        drive.folder("root/D"); drive.file("root/D/a", 1)
        runBlocking { CopyEngine(StorageRouter(listOf(drive, other))).run(drive.id("root/D"), other.id("root"), move = true) }
        assertEquals(listOf("root/D/a"), drive.trashed)
        assertEquals(listOf("root/D"), drive.paths())
    }

    @Test fun aStaleCachedStatDoesNotHideAnEdit() {
        val other = Mem("saf", atomicRmdir = false).also { it.source = cloud }
        cloud.file("root/a", 1)
        cloud.staleStat = runBlocking { cloud.stat(cloud.id("root/a")) }
        other.onUpload = { cloud.nodes.getValue("root/a").modified = 9 }
        assertFailsWith<IOException> { runBlocking { CopyEngine(StorageRouter(listOf(cloud, other))).run(cloud.id("root/a"), other.id("root"), move = true) } }
        assertEquals(emptyList(), cloud.trashed)
    }

    @Test fun theLedgerSurvivesARestartAndATornLine() {
        val f = File.createTempFile("ledger", "").apply { delete(); deleteOnExit() }
        val e = CopyLedger.Entry(ItemId("filen:x y\tz"), 1, 2, 3, 4)
        CopyLedger(f).put(ItemId("local:/a\nb"), ItemId("filen:root"), e)
        f.appendText("\nhalf\twritten\t12")
        CopyLedger(f).put(ItemId("local:/c"), ItemId("filen:root"), e)
        val read = CopyLedger(f)
        assertEquals(e, read[ItemId("local:/a\nb"), ItemId("filen:root")])
        assertEquals(e, read[ItemId("local:/c"), ItemId("filen:root")])
        assertNull(read[ItemId("local:/c"), ItemId("filen:other")])
    }

    @Test fun aListingStillLoadingIsWaitedForAndNeverCopiedFromHalfDone() {
        docs()
        phone.loading["root/Docs"] = 2 // loads after two asks
        runBlocking { CopyEngine(router, loadingRetryMs = 0).run(phone.id("root/Docs"), cloud.id("root"), move = true) }
        assertEquals(emptyList(), phone.paths())
        assertEquals(4, cloud.paths().size)
    }

    @Test fun aListingThatNeverFinishesLoadingStopsTheMoveAndKeepsEverything() {
        docs()
        phone.loading["root/Docs"] = 1000
        assertFailsWith<IOException> { runBlocking { CopyEngine(router, loadingRetryMs = 0).run(phone.id("root/Docs"), cloud.id("root"), move = true) } }
        assertEquals(4, phone.paths().size)
        assertEquals(emptyList(), cloud.paths())
    }

    @Test fun aNameTheDestinationRefusesIsSkippedTheRestIsCopiedAndTheOriginalStays() {
        docs(); phone.file("root/Docs/a:b.txt", 2); phone.folder("root/Docs/x?"); phone.file("root/Docs/x?/c.txt", 1)
        cloud.refuses = { if (':' in it || '?' in it) "bad character" else null }
        val e = assertFailsWith<IOException> { move() }
        assertTrue(e.message!!.contains("2 item(s) were not copied"))
        assertEquals(listOf("root/Docs", "root/Docs/a:b.txt", "root/Docs/x?", "root/Docs/x?/c.txt"), phone.paths())
        assertEquals(listOf("root/Docs", "root/Docs/a.txt", "root/Docs/sub", "root/Docs/sub/b.txt"), cloud.paths())
    }
}
