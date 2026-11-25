package app.filenfiles.core

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class UploadJournalTest {
    @Test fun survivesARestartAndForgetsWhatWasNamed() {
        val f = File.createTempFile("journal", "").apply { delete(); deleteOnExit() }
        val e = UploadJournal.Entry(".filen-upload-ab.txt", "filen:root", "a\tb\n.txt", "me@x")
        UploadJournal(f).put(e)
        UploadJournal(f).put(e.copy(uuid = "u1"))
        val read = UploadJournal(f)
        assertEquals(e.copy(uuid = "u1"), read[e.tempName])
        read.remove(e.tempName)
        assertNull(UploadJournal(f)[e.tempName])
    }

    @Test fun aDamagedLineIsSkipped() {
        val f = File.createTempFile("journal", "").apply { deleteOnExit() }
        f.writeText("half\tline\n")
        assertEquals(emptyList(), UploadJournal(f).all())
    }
}
