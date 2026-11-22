package app.filenfiles.core

/** How long a public link works; mirrors the SDK's presets. */
enum class LinkExpiry(val label: String) {
    NEVER("Never"), ONE_HOUR("1 hour"), SIX_HOURS("6 hours"), ONE_DAY("1 day"), THREE_DAYS("3 days"),
    ONE_WEEK("1 week"), TWO_WEEKS("2 weeks"), THIRTY_DAYS("30 days"),
}

/** What to do with a link's password when saving settings. */
sealed interface PasswordChange {
    /** Leave the link's password as it is (an existing password cannot be read back, only replaced or removed). */
    data object Keep : PasswordChange
    data object Remove : PasswordChange
    data class Set(val password: String) : PasswordChange
}

data class LinkSettings(
    val expiry: LinkExpiry = LinkExpiry.ONE_WEEK,
    val password: PasswordChange = PasswordChange.Keep,
    val downloadable: Boolean = true,
)

/** An existing public link. [url] is null when the key could not be decrypted. Never log it: the key is in the URL. */
data class LinkInfo(
    val url: String?,
    val expiry: LinkExpiry,
    /** epoch millis when the server told us, else null */
    val expiresAt: Long?,
    val hasPassword: Boolean,
    val downloadable: Boolean,
)

data class LinkedItem(val item: FileItem, val info: LinkInfo)

/** Public links of personal-drive items (Filen). */
interface LinkStorage {
    fun canLink(item: FileItem): Boolean
    /** The item's current link, or null when it has none. */
    suspend fun linkOf(item: FileItem): LinkInfo?
    /** Creates the link when there is none, then applies [settings]. Folder links publish everything inside. */
    suspend fun saveLink(item: FileItem, settings: LinkSettings): LinkInfo
    suspend fun removeLink(item: FileItem)
    /** Every item that has a link (personal drive). */
    suspend fun links(): List<LinkedItem>
}

object LinkUrl {
    private const val BASE = "https://app.filen.io/#/"

    /**
     * The URL the Filen web app gives a public link: route `d` for a file, `f` for a folder, the link uuid, `%23` (an escaped `#`)
     * and the key with every byte of its string written as two lowercase hex digits. [key] is the file's own key for a file link and
     * the link key for a folder link. Same output as the official filen-cli (its tests are the golden vectors in LinkUrlTest).
     */
    fun build(isDirectory: Boolean, linkUuid: String, key: String): String {
        val hex = StringBuilder(key.length * 2)
        for (b in key.toByteArray(Charsets.UTF_8)) hex.append(HEX[(b.toInt() shr 4) and 0xF]).append(HEX[b.toInt() and 0xF])
        return BASE + (if (isDirectory) "f" else "d") + "/" + linkUuid + "%23" + hex
    }
    private const val HEX = "0123456789abcdef"
}
