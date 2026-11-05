package app.filenfiles

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import app.filenfiles.core.FileItem
import app.filenfiles.core.ItemId
import org.json.JSONArray

enum class SortKey { NAME, DATE, SIZE }

/** View + sort + favorites, persisted in SharedPreferences. Observable through Compose state. */
class Prefs(context: Context) {
    private val sp = context.getSharedPreferences("prefs", Context.MODE_PRIVATE)

    var grid by mutableStateOf(sp.getBoolean("grid", false)); private set
    var sortKey by mutableStateOf(SortKey.valueOf(sp.getString("sortKey", "NAME")!!)); private set
    var ascending by mutableStateOf(sp.getBoolean("asc", true)); private set
    var skippedLogin by mutableStateOf(sp.getBoolean("skippedLogin", false)); private set
    fun skipLogin() { skippedLogin = true; sp.edit().putBoolean("skippedLogin", true).apply() }
    var favorites by mutableStateOf(loadFavorites()); private set

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
