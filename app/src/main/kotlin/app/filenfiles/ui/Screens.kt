package app.filenfiles.ui

import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import app.filenfiles.StorageStyle
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import app.filenfiles.core.FileItem
import app.filenfiles.core.Folder
import app.filenfiles.core.ItemId
import app.filenfiles.core.Origin
import app.filenfiles.filen.FilenSession
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch

// ---------- shared bits ----------

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AppBar(title: String, actions: @Composable RowScope.() -> Unit = {}, onBack: (() -> Unit)? = null) {
    TopAppBar(
        title = { Text(title, maxLines = 1) },
        navigationIcon = { if (onBack != null) IconButton(onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } },
        actions = actions,
        colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.surface, titleContentColor = MaterialTheme.colorScheme.onSurface,
            actionIconContentColor = MaterialTheme.colorScheme.onSurfaceVariant, navigationIconContentColor = MaterialTheme.colorScheme.onSurfaceVariant),
    )
}

/** True while the app holds All-files access; re-evaluated each time the app resumes. */
@Composable
fun rememberHasAllFiles(env: AppEnv): Boolean {
    var has by remember { mutableStateOf(env.app.local.hasAccess()) }
    val owner = LocalLifecycleOwner.current
    DisposableEffect(owner) {
        val o = LifecycleEventObserver { _, e -> if (e == Lifecycle.Event.ON_RESUME) has = env.app.local.hasAccess() }
        owner.lifecycle.addObserver(o); onDispose { owner.lifecycle.removeObserver(o) }
    }
    return has
}

@Composable
fun AllFilesBanner(env: AppEnv) {
    val ctx = LocalContext.current
    Card(Modifier.fillMaxWidth().padding(12.dp), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer)) {
        Column(Modifier.padding(16.dp)) {
            Text("Allow access to files on this device", style = MaterialTheme.typography.titleSmall)
            Text("A file manager needs “All files access” to browse Downloads, Documents and other folders.", style = MaterialTheme.typography.bodySmall)
            Spacer(Modifier.height(8.dp))
            Button({
                ctx.startActivity(Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION, Uri.parse("package:${ctx.packageName}")))
            }) { Text("Grant access") }
        }
    }
}

@Composable
fun rememberSignedIn(env: AppEnv): Boolean {
    val s by env.app.session.state.collectAsState()
    return s is FilenSession.State.LoggedIn
}

/** Round account button (initial of the signed-in email); opens the sign-in/sign-out dialog. */
@Composable
fun AccountAction(env: AppEnv) {
    var show by remember { mutableStateOf(false) }
    val state by env.app.session.state.collectAsState()
    val email = (state as? FilenSession.State.LoggedIn)?.email
    Box(
        Modifier.size(40.dp).clip(CircleShape).background(MaterialTheme.colorScheme.primaryContainer)
            .clickable { show = true }.semantics { contentDescription = "Account" },
        Alignment.Center,
    ) {
        if (email != null) Text(email.first().uppercase(), color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.titleMedium)
        else Icon(Icons.Filled.Person, null, Modifier.size(22.dp), tint = MaterialTheme.colorScheme.primary)
    }
    if (show) AlertDialog(
        onDismissRequest = { show = false },
        title = { Text("Filen account") },
        text = { Text(email?.let { "Signed in as $it" } ?: if (state is FilenSession.State.Expired) "Session expired" else "Not signed in") },
        confirmButton = {
            if (state is FilenSession.State.LoggedIn)
                TextButton({ show = false; env.app.session.logout() }) { Text("Sign out") }
            else TextButton({ show = false; env.nav.navigate("login") }) { Text("Sign in") }
        },
        dismissButton = { TextButton({ show = false }) { Text("Close") } },
    )
}

