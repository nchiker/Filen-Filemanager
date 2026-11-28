package app.filenfiles.core

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ShareStopsTest {
    @Test fun aStopAfterTheMarkWinsByAnyOfItsIds() {
        val s = ShareStops(); val m = s.mark()
        s.stop(listOf("V2", "life"))
        assertTrue(s.stoppedSince(m, listOf("v1", "LIFE")))
        assertTrue(s.stoppedSince(m, listOf("v2")))
        assertFalse(s.stoppedSince(m, listOf("other")))
    }

    @Test fun anEarlierStopDoesNotBlockALaterSave() {
        val s = ShareStops(); s.stop(listOf("life"))
        assertFalse(s.stoppedSince(s.mark(), listOf("life")))
    }
}
