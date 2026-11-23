package app.filenfiles.transfer

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class DocIdMapTest {
    private fun tmp() = File.createTempFile("docids", ".json").apply { delete(); deleteOnExit() }

    @Test fun mapsAndSurvivesRestart() {
        val f = tmp()
        val a = DocIdMap(f)
        assertNull(a.get("filen-pending:1-1"))
        a.put("filen-pending:1-1", "filen:abc")
        assertEquals("filen:abc", a.get("filen-pending:1-1"))
        assertEquals("filen:abc", DocIdMap(f).get("filen-pending:1-1")) // a new process reads it back
    }

    @Test fun dropsOldestPastTheCap() {
        val m = DocIdMap(tmp(), cap = 3)
        (1..5).forEach { m.put("p$it", "filen:$it") }
        assertNull(m.get("p1")); assertNull(m.get("p2"))
        assertEquals("filen:5", m.get("p5"))
    }
}
