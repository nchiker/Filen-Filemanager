package app.filenfiles

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class PairCodeTest {
    private val n = "0123456789abcdef0123456789abcdef"
    @Test fun fourDigitsAndStable() {
        val c = PairingActivity.pairCode(n, "Partner@X.com")
        assertTrue(Regex("\\d{4}").matches(c))
        assertEquals(c, PairingActivity.pairCode(n, " partner@x.com ")) // case and spaces do not matter
    }
    @Test fun differsByNonceAndEmail() {
        val a = PairingActivity.pairCode(n, "a@x.com")
        // 10 000 possible codes: a handful of different inputs must not all collide
        val others = listOf(PairingActivity.pairCode(n, "b@x.com"), PairingActivity.pairCode(n, "c@x.com"), PairingActivity.pairCode("f".repeat(32), "a@x.com"))
        assertTrue(others.any { it != a })
        assertNotEquals(a, "")
    }
}
