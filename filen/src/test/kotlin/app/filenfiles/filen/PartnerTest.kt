package app.filenfiles.filen

import kotlin.test.*

class PartnerTest {
    private fun p(e: String, n: String? = null) = Person(e, n)
    @Test fun noChoiceOffersIncomingOnly() {
        assertEquals(PartnerState.NONE, Partner.decide(null, listOf(p("a@x.com")), listOf(p("b@x.com")), emptyList()).state)
        val s = Partner.decide(null, emptyList(), emptyList(), listOf(p("c@x.com", "Cy")))
        assertEquals(PartnerState.INCOMING, s.state); assertEquals("Cy", Partner.displayName(s.who!!))
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
}
