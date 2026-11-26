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

    /** Longest nickname shown, in characters. */
    const val MAX_NICK = 40

    /**
     * A nickname made safe to show: control and formatting characters (this includes the bidi marks and embeddings that
     * can reorder text, zero-width characters and line breaks) are dropped, runs of spaces become one, and the result is
     * cut to [MAX_NICK] characters (never in the middle of a surrogate pair). Empty when nothing is left.
     */
    fun cleanNick(raw: String?): String {
        if (raw == null) return ""
        val sb = StringBuilder()
        var count = 0
        var space = false
        var i = 0
        while (i < raw.length && count < MAX_NICK) {
            val cp = raw.codePointAt(i); i += Character.charCount(cp)
            if (cp == 0x09 || cp == 0x0A || cp == 0x0D || Character.isWhitespace(cp) || Character.getType(cp) == Character.SPACE_SEPARATOR.toInt()) {
                space = sb.isNotEmpty(); continue
            }
            if (invisible(cp)) continue
            if (space) { sb.append(' '); count++; space = false; if (count >= MAX_NICK) break }
            sb.appendCodePoint(cp); count++
        }
        return sb.toString().trim()
    }

    private fun invisible(cp: Int): Boolean = when (Character.getType(cp)) {
        Character.CONTROL.toInt(), Character.FORMAT.toInt(), Character.LINE_SEPARATOR.toInt(), Character.PARAGRAPH_SEPARATOR.toInt(),
        Character.UNASSIGNED.toInt(), Character.PRIVATE_USE.toInt(), Character.SURROGATE.toInt() -> true
        else -> false
    }

    /** The cleaned nickname when it is usable as a name (not empty, and not shaped like an address), else null. */
    private fun usableNick(p: Person): String? = cleanNick(p.nick).takeIf { it.isNotEmpty() && '@' !in it }

    /**
     * A name to show to other apps: the person's own display name (cleaned), else the part of the address before the "@"
     * (cleaned). Never the address (privacy toward other apps).
     */
    fun displayName(p: Person): String = usableNick(p) ?: displayName(p.email)
    fun displayName(email: String): String = cleanNick(email.trim().substringBefore('@')).ifEmpty { "Your partner" }

    /**
     * What Filen's own sheets show: "nick (email)", so a nickname can never pass for someone else (the address is
     * always there). Without a usable nickname, the address alone.
     */
    fun label(p: Person): String {
        val mail = cleanEmail(p.email)
        val nick = usableNick(p) ?: return mail
        return "$nick ($mail)"
    }

    /** An address with control/formatting characters dropped (not shortened: the whole address is what identifies them). */
    fun cleanEmail(email: String): String {
        val sb = StringBuilder()
        var i = 0
        val t = email.trim()
        while (i < t.length) {
            val cp = t.codePointAt(i); i += Character.charCount(cp)
            if (invisible(cp) || Character.isWhitespace(cp)) continue
            sb.appendCodePoint(cp)
        }
        return sb.toString()
    }

    /** The saved partner's contact among [contacts] (by address, ignoring case); null without a partner or contact. */
    fun contactFor(saved: String?, contacts: List<Person>): Person? =
        saved?.let { s -> contacts.firstOrNull { it.email.equals(s.trim(), true) } }
}

/** I4 F11: whether a request for a shared folder reuses a folder, or creates one. */
sealed interface FolderPlan {
    /** An existing folder this app's earlier F11 call created, still in the drive root and still shared with the partner. */
    data class Reuse(val uuid: String, val name: String) : FolderPlan
    data class Create(val name: String) : FolderPlan
}

object FolderReuse {
    /**
     * [recorded]: the uuid of the folder F11 created earlier for this caller and account (null if none). A folder is
     * reused only when it is that one (never because it merely has the same name), is still among [rootDirs]
     * (uuid, name) and is still shared with the partner ([sharedIds]). Anything else gets a new folder, named
     * [wanted], or "[wanted] 2"... when a root folder already has that name.
     */
    fun plan(wanted: String, recorded: String?, rootDirs: List<Pair<String, String>>, sharedIds: Set<String>): FolderPlan {
        if (recorded != null) {
            val rec = recorded.lowercase()
            val hit = rootDirs.firstOrNull { it.first.lowercase() == rec }
            if (hit != null && rec in sharedIds.map { it.lowercase() }) return FolderPlan.Reuse(hit.first, hit.second)
        }
        return FolderPlan.Create(FolderNames.unique(wanted, rootDirs.map { it.second }))
    }
}

/** Account switches: what is kept for one account must not carry over to another. */
object AccountChange {
    /** True when a different account is signed in than the last one seen (nothing known = not a change). */
    fun differs(lastSeen: String?, current: String?) = lastSeen != null && current != null && lastSeen != current
}
