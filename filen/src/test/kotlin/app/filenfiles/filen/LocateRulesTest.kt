package app.filenfiles.filen

import app.filenfiles.core.ItemId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class LocateRulesTest {
    private val u = "0a1b2c3d-0000-1111-2222-333344445555"

    @Test fun ownDriveIdIsItself() = assertEquals(ItemId("filen:$u"), LocateRules.target("filen:$u"))

    @Test fun sharedByMeIsMyOwnFile() = assertEquals(ItemId("filen:$u"), LocateRules.target("filen-out:$u"))

    @Test fun receivedStaysReceived() = assertEquals(ItemId("filen-in:$u"), LocateRules.target("filen-in:$u"))

    @Test fun onlySharedByMeHasAnOwnFileBehindIt() {
        assertEquals(ItemId("filen:$u"), LocateRules.ownBehindShare("filen-out:$u"))
        for (id in listOf("filen:$u", "filen-in:$u", "filen-out:root", "filen-out:", "filen-pending:1")) assertNull(id, LocateRules.ownBehindShare(id))
    }

    @Test fun rootsAndOtherShapesAreNotAFilenFile() {
        for (id in listOf("filen:root", "filen-out:root", "filen-in:root", "filen:", "filen-pending:3", "local:/sdcard/a.sam", "nocolon", ""))
            assertNull(id, LocateRules.target(id))
    }
}
