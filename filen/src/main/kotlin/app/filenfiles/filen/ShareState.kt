package app.filenfiles.filen

/**
 * I4 F10: remembers, for a short time, which items are shared with one contact, so a screen that asks about many files
 * does not list the out-shares every time. Pure; [now] is injected for tests.
 */
class OutShareCache(private val ttlMs: Long = 20_000, private val now: () -> Long = System::currentTimeMillis) {
    private class Entry(val ids: Set<String>, val at: Long)
    private val entries = HashMap<String, Entry>()

    @Synchronized fun get(contact: String): Set<String>? =
        entries[contact.lowercase()]?.takeIf { now() - it.at in 0..ttlMs }?.ids

    @Synchronized fun put(contact: String, ids: Set<String>) { entries[contact.lowercase()] = Entry(ids, now()) }

    /** After a share or stop-share made here, or at sign-out. */
    @Synchronized fun clear() = entries.clear()
}

/** What `share_state` found out. */
sealed interface ShareAnswer {
    /** [via]: name of the nearest folder above the item that is shared with the partner (only when the item itself is not). */
    data class Known(val shared: Boolean, val via: String? = null, val viaUuid: String? = null) : ShareAnswer
    /** The item is not in the signed-in user's drive (or is in the trash). */
    data object NotFound : ShareAnswer
}

/** What the STOP sheet offers for an item. */
sealed interface StopChoice {
    /** The item itself is shared: stop it. */
    data object Direct : StopChoice
    /** Access is only through the folder [uuid] named [name]: say so and offer to stop that folder. */
    data class ViaFolder(val uuid: String, val name: String) : StopChoice
    /** Nothing shares it with the partner. */
    data object NotShared : StopChoice

    companion object {
        fun of(a: ShareAnswer): StopChoice = when {
            a !is ShareAnswer.Known -> Direct // cannot tell: the old behaviour (stop the item itself)
            a.shared -> Direct
            a.via != null && a.viaUuid != null -> ViaFolder(a.viaUuid, a.via)
            else -> NotShared
        }
    }
}

object ShareRules {
    /** An item counts as shared when it is itself shared with the partner (not merely inside a shared folder). */
    fun isShared(uuid: String, sharedWithPartner: Set<String>) = uuid.lowercase() in sharedWithPartner
}

object FolderNames {
    /** [base], or "[base] 2", "[base] 3"... : the first name not in [taken] (compared ignoring case). */
    fun unique(base: String, taken: Collection<String>): String {
        val t = taken.map { it.lowercase() }.toSet()
        if (base.lowercase() !in t) return base
        var n = 2
        while ("$base $n".lowercase() in t) n++
        return "$base $n"
    }
}

object Ancestors {
    /**
     * Walks up from [start] (the item's parent) with [step] (uuid -> name and parent of that folder, null at the top or when
     * unknown) and returns the name of the first folder in [shared]. At most [maxSteps] folders are looked at.
     */
    inline fun nearestShared(start: String?, shared: Set<String>, maxSteps: Int = 32, step: (String) -> Pair<String, String?>?): String? =
        nearestSharedHit(start, shared, maxSteps, step)?.second

    /** Like [nearestShared] but returns (uuid, name) of that folder. */
    inline fun nearestSharedHit(start: String?, shared: Set<String>, maxSteps: Int = 32, step: (String) -> Pair<String, String?>?): Pair<String, String>? {
        var cur = start
        repeat(maxSteps) {
            val c = cur ?: return null
            val (name, parent) = step(c) ?: return null
            if (c.lowercase() in shared) return c to name
            cur = parent
        }
        return null
    }
}
