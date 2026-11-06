package app.filenfiles.ui

import android.media.MediaScannerConnection
import android.text.format.Formatter
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.filenfiles.core.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

private sealed interface Phase {
    data object Idle : Phase
    data class Scanning(val text: String, val done: Int = 0, val total: Int = 0) : Phase
    data object Done : Phase
    data class Failed(val message: String) : Phase
}

private val MIN_SIZES = listOf(10L shl 10 to "10 KB", 100L shl 10 to "100 KB", 1L shl 20 to "1 MB", 10L shl 20 to "10 MB")
private const val LARGE_MIN = 50L shl 20

/** Finds duplicate files and the biggest files on the device or in Filen Drive, and deletes what the user ticks. */
@Composable
fun CleanupScreen(env: AppEnv) {
    val app = env.app
    val ctx = LocalContext.current
    // "This device" first: it is the common case and the cheaper scan.
    val scopes = remember { app.router.cleanupScopes().sortedBy { if (it.label == "This device") 0 else 1 } }
    var scopeIdx by rememberSaveable { mutableIntStateOf(0) }
    var minIdx by rememberSaveable { mutableIntStateOf(1) }
    var tab by rememberSaveable { mutableIntStateOf(0) }
    var phase by remember { mutableStateOf<Phase>(Phase.Idle) }
    var groups by remember { mutableStateOf<List<DupGroup>>(emptyList()) }
    var large by remember { mutableStateOf<List<ScanFile>>(emptyList()) }
    var skippedNoHash by remember { mutableIntStateOf(0) }
    var selected by remember { mutableStateOf<Set<String>>(emptySet()) }
    var keepRule by remember { mutableStateOf(KeepRule.OLDEST) }
    var confirm by remember { mutableStateOf(false) }
    var menu by remember { mutableStateOf(false) }
    var job by remember { mutableStateOf<Job?>(null) }
    val scope = rememberCoroutineScope()
    val storage = scopes.getOrNull(scopeIdx)

    fun select(rule: KeepRule, from: List<DupGroup> = groups) {
        keepRule = rule
        selected = from.flatMap { g -> Dedupe.suggestDeletes(g, rule).map { it.item.id.value } }.toSet()
    }

    fun scan() {
        val st = storage ?: return
        job?.cancel()
        selected = emptySet(); groups = emptyList(); large = emptyList()
        job = scope.launch {
            try {
                phase = Phase.Scanning("Listing files…")
                val all = st.scanFiles { n -> phase = Phase.Scanning("Found $n files") }
                large = Dedupe.largest(all, LARGE_MIN)
                skippedNoHash = if (st.label == "Filen Drive") all.count { it.hash == null } else 0
                val found = Dedupe.find(all, st.hasher, MIN_SIZES[minIdx].first) { stage, d, t -> phase = Phase.Scanning(stage, d, t) }
                groups = found
                select(keepRule, found)
                phase = Phase.Done
            } catch (e: CancellationException) { phase = Phase.Idle; throw e
            } catch (e: Throwable) { phase = Phase.Failed(e.friendly()) }
        }
    }

    val allShown: List<ScanFile> = if (tab == 0) groups.flatMap { it.files } else large
    val chosen = allShown.filter { it.item.id.value in selected }
    val chosenBytes = chosen.sumOf { it.item.size }

    fun toggle(f: ScanFile) {
        val id = f.item.id.value
        if (id in selected) { selected = selected - id; return }
        // A duplicate group must always keep at least one copy.
        val g = if (tab == 0) groups.firstOrNull { gr -> gr.files.any { it.item.id.value == id } } else null
        if (g != null && g.files.count { it.item.id.value in selected } >= g.files.size - 1) {
            scope.launch { env.snackbar.showSnackbar("Keep at least one copy of each file") }; return
        }
        selected = selected + id
    }

    if (confirm) {
        val fromFilen = storage?.label == "Filen Drive"
        AlertDialog(
            onDismissRequest = { confirm = false },
            title = { Text("Delete ${chosen.size} file${if (chosen.size == 1) "" else "s"}?") },
            text = {
                Text(Formatter.formatFileSize(ctx, chosenBytes) + " will be freed. " +
                    (if (chosen.size == 1) (if (fromFilen) "The file moves to the Filen trash, where you can restore it."
                        else "It is deleted from this phone for good. This can't be undone.")
                    else if (fromFilen) "The files move to the Filen trash, where you can restore them."
                    else "They are deleted from this phone for good. This can't be undone."))
            },
            confirmButton = {
                TextButton({
                    confirm = false
                    val items = chosen.map { it.item }
                    val keepGroups = groups
                    app.appScope.launch {
                        var ok = 0; var freed = 0L; val gone = HashSet<String>(); val paths = ArrayList<String>()
                        for (item in items) {
                            // Re-check at the last moment: never remove the final copy of anything.
                            val g = keepGroups.firstOrNull { gr -> gr.files.any { it.item.id == item.id } }
                            if (tab == 0 && g != null && g.files.count { it.item.id.value in gone || it.item.id == item.id } >= g.files.size) continue
                            val m = app.router.modifier(item)
                            if (m != null && runCatching { m.trash(item) }.isSuccess) {
                                ok++; freed += item.size; gone += item.id.value
                                if (item.origin == Origin.LOCAL) paths += item.id.key
                            }
                        }
                        if (paths.isNotEmpty()) MediaScannerConnection.scanFile(ctx, paths.toTypedArray(), null, null)
                        groups = groups.map { g -> g.copy(files = g.files.filter { it.item.id.value !in gone }) }.filter { it.files.size > 1 }
                        large = large.filter { it.item.id.value !in gone }
                        selected = selected - gone
                        app.notifyChanged()
                        val failed = items.size - ok
                        env.snackbar.showSnackbar("Deleted $ok file${if (ok == 1) "" else "s"}, freed ${Formatter.formatFileSize(ctx, freed)}" +
                            if (failed > 0) " ($failed couldn't be deleted)" else "")
                    }
                }) { Text("Delete") }
            },
            dismissButton = { TextButton({ confirm = false }) { Text("Cancel") } },
        )
    }

    Column(Modifier.fillMaxSize()) {
        AppBar("Free up space", onBack = { env.nav.popBackStack() }, actions = {
            if (phase == Phase.Done && tab == 0 && groups.isNotEmpty()) Box {
                IconButton({ menu = true }) { Icon(Icons.Filled.MoreVert, "Selection options") }
                DropdownMenu(menu, { menu = false }) {
                    KeepRule.entries.forEach { r -> DropdownMenuItem({ Text(r.label) }, { menu = false; select(r) }) }
                    DropdownMenuItem({ Text("Clear selection") }, { menu = false; selected = emptySet() })
                }
            }
        })
        if (storage == null) { EmptyState("Nothing to scan on this device yet."); return@Column }
        Row(Modifier.padding(horizontal = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            scopes.forEachIndexed { i, s ->
                FilterChip(scopeIdx == i, { if (phase !is Phase.Scanning) { scopeIdx = i; phase = Phase.Idle; groups = emptyList(); large = emptyList(); selected = emptySet() } }, { Text(s.label) })
            }
        }
        Row(Modifier.padding(horizontal = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            MIN_SIZES.forEachIndexed { i, (_, label) ->
                FilterChip(minIdx == i, { minIdx = i }, { Text("≥ $label") }, enabled = phase !is Phase.Scanning)
            }
        }
        Row(Modifier.padding(horizontal = 12.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            when (val p = phase) {
                is Phase.Scanning -> {
                    Column(Modifier.weight(1f)) {
                        Text(p.text + if (p.total > 0) " (${p.done} of ${p.total})" else "", style = MaterialTheme.typography.labelLarge)
                        if (p.total > 0) LinearProgressIndicator(progress = { p.done.toFloat() / p.total }, Modifier.fillMaxWidth())
                        else LinearProgressIndicator(Modifier.fillMaxWidth())
                    }
                    TextButton({ job?.cancel() }) { Text("Stop") }
                }
                else -> Button({ scan() }) { Text(if (phase == Phase.Done) "Scan again" else "Scan ${storage.label}") }
            }
        }
        if (phase == Phase.Done) {
            TabRow(tab) {
                Tab(tab == 0, { tab = 0 }, text = { Text("Duplicates (${groups.size})") })
                Tab(tab == 1, { tab = 1 }, text = { Text("Large files (${large.size})") })
            }
        }
        when (val p = phase) {
            Phase.Idle -> EmptyState("Finds duplicate files and your biggest files. Nothing is deleted until you confirm.")
            is Phase.Scanning -> Spacer(Modifier.weight(1f))
            is Phase.Failed -> EmptyState(p.message)
            Phase.Done -> {
                if (tab == 0) {
                    val total = groups.sumOf { it.reclaimable }
                    if (groups.isEmpty()) EmptyState("No duplicates found at this size or larger." +
                        if (skippedNoHash > 0) "\n$skippedNoHash older Filen files have no content fingerprint and could not be compared." else "")
                    else {
                        Text("${groups.size} ${if (groups.size == 1) "set" else "sets"} of duplicates · up to ${Formatter.formatFileSize(ctx, total)} can be freed" +
                            (if (skippedNoHash > 0) " · $skippedNoHash older files couldn't be compared" else ""),
                            Modifier.padding(horizontal = 16.dp, vertical = 6.dp), style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                        LazyColumn(Modifier.weight(1f)) {
                            groups.forEach { g ->
                                item(key = "h" + g.files.first().item.id.value) {
                                    Text("${g.files.size} copies · ${Formatter.formatFileSize(ctx, g.size)} each", Modifier.padding(start = 16.dp, top = 12.dp, bottom = 2.dp),
                                        style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)
                                }
                                items(g.files, key = { it.item.id.value }) { f -> CleanupRow(f, f.item.id.value in selected) { toggle(f) } }
                            }
                        }
                    }
                } else {
                    if (large.isEmpty()) EmptyState("No files of 50 MB or more.")
                    else LazyColumn(Modifier.weight(1f)) {
                        items(large, key = { it.item.id.value }) { f -> CleanupRow(f, f.item.id.value in selected, showSize = true) { toggle(f) } }
                    }
                }
            }
        }
        if (phase == Phase.Done && selected.isNotEmpty()) Surface(tonalElevation = 3.dp, modifier = Modifier.fillMaxWidth()) {
            Button({ confirm = true }, Modifier.fillMaxWidth().padding(12.dp).navigationBarsPadding()) {
                Text("Delete ${chosen.size} file${if (chosen.size == 1) "" else "s"} · ${Formatter.formatFileSize(ctx, chosenBytes)}")
            }
        }
    }
}

@Composable
private fun CleanupRow(f: ScanFile, checked: Boolean, showSize: Boolean = false, onToggle: () -> Unit) {
    val ctx = LocalContext.current
    ListItem(
        modifier = Modifier.clickable(onClick = onToggle),
        leadingContent = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Checkbox(checked, { onToggle() })
                ItemThumb(f.item, 40.dp)
            }
        },
        headlineContent = { Text(f.item.name, maxLines = 1, overflow = TextOverflow.Ellipsis) },
        supportingContent = {
            Column {
                Text(f.path.substringBeforeLast('/', "").ifEmpty { "/" }, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodySmall)
                Text((if (showSize) Formatter.formatFileSize(ctx, f.item.size) + " · " else "") + formatDate(f.item.modified), style = MaterialTheme.typography.bodySmall)
            }
        },
    )
}
