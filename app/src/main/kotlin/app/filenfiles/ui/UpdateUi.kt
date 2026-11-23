package app.filenfiles.ui

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import app.filenfiles.UpdateState

/** The one button of the in-app update flow: Update (download + check) -> Install (Android confirms) -> done. */
@Composable
fun UpdateButton(env: AppEnv) {
    val u = env.app.updater
    val ctx = LocalContext.current
    // Like Stack: if installing updates isn't allowed yet, explain and open Android's setting BEFORE downloading; the person comes back and taps Update again.
    var explain by remember { mutableStateOf(false) }
    if (explain) StackDialog({ explain = false }, "Allow installing updates", buttons = {
        TextButton({ explain = false }) { Text("Not now") }
        TextButton({ explain = false; u.openInstallSettings(ctx) }) { Text("Open settings") }
    }) {
        Text("Android needs your OK once. Turn it on, then come back and tap Update.")
    }
    when (val s = u.state) {
        is UpdateState.Idle, is UpdateState.Failed -> TextButton({ if (env.app.packageManager.canRequestPackageInstalls()) u.start() else explain = true }) { Text(if (s is UpdateState.Failed) "Try again" else "Update") }
        is UpdateState.Downloading -> Text(if (s.percent > 0) "Downloading ${s.percent}%" else "Downloading…", Modifier.padding(horizontal = 12.dp), style = MaterialTheme.typography.labelLarge)
        is UpdateState.Ready, is UpdateState.NeedsPermission -> TextButton({ if (env.app.packageManager.canRequestPackageInstalls()) u.install(ctx) else explain = true }) { Text("Install") }
        is UpdateState.Installing -> Text("Installing…", Modifier.padding(horizontal = 12.dp), style = MaterialTheme.typography.labelLarge)
    }
}

/** Why the last update attempt failed, or null. */
@Composable
fun updateFailure(env: AppEnv): String? = (env.app.updater.state as? UpdateState.Failed)?.reason