/** Stack's home header: one raised white pill - search icon, hint, settings, and the account avatar. */
@Composable
private fun StackSearchBar(env: AppEnv) {
    Surface(
        Modifier.statusBarsPadding().padding(horizontal = 16.dp, vertical = 12.dp).fillMaxWidth().height(56.dp),
        shape = androidx.compose.foundation.shape.RoundedCornerShape(28.dp), color = MaterialTheme.colorScheme.surface,
        shadowElevation = 4.dp, border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
    ) {
        Row(Modifier.clickable { env.nav.navigate("search") }.padding(start = 16.dp, end = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Filled.Search, "Search", tint = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.width(12.dp))
            Text("Search your files", Modifier.weight(1f), color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1,
                style = MaterialTheme.typography.titleMedium)
            Box(
                Modifier.size(40.dp).clip(CircleShape).background(MaterialTheme.colorScheme.surfaceVariant)
                    .clickable { env.nav.navigate("settings") }.semantics { contentDescription = "Settings" },
                Alignment.Center,
            ) { Icon(Icons.Filled.Tune, null, Modifier.size(20.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant) }
            Spacer(Modifier.width(8.dp))
            AccountAction(env)
        }
    }
}

// ---------- login ----------

@Composable
fun LoginScreen(env: AppEnv) {
    var email by remember { mutableStateOf(env.app.session.lastEmail.orEmpty()) }
    var pw by remember { mutableStateOf("") }
    var code by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    val state by env.app.session.state.collectAsState()
    LaunchedEffect(state) {
        if (state is FilenSession.State.LoggedIn) env.nav.navigate("home") { popUpTo("login") { inclusive = true } }
    }
    Column(Modifier.fillMaxSize().padding(24.dp).imePadding(), Arrangement.Center, Alignment.CenterHorizontally) {
        Icon(Icons.Filled.Cloud, null, Modifier.size(64.dp), tint = MaterialTheme.colorScheme.primary)
        Text("Sign in to Filen", style = MaterialTheme.typography.headlineSmall, modifier = Modifier.padding(vertical = 12.dp))
        if (state is FilenSession.State.Expired)
            Text("Your session expired. Sign in again to keep using your Filen files.", color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(bottom = 8.dp))
        OutlinedTextField(email, { email = it }, label = { Text("Email") }, singleLine = true, modifier = Modifier.fillMaxWidth(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email))
        Spacer(Modifier.height(8.dp))
        OutlinedTextField(pw, { pw = it }, label = { Text("Password") }, singleLine = true, modifier = Modifier.fillMaxWidth(),
            visualTransformation = PasswordVisualTransformation(), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password))
        Spacer(Modifier.height(8.dp))
        OutlinedTextField(code, { code = it }, label = { Text("2FA code (only if enabled)") }, singleLine = true, modifier = Modifier.fillMaxWidth(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number))
        error?.let { Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(top = 8.dp)) }
        Spacer(Modifier.height(16.dp))
        Button(
            onClick = {
                busy = true; error = null
                scope.launch {
                    try { env.app.session.login(email, pw, code) }
                    catch (e: CancellationException) { throw e }
                    catch (e: Throwable) { error = e.friendly() }
                    busy = false
                }
            },
            enabled = !busy && email.isNotBlank() && pw.isNotEmpty(), modifier = Modifier.fillMaxWidth(),
        ) { if (busy) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp) else Text("Sign in") }
        TextButton({
            env.app.prefs.skipLogin()
            env.nav.navigate("home") { popUpTo("login") { inclusive = true } }
        }) { Text("Skip — use local files only") }
    }
}

// ---------- home ----------

