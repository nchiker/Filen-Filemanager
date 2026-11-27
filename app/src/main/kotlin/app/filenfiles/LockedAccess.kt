package app.filenfiles

/**
 * Locked-access allowlists are keyed by (package, tree URI): an app that the owner let read one folder while Filen is locked
 * is not thereby allowed to read any other folder it holds a grant on. Entries are "package\ttreeUri" strings. Pure.
 */
object LockedAccess {
    fun entry(pkg: String, tree: String) = "$pkg\t$tree"
    fun pkgOf(entry: String) = entry.substringBefore('\t')
    fun treeOf(entry: String) = entry.substringAfter('\t', "")

    /** Whether any of the caller's [pkgs] is allowed on exactly this [tree]. */
    fun allows(entries: Set<String>, pkgs: Collection<String>, tree: String) = pkgs.any { entry(it, tree) in entries }

    /** The first (package, tree) among the caller's covering [trees] that the owner was never asked about, or null. */
    fun firstUnasked(asked: Set<String>, pkgs: Collection<String>, trees: List<String>): String? {
        for (t in trees) for (p in pkgs) { val e = entry(p, t); if (e !in asked) return e }
        return null
    }

    /** The tree document id inside a tree URI string (".../tree/filen-in%3Aroot" -> "filen-in:root"); the string itself if it has none. */
    fun treeDocId(tree: String): String {
        val raw = tree.substringAfter("/tree/", tree).substringBefore('/')
        return runCatching { java.net.URLDecoder.decode(raw.replace("+", "%2B"), "UTF-8") }.getOrDefault(raw)
    }

    /** What a tree is, in words for the owner. */
    fun scope(tree: String): String = when (treeDocId(tree)) {
        "filen:root" -> "your whole Filen Drive"
        "filen-in:root" -> "“Shared with me”"
        "filen-out:root" -> "“Shared by me”"
        else -> "one folder"
    }

    /**
     * Rebuilds (package, tree) entries from what Filen already recorded: the F11 folders (`f11:<acct>:<pkg>` -> folder uuid,
     * as [f11] pkg -> uuid) and the folders GrantFolderActivity / F11 granted ([granted] pkg -> tree URIs). [treeOf] turns a
     * folder uuid into its tree URI string.
     */
    fun rebuildWriters(f11: Map<String, String>, granted: Map<String, Set<String>>, treeOf: (String) -> String): Set<String> {
        val out = LinkedHashSet<String>()
        f11.forEach { (pkg, uuid) -> if (pkg.isNotEmpty() && uuid.isNotEmpty()) out += entry(pkg, treeOf(uuid)) }
        granted.forEach { (pkg, trees) -> trees.forEach { if (pkg.isNotEmpty() && it.isNotEmpty()) out += entry(pkg, it) } }
        return out
    }

    /** Rebuilt entries minus any the owner already answered (reader allowed, reader asked, or write asked). Never overrides an answer. */
    fun rebuildUnanswered(rebuilt: Set<String>, readers: Set<String>, readersAsked: Set<String>, writeAsked: Set<String>): Set<String> =
        rebuilt.filter { it !in readers && it !in readersAsked && it !in writeAsked }.toSet()

    /** Whether a tree is a whole-drive / shared-with-me / shared-by-me tree: never asked about for writing. */
    fun isBroadTree(tree: String): Boolean = treeDocId(tree).let { it.endsWith(":root") || it.startsWith("filen-in:") || it.startsWith("filen-out:") }

    /** Of the covering [trees], those that may be asked about for writing: recorded for [pkgs] (gfa / F11 -> [recorded] entries) and not a broad tree. */
    fun askableTrees(recorded: Set<String>, pkgs: Collection<String>, trees: List<String>): List<String> =
        trees.filter { t -> !isBroadTree(t) && pkgs.any { entry(it, t) in recorded } }

    /** Write Allow: (readers, readersAsked, origin). Allow is explicit consent to read that same entry too: the reader is added (marked write-created) unless already allowed; an earlier "Not now" is overridden for this entry only. */
    fun allowWrite(readers: Set<String>, readersAsked: Set<String>, origin: Set<String>, e: String): Triple<Set<String>, Set<String>, Set<String>> =
        if (e in readers) Triple(readers, readersAsked, origin) else Triple(readers + e, readersAsked + e, origin + e)

    /** Remove: the reader entry goes only if the write grant created it ([origin]). Returns (readers, origin). */
    fun removeWrite(readers: Set<String>, origin: Set<String>, e: String): Pair<Set<String>, Set<String>> =
        (if (e in origin) readers - e else readers) to (origin - e)

    /** Methods Stack Link answers to pinned callers even while locked: opaque ids and a display name only, no file access. */
    fun answeredWhileLocked(method: String) = method == "contract" || method == "partner_status"

    /**
     * A locked write was refused for the caller's [pkgs] on the covering [trees]: the (package, tree) entries to remember so the
     * owner is asked once. Nothing for an entry already allowed to write, or already asked about.
     */
    fun noteRefusedWrites(pending: Set<String>, writers: Set<String>, asked: Set<String>, pkgs: Collection<String>, trees: List<String>): Set<String> {
        var out = pending
        for (t in trees) for (p in pkgs) { val e = entry(p, t); if (e !in writers && e !in asked) out = out + e }
        return out
    }

    /** The remembered entry to ask about next (never one already asked about), or null. */
    fun nextWriteAsk(pending: Set<String>, asked: Set<String>): String? = pending.sorted().firstOrNull { it !in asked }

    /**
     * Old (0.5.0 and earlier) allowlists were per package and are dropped: nothing recorded which folder a package had been
     * allowed for, so the apps are asked again (readers) or must repeat their folder request (backup / F11 writers).
     */
    val LEGACY_KEYS = listOf("lockedReaders", "lockedWriters", "lockedReadersAsked")
}
