package app.filenfiles.stacklink

import java.security.MessageDigest

/** Pure rules of [SharedWatcher]. */
object WatchRules {
    /**
     * Wait before a check after an event: normally [quietMs], but never past [maxWaitMs] since the first event of the burst
     * (a steady stream of events can no longer postpone the check forever). [firstAt] = when the burst began.
     */
    fun delayFor(now: Long, firstAt: Long, quietMs: Long, maxWaitMs: Long): Long =
        minOf(quietMs, maxOf(0L, firstAt + maxWaitMs - now))
}

/**
 * Hash of a tree's first [maxItems] entries in a fixed order. Past the cap further entries are not hashed (a change there is
 * not noticed until the earlier part changes), but the answer is still a usable fingerprint, not "unknown".
 */
class TreeFingerprint(private val maxItems: Int) {
    private val md = MessageDigest.getInstance("SHA-256")
    private var count = 0
    var capped = false; private set

    /** Adds one entry; false once the cap is reached (the caller stops walking). */
    fun add(line: String): Boolean {
        if (count >= maxItems) { capped = true; return false }
        count++
        md.update((line + "\n").toByteArray())
        return true
    }

    fun digest(): String {
        if (capped) md.update("capped\n".toByteArray())
        return md.digest().joinToString("") { "%02x".format(it) }
    }
}
