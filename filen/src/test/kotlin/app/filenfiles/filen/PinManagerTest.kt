package app.filenfiles.filen

import app.filenfiles.core.*
import kotlinx.coroutines.*
import java.io.File
import java.io.IOException
import kotlin.test.*

/** The device checklist for offline pinning, replayed against a fake Filen (no SDK, no phone). */
class PinManagerTest {
    private class FakeRemote : PinRemote {
        val files = HashMap<String, RemoteFile>()         // key -> current version
        val content = HashMap<String, String>()           // version -> bytes
        val folders = HashMap<String, MutableList<String>>() // folder uuid -> keys
        val trashed = HashSet<String>()
        var offline = false
        var downloads = 0
        var failNext = 0
        var gate: CompletableDeferred<Unit>? = null
        var entered: CompletableDeferred<Unit>? = null

        fun put(key: String, version: String, name: String, text: String, folder: String? = null) {
            files[key] = RemoteFile(key, version, name, text.length.toLong(), 1, null); content[version] = text
            if (folder != null) folders.getOrPut(folder) { mutableListOf() }.let { if (key !in it) it.add(key) }
        }
        override suspend fun file(key: String): RemoteLookup? {
            if (offline) throw IOException("offline")
            val f = files[key] ?: files.values.firstOrNull { it.version == key } ?: return null
            return RemoteLookup(f, f.key in trashed)
        }
        override suspend fun folder(uuid: String): List<RemoteFile>? {
            if (offline) throw IOException("offline")
            val keys = folders[uuid] ?: return null
            return keys.mapNotNull { files[it] }.filter { it.key !in trashed }
        }
        override suspend fun download(file: RemoteFile, dest: File) {
            if (offline) throw IOException("offline")
            entered?.complete(Unit); gate?.await()
            downloads++
            if (failNext > 0) { failNext--; dest.writeText("par"); throw IOException("connection reset") }
            dest.writeText(content.getValue(file.version))
        }
    }

    private lateinit var dir: File
    private lateinit var remote: FakeRemote
    private var account: String? = "me@example.com"
    private var free = 10L shl 30
    private fun mgr() = PinManager(File(dir, "pinned"), { account }, remote, { free })

    @BeforeTest fun setUp() { dir = kotlin.io.path.createTempDirectory("pins").toFile(); remote = FakeRemote(); account = "me@example.com" }
    @AfterTest fun tearDown() { dir.deleteRecursively() }

    private fun item(uuid: String, name: String, isDir: Boolean = false, size: Long = 0) =
        FileItem(ItemId("filen:$uuid"), name, isDir, size, 1, null, Origin.FILEN_PERSONAL)
    private fun read(m: PinManager, version: String) = m.copyOf(version)?.second?.readText()
    private fun onDisk(m: PinManager) = File(dir, "pinned/files").walkTopDown().filter { it.isFile }.map { it.name }.toSet()

    // ---- pin a file; open it with no network ----
    @Test fun pinnedFileOpensOffline() = runBlocking {
        remote.put("k1", "v1", "a.txt", "hello")
        val m = mgr(); val it = item("v1", "a.txt", size = 5)
        m.pin(listOf(it))
        assertTrue(m.isPinned(it)) // recognised before the first sync
        assertEquals(0, m.sync())
        assertEquals("hello", read(m, "v1"))
        assertTrue(m.isOffline(it) && m.isPinned(it))
        remote.offline = true
        assertEquals("hello", read(m, "v1")) // reads never need the network
        assertEquals(1, mgr().offline().size) // survives a restart (state file)
    }

    @Test fun pinningWhileOfflineDoesNotThrowAndSyncsLater() = runBlocking {
        remote.put("k1", "v1", "a.txt", "hello"); remote.offline = true
        val m = mgr(); m.pin(listOf(item("v1", "a.txt")))
        assertFailsWith<IOException> { m.sync() } // listing failed: nothing changes
        assertTrue(m.offline().isEmpty())
        remote.offline = false; m.sync()
        assertEquals("hello", read(m, "v1"))
    }

    // ---- pinned folder follows the web ----
    @Test fun folderPinFollowsAddAndRemove() = runBlocking {
        remote.put("a", "va", "a.txt", "A", "d"); remote.put("b", "vb", "b.txt", "B", "d")
        val m = mgr(); m.pin(listOf(item("d", "Docs", isDir = true))); m.sync()
        assertEquals(setOf("a.txt", "b.txt"), onDisk(m))
        remote.put("c", "vc", "c.txt", "C", "d"); remote.folders.getValue("d").remove("a") // c added, a moved out
        m.sync()
        assertEquals(setOf("b.txt", "c.txt"), onDisk(m))
        remote.trashed.add("b"); m.sync()
        assertEquals(setOf("c.txt"), onDisk(m))
    }

    // ---- edited on the web ----
    @Test fun editedFileIsFetchedAgain() = runBlocking {
        remote.put("k", "v1", "a.txt", "old")
        val m = mgr(); m.pin(listOf(item("v1", "a.txt"))); m.sync()
        remote.put("k", "v2", "a.txt", "new content") // same stable key, new version
        assertEquals("old", read(m, "v1")) // still usable until the sync
        m.sync()
        assertEquals("new content", read(m, "v2"))
        assertNull(m.copyOf("v1"))
        assertEquals(setOf("a.txt"), onDisk(m))
        assertTrue(m.isPinned(item("v2", "a.txt"))) // the new version's id is still recognised
    }

    // ---- rename ----
    @Test fun renameIsLocalNotADownload() = runBlocking {
        remote.put("k", "v1", "old.txt", "data")
        val m = mgr(); m.pin(listOf(item("v1", "old.txt"))); m.sync()
        val n = remote.downloads
        remote.put("k", "v1", "new.txt", "data")
        m.sync()
        assertEquals(n, remote.downloads)
        assertEquals(setOf("new.txt"), onDisk(m))
        assertEquals("data", read(m, "v1"))
    }

