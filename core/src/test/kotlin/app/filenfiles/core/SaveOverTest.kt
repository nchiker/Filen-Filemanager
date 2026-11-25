package app.filenfiles.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SaveOverTest {
    private val head = SaveOver.Head("v2", "filen:dir", "Budget.xlsx", trashed = false)

    @Test fun unchangedSinceOpenIsSavedOverAtItsCurrentPlace() {
        assertEquals(SaveOver.Plan.Replace("filen:dir", "Budget.xlsx"), SaveOver.plan(true, head, "v2", emptyList()))
    }

    @Test fun saveAgainBuildsOnOurOwnEarlierSave() {
        assertEquals(SaveOver.Plan.Replace("filen:dir", "Budget.xlsx"), SaveOver.plan(true, head, "v1", listOf("v2")))
    }

    @Test fun everythingElseIsKeptAsACopy() {
        assertTrue(SaveOver.plan(true, head, "v1", emptyList()) is SaveOver.Plan.Copy)       // changed elsewhere
        assertTrue(SaveOver.plan(false, head, "v2", emptyList()) is SaveOver.Plan.Copy)      // versioning off
        assertTrue(SaveOver.plan(true, null, "v2", emptyList()) is SaveOver.Plan.Copy)       // gone
        assertTrue(SaveOver.plan(true, head.copy(trashed = true), "v2", emptyList()) is SaveOver.Plan.Copy)
        assertTrue(SaveOver.plan(true, head, null, listOf("v2")) is SaveOver.Plan.Copy)      // base unknown
    }

    @Test fun conflictNamesKeepTheExtension() {
        val n = SaveOver.conflictName("Budget.xlsx", 0)
        assertTrue(n.startsWith("Budget (conflict ") && n.endsWith(").xlsx"), n)
        assertEquals(null, FilenNames.problem(n))
    }
}
