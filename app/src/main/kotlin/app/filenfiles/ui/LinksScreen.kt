package app.filenfiles.ui

import android.text.format.DateFormat
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.filenfiles.core.FileItem

/** Everything that currently has a public link, so none is forgotten. Tap an entry to copy, change or remove its link. */
@Composable
fun LinksScreen(env: AppEnv) {
    val links = env.app.router.linkStorage()
    var refresh by remember { mutableIntStateOf(0) }
    var open by remember { mutableStateOf<FileItem?>(null) }
    val state = loadState(refresh = refresh) { links?.links() ?: error("Sign in to Filen to see your links") }
    Column(Modifier.fillMaxSize()) {
        AppBar("Public links", onBack = { env.nav.popBackStack() })
        LoadHost(state, onRetry = { refresh++ }) { list ->
            if (list.isEmpty()) EmptyState("No public links. Use “Public link…” on a file or folder.")
            else LazyColumn(Modifier.fillMaxSize()) {
                items(list, key = { it.item.id.value }) { l ->
                    ListItem(
                        leadingContent = { ItemThumb(l.item, 44.dp) },
                        headlineContent = { Text(l.item.name, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                        supportingContent = {
                            val until = l.info.expiresAt?.let { "Expires " + DateFormat.getMediumDateFormat(env.app).format(it) } ?: l.info.expiry.label.let { if (it == "Never") "Never expires" else "Expires in $it" }
                            Text(until + if (l.info.downloadable) "" else " · no downloads")
                        },
                        trailingContent = { if (l.info.hasPassword) Icon(Icons.Filled.Lock, "Password protected") },
                        modifier = Modifier.clickable { open = l.item },
                    )
                }
            }
        }
    }
    open?.let { LinkDialog(env, it, { open = null; refresh++ }, onChanged = { refresh++ }) }
}
