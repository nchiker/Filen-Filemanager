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
    when (val s = u.state) {
        is UpdateState.Idle, is UpdateState.Failed -> TextButton({ u.start() }) { Text(if (s is UpdateState.Failed) "Try again" else "Update") }
        is UpdateState.Downloading -> Text(if (s.percent > 0) "Downloading ${s.percent}%" else "Downloading…", Modifier.padding(horizontal = 12.dp), style = MaterialTheme.typography.labelLarge)
        is UpdateState.Ready, is UpdateState.NeedsPermission -> {
            var explain by remember { mutableStateOf(false) }
            TextButton({ if (env.app.packageManager.canRequestPackageInstalls()) u.install(ctx) else explain = true }) { Text("Install") }
            if (explain) StackDialog({ explain = false }, "Allow installing updates", buttons = {
                TextButton({ explain = false }) { Text("Not now") }
                TextButton({ explain = false; u.install(ctx) }) { Text("Continue") }
            }) {
                Text("To install an update, Android needs you to allow Filen Files to install apps. The next screen is Android's setting for that; turn it on, then come back and tap Install again. Android still asks you to confirm each update.")
            }
        }
        is UpdateState.Installing -> Text("Installing…", Modifier.padding(horizontal = 12.dp), style = MaterialTheme.typography.labelLarge)
    }
}

/** Why the last update attempt failed, or null. */
@Composable
fun updateFailure(env: AppEnv): String? = (env.app.updater.state as? UpdateState.Failed)?.reason
