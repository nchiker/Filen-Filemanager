package app.filenfiles.core

/** Pure helpers shared by copy, zip and backup code (kept free of Android so they are unit-tested on the JVM). */
object Names {
    /** "a.txt" -> "a (1).txt", "a (2).txt"... when [name] is taken (case-insensitive) among [existing]. */
    fun unique(existing: Collection<String>, name: String): String {
        val taken = existing.mapTo(HashSet()) { it.lowercase() }
        if (name.lowercase() !in taken) return name
        val dot = name.lastIndexOf('.').takeIf { it > 0 } ?: name.length
        val stem = name.substring(0, dot); val ext = name.substring(dot)
        var n = 1
        while ("$stem ($n)$ext".lowercase() in taken) n++
        return "$stem ($n)$ext"
    }

    /**
     * Resolves a zip entry name against [root]-relative output, refusing anything that would land outside it
     * ("zip slip": `../x`, absolute paths, drive letters, backslash tricks). Returns the safe relative path or null.
     */
    fun safeEntryPath(entry: String): String? {
        val normalized = entry.replace('\\', '/')
        if (normalized.startsWith("/") || Regex("^[A-Za-z]:").containsMatchIn(normalized)) return null
        val parts = normalized.split('/').filter { it.isNotEmpty() && it != "." }
        if (parts.isEmpty() || parts.any { it == ".." }) return null
        return parts.joinToString("/")
    }
}

/** Dotted release versions ("v1.2.3", "0.1.0"): which of two is newer. Non-numeric suffixes are ignored. */
object Versions {
    private fun parts(v: String) = v.trim().removePrefix("v").removePrefix("V")
        .substringBefore('-').substringBefore('+').split('.').map { it.takeWhile(Char::isDigit).toIntOrNull() ?: 0 }

    fun isNewer(candidate: String, installed: String): Boolean {
        val a = parts(candidate); val b = parts(installed)
        for (i in 0 until maxOf(a.size, b.size)) {
            val x = a.getOrElse(i) { 0 }; val y = b.getOrElse(i) { 0 }
            if (x != y) return x > y
        }
        return false
    }
}
