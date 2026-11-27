package app.filenfiles

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import app.filenfiles.core.FileItem
import app.filenfiles.core.ItemId
import org.json.JSONArray

enum class SortKey { NAME, DATE, SIZE }

enum class ThemeMode(val label: String) { SYSTEM("Follow the system"), LIGHT("Light"), DARK("Dark") }

/** How the Storage shortcuts on Home are drawn; tapping the toggle cycles through them. */
enum class StorageStyle(val label: String) { RINGS("Rings"), CARDS("Large cards"), COMPACT("Icons with text"), ICONS("Icons only"), TEXT("Text only") }

/** View + sort + favorites, persisted in SharedPreferences. Observable through Compose state. */
class Prefs(context: Context) {
    private val sp = context.getSharedPreferences("prefs", Context.MODE_PRIVATE)

    var grid by mutableStateOf(sp.getBoolean("grid", false)); private set
    var sortKey by mutableStateOf(SortKey.valueOf(sp.getString("sortKey", "NAME")!!)); private set
    var ascending by mutableStateOf(sp.getBoolean("asc", true)); private set
    var skippedLogin by mutableStateOf(sp.getBoolean("skippedLogin", false)); private set
    fun skipLogin() { skippedLogin = true; sp.edit().putBoolean("skippedLogin", true).apply() }
    var favorites by mutableStateOf(loadFavorites()); private set
    // 0.4.3 (Stack look): everyone starts on Stack's ring tiles once; the Layout button still cycles the other styles.
    var storageStyle by mutableStateOf(
        if (!sp.getBoolean("ringsDefaultApplied", false)) { sp.edit().putBoolean("ringsDefaultApplied", true).putString("storageStyle", StorageStyle.RINGS.name).apply(); StorageStyle.RINGS }
        else runCatching { StorageStyle.valueOf(sp.getString("storageStyle", null)!!) }.getOrDefault(StorageStyle.RINGS)); private set
    var themeMode by mutableStateOf(runCatching { ThemeMode.valueOf(sp.getString("themeMode", null)!!) }.getOrDefault(ThemeMode.SYSTEM)); private set
    fun updateThemeMode(m: ThemeMode) { themeMode = m; sp.edit().putString("themeMode", m.name).apply() }
    // ---- security / updates ----
    var appLock by mutableStateOf(sp.getBoolean("appLock", false)); private set
    var lockTimeoutMs by mutableStateOf(sp.getLong("lockTimeoutMs", 60_000L)); private set
    var secureScreen by mutableStateOf(sp.getBoolean("secureScreen", false)); private set
    var autoUpdateCheck by mutableStateOf(sp.getBoolean("autoUpdateCheck", true)); private set
    var updateVersion by mutableStateOf(sp.getString("updateVersion", null)); private set
    var updateUrl by mutableStateOf(sp.getString("updateUrl", null)); private set
    var dismissedUpdate by mutableStateOf(sp.getString("dismissedUpdate", null)); private set
    var lastUpdateCheck: Long get() = sp.getLong("lastUpdateCheck", 0); set(v) { sp.edit().putLong("lastUpdateCheck", v).apply() }
    fun updateAppLock(v: Boolean) { appLock = v; sp.edit().putBoolean("appLock", v).apply() }
    fun updateLockTimeout(ms: Long) { lockTimeoutMs = ms; sp.edit().putLong("lockTimeoutMs", ms).apply() }
    fun updateSecureScreen(v: Boolean) { secureScreen = v; sp.edit().putBoolean("secureScreen", v).apply() }
    // One-time migration: the old per-package allowlists are dropped (see LockedAccess.LEGACY_KEYS).
    init { if (LockedAccess.LEGACY_KEYS.any { sp.contains(it) }) sp.edit().apply { LockedAccess.LEGACY_KEYS.forEach { remove(it) } }.apply() }
    /** (package, tree URI) entries the owner lets read that one tree while the app is locked (see [LockedAccess] and FilenDocumentsProvider). */
    var lockedReaders by mutableStateOf(sp.getStringSet("lockedGrants", null)?.toSet() ?: emptySet()); private set
    /** (package, tree URI) entries the owner was already asked about (allowed or "Not now"); listed in Settings, never asked again. */
    var lockedReadersAsked by mutableStateOf(sp.getStringSet("lockedGrantsAsked", null)?.toSet() ?: emptySet()); private set
    private fun saveReaders() { sp.edit().putStringSet("lockedGrants", lockedReaders).putStringSet("lockedGrantsAsked", lockedReadersAsked).apply() }
    fun setLockedReader(pkg: String, tree: String, allowed: Boolean, label: String? = null) {
        val e = LockedAccess.entry(pkg, tree)
        lockedReaders = if (allowed) lockedReaders + e else lockedReaders - e
        lockedReadersAsked = lockedReadersAsked + e
        saveReaders()
        if (label != null) sp.edit().putString("readerLabel:$pkg", label).apply()
    }
    /** Partner setup: [pkg] may read exactly these [trees] while locked. */
    fun allowReaderTrees(pkg: String, trees: List<String>, label: String) { trees.forEach { setLockedReader(pkg, it, true, label) } }
    /**
     * (package, tree URI) entries for a folder an app got through GrantFolderActivity or F11: while locked they may also create
     * files in and delete from that folder (only while the same entry is in [lockedReaders] too - the Settings switch turns both off).
     */
    var lockedWriters by mutableStateOf(sp.getStringSet("lockedWriteGrants", null)?.toSet() ?: emptySet()); private set
    fun grantBackupWriter(pkg: String, tree: String, label: String) {
        lockedWriters = lockedWriters + LockedAccess.entry(pkg, tree)
        sp.edit().putStringSet("lockedWriteGrants", lockedWriters).apply()
        setLockedReader(pkg, tree, true, label)
    }
    /**
     * The partner the owner chose in the partner setup (Stack folder sharing). Tied to the account that chose it, so a
     * different account never inherits it. [account] is the opaque account id.
     */
    fun partnerFor(account: String?): String? =
        if (account != null && sp.getString("partnerAccount", null) == account) sp.getString("partnerEmail", null) else null
    fun setPartner(account: String?, email: String?) {
        if (account == null || email == null) sp.edit().remove("partnerEmail").remove("partnerAccount").apply()
        else sp.edit().putString("partnerEmail", email.trim()).putString("partnerAccount", account).apply()
    }
    /** Forgets which apps may read/write while locked (account change, sign-out). Settings then lists nothing until they ask again. */
    fun clearLockedAccess() {
        lockedReaders = emptySet(); lockedWriters = emptySet(); lockedReadersAsked = emptySet()
        val e = sp.edit().remove("lockedGrants").remove("lockedWriteGrants").remove("lockedGrantsAsked")
        sp.all.keys.filter { it.startsWith("readerLabel:") || it.startsWith("f11:") }.forEach { e.remove(it) }
        e.apply()
    }
    /**
     * One-time move to the HMAC account id (0.5.x): everything saved under the legacy id (last account, partner, F11 folders)
     * is kept for the same account. A different account's data is left to the normal account-change reset.
     */
    fun migrateAccountId(legacy: String?, current: String?) {
        if (legacy == null || current == null || legacy == current) return
        val e = sp.edit()
        if (sp.getString("lastAccount", null) == legacy) e.putString("lastAccount", current)
        if (sp.getString("partnerAccount", null) == legacy) e.putString("partnerAccount", current)
        sp.all.filter { it.key.startsWith("f11:$legacy:") }.forEach { (k, v) -> e.remove(k).putString("f11:$current:" + k.removePrefix("f11:$legacy:"), v as String) }
        e.apply()
    }
    /** The account whose session was last seen signed in (opaque id); null before the first sign-in. */
    var lastAccount: String?
        get() = sp.getString("lastAccount", null)
        set(v) { sp.edit().apply { if (v == null) remove("lastAccount") else putString("lastAccount", v) }.apply() }
    /** The folder F11 created for [pkg] under [account] (its uuid), if any. Only that folder is ever reused. */
    fun f11Folder(pkg: String, account: String?): String? = account?.let { sp.getString("f11:$it:$pkg", null) }
    fun setF11Folder(pkg: String, account: String?, uuid: String) { if (account != null) sp.edit().putString("f11:$account:$pkg", uuid).apply() }
    /** The name saved when the owner was asked (Android may hide other apps' names later). */
    fun lockedReaderLabel(pkg: String): String? = sp.getString("readerLabel:$pkg", null)
    fun updateAutoUpdateCheck(v: Boolean) { autoUpdateCheck = v; sp.edit().putBoolean("autoUpdateCheck", v).apply() }
    fun setAvailableUpdate(version: String?, url: String?) {
        updateVersion = version; updateUrl = url
        sp.edit().putString("updateVersion", version).putString("updateUrl", url).apply()
    }
    fun dismissUpdate(version: String) { dismissedUpdate = version; sp.edit().putString("dismissedUpdate", version).apply() }

