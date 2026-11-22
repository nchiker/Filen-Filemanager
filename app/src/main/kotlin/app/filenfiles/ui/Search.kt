package app.filenfiles.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import app.filenfiles.core.FileItem
import app.filenfiles.core.DateFilter
import app.filenfiles.core.Origin
import app.filenfiles.core.SearchFilters
import app.filenfiles.core.SizeFilter
import app.filenfiles.core.TypeFilter
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay

private enum class Scope(val label: String, val match: (Origin) -> Boolean) {
    ALL("All", { true }),
    FILEN("Filen", { it == Origin.FILEN_PERSONAL }),
    SHARED("Shared", { it == Origin.FILEN_SHARED_WITH_ME || it == Origin.FILEN_SHARED_BY_ME }),
    LOCAL("This device", { it == Origin.LOCAL }),
}

@Composable
fun SearchScreen(env: AppEnv) {
    var query by rememberSaveable { mutableStateOf("") }
    var scope by rememberSaveable { mutableStateOf(Scope.ALL) }
    var filters by rememberSaveable(stateSaver = FiltersSaver) { mutableStateOf(SearchFilters()) }
    var results by remember { mutableStateOf<List<FileItem>?>(null) }
    var searching by remember { mutableStateOf(false) }
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { focus.requestFocus() }
    val signedIn = rememberSignedIn(env)
    // Providers cap results (100); with a filter on, look deeper so matches past the first 100 names aren't missed.
    val limit = if (filters.active) 1000 else 100
    LaunchedEffect(query, signedIn, limit) {
        if (query.isBlank()) { results = null; searching = false; return@LaunchedEffect }
        delay(300) // debounce
        searching = true
        results = emptyList()
        try {
            // Providers finish at different speeds (local is fast, a cold Filen index is not): show each as it lands.
            env.app.router.searchProgressive(query, limit).collect { results = it }
        } catch (e: CancellationException) { throw e } finally { searching = false }
    }
    Column(Modifier.fillMaxSize().statusBarsPadding()) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton({ env.nav.popBackStack() }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") }
            OutlinedTextField(
                query, { query = it }, Modifier.weight(1f).focusRequester(focus).padding(end = 8.dp),
                placeholder = { Text("Search files and folders") }, singleLine = true,
                shape = androidx.compose.foundation.shape.RoundedCornerShape(28.dp),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search), keyboardActions = KeyboardActions(),
                trailingIcon = { if (query.isNotEmpty()) IconButton({ query = "" }) { Icon(Icons.Filled.Close, "Clear") } },
            )
        }
        LazyRow(contentPadding = PaddingValues(horizontal = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            items(Scope.entries) { s -> FilterChip(scope == s, { scope = s }, { Text(s.label) }) }
        }
        LazyRow(contentPadding = PaddingValues(horizontal = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            item { FilterMenu("Type", filters.type != TypeFilter.ANY, filters.type.label, TypeFilter.entries.map { it.label }) { filters = filters.copy(type = TypeFilter.entries[it]) } }
            item { FilterMenu("Date", filters.date != DateFilter.ANY, filters.date.label, DateFilter.entries.map { it.label }) { filters = filters.copy(date = DateFilter.entries[it]) } }
            item { FilterMenu("Size", filters.size != SizeFilter.ANY, filters.size.label, SizeFilter.entries.map { it.label }) { filters = filters.copy(size = SizeFilter.entries[it]) } }
            if (filters.active) item { AssistChip({ filters = SearchFilters() }, { Text("Clear filters") }) }
        }
        if (searching) LinearProgressIndicator(Modifier.fillMaxWidth().padding(top = 4.dp))
        val r = results
        if (r == null) EmptyState("Search across Filen, shared and local files")
        else {
            val q = query.trim().lowercase()
            val now = System.currentTimeMillis()
            val shown = r.filter { scope.match(it.origin) && filters.matches(it, now) }
                .sortedWith(compareBy({ !it.name.lowercase().startsWith(q) }, { it.name.lowercase() }))
            when {
                shown.isNotEmpty() -> FileCollection(shown, false, env.actions, mixed = true)
                searching -> EmptyState("Searching…")
                else -> EmptyState("No results for “${query.trim()}”" + (if (scope == Scope.ALL) "" else " in ${scope.label}") + (if (filters.active) " with these filters" else ""))
            }
        }
    }
}

private val FiltersSaver = androidx.compose.runtime.saveable.Saver<SearchFilters, List<Int>>(
    save = { listOf(it.type.ordinal, it.date.ordinal, it.size.ordinal) },
    restore = { SearchFilters(TypeFilter.entries[it[0]], DateFilter.entries[it[1]], SizeFilter.entries[it[2]]) },
)

@Composable
private fun FilterMenu(title: String, active: Boolean, current: String, options: List<String>, onPick: (Int) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box {
        FilterChip(active, { open = true }, { Text(if (active) current else title) })
        DropdownMenu(open, { open = false }) {
            options.forEachIndexed { i, label -> DropdownMenuItem({ Text(label) }, { open = false; onPick(i) }) }
        }
    }
}
