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
enum class StorageStyle(val label: String) { CARDS("Large cards"), COMPACT("Icons with text"), ICONS("Icons only"), TEXT("Text only") }

/** View + sort + favorites, persisted in SharedPreferences. Observable through Compose state. */
class Prefs(context: Context) {
    private val sp = context.getSharedPreferences("prefs", Context.MODE_PRIVATE)

    var grid by mutableStateOf(sp.getBoolean("grid", false)); private set
    var sortKey by mutableStateOf(SortKey.valueOf(sp.getString("sortKey", "NAME")!!)); private set
    var ascending by mutableStateOf(sp.getBoolean("asc", true)); private set
    var skippedLogin by mutableStateOf(sp.getBoolean("skippedLogin", false)); private set
    fun skipLogin() { skippedLogin = true; sp.edit().putBoolean("skippedLogin", true).apply() }
    var favorites by mutableStateOf(loadFavorites()); private set
    var storageStyle by mutableStateOf(runCatching { StorageStyle.valueOf(sp.getString("storageStyle", null)!!) }.getOrDefault(StorageStyle.COMPACT)); private set
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
    /** Packages the owner lets read the folder they gave them while the app is locked (see FilenDocumentsProvider). */
    var lockedReaders by mutableStateOf(sp.getStringSet("lockedReaders", null)?.toSet() ?: emptySet()); private set
    /** Packages the owner was already asked about (allowed or "Not now"); listed in Settings, never asked again. */
    var lockedReadersAsked by mutableStateOf(sp.getStringSet("lockedReadersAsked", null)?.toSet() ?: emptySet()); private set
    fun setLockedReader(pkg: String, allowed: Boolean, label: String? = null) {
        lockedReaders = if (allowed) lockedReaders + pkg else lockedReaders - pkg
        lockedReadersAsked = lockedReadersAsked + pkg
        sp.edit().putStringSet("lockedReaders", lockedReaders).putStringSet("lockedReadersAsked", lockedReadersAsked)
            .apply { if (label != null) putString("readerLabel:$pkg", label) }.apply()
    }
    /**
     * Packages that got a backup folder through GrantFolderActivity: while locked they may also create files in and
     * delete from that folder (only while they are in [lockedReaders] too - the Settings switch turns both off).
     */
    var lockedWriters by mutableStateOf(sp.getStringSet("lockedWriters", null)?.toSet() ?: emptySet()); private set
    fun grantBackupWriter(pkg: String, label: String) {
        lockedWriters = lockedWriters + pkg
        sp.edit().putStringSet("lockedWriters", lockedWriters).apply()
        setLockedReader(pkg, true, label)
    }
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
