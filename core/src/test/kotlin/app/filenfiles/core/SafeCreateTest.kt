package app.filenfiles.core

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class SafeCreateTest {
    private class Taken : Exception()

    /** A folder that refuses a rename onto a held name, ignoring case, like the Filen server. */
    private class Folder(vararg names: String) {
        val names = names.toMutableList()
        val tried = mutableListOf<String>()
        var listed = 0
        fun claim(name: String, listing: () -> List<String> = { names.toList() }) = runBlocking {
            SafeCreate.claimName(name, { listed++; listing() }, { it is Taken }) { c ->
                tried += c
                if (names.any { it.equals(c, ignoreCase = true) }) throw Taken()
                names += c; c
            }
        }
    }

    @Test fun freeNameIsKeptWithoutListing() {
        val f = Folder("other.txt")
        assertEquals("newfile.txt", f.claim("newfile.txt"))
        assertEquals(0, f.listed)
    }

    @Test fun takenNameGetsANumberAndTheOldFileStays() {
        val f = Folder("newfile.txt")
        assertEquals("newfile (1).txt", f.claim("newfile.txt"))
        assertEquals(listOf("newfile.txt", "newfile (1).txt"), f.names)
    }

    @Test fun caseOnlyDifferenceCountsAsTaken() {
        assertEquals("NEWFILE (1).TXT", Folder("newfile.txt").claim("NEWFILE.TXT"))
    }

    @Test fun listedNamesAreSkipped() {
        val f = Folder("a.txt", "a (1).txt", "a (2).txt")
        assertEquals("a (3).txt", f.claim("a.txt"))
        assertEquals(listOf("a.txt", "a (3).txt"), f.tried)
        assertEquals(1, f.listed)
    }

    @Test fun staleOrFailingListingStillNeverReusesATakenName() {
        assertEquals("a (2).txt", Folder("a.txt", "a (1).txt").claim("a.txt") { emptyList() })
        assertEquals("a (2).txt", Folder("a.txt", "a (1).txt").claim("a.txt") { error("offline") })
    }

    @Test fun otherFailuresAreNotRetried() {
        var calls = 0
        assertFailsWith<java.io.IOException> {
            runBlocking { SafeCreate.claimName("a", { emptyList() }, { it is Taken }) { calls++; throw java.io.IOException("net") } }
        }
        assertEquals(1, calls)
    }

    @Test fun cancellationPassesThrough() {
        assertFailsWith<CancellationException> {
            runBlocking { SafeCreate.claimName("a", { emptyList() }, { true }) { throw CancellationException() } }
        }
    }

    @Test fun givesUpWhenEverythingIsRefused() {
        var calls = 0
        assertFailsWith<IllegalStateException> {
            runBlocking { SafeCreate.claimName("a", { emptyList() }, { true }) { calls++; throw Taken() } }
        }
        assertEquals(100, calls)
    }

    @Test fun tempNameKeepsAUsableExtensionAndFollowsTheDigest() {
        val d = ByteArray(32) { it.toByte() }
        assertEquals(".filen-upload-000102030405060708090a0b0c0d0e0f.txt", SafeCreate.tempName(d, "My notes.txt"))
        assertTrue(SafeCreate.tempName(d, "noext").endsWith("0f"))
        assertTrue(SafeCreate.tempName(d, "x.bad ext").endsWith("0f"))
        assertNotEquals(SafeCreate.tempName(d, "a.txt"), SafeCreate.tempName(ByteArray(32), "a.txt"))
    }
}