    // ---- unpin ----
    @Test fun unpinRemovesTheCopyImmediatelyEvenOffline() = runBlocking {
        remote.put("k", "v1", "a.txt", "x")
        val m = mgr(); val it = item("v1", "a.txt"); m.pin(listOf(it)); m.sync()
        remote.offline = true
        m.unpin(listOf(it))
        assertTrue(onDisk(m).isEmpty())
        assertFalse(m.isOffline(it)); assertFalse(m.isPinned(it))
    }

    @Test fun unpinKeepsAFileThatAPinnedFolderStillHolds() = runBlocking {
        remote.put("k", "v1", "a.txt", "x", "d"); remote.put("z", "vz", "z.txt", "z", "d")
        val m = mgr(); val it = item("v1", "a.txt")
        m.pin(listOf(item("d", "D", isDir = true), it)); m.sync()
        m.unpin(listOf(it))
        assertEquals(setOf("a.txt", "z.txt"), onDisk(m))
        assertFalse(m.isPinned(it)); assertTrue(m.isOffline(it)) // still there via the folder
        m.unpin(listOf(item("d", "D", isDir = true))) // last folder pin: nothing is wanted any more
        assertTrue(onDisk(m).isEmpty())
    }

    // ---- deleted / trashed in Filen ----
    @Test fun fileDeletedInFilenKeepsItsCopyUntilUnpinned() = runBlocking {
        remote.put("k", "v1", "a.txt", "x")
        val m = mgr(); val it = item("v1", "a.txt"); m.pin(listOf(it)); m.sync()
        remote.trashed.add("k"); m.sync()
        assertEquals("x", read(m, "v1"))
        assertTrue("k" in m.orphaned)
        m.unpin(listOf(it)); assertTrue(onDisk(m).isEmpty())
    }

    // ---- failures ----
    @Test fun failedDownloadLeavesNoPartialFileAndRetries() = runBlocking {
        remote.put("k", "v1", "a.txt", "payload"); remote.failNext = 1
        val m = mgr(); m.pin(listOf(item("v1", "a.txt")))
        assertEquals(1, m.sync())
        assertTrue(onDisk(m).isEmpty()); assertNull(m.copyOf("v1"))
        assertEquals(0, m.sync()); assertEquals("payload", read(m, "v1"))
    }

    @Test fun wrongSizeIsNeverAccepted() = runBlocking {
        remote.put("k", "v1", "a.txt", "payload")
        remote.files["k"] = remote.files.getValue("k").copy(size = 999) // listing says 999 bytes, download gives 7
        val m = mgr(); m.pin(listOf(item("v1", "a.txt")))
        assertEquals(1, m.sync()); assertNull(m.copyOf("v1")); assertTrue(onDisk(m).isEmpty())
    }

    @Test fun lowDiskSpaceStopsTheSync() = runBlocking {
        remote.put("k", "v1", "a.txt", "payload"); free = 1000
        val m = mgr(); m.pin(listOf(item("v1", "a.txt")))
        assertEquals(1, m.sync()); assertNotNull(m.lastError); assertEquals(0, remote.downloads)
    }

    // ---- concurrency / sign-out ----
    @Test fun pinAndUnpinDoNotWaitForARunningSync() = runBlocking {
        remote.put("k", "v1", "a.txt", "x"); remote.gate = CompletableDeferred(); remote.entered = CompletableDeferred()
        val m = mgr(); m.pin(listOf(item("v1", "a.txt")))
        val syncing = async(Dispatchers.Default) { m.sync() }
        withTimeout(5000) { remote.entered!!.await() }
        withTimeout(5000) { m.pin(listOf(item("d", "Docs", isDir = true))) } // returns while the download is stuck
        assertEquals(1, m.folders().size)
        remote.gate!!.complete(Unit); syncing.await()
        assertEquals("x", read(m, "v1"))
        assertEquals(1, mgr().folders().size) // the mid-sync pin was not lost by the sync's own commits
    }

    @Test fun signOutDuringASyncLeavesNothingBehind() = runBlocking {
        remote.put("k", "v1", "a.txt", "x"); remote.gate = CompletableDeferred(); remote.entered = CompletableDeferred()
        val m = mgr(); m.pin(listOf(item("v1", "a.txt")))
        val syncing = async(Dispatchers.Default) { m.sync() }
        withTimeout(5000) { remote.entered!!.await() }
        m.wipe(); account = null
        remote.gate!!.complete(Unit); syncing.await()
        assertFalse(File(dir, "pinned").exists(), "pinned/ must not come back after the wipe")
    }

    @Test fun anotherAccountSeesNoPins() = runBlocking {
        remote.put("k", "v1", "a.txt", "x")
        val m = mgr(); m.pin(listOf(item("v1", "a.txt"))); m.sync()
        account = "other@example.com"
        assertTrue(mgr().offline().isEmpty())
    }

    @Test fun strayCopiesAreSwept() = runBlocking {
        remote.put("k", "v1", "a.txt", "x")
        val stray = File(dir, "pinned/files/orphan").apply { mkdirs() }; File(stray, "old.bin").writeText("junk")
        val m = mgr(); m.pin(listOf(item("v1", "a.txt"))); m.sync()
        assertFalse(stray.exists())
    }

    @Test fun sizeOfFolderSumsTheRecursiveListing() = runBlocking {
        remote.put("a", "va", "a.txt", "12345", "d"); remote.put("b", "vb", "b.txt", "123", "d")
        assertEquals(8L, mgr().sizeOf(listOf(item("d", "D", isDir = true))))
    }
}
