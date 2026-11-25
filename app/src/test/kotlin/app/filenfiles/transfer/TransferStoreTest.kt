package app.filenfiles.transfer

import app.filenfiles.core.ItemId
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class TransferStoreTest {
    private val root = Files.createTempDirectory("store").toFile().apply { deleteOnExit() }
    private val filesDir = File(root, "files").apply { mkdirs() }
    private val outbox = File(filesDir, "outbox")
    private val oldStaging = File(root, "cache/upload")
    private var account: String? = "a@x"
    private val parent = ItemId("filen:root")

    private fun store() = TransferStore(filesDir, outbox, oldStaging) { account }.apply { open() }

    /** A file another app wrote through the provider, as createDocument + the writer leave it. */
    private fun providerWrite(s: TransferStore, name: String = "n.txt", text: String = "hello"): File {
        val dir = File(outbox, "doc-${System.nanoTime()}-1").apply { mkdirs() }
        s.describeWrite(dir, "filen-pending:1-1", parent, name, "text/plain")
        return File(dir, name).apply { writeText(text) }
    }

    private fun upload(s: TransferStore, file: File? = null) =
        Transfer(s.nextId(), "n.txt", "", parent, "text/plain", 5, file = file, docId = file?.let { "filen-pending:1-1" }).also { s.add(listOf(it)) }

    @Test fun holdKeepsEverythingAndResumeCarriesOnForTheSameAccount() {
        val s = store()
        val f = providerWrite(s)
        val t = upload(s, f)
        assertEquals(listOf(t.id), s.hold())
        assertEquals(TransferState.FAILED, s.find(t.id)!!.state)
        s.afterJob(t)                                  // the cancelled job stops: a held transfer keeps its file
        assertTrue(f.isFile)
        account = null; assertEquals(emptyList(), s.resumeStates()) // still signed out
        account = "a@x"; assertEquals(listOf(t.id), s.resumeStates())
    }

    @Test fun anotherAccountNeverRunsItAndCantRetryIt() {
        val s = store()
        val t = upload(s)
        account = "b@x"
        assertEquals(emptyList(), s.resumeStates())
        assertEquals(TransferState.FAILED, s.find(t.id)!!.state)
        assertFalse(s.retry(t.id))
        assertEquals(0, s.unfinishedCount())
        account = "a@x"
        assertEquals(listOf(t.id), s.resumeStates())
    }

    @Test fun aRunningJobKeepsItsFileUntilItStops() {
        val s = store()
        val f = providerWrite(s)
        val t = upload(s, f)
        s.cancel(t.id, running = true)
        assertTrue(f.isFile)                            // the job may still be naming the upload
        s.finish(t.id)                                  // ... and it finished: say so, don't claim it was cancelled
        assertEquals(TransferState.DONE, s.find(t.id)!!.state)
        assertTrue(s.find(t.id)!!.error!!.contains("Finished before"))
        s.afterJob(t)
        assertFalse(f.exists())
    }

    @Test fun cancelOfAQueuedTransferDeletesAtOnce() {
        val s = store()
        val f = providerWrite(s)
        val t = upload(s, f)
        s.cancel(t.id, running = false)
        assertFalse(f.exists())
    }

    @Test fun aFailedJobKeepsItsStagedCopyForRetry() {
        val s = store()
        val t = upload(s)
        val staged = File(s.stagingDir(t.id), "n.txt").apply { parentFile.mkdirs(); writeText("x") }
        s.fail(t.id, "boom"); s.afterJob(t)
        assertTrue(staged.isFile)
        assertTrue(s.retry(t.id))
        s.update(t.id) { it.copy(state = TransferState.FAILED) }
        s.dismiss(t.id, running = false)
        assertFalse(staged.exists())
    }

    @Test fun signOutForgetsOnlyThatAccountsWork() {
        val s = store()
        val mine = providerWrite(s, "a.txt"); val tm = upload(s, mine)
        account = "b@x"
        val theirs = providerWrite(s, "b.txt"); val tt = upload(s, theirs)
        account = null
        s.clearFor("b@x")
        assertTrue(mine.isFile); assertFalse(theirs.exists())
        assertEquals(listOf(tm.id), s.items.value.map { it.id })
        assertNull(s.find(tt.id))
    }

    @Test fun aWriteThatNeverReachedTheQueueComesBackAsFailedNotUploaded() {
        val s = store()
        val f = providerWrite(s)                        // the process dies before enqueueFile
        val again = store()
        val t = again.items.value.single()
        assertEquals(TransferState.FAILED, t.state)
        assertEquals(TransferStore.INTERRUPTED, t.error)
        assertEquals(f, t.file)
        assertEquals("a@x", t.account)
        assertEquals(emptyList(), again.resumeStates()) // never uploaded by itself
        store(); assertTrue(f.isFile)                  // and kept across restarts
    }

    @Test fun emptyAndUnreferencedStagingIsSwept() {
        store()
        val emptyDoc = File(outbox, "doc-1-1").apply { mkdirs() }
        val strayNumbered = File(outbox, "77").apply { mkdirs(); File(this, "x").writeText("x") }
        store()
        assertFalse(emptyDoc.exists()); assertFalse(strayNumbered.exists())
    }

    @Test fun anUnreadableQueueStopsAllSweepingAndIdsSkipItsFolders() {
        val s = store()
        upload(s); s.save()
        val staged = File(outbox, "41").apply { mkdirs(); File(this, "p.jpg").writeText("only copy") }
        File(filesDir, "transfers.json").writeText("{ not json")
        val again = store()
        assertTrue(staged.isDirectory)
        assertTrue(again.nextId() > 41)
        store(); assertTrue(staged.isDirectory)        // still kept on the launch after
    }

    @Test fun aQueueSavedBy048IsAdoptedFromTheCache() {
        // 0.4.8: a picked upload staged in cache/upload/<id>/ and a provider write in cache/upload/doc-*/.
        val picked = File(oldStaging, "5/photo.jpg").apply { parentFile.mkdirs(); writeText("p") }
        val written = File(oldStaging, "doc-9-1/n.txt").apply { parentFile.mkdirs(); writeText("w") }
        val items = JSONArray()
            .put(JSONObject().put("id", 5).put("name", "photo.jpg").put("uri", "content://x/1").put("parent", "filen:root")
                .put("mime", "image/jpeg").put("size", 1).put("state", "QUEUED").put("error", JSONObject.NULL).put("file", JSONObject.NULL)
                .put("startedAt", 0).put("staged", true).put("kind", "UPLOAD"))
            .put(JSONObject().put("id", 6).put("name", "n.txt").put("uri", "file://x").put("parent", "filen:root")
                .put("mime", "text/plain").put("size", 1).put("state", "FAILED").put("error", "net").put("file", written.absolutePath)
                .put("startedAt", 0).put("staged", false).put("kind", "UPLOAD").put("docId", "filen-pending:9-1"))
        File(filesDir, "transfers.json").writeText(JSONObject().put("v", 1).put("items", items).toString())
        val s = store()
        assertEquals("p", File(s.stagingDir(5), "photo.jpg").readText())
        val t6 = s.find(6)!!
        assertEquals("w", t6.file!!.readText())
        assertTrue(t6.file!!.path.startsWith(outbox.path))
        assertNull(t6.account)
        assertFalse(picked.exists())
    }

    @Test fun idsNeverReuseALeftoverLedger() {
        File(filesDir, "${TransferStore.LEDGERS}/12").apply { parentFile.mkdirs(); writeText("x") }
        assertTrue(store().nextId() > 12)
    }

    @Test fun anUnsentSaveIsWhatReadersGetAndSurvivesAsAFailedSave() {
        val s = store()
        val dir = File(outbox, "doc-save-1-1").apply { mkdirs() }
        s.describeWrite(dir, "filen:old", parent, "Budget.xlsx", "x", lineage = "L", base = "v1")
        val f = File(dir, "Budget.xlsx").apply { writeText("new") }
        val again = store()                                  // died before the save was queued
        val t = again.items.value.single()
        assertEquals(TransferKind.SAVE, t.kind)
        assertEquals("L", t.lineage); assertEquals("v1", t.base)
        assertNull(t.docId)                                  // an existing file's id is not a pending upload's
        assertEquals(f, again.pendingSave("L")!!.file)
        assertEquals(emptyList(), again.resumeStates())      // never uploaded by itself
    }

    @Test fun ownSavesAreRememberedPerFileAndApp() {
        val s = store()
        s.recordOwnSave("L", "v2", 5, writer = 10); s.recordOwnSave("M", "x", 6, writer = 10); s.recordOwnSave("L", "v3", 7, writer = 10)
        s.recordOwnSave("L", "other-app", 6, writer = 11)
        assertEquals(listOf("v2", "v3"), store().ownSaves("L", writer = 10))
        assertEquals(listOf("v2"), store().ownSaves("L", writer = 10, before = 7)) // an older save never builds on a newer one
    }

    @Test fun aWriteStartsFromWhatThatAppReadNotFromWhatIsCurrentLater() {
        val s = store()
        assertEquals(null, s.baseFor(10, "L", copied = null, copiedFrom = null)) // never read: unknown, kept as a copy
        s.noteRead(10, "L", "v1")
        s.noteRead(11, "L", "v2") // another app read the newer version
        assertEquals("v1", store().baseFor(10, "L", copied = null, copiedFrom = null)) // survives a restart
        assertEquals("v2", store().baseFor(10, "L", copied = "v2", copiedFrom = null)) // given v2 to edit in place
        assertEquals("v2", store().readBase(10, "L"))
    }

    @Test fun aWriteBuiltOnAnotherAppsUnsentSaveHasNoKnownBase() {
        val s = store()
        s.noteRead(10, "L", "v1"); s.noteRead(11, "L", "v1")
        val unsent = Transfer(1, "a", "", parent, null, 1, kind = TransferKind.SAVE, lineage = "L", base = "v1", writer = 10)
        assertEquals("v1", s.baseFor(10, "L", null, unsent))
        assertEquals(null, s.baseFor(11, "L", null, unsent))
    }

    @Test fun aSaveKeepsItsFieldsAcrossARestart() {
        val s = store()
        val dir = File(outbox, "doc-save-2-1").apply { mkdirs() }
        val f = File(dir, "a.txt").apply { writeText("x") }
        val t = Transfer(s.nextId(), "a.txt", "", parent, "text/plain", 1, file = f, kind = TransferKind.SAVE, lineage = "L", base = "v1", writer = 10)
        s.add(listOf(t)); s.save()
        val back = store().find(t.id)!!
        assertEquals(TransferKind.SAVE, back.kind); assertEquals("L", back.lineage); assertEquals("v1", back.base)
        assertEquals(TransferState.QUEUED, back.state)
    }
}
