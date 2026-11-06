package app.filenfiles.ui

import android.net.Uri
import android.widget.Toast
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.*
import androidx.navigation.navArgument
import app.filenfiles.FilesApp
import app.filenfiles.OpenWith
import app.filenfiles.core.FileItem
import app.filenfiles.core.ItemId
import app.filenfiles.core.MaterializedFile
import app.filenfiles.core.Origin
import app.filenfiles.filen.FilenSession
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

private data class Tab(val route: String, val label: String, val icon: androidx.compose.ui.graphics.vector.ImageVector)

private val tabs = listOf(
    Tab("home", "Home", Icons.Filled.Home),
    Tab("files", "Files", Icons.Filled.Folder),
    Tab("shared", "Shared", Icons.Filled.People),
    Tab("favorites", "Favorites", Icons.Filled.Star),
)

fun folderRoute(id: ItemId) = "folder/${Uri.encode(id.value)}"
fun viewerRoute(id: ItemId) = "viewer/${Uri.encode(id.value)}"

/** Everything screens need: services plus item-level actions wired to navigation. */
class AppEnv(val app: FilesApp, val nav: NavHostController, val actions: ItemActions, val snackbar: SnackbarHostState)

@Composable
fun AppRoot(app: FilesApp) {
    val nav = rememberNavController()
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val session by app.session.state.collectAsState()

    val snackbar = remember { SnackbarHostState() }
    var op by remember { mutableStateOf<Op?>(null) }

    val selection = remember { Selection() }
    /** "Save to device": copies Filen items into the Downloads folder (needs All files access). */
    val saveToDownloads: (List<FileItem>) -> Unit = { items ->
        val cloud = items.filter { it.origin != Origin.LOCAL }
        if (!android.os.Environment.isExternalStorageManager()) {
            Toast.makeText(ctx, "Grant “All files access” first (Home > the banner) to save files to this device.", Toast.LENGTH_LONG).show()
        } else if (cloud.isNotEmpty()) {
            val downloads = android.os.Environment.getExternalStoragePublicDirectory(android.os.Environment.DIRECTORY_DOWNLOADS)
            cloud.forEach { app.transfers.enqueueCopy(it, ItemId("local:" + downloads.absolutePath), move = false) }
            scope.launch { snackbar.showSnackbar("Saving ${itemsLabel(cloud)} to Downloads…") }
        }
    }
    val actions = remember(app, nav) {
        fun withFile(item: FileItem, then: (MaterializedFile) -> Unit) {
            scope.launch {
                try {
                    if (item.origin != Origin.LOCAL) Toast.makeText(ctx, "Downloading ${item.name}…", Toast.LENGTH_SHORT).show()
                    then(app.router.materialize(item))
                } catch (e: CancellationException) { throw e } catch (e: Throwable) {
                    Toast.makeText(ctx, e.friendly(), Toast.LENGTH_LONG).show()
                }
            }
        }
        ItemActions(
            selection = selection,
            open = { if (it.isDirectory) nav.navigate(folderRoute(it.id)) else nav.navigate(viewerRoute(it.id)) },
            openWith = { withFile(it) { f -> OpenWith.open(ctx, f) } },
            share = { withFile(it) { f -> OpenWith.share(ctx, f) } },
            toggleFavorite = { item ->
                scope.launch {
                    try { app.setStarred(item, !app.isStarred(item.id)) }
                    catch (e: CancellationException) { throw e }
                    catch (e: Throwable) { Toast.makeText(ctx, "Couldn't update favorite: " + e.friendly(), Toast.LENGTH_LONG).show() }
                }
            },
            isFavorite = { app.isStarred(it.id) },
            canModify = { app.router.modifier(it) != null },
            rename = { op = Op.Rename(it) },
            move = { op = Op.Move(listOf(it)) },
            delete = { op = Op.Delete(listOf(it)) },
            copyTo = { op = Op.Copy(listOf(it)) },
            history = { op = Op.History(it) },
            canHistory = { app.router.versioning(it) != null },
            compress = { op = Op.Compress(listOf(it)) },
            extract = { op = Op.Extract(it) },
            saveToDevice = { saveToDownloads(listOf(it)) },
            canShareWith = { app.router.sharing()?.canShare(it) == true },
            shareWith = { op = Op.ShareWith(listOf(it)) },
            canStopSharing = { app.router.sharing()?.canUnshare(it) == true },
            stopSharing = { op = Op.StopSharing(it) },
        )
    }
    val env = remember(app, nav, actions) { AppEnv(app, nav, actions, snackbar) }

    val backEntry by nav.currentBackStackEntryAsState()
    val route = backEntry?.destination?.route
    val showBar = route in tabs.map { it.route }
    // An Undo snackbar belongs to the screen it was raised on.
    LaunchedEffect(route) { snackbar.currentSnackbarData?.dismiss() }
    // Folder-to-folder navigation keeps the same route string, so key on the back-stack entry itself.
    LaunchedEffect(backEntry?.id) { selection.clear() }

    if (session == FilenSession.State.Unknown) {
        Box(Modifier.fillMaxSize(), Alignment.Center) { CircularProgressIndicator() }
        return
    }
    // A rejected session (revoked/changed password) can't be retried away: go to sign-in.
    LaunchedEffect(session) {
        if (session is FilenSession.State.Expired && nav.currentDestination?.route != "login") {
            nav.navigate("login") { launchSingleTop = true }
        }
    }
    val start = if (session == FilenSession.State.LoggedOut && !app.prefs.skippedLogin) "login" else "home"

    Scaffold(
        snackbarHost = { SnackbarHost(snackbar) },
        bottomBar = { Column {
            TransfersPanel(env, padForNavBar = !showBar)
            if (showBar) NavigationBar {
                tabs.forEach { t ->
                    NavigationBarItem(
                        selected = route == t.route,
                        onClick = {
                            nav.navigate(t.route) {
                                popUpTo(nav.graph.startDestinationId) { saveState = true }
                                launchSingleTop = true; restoreState = true
                            }
                        },
                        icon = { Icon(t.icon, t.label) }, label = { Text(t.label) },
                    )
                }
            }
        } },
    ) { pad ->
        Box(Modifier.padding(pad)) {
        NavHost(nav, start) {
            composable("login") { LoginScreen(env) }
            composable("home") { HomeScreen(env) }
            composable("files") { FilesScreen(env) }
            composable("shared") { SharedScreen(env) }
            composable("favorites") { FavoritesScreen(env) }
            composable("search") { SearchScreen(env) }
            composable("trash") { TrashScreen(env) }
            composable("settings") { SettingsScreen(env) }
            composable("folder/{id}", listOf(navArgument("id") { type = NavType.StringType })) {
                FolderScreen(env, ItemId(it.arguments!!.getString("id")!!))
            }
            composable("viewer/{id}", listOf(navArgument("id") { type = NavType.StringType })) {
                ViewerScreen(env, ItemId(it.arguments!!.getString("id")!!))
            }
        }
        // Registered after the NavHost on purpose: the most recently registered handler gets Back first.
        androidx.activity.compose.BackHandler(selection.active) { selection.clear() }
        if (selection.active) SelectionBar(env, Modifier.align(Alignment.TopStart)) { kind, items ->
            selection.clear()
            when (kind) {
                Batch.COPY -> op = Op.Copy(items)
                Batch.MOVE -> op = Op.Move(items)
                Batch.DELETE -> op = Op.Delete(items)
                Batch.SHARE_WITH -> op = Op.ShareWith(items)
                Batch.STAR -> scope.launch {
                    val star = !items.all { app.isStarred(it.id) }
                    val failed = items.count { runCatching { app.setStarred(it, star) }.isFailure }
                    snackbar.showSnackbar(if (failed > 0) "Couldn't update $failed of ${items.size} favorites"
                        else if (star) "Added ${itemsLabel(items)} to favorites" else "Removed ${itemsLabel(items)} from favorites")
                }
                Batch.SAVE -> saveToDownloads(items)
                Batch.COMPRESS -> op = Op.Compress(items)
            }
        }
        }
        OperationHost(env, op) { op = null }
    }
}
