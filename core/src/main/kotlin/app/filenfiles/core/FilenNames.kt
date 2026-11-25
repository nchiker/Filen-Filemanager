package app.filenfiles.core

import java.text.Normalizer

/** The file name rules of the Filen SDK (`filen-sdk-rs/src/fs/name`), checked before anything is uploaded. */
object FilenNames {
    const val MAX_BYTES = 255
    private const val FORBIDDEN = "/\\:*?\"<>|"
    private val RESERVED = Regex("(?i)CON|PRN|AUX|NUL|(COM|LPT)[1-9]")

    private fun nfc(s: String) = Normalizer.normalize(s, Normalizer.Form.NFC)
    fun bytes(name: String): Int = nfc(name).toByteArray(Charsets.UTF_8).size
    private fun forbidden(c: Char) = c.code < 0x20 || c.code == 0x7F || c in FORBIDDEN

    /** Why Filen would refuse [name], in words for the user, or null when it is fine. */
    fun problem(name: String): String? {
        val n = nfc(name)
        return when {
            n.isEmpty() || n == "." || n == ".." -> "The name is empty or not allowed."
            bytes(n) > MAX_BYTES -> "The name is too long."
            n.startsWith(' ') -> "A name can't start with a space."
            n.endsWith('.') || n.endsWith(' ') -> "A name can't end with a dot or a space."
            n.any(::forbidden) -> "A name can't contain / \\ : * ? \" < > | or control characters."
            RESERVED.matches(n) -> "“$n” is a reserved name."
            else -> null
        }
    }

    /** [name] made acceptable, for names another app proposes: bad characters become "_", the extension is kept. */
    fun sanitize(name: String): String {
        var n = nfc(name).map { if (forbidden(it)) '_' else it }.joinToString("").trimStart(' ').trimEnd('.', ' ')
        if (n.isEmpty()) n = "file"
        if (RESERVED.matches(n)) n = "_$n"
        return shorten(n, MAX_BYTES)
    }

    /** [name] cut to [max] bytes by shortening what precedes the extension. */
    fun shorten(name: String, max: Int): String {
        if (bytes(name) <= max) return name
        val dot = name.lastIndexOf('.').takeIf { it > 0 && bytes(name.substring(it)) <= 20 } ?: name.length
        var stem = name.substring(0, dot)
        val ext = name.substring(dot)
        while (stem.isNotEmpty() && bytes(stem + ext) > max) {
            stem = stem.dropLast(if (stem.last().isLowSurrogate() && stem.length > 1) 2 else 1)
        }
        return (stem.trimEnd(' ', '.').ifEmpty { "_" }) + ext
    }
}
