package app.filenfiles.filen

/** One person in the contact lists. [nick] is the display name they chose, when there is one. */
class Person(val email: String, val nick: String?)

/** I4 F9: where the partner connection stands. [wire] is the value Stack reads. */
enum class PartnerState(val wire: String) { NONE("none"), REQUESTED("requested"), INCOMING("incoming"), CONNECTED("connected") }

class PartnerStatus(val state: PartnerState, val who: Person?)

object Partner {
    /**
     * [saved] is the partner this user chose (set by the partner setup); without one, an incoming request is offered
     * ("incoming") and nothing else is guessed: a contact made for other reasons is not a partner.
     */
    fun decide(saved: String?, contacts: List<Person>, outgoing: List<Person>, incoming: List<Person>): PartnerStatus {
        fun find(list: List<Person>) = saved?.let { s -> list.firstOrNull { it.email.equals(s, true) } }
        if (saved == null) return incoming.firstOrNull()?.let { PartnerStatus(PartnerState.INCOMING, it) } ?: PartnerStatus(PartnerState.NONE, null)
        find(contacts)?.let { return PartnerStatus(PartnerState.CONNECTED, it) }
        find(outgoing)?.let { return PartnerStatus(PartnerState.REQUESTED, it) }
        find(incoming)?.let { return PartnerStatus(PartnerState.INCOMING, it) }
        return PartnerStatus(PartnerState.NONE, null)
    }

    /** A name to show: the person's own display name, else the part of the address before the "@" (never the address). */
    fun displayName(p: Person): String = p.nick?.trim()?.takeIf { it.isNotEmpty() && '@' !in it } ?: displayName(p.email)
    fun displayName(email: String): String = email.trim().substringBefore('@').ifEmpty { "Your partner" }
}