    // ---- camera backup ----
    var backupEnabled by mutableStateOf(sp.getBoolean("backupEnabled", false)); private set
    var backupFolder by mutableStateOf(sp.getString("backupFolder", null)); private set
    var backupFolderName by mutableStateOf(sp.getString("backupFolderName", null)); private set
    var backupSince by mutableStateOf(sp.getLong("backupSince", 0L)); private set
    var backupWifiOnly by mutableStateOf(sp.getBoolean("backupWifiOnly", true)); private set
    var backupCharging by mutableStateOf(sp.getBoolean("backupCharging", false)); private set
    var backupVideos by mutableStateOf(sp.getBoolean("backupVideos", true)); private set
    var backupScreenshots by mutableStateOf(sp.getBoolean("backupScreenshots", false)); private set
    var backupStatus by mutableStateOf(sp.getString("backupStatus", null)); private set
    var backupLastRun: Long get() = sp.getLong("backupLastRun", 0); set(v) { sp.edit().putLong("backupLastRun", v).apply() }
    fun updateBackupEnabled(v: Boolean) { backupEnabled = v; sp.edit().putBoolean("backupEnabled", v).apply() }
    fun updateBackupDestination(id: String, name: String) {
        backupFolder = id; backupFolderName = name
        sp.edit().putString("backupFolder", id).putString("backupFolderName", name).apply()
    }
    fun updateBackupSince(ms: Long) { backupSince = ms; sp.edit().putLong("backupSince", ms).apply() }
    fun updateBackupWifiOnly(v: Boolean) { backupWifiOnly = v; sp.edit().putBoolean("backupWifiOnly", v).apply() }
    fun updateBackupCharging(v: Boolean) { backupCharging = v; sp.edit().putBoolean("backupCharging", v).apply() }
    fun updateBackupVideos(v: Boolean) { backupVideos = v; sp.edit().putBoolean("backupVideos", v).apply() }
    fun updateBackupScreenshots(v: Boolean) { backupScreenshots = v; sp.edit().putBoolean("backupScreenshots", v).apply() }
    fun updateBackupStatus(s: String?) { backupStatus = s; sp.edit().putString("backupStatus", s).apply() }

