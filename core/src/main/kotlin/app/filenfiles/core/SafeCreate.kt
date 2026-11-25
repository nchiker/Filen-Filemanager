package app.filenfiles.core

import kotlinx.coroutines.CancellationException

/**
 * Creating a file on a store where uploading onto a taken name REPLACES that file (Filen: for good when file
 * versioning is off). The file is uploaded under a name nobody uses ([tempName]) and then renamed to the wanted
 * name; the server refuses a rename onto a taken name, so [claimName] moves on to "name (1)" and so on. Unlike
 * "list, then upload", this cannot replace a file that appeared in between.
 */
object SafeCreate {
    const val TEMP_PREFIX = ".filen-upload-"
    private const val MAX_TRIES = 100

    /**
     * The upload name for the file whose [digest] covers its content, wanted name and folder: the same upload retried
     * gets the same name (so a leftover is reused, not piled up), anything else gets another. The extension is kept
     * because the SDK guesses the MIME type from it.
     */
    fun tempName(digest: ByteArray, name: String): String {
        val ext = name.substringAfterLast('.', "").takeIf { it.length in 1..16 && it.all(Char::isLetterOrDigit) }
        return TEMP_PREFIX + digest.take(16).joinToString("") { "%02x".format(it) } + (ext?.let { ".$it" } ?: "")
    }

    /** [Names.unique], with [name] shortened as far as needed for the numbered result to stay a legal length. */
    private fun numbered(taken: Collection<String>, name: String): String {
        val unique = Names.unique(taken, name)
        if (FilenNames.bytes(unique) <= FilenNames.MAX_BYTES) return unique
        // Room for the longest suffix we try (" (100)"), so a shortened name always keeps its number.
        val short = FilenNames.shorten(name, FilenNames.MAX_BYTES - 6)
        return Names.unique(taken + short, short)
    }

    /**
     * Calls [rename] with [name], then numbered variants, until one is not refused as taken ([isTaken]). Any other
     * failure is thrown as it is. After the first refusal [takenNames] is asked once so known names are skipped;
     * if it fails, the names are simply tried one by one.
     */
    suspend fun <T> claimName(
        name: String,
        takenNames: suspend () -> Collection<String>,
        isTaken: (Throwable) -> Boolean,
        rename: suspend (String) -> T,
    ): T {
        val taken = ArrayList<String>()
        var candidate = name
        var refusal: Throwable? = null
        repeat(MAX_TRIES) {
            try {
                return rename(candidate)
            } catch (e: CancellationException) { throw e
            } catch (e: Throwable) {
                if (!isTaken(e)) throw e
                val first = refusal == null
                refusal = e
                taken += candidate
                if (first) {
                    try { taken += takenNames() } catch (c: CancellationException) { throw c } catch (_: Throwable) { }
                }
                candidate = numbered(taken, name)
            }
        }
        throw IllegalStateException("Couldn't find a free name for “$name”.", refusal)
    }
}
