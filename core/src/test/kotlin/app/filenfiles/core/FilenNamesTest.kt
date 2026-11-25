package app.filenfiles.core

import kotlinx.coroutines.runBlocking
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class FilenNamesTest {
    @Test fun refusesWhatTheSdkRefuses() {
        listOf("", ".", "..", "a:b.txt", "a/b", "a\\b", "q?", "x\u0001", " lead", "trail ", "dot.", "con", "LPT9", "é".repeat(128))
            .forEach { assertNotNull(FilenNames.problem(it), it) }
        listOf("a.txt", ".hidden", "CON.txt", "COM0", "é".repeat(127), "Notes 10-30.txt").forEach { assertNull(FilenNames.problem(it), it) }
    }

    @Test fun sanitizedNamesAreAlwaysAccepted() {
        listOf("", "..", "Notes 10:30.txt", " x. ", "con", "é".repeat(300) + ".txt", "😀".repeat(100))
            .forEach { assertNull(FilenNames.problem(FilenNames.sanitize(it)), it) }
        assertEquals("Notes 10_30.txt", FilenNames.sanitize("Notes 10:30.txt"))
        assertTrue(FilenNames.sanitize("é".repeat(300) + ".txt").endsWith(".txt"))
    }

    @Test fun aNumberedNameStaysWithinTheLimit() {
        val long = "a".repeat(251) + ".txt"
        val got = runBlocking {
            SafeCreate.claimName(long, { emptyList() }, { it is IllegalArgumentException }) { require(it != long); it }
        }
        assertNull(FilenNames.problem(got))
        assertTrue(got.endsWith(" (1).txt"), got)
    }
}
