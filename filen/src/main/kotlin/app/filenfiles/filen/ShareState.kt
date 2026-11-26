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
    data class Known(val shared: Boolean) : ShareAnswer
    /** The item is not in the signed-in user's drive (or is in the trash). */
    data object NotFound : ShareAnswer
}

object ShareRules {
    /** An item counts as shared when it is itself shared with the partner (not merely inside a shared folder). */
    fun isShared(uuid: String, sharedWithPartner: Set<String>) = uuid.lowercase() in sharedWithPartner
}
