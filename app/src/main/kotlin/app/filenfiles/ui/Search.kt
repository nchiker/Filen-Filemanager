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
import app.filenfiles.core.Origin
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
    var results by remember { mutableStateOf<List<FileItem>?>(null) }
    var searching by remember { mutableStateOf(false) }
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { focus.requestFocus() }
    val signedIn = rememberSignedIn(env)
    LaunchedEffect(query, signedIn) {
        if (query.isBlank()) { results = null; searching = false; return@LaunchedEffect }
        delay(300) // debounce
        searching = true
        results = emptyList()
        try {
            // Providers finish at different speeds (local is fast, a cold Filen index is not): show each as it lands.
            env.app.router.searchProgressive(query).collect { results = it }
        } catch (e: CancellationException) { throw e } finally { searching = false }
    }
    Column(Modifier.fillMaxSize().statusBarsPadding()) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton({ env.nav.popBackStack() }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") }
            OutlinedTextField(
                query, { query = it }, Modifier.weight(1f).focusRequester(focus).padding(end = 8.dp),
                placeholder = { Text("Search files and folders") }, singleLine = true,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search), keyboardActions = KeyboardActions(),
                trailingIcon = { if (query.isNotEmpty()) IconButton({ query = "" }) { Icon(Icons.Filled.Close, "Clear") } },
            )
        }
        LazyRow(contentPadding = PaddingValues(horizontal = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            items(Scope.entries) { s -> FilterChip(scope == s, { scope = s }, { Text(s.label) }) }
        }
        if (searching) LinearProgressIndicator(Modifier.fillMaxWidth().padding(top = 4.dp))
        val r = results
        if (r == null) EmptyState("Search across Filen, shared and local files")
        else {
            val q = query.trim().lowercase()
            val shown = r.filter { scope.match(it.origin) }
                .sortedWith(compareBy({ !it.name.lowercase().startsWith(q) }, { it.name.lowercase() }))
            when {
                shown.isNotEmpty() -> FileCollection(shown, false, env.actions, mixed = true)
                searching -> EmptyState("Searching…")
                else -> EmptyState("No results for “${query.trim()}”" + if (scope == Scope.ALL) "" else " in ${scope.label}")
            }
        }
    }
}