    fun cycleStorageStyle() {
        storageStyle = StorageStyle.entries[(storageStyle.ordinal + 1) % StorageStyle.entries.size]
        sp.edit().putString("storageStyle", storageStyle.name).apply()
    }

    fun updateGrid(v: Boolean) { grid = v; sp.edit().putBoolean("grid", v).apply() }
    fun updateSort(k: SortKey, asc: Boolean) {
        sortKey = k; ascending = asc
        sp.edit().putString("sortKey", k.name).putBoolean("asc", asc).apply()
    }

    fun isFavorite(id: ItemId) = id.value in favorites
    fun toggleFavorite(id: ItemId) {
        favorites = if (id.value in favorites) favorites - id.value else favorites + id.value
        sp.edit().putString("favorites", JSONArray(favorites.toList()).toString()).apply()
    }

    fun setFavorite(id: ItemId, on: Boolean) {
        if ((id.value in favorites) == on) return
        toggleFavorite(id)
    }

    private fun loadFavorites(): Set<String> {
        val raw = sp.getString("favorites", null) ?: return emptySet()
        val a = JSONArray(raw)
        return (0 until a.length()).map { a.getString(it) }.toCollection(LinkedHashSet())
    }

    fun sort(items: List<FileItem>): List<FileItem> {
        val cmp: Comparator<FileItem> = when (sortKey) {
            SortKey.NAME -> compareBy(String.CASE_INSENSITIVE_ORDER) { it.name }
            SortKey.DATE -> compareBy { it.modified }
            SortKey.SIZE -> compareBy { it.size }
        }
        // folders always first
        return items.sortedWith(compareBy<FileItem> { !it.isDirectory }.then(if (ascending) cmp else cmp.reversed()))
    }
}
