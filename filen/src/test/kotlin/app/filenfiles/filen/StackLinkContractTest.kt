package app.filenfiles.filen

import kotlin.test.*

class StackLinkContractTest {
    @Test fun contractLevelIsOneForFolderSharing() = assertEquals(1, StackLink.CONTRACT_LEVEL)

    private val pinned = mapOf("org.stackdocs.app" to setOf("aa"))
    @Test fun testPackageNeedsItsCertificate() {
        assertEquals(setOf("bb"), StackLink.acceptedCerts("t.pkg", pinned, "t.pkg", "bb"))
        assertTrue(StackLink.acceptedCerts("t.pkg", pinned, "t.pkg", "").isEmpty()) // no cert configured: not trusted
        assertTrue(StackLink.acceptedCerts("t.pkg", pinned, "", "bb").isEmpty()) // shipped build: no test package
        assertTrue(StackLink.acceptedCerts("other", pinned, "t.pkg", "bb").isEmpty())
        assertEquals(setOf("aa"), StackLink.acceptedCerts("org.stackdocs.app", pinned, "t.pkg", "bb"))
    }
}
