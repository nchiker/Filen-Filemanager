package app.filenfiles.core

import kotlin.test.*

class NamesTest {
    @Test fun uniqueKeepsFreeNames() = assertEquals("a.txt", Names.unique(listOf("b.txt"), "a.txt"))

    @Test fun uniqueNumbersClashes() {
        assertEquals("a (1).txt", Names.unique(listOf("a.txt"), "a.txt"))
        assertEquals("a (2).txt", Names.unique(listOf("a.txt", "a (1).txt"), "a.txt"))
    }

    @Test fun uniqueIsCaseInsensitiveAndHandlesNoExtension() {
        assertEquals("Photo (1).JPG", Names.unique(listOf("photo.jpg"), "Photo.JPG"))
        assertEquals("folder (1)", Names.unique(listOf("folder"), "folder"))
        assertEquals(".hidden (1)", Names.unique(listOf(".hidden"), ".hidden")) // a leading dot is not an extension
    }

    @Test fun safeEntryPathAcceptsNormalEntries() {
        assertEquals("a/b/c.txt", Names.safeEntryPath("a/b/c.txt"))
        assertEquals("a/b", Names.safeEntryPath("./a//b/"))
    }

    @Test fun safeEntryPathRejectsEscapes() {
        for (bad in listOf("../x", "a/../../x", "/etc/passwd", "C:/x", "..\\x", "a\\..\\..\\x", "", "./", "a/../b")) {
            assertNull(Names.safeEntryPath(bad), "should reject '$bad'")
        }
    }
}

class VersionsTest {
    @Test fun comparesNumerically() {
        assertTrue(Versions.isNewer("0.1.1", "0.1.0"))
        assertTrue(Versions.isNewer("v0.10.0", "0.9.9"))
        assertTrue(Versions.isNewer("1.0", "0.9.9"))
        assertFalse(Versions.isNewer("0.1.0", "0.1.0"))
        assertFalse(Versions.isNewer("0.1.0", "0.2.0"))
        assertFalse(Versions.isNewer("1.0", "1.0.0"))
    }

    @Test fun ignoresSuffixesAndGarbage() {
        assertTrue(Versions.isNewer("v0.2.0-beta", "0.1.0"))
        assertFalse(Versions.isNewer("nonsense", "0.1.0"))
    }
}