@Composable
fun HomeScreen(env: AppEnv) {
    val signedIn = rememberSignedIn(env)
    val hasFiles = rememberHasAllFiles(env)
    val roots = loadState(signedIn, hasFiles) { env.app.router.roots() }
    val recents = loadState(signedIn, hasFiles, env.app.changeCounter) { env.app.router.recent(40) }
    val favs = loadFavorites(env, signedIn)
    Column(Modifier.fillMaxSize()) {
        StackSearchBar(env)
        LazyColumn(Modifier.fillMaxSize()) {
            if (!hasFiles) item { AllFilesBanner(env) }
            env.app.prefs.updateVersion?.takeIf { it != env.app.prefs.dismissedUpdate }?.let { v ->
                item {
                    val ctx = LocalContext.current
                    Card(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp)) {
                        Row(Modifier.padding(start = 16.dp, top = 8.dp, bottom = 8.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Filled.SystemUpdate, null); Spacer(Modifier.width(12.dp))
                            Text("Version $v is available", Modifier.weight(1f))
                            TextButton({ runCatching { ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(env.app.prefs.updateUrl ?: app.filenfiles.UpdateChecker.RELEASES_PAGE))) } }) { Text("Download") }
                            IconButton({ env.app.prefs.dismissUpdate(v) }) { Icon(Icons.Filled.Close, "Dismiss") }
                        }
                    }
                }
            }
            if (!signedIn) item {
                Card(Modifier.fillMaxWidth().padding(horizontal = 12.dp).clickable { env.nav.navigate("login") }) {
                    Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Filled.Cloud, null); Spacer(Modifier.width(12.dp)); Text("Sign in to Filen to add your cloud storage")
                    }
                }
            }
            item {
                val style = env.app.prefs.storageStyle
                Row(Modifier.fillMaxWidth().padding(end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.weight(1f)) { SectionTitle("Storage") }
                    IconButton({ env.app.prefs.cycleStorageStyle() }, Modifier.size(40.dp)) {
                        // Shows the layout that tapping switches TO, described for accessibility.
                        val next = StorageStyle.entries[(style.ordinal + 1) % StorageStyle.entries.size]
                        Icon(when (style) {
                            StorageStyle.CARDS -> Icons.Filled.ViewAgenda
                            StorageStyle.COMPACT -> Icons.Filled.Apps
                            StorageStyle.ICONS -> Icons.Filled.TextFields
                            StorageStyle.TEXT -> Icons.Filled.GridView
                        }, "Storage layout: ${style.label}. Tap for ${next.label}", Modifier.size(20.dp))
                    }
                }
            }
            item {
                when (val r = roots) {
                    is Load.Ok -> StorageShortcuts(env.app.prefs.storageStyle, r.value) { env.nav.navigate(folderRoute(it.id)) }
                    else -> LinearProgressIndicator(Modifier.fillMaxWidth().padding(16.dp))
                }
            }
            item {
                ListItem(
                    modifier = Modifier.clickable { env.nav.navigate("cleanup") },
                    leadingContent = { Icon(Icons.Filled.CleaningServices, null, tint = MaterialTheme.colorScheme.primary) },
                    headlineContent = { Text("Free up space") },
                    supportingContent = { Text("Find duplicate and large files") },
                )
            }
            val fv = (favs as? Load.Ok)?.value.orEmpty()
            if (fv.isNotEmpty()) {
                item { SectionTitle("Favorites") }
                item {
                    LazyRow(contentPadding = PaddingValues(horizontal = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        items(fv, key = { it.id.value }) { FavoriteChip(it) { env.actions.open(it) } }
                    }
                }
            }
            item { SectionTitle("Recent files") }
            when (val r = recents) {
                Load.Loading -> item { LinearProgressIndicator(Modifier.fillMaxWidth().padding(16.dp)) }
                is Load.Fail -> item { Text(r.message, Modifier.padding(16.dp), color = MaterialTheme.colorScheme.error) }
                is Load.Ok -> if (r.value.isEmpty()) item { Text("No recent files", Modifier.padding(16.dp)) }
                else items(r.value, key = { it.id.value }) { FileRow(it, env.actions, mixed = true) }
            }
        }
    }
}

@Composable
fun SectionTitle(t: String) =
    Text(t, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(start = 16.dp, top = 16.dp, bottom = 8.dp))

/** The storage entry points, wrapped (never scrolled sideways) in the chosen density. */
@OptIn(ExperimentalLayoutApi::class, ExperimentalFoundationApi::class)
@Composable
private fun StorageShortcuts(style: StorageStyle, folders: List<Folder>, onOpen: (Folder) -> Unit) {
    val ctx = androidx.compose.ui.platform.LocalContext.current
    FlowRow(
        Modifier.padding(horizontal = 12.dp),
        maxItemsInEachRow = if (style == StorageStyle.CARDS) 3 else Int.MAX_VALUE,
        horizontalArrangement = Arrangement.spacedBy(if (style == StorageStyle.ICONS) 4.dp else 8.dp),
        verticalArrangement = Arrangement.spacedBy(if (style == StorageStyle.CARDS) 8.dp else 4.dp),
    ) {
        folders.forEach { f ->
            key(f.id.value) {
                when (style) {
                    StorageStyle.CARDS -> ShortcutCard(f, Modifier.weight(1f)) { onOpen(f) }
                    StorageStyle.COMPACT -> AssistChip(
                        { onOpen(f) }, { Text(f.name, maxLines = 1) },
                        leadingIcon = { Icon(rootIcon(f), null, Modifier.size(18.dp), tint = MaterialTheme.colorScheme.primary) },
                    )
                    StorageStyle.TEXT -> AssistChip({ onOpen(f) }, { Text(f.name, maxLines = 1) })
                    StorageStyle.ICONS -> Box(
                        Modifier.size(48.dp).clip(androidx.compose.foundation.shape.CircleShape)
                            .background(MaterialTheme.colorScheme.secondaryContainer)
                            .semantics { contentDescription = f.name }
                            // No label is drawn, so a long press names it.
                            .combinedClickable(onClick = { onOpen(f) }, onLongClick = {
                                android.widget.Toast.makeText(ctx, f.name, android.widget.Toast.LENGTH_SHORT).show()
                            }),
                        Alignment.Center,
                    ) { Icon(rootIcon(f), null, tint = MaterialTheme.colorScheme.onSecondaryContainer) }
                }
            }
        }
        // Keep a short last row of large cards at the same width as the others.
        if (style == StorageStyle.CARDS) repeat((3 - folders.size % 3) % 3) { Spacer(Modifier.weight(1f)) }
    }
}

