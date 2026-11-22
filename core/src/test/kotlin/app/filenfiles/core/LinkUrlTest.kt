package app.filenfiles.core

import kotlin.test.*

/** Golden vectors taken from the official filen-cli tests (public_links_cmds.rs). */
class LinkUrlTest {
    private val uuid = "0f8fad5b-d9cb-469f-a165-70867728950e"

    @Test fun fileLinkUsesTheDownloadRouteAndHexKey() =
        assertEquals("https://app.filen.io/#/d/$uuid%2361626358595a3039", LinkUrl.build(false, uuid, "abcXYZ09"))

    @Test fun folderLinkUsesTheFolderRoute() =
        assertEquals("https://app.filen.io/#/f/$uuid%236b6579", LinkUrl.build(true, uuid, "key"))

    @Test fun aKeyThatLooksLikeHexIsEncodedAgain() {
        val url = LinkUrl.build(false, uuid, "00ff".repeat(16))
        assertEquals("30306666".repeat(16), url.substringAfter("%23"))
    }

    @Test fun defaultsAreSafe() {
        val s = LinkSettings()
        assertEquals(LinkExpiry.ONE_WEEK, s.expiry) // never "never" by default
        assertEquals(PasswordChange.Keep, s.password)
    }
}
