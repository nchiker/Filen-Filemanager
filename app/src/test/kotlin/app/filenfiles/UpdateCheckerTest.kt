package app.filenfiles

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class UpdateCheckerTest {
    private fun rel(v: String, code: Long?) = UpdateChecker.Release(v, "p", null, versionCode = code)

    @Test fun usesVersionCodeWhenTheReleaseStatesOne() {
        assertTrue(UpdateChecker.isNewer(rel("v0.4.7", 15), "0.4.7", 14))   // same name, higher code
        assertFalse(UpdateChecker.isNewer(rel("v0.4.8", 14), "0.4.7", 14))  // higher name, same code: not newer
        assertFalse(UpdateChecker.isNewer(rel("v0.4.7", 14), "0.4.7", 14))
    }

    @Test fun fallsBackToTheNameForOlderReleases() {
        assertTrue(UpdateChecker.isNewer(rel("v0.4.8", null), "0.4.7", 14))
        assertFalse(UpdateChecker.isNewer(rel("v0.4.7", null), "0.4.7", 14))
        assertTrue(UpdateChecker.isNewer(rel("v0.4.8", 15), "0.4.7", null)) // installed code unknown: name decides
    }
}