@Composable
private fun ShortcutCard(f: Folder, modifier: Modifier = Modifier, onClick: () -> Unit) {
    OutlinedCard(modifier.clickable(onClick = onClick), shape = androidx.compose.foundation.shape.RoundedCornerShape(12.dp),
        colors = CardDefaults.outlinedCardColors(containerColor = MaterialTheme.colorScheme.surface)) {
        Column(Modifier.fillMaxWidth().padding(vertical = 14.dp, horizontal = 8.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(rootIcon(f), null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(28.dp))
            Spacer(Modifier.height(4.dp))
            Text(f.name, style = MaterialTheme.typography.labelMedium, maxLines = 2, textAlign = androidx.compose.ui.text.style.TextAlign.Center)
        }
    }
}

private fun rootIcon(f: Folder) = when {
    f.origin == Origin.FILEN_PERSONAL -> Icons.Filled.Cloud
    f.origin == Origin.FILEN_SHARED_WITH_ME -> Icons.Filled.People
    f.origin == Origin.FILEN_SHARED_BY_ME -> Icons.Filled.Share
    f.origin == Origin.EXTERNAL -> Icons.Filled.CloudQueue
    f.name == "Downloads" -> Icons.Filled.Download
    f.name == "Pictures" || f.name == "Camera" -> Icons.Filled.Image
    f.name == "Music" -> Icons.Filled.AudioFile
    f.name == "Movies" -> Icons.Filled.VideoFile
    f.name == "Documents" -> Icons.Filled.Description
    else -> Icons.Filled.PhoneAndroid
}

@Composable
private fun FavoriteChip(item: FileItem, onClick: () -> Unit) {
    AssistChip(onClick, { Text(item.name, maxLines = 1) }, leadingIcon = { Icon(iconFor(item), null, Modifier.size(18.dp)) })
}

@Composable
fun loadFavorites(env: AppEnv, signedIn: Boolean): Load<List<FileItem>> {
    val ids = env.app.prefs.favorites
    return loadState(ids, signedIn, env.app.changeCounter) {
        coroutineScope {
            val local = ids.map { id -> async { runCatching { env.app.router.stat(ItemId(id)) }.getOrNull() } }.awaitAll().filterNotNull()
            val server = runCatching { env.app.router.serverFavorites() }.getOrNull()
            // Filen's own favorites: refresh the app's view, and honour toggles the server listing hasn't caught up with.
            if (server != null) env.app.applyServerFavorites(server.filter { it.origin == Origin.FILEN_PERSONAL }.map { it.id.value }.toSet())
            val starred = env.app.serverFavIds
            (local + (server ?: emptyList()).filter { it.origin != Origin.FILEN_PERSONAL || it.id.value in starred })
                .distinctBy { it.id.value }
        }
    }
}

// ---------- files tab ----------

@Composable
fun FilesScreen(env: AppEnv) {
    val signedIn = rememberSignedIn(env)
    val hasFiles = rememberHasAllFiles(env)
    val roots = loadState(signedIn, hasFiles, env.app.changeCounter) { env.app.router.roots() }
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var removing by remember { mutableStateOf<Folder?>(null) }
    // The system folder picker: the user chooses e.g. "My Drive" in the Google Drive app and grants this app access to it.
    val pickTree = androidx.activity.compose.rememberLauncherForActivityResult(androidx.activity.result.contract.ActivityResultContracts.OpenDocumentTree()) { tree ->
        if (tree != null) scope.launch {
            try { env.app.saf.addRoot(tree); env.app.notifyChanged() }
            catch (e: CancellationException) { throw e }
            catch (e: Throwable) { android.widget.Toast.makeText(ctx, "Couldn't add that folder: ${e.friendly()}", android.widget.Toast.LENGTH_LONG).show() }
        }
    }
    removing?.let { f ->
        AlertDialog(
            onDismissRequest = { removing = null },
            title = { Text("Remove “${f.name}”?") },
            text = { Text("Your files are not touched. Filen Files just stops showing this storage; you can add it again any time.") },
            confirmButton = { TextButton({ env.app.saf.removeRoot(f.id); removing = null; env.app.notifyChanged() }) { Text("Remove") } },
            dismissButton = { TextButton({ removing = null }) { Text("Cancel") } },
        )
    }
    Column(Modifier.fillMaxSize()) {
        AppBar("Files", actions = {
            IconButton({ env.nav.navigate("search") }) { Icon(Icons.Filled.Search, "Search") }
            AccountAction(env)
        })
        LoadHost(roots) { all ->
            LazyColumn(Modifier.fillMaxSize()) {
                val filen = all.filter { it.origin == Origin.FILEN_PERSONAL }
                if (filen.isNotEmpty()) item { SectionTitle("Filen") }
                else if (!signedIn) item { SectionTitle("Filen") }
                items(filen, key = { it.id.value }) { RootRow(it) { env.nav.navigate(folderRoute(it.id)) } }
                if (!signedIn) item { SignInRow { env.nav.navigate("login") } }
                else item {
                    ListItem(
                        modifier = Modifier.clickable { env.nav.navigate("trash") },
                        leadingContent = { Icon(Icons.Filled.Delete, null, tint = MaterialTheme.colorScheme.primary) },
                        headlineContent = { Text("Trash") },
                        supportingContent = { Text("Restore or permanently delete Filen items") },
                    )
                }
                item { SectionTitle("This device") }
                if (!hasFiles) item { AllFilesBanner(env) }
                items(all.filter { it.origin == Origin.LOCAL }, key = { it.id.value }) { RootRow(it) { env.nav.navigate(folderRoute(it.id)) } }
                item { SectionTitle("Cloud storage") }
                items(all.filter { it.origin == Origin.EXTERNAL }, key = { it.id.value }) { f ->
                    ListItem(
                        modifier = Modifier.clickable { env.nav.navigate(folderRoute(f.id)) },
                        leadingContent = { Icon(rootIcon(f), null, tint = MaterialTheme.colorScheme.primary) },
                        headlineContent = { Text(f.name) },
                        trailingContent = { IconButton({ removing = f }) { Icon(Icons.Filled.Close, "Remove ${f.name}") } },
                    )
                }
                item {
                    ListItem(
                        modifier = Modifier.clickable { pickTree.launch(null) },
                        leadingContent = { Icon(Icons.Filled.AddCircleOutline, null, tint = MaterialTheme.colorScheme.primary) },
                        headlineContent = { Text("Add cloud storage…") },
                        supportingContent = { Text("Google Drive, Dropbox, OneDrive and others, through their Android apps") },
                    )
                }
            }
        }
    }
}

@Composable
private fun SignInRow(onClick: () -> Unit) = ListItem(
    modifier = Modifier.clickable(onClick = onClick),
    leadingContent = { Icon(Icons.Filled.Cloud, null) },
    headlineContent = { Text("Sign in to Filen") },
)

@Composable
private fun RootRow(f: Folder, onClick: () -> Unit) = ListItem(
    modifier = Modifier.clickable(onClick = onClick),
    leadingContent = { Icon(rootIcon(f), null, tint = MaterialTheme.colorScheme.primary) },
    headlineContent = { Text(f.name) },
)

// ---------- shared tab ----------

@Composable
fun SharedScreen(env: AppEnv) {
    val signedIn = rememberSignedIn(env)
    var tab by rememberSaveable { mutableIntStateOf(0) }
    var refresh by remember { mutableIntStateOf(0) }
    Column(Modifier.fillMaxSize()) {
        AppBar("Shared", actions = {
            IconButton({ refresh++ }) { Icon(Icons.Filled.Refresh, "Refresh") }
            IconButton({ env.nav.navigate("search") }) { Icon(Icons.Filled.Search, "Search") }
        })
        if (!signedIn) { SignInPrompt { env.nav.navigate("login") }; return@Column }
        PrimaryTabRow(tab) {
            Tab(tab == 0, { tab = 0 }, text = { Text("Shared with me") })
            Tab(tab == 1, { tab = 1 }, text = { Text("Shared by me") })
        }
        val root = ItemId(if (tab == 0) "filen-in:root" else "filen-out:root")
        FolderContent(env, root, refresh, showCrumbs = false)
    }
}

// ---------- favorites tab ----------

@Composable
fun FavoritesScreen(env: AppEnv) {
    val signedIn = rememberSignedIn(env)
    val favs = loadFavorites(env, signedIn)
    Column(Modifier.fillMaxSize()) {
        AppBar("Favorites", actions = { IconButton({ env.nav.navigate("search") }) { Icon(Icons.Filled.Search, "Search") } })
        LoadHost(favs) { items ->
            if (items.isEmpty()) EmptyState("Star files or folders from any location to find them here")
            else FileCollection(env.app.prefs.sort(items), env.app.prefs.grid, env.actions, mixed = true)
        }
    }
}
