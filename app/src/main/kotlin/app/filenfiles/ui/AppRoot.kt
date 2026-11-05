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
            open = { if (it.isDirectory) nav.navigate(folderRoute(it.id)) else nav.navigate(viewerRoute(it.id)) },
            openWith = { withFile(it) { f -> OpenWith.open(ctx, f) } },
            share = { withFile(it) { f -> OpenWith.share(ctx, f) } },
            toggleFavorite = { app.prefs.toggleFavorite(it.id) },
            isFavorite = { app.prefs.isFavorite(it.id) },
            canModify = { app.router.modifier(it) != null },
            rename = { op = Op.Rename(it) },
            move = { op = Op.Move(it) },
            delete = { op = Op.Delete(it) },
        )
    }
    val env = remember(app, nav, actions) { AppEnv(app, nav, actions, snackbar) }

    val backEntry by nav.currentBackStackEntryAsState()
    val route = backEntry?.destination?.route
    val showBar = route in tabs.map { it.route }

    if (session == FilenSession.State.Unknown) {
        Box(Modifier.fillMaxSize(), Alignment.Center) { CircularProgressIndicator() }
        return
    }
    val start = if (session == FilenSession.State.LoggedOut && !app.prefs.skippedLogin) "login" else "home"

    Scaffold(
        snackbarHost = { SnackbarHost(snackbar) },
        bottomBar = {
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
        },
    ) { pad ->
        NavHost(nav, start, Modifier.padding(pad)) {
            composable("login") { LoginScreen(env) }
            composable("home") { HomeScreen(env) }
            composable("files") { FilesScreen(env) }
            composable("shared") { SharedScreen(env) }
            composable("favorites") { FavoritesScreen(env) }
            composable("search") { SearchScreen(env) }
            composable("folder/{id}", listOf(navArgument("id") { type = NavType.StringType })) {
                FolderScreen(env, ItemId(it.arguments!!.getString("id")!!))
            }
            composable("viewer/{id}", listOf(navArgument("id") { type = NavType.StringType })) {
                ViewerScreen(env, ItemId(it.arguments!!.getString("id")!!))
            }
        }
        OperationHost(env, op) { op = null }
    }
}
