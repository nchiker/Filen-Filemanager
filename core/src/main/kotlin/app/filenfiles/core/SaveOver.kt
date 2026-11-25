package app.filenfiles.core

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Saving new content over an existing Filen file. Filen has no "replace only if still version X" request, so the
 * check is ours and is made just before the upload; the upload itself then adds a version (versioning is required,
 * so nothing it replaces is lost). Whatever the check cannot vouch for becomes a separate conflict copy instead.
 */
object SaveOver {
    /** The file as Filen has it now. */
    data class Head(val uuid: String, val parent: String, val name: String, val trashed: Boolean)

    sealed interface Plan {
        /** Upload as the newest version of the file at [parent]/[name] (its current place). */
        data class Replace(val parent: String, val name: String) : Plan
        /** Keep both: upload as a new file named like [conflictName] next to the original. */
        data class Copy(val reason: String) : Plan
    }

    /**
     * [base]: the version the edit started from. [ours]: versions this app itself uploaded for this file (a later save
     * of the same document builds on them). A head that is neither changed elsewhere, so the edit is kept as a copy.
     */
    fun plan(versioning: Boolean, head: Head?, base: String?, ours: Collection<String>): Plan = when {
        !versioning -> Plan.Copy("file versioning is off, so saving over it could lose the old content")
        head == null -> Plan.Copy("the file is gone")
        head.trashed -> Plan.Copy("the file is in the trash")
        base == null || (head.uuid != base && head.uuid !in ours) -> Plan.Copy("it was changed elsewhere since it was opened")
        else -> Plan.Replace(head.parent, head.name)
    }

    /** "Budget.xlsx" -> "Budget (conflict 2026-11-25 1432).xlsx". */
    fun conflictName(name: String, now: Long = System.currentTimeMillis()): String {
        val stamp = SimpleDateFormat("yyyy-MM-dd HHmm", Locale.ROOT).format(Date(now))
        val dot = name.lastIndexOf('.').takeIf { it > 0 } ?: name.length
        return FilenNames.shorten("${name.substring(0, dot)} (conflict $stamp)${name.substring(dot)}", FilenNames.MAX_BYTES - 8)
    }
}
