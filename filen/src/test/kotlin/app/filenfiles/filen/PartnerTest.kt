package app.filenfiles.filen

import kotlin.test.*

class PartnerTest {
    private fun p(e: String, n: String? = null) = Person(e, n)
    @Test fun noChoiceOffersIncomingOnly() {
        assertEquals(PartnerState.NONE, Partner.decide(null, listOf(p("a@x.com")), listOf(p("b@x.com")), emptyList()).state)
        val s = Partner.decide(null, emptyList(), emptyList(), listOf(p("c@x.com", "Cy")))
        assertEquals(PartnerState.INCOMING, s.state); assertEquals("c", Partner.displayName(s.who!!)) // the stranger's nickname never goes out
        val evil = Partner.decide(null, emptyList(), emptyList(), listOf(p("sam\u202E@x.com", "Your bank")))
        assertEquals("sam", Partner.displayName(evil.who!!)); assertNull(evil.who!!.nick)
    }
    @Test fun chosenPartnerStates() {
        assertEquals(PartnerState.CONNECTED, Partner.decide("A@X.com", listOf(p("a@x.com")), emptyList(), emptyList()).state)
        assertEquals(PartnerState.REQUESTED, Partner.decide("a@x.com", emptyList(), listOf(p("a@x.com")), emptyList()).state)
        assertEquals(PartnerState.INCOMING, Partner.decide("a@x.com", emptyList(), emptyList(), listOf(p("a@x.com"))).state)
        assertEquals(PartnerState.NONE, Partner.decide("a@x.com", emptyList(), emptyList(), emptyList()).state)
    }
    @Test fun displayNameNeverShowsAnAddress() {
        assertEquals("sam", Partner.displayName(p("sam@x.com")))
        assertEquals("sam", Partner.displayName(p("sam@x.com", "other@y.com")))
        assertEquals("Sam", Partner.displayName(p("sam@x.com", " Sam ")))
    }

    // ---- finding 3: nickname cleaning ----
    @Test fun nickIsCappedAt40AndLosesBidiAndControlCharacters() {
        assertEquals("Mom evil", Partner.cleanNick("Mom \u202E\u2066evil\u2069\u200B"))
        assertEquals("ab", Partner.cleanNick("a\u0000b\u0007"))
        assertEquals("a b", Partner.cleanNick("a\n\t  b"))
        assertEquals(40, Partner.cleanNick("x".repeat(100)).length)
        assertEquals("", Partner.cleanNick("\u202E\u200F"))
        val emoji = Partner.cleanNick("😀".repeat(100)) // 40 characters, none cut in half
        assertEquals(40, emoji.codePointCount(0, emoji.length))
        assertEquals("", Partner.cleanNick(null))
    }
    @Test fun displayNameIsTheCleanedNickNeverTheEmail() {
        assertEquals("Sam", Partner.displayName(p("sam@x.com", "\u202ESam")))
        assertEquals("sam", Partner.displayName(p("sam@x.com", "\u202E\u200B")))
        assertEquals("sam", Partner.displayName(p("sam@x.com", "boss@y.com")))
        assertFalse('@' in Partner.displayName(p("sam@x.com", "x".repeat(60))))
        assertEquals(40, Partner.displayName(p("sam@x.com", "x".repeat(60))).length)
    }
    @Test fun sheetLabelAlwaysCarriesTheAddress() {
        assertEquals("Sam (sam@x.com)", Partner.label(p("sam@x.com", "Sam")))
        assertEquals("sam@x.com", Partner.label(p("sam@x.com")))
        assertEquals("sam@x.com", Partner.label(p("sam@x.com", "boss@y.com"))) // an address-shaped nick is never shown
        assertEquals("Mom (evil@y.com)", Partner.label(p("evil@y.com", "\u202EMom")))
    }

    // ---- finding 6 (pure part): which contact "Shared by me" is listed for ----
    @Test fun sharedByMeUsesOnlyTheSavedPartnersContact() {
        val contacts = listOf(p("a@x.com"), p("B@x.com"))
        assertEquals("B@x.com", Partner.contactFor(" b@x.com ", contacts)?.email)
        assertNull(Partner.contactFor(null, contacts))
        assertNull(Partner.contactFor("c@x.com", contacts))
    }

    // ---- finding 4: F11 reuse ----
    private val root = listOf("u1" to "Scans", "u2" to "Taxes")
    @Test fun onlyTheFolderF11MadeIsReused() {
        assertEquals(FolderPlan.Reuse("u1", "Scans"), FolderReuse.plan("Scans", "u1", root, setOf("u1")))
        assertEquals(FolderPlan.Reuse("u1", "Scans"), FolderReuse.plan("Scans", "U1", root, setOf("u1")))
    }
    @Test fun aSameNameFolderNobodyRecordedGetsANewName() {
        assertEquals(FolderPlan.Create("Scans 2"), FolderReuse.plan("Scans", null, root, setOf("u1")))
        assertEquals(FolderPlan.Create("Scans 2"), FolderReuse.plan("Scans", "other", root, setOf("u1", "other")))
    }
    @Test fun aRecordedFolderThatIsGoneOrNoLongerSharedIsNotReused() {
        assertEquals(FolderPlan.Create("Scans 2"), FolderReuse.plan("Scans", "u1", root, emptySet()))
        assertEquals(FolderPlan.Create("Scans"), FolderReuse.plan("Scans", "u9", listOf("u2" to "Taxes"), setOf("u9")))
    }

    // ---- finding 5 ----
    @Test fun accountChangeNeedsTwoKnownDifferentAccounts() {
        assertTrue(AccountChange.differs("a", "b")); assertFalse(AccountChange.differs("a", "a"))
        assertFalse(AccountChange.differs(null, "b")); assertFalse(AccountChange.differs("a", null))
    }
}
