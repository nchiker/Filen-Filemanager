package app.filenfiles.ui

import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import app.filenfiles.ThemeMode
import app.filenfiles.UpdateChecker
import app.filenfiles.backup.CameraBackup
import app.filenfiles.core.ItemId
import kotlinx.coroutines.launch

@Composable
fun SettingsScreen(env: AppEnv) {
    val app = env.app
    val prefs = app.prefs
    val ctx = LocalContext.current
    var checking by remember { mutableStateOf(false) }
    var updateStatus by remember { mutableStateOf<String?>(null) }

    fun open(url: String) = runCatching { ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) }

    fun setLock(want: Boolean) {
        val activity = ctx.findActivity() ?: return
        if (want && !canAuthenticate(ctx)) {
            Toast.makeText(ctx, "Set up a screen lock or fingerprint in Android settings first.", Toast.LENGTH_LONG).show(); return
        }
        // Prove it works before turning it on (or off), so nobody gets locked out by a misconfiguration.
        authenticate(activity, if (want) "Turn on app lock" else "Turn off app lock") { ok ->
            if (!ok) return@authenticate
            prefs.updateAppLock(want)
            if (want) { app.appLock.unlock(); prefs.updateSecureScreen(true) }
        }
    }

    var showBackupSetup by remember { mutableStateOf(false) }
    var pickBackupFolder by remember { mutableStateOf(false) }
    if (showBackupSetup) BackupSetupDialog(env) { showBackupSetup = false }
    if (pickBackupFolder) FolderPickerDialog(
        env, "Backup folder", "Use this folder", ItemId("filen:root"), filenOnly = true,
        onDismiss = { pickBackupFolder = false },
    ) { dest ->
        pickBackupFolder = false
        app.appScope.launch {
            val name = env.app.router.stat(dest)?.name ?: "Filen Drive"
            prefs.updateBackupDestination(dest.value, name)
            CameraBackup.runNow(app)
        }
    }
    Column(Modifier.fillMaxSize()) {
        AppBar("Settings", onBack = { env.nav.popBackStack() })
        LazyColumn(Modifier.fillMaxSize()) {
            item { SectionTitle("Appearance") }
            item {
                var menu by remember { mutableStateOf(false) }
                ListItem(
                    modifier = Modifier.clickable { menu = true },
                    headlineContent = { Text("Theme") },
                    supportingContent = { Text(prefs.themeMode.label) },
                    trailingContent = {
                        DropdownMenu(menu, { menu = false }) {
                            ThemeMode.entries.forEach { m -> DropdownMenuItem({ Text(m.label) }, { prefs.updateThemeMode(m); menu = false }) }
                        }
                    },
                )
            }
            item { SectionTitle("Storage") }
            item {
                ListItem(
                    modifier = Modifier.clickable { env.nav.navigate("cleanup") },
                    headlineContent = { Text("Free up space") }, supportingContent = { Text("Find duplicate and large files") },
                )
            }
            item {
                ListItem(
                    modifier = Modifier.clickable { env.nav.navigate("trash") },
                    headlineContent = { Text("Trash") }, supportingContent = { Text("Restore or permanently delete items you removed from Filen") },
                )
            }

            item { SectionTitle("Security") }
            item { SwitchRow("App lock", "Ask for fingerprint, face or screen lock when opening the app, and keep other apps out of your Filen files while it is locked", prefs.appLock, ::setLock) }
            if (prefs.appLock) item {
                var menu by remember { mutableStateOf(false) }
                val options = listOf(0L to "Immediately", 60_000L to "After 1 minute", 5 * 60_000L to "After 5 minutes", 30 * 60_000L to "After 30 minutes")
                ListItem(
                    modifier = Modifier.clickable { menu = true },
                    headlineContent = { Text("Lock") },
                    supportingContent = { Text(options.firstOrNull { it.first == prefs.lockTimeoutMs }?.second ?: "After ${prefs.lockTimeoutMs / 1000} s") },
                    trailingContent = {
                        DropdownMenu(menu, { menu = false }) {
                            options.forEach { (ms, label) -> DropdownMenuItem({ Text(label) }, { prefs.updateLockTimeout(ms); menu = false }) }
                        }
                    },
                )
            }
            item { SwitchRow("Hide contents in recent apps", "Blocks screenshots and blanks the app's preview in the app switcher", prefs.secureScreen) { prefs.updateSecureScreen(it) } }

            item { SectionTitle("Camera backup") }
            item {
                SwitchRow("Back up photos and videos", "Uploads new pictures from your camera to a Filen folder in the background, sorted by month. Nothing is ever deleted from your phone.", prefs.backupEnabled) { want ->
                    if (want) showBackupSetup = true else { prefs.updateBackupEnabled(false); CameraBackup.apply(app) }
                }
            }
            if (prefs.backupEnabled) {
                item {
                    ListItem(
                        modifier = Modifier.clickable { pickBackupFolder = true },
                        headlineContent = { Text("Backup folder") },
                        supportingContent = { Text("Filen Drive › ${prefs.backupFolderName ?: "Camera Uploads"}") },
                        trailingContent = { Text("Change", color = MaterialTheme.colorScheme.primary) },
                    )
                }
                item { SwitchRow("Wi-Fi only", "Don't use mobile data", prefs.backupWifiOnly) { prefs.updateBackupWifiOnly(it); CameraBackup.apply(app) } }
                item { SwitchRow("Only while charging", "Wait until the phone is plugged in", prefs.backupCharging) { prefs.updateBackupCharging(it); CameraBackup.apply(app) } }
                item { SwitchRow("Include videos", "Otherwise only photos are backed up", prefs.backupVideos) { prefs.updateBackupVideos(it) } }
                item { SwitchRow("Include screenshots", "Also back up Pictures/Screenshots", prefs.backupScreenshots) { prefs.updateBackupScreenshots(it) } }
                item {
                    ListItem(
                        modifier = Modifier.clickable { CameraBackup.runNow(app); Toast.makeText(ctx, "Backup will start as soon as the conditions above are met.", Toast.LENGTH_LONG).show() },
                        headlineContent = { Text("Back up now") },
                        supportingContent = {
                            val last = prefs.backupLastRun.takeIf { it > 0 }?.let { " · last run ${formatDate(it)}" }.orEmpty()
                            Text((prefs.backupStatus ?: "Not run yet") + last)
                        },
                    )
                }
            }

            item { SectionTitle("Updates") }
            item { SwitchRow("Check for updates", "Once a day the app asks GitHub for the newest release (an anonymous request) and shows a notice on Home. It never installs anything by itself.", prefs.autoUpdateCheck) { prefs.updateAutoUpdateCheck(it) } }
            item {
                ListItem(
                    headlineContent = { Text("Check now") },
                    supportingContent = { Text(updateStatus ?: "Installed version ${app.installedVersion}") },
                    trailingContent = { if (checking) CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 2.dp) },
                    modifier = Modifier.clickable(enabled = !checking) {
                        checking = true; updateStatus = null
                        app.checkForUpdate(force = true) { r ->
                            checking = false
                            updateStatus = when {
                                r == null -> "Couldn't reach GitHub. Try again later."
                                prefs.updateVersion != null -> "Version ${prefs.updateVersion} is available."
                                else -> "You have the latest version (${app.installedVersion})."
                            }
                        }
                    },
                )
            }
            prefs.updateVersion?.let { v ->
                item {
                    ListItem(
                        modifier = Modifier.clickable { open(prefs.updateUrl ?: UpdateChecker.RELEASES_PAGE) },
                        headlineContent = { Text("Download version $v") },
                        supportingContent = { Text("Opens the download in your browser; then open the file to update.") },
                    )
                }
            }

            item { SectionTitle("About") }
            item { ListItem(headlineContent = { Text("Filen Files") }, supportingContent = { Text("Version ${app.installedVersion}") }) }
            item {
                ListItem(
                    modifier = Modifier.clickable { open("https://github.com/${UpdateChecker.REPO}") },
                    headlineContent = { Text("Source code") },
                    supportingContent = { Text("github.com/${UpdateChecker.REPO}") },
                )
            }
            item {
                ListItem(
                    modifier = Modifier.clickable { open("https://github.com/${UpdateChecker.REPO}/blob/main/LICENSE") },
                    headlineContent = { Text("License") },
                    supportingContent = { Text("GNU AGPL v3 or later. Built on the official Filen SDK (also AGPL-3.0).") },
                )
            }
        }
    }
}

@Composable
private fun SwitchRow(title: String, subtitle: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    ListItem(
        modifier = Modifier.clickable { onChange(!checked) },
        headlineContent = { Text(title) },
        supportingContent = { Text(subtitle) },
        trailingContent = { Switch(checked, onChange) },
    )
}

/** First-time setup for camera backup: where to put it, and whether to include photos already on the phone. */
@Composable
private fun BackupSetupDialog(env: AppEnv, onClose: () -> Unit) {
    val app = env.app
    val ctx = LocalContext.current
    var existing by remember { mutableStateOf(false) }
    var picked by remember { mutableStateOf<ItemId?>(null) }
    var pickedName by remember { mutableStateOf<String?>(null) }
    var picking by remember { mutableStateOf(false) }
    if (picking) {
        FolderPickerDialog(env, "Backup folder", "Use this folder", ItemId("filen:root"), filenOnly = true, onDismiss = { picking = false }) { dest ->
            picking = false; picked = dest
            app.appScope.launch { pickedName = env.app.router.stat(dest)?.name ?: "Filen Drive" }
        }
    }
    AlertDialog(
        onDismissRequest = onClose,
        title = { Text("Back up camera photos") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("New photos are uploaded to Filen in the background, in one folder per month. Your photos on the phone are never touched.")
                ListItem(
                    modifier = Modifier.clickable { picking = true },
                    headlineContent = { Text("Folder") },
                    supportingContent = { Text("Filen Drive › " + (if (picked != null) (pickedName ?: "…") else "Camera Uploads (created for you)")) },
                    trailingContent = { Text("Change", color = MaterialTheme.colorScheme.primary) },
                )
                Row(Modifier.fillMaxWidth().clickable { existing = false }, verticalAlignment = Alignment.CenterVertically) {
                    RadioButton(!existing, { existing = false }); Text("Only photos taken from now on")
                }
                Row(Modifier.fillMaxWidth().clickable { existing = true }, verticalAlignment = Alignment.CenterVertically) {
                    RadioButton(existing, { existing = true }); Text("Also the photos already on this phone")
                }
                if (existing) Text("This can be a lot of data. Leave it on Wi-Fi only (the default).", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        },
        confirmButton = {
            TextButton({
                if (!android.os.Environment.isExternalStorageManager()) {
                    Toast.makeText(ctx, "Grant “All files access” first (Home > the banner) so the app can read your camera folder.", Toast.LENGTH_LONG).show(); return@TextButton
                }
                if (!app.session.isSignedIn) { Toast.makeText(ctx, "Sign in to Filen first.", Toast.LENGTH_LONG).show(); return@TextButton }
                onClose()
                app.appScope.launch {
                    try {
                        val dest = picked ?: run {
                            val root = ItemId("filen:root")
                            app.router.list(root).items.firstOrNull { it.isDirectory && it.name == "Camera Uploads" }?.id
                                ?: app.router.writable(root)!!.createFolder(root, "Camera Uploads")
                        }
                        val name = pickedName ?: if (picked == null) "Camera Uploads" else (app.router.stat(dest)?.name ?: "Filen Drive")
                        app.prefs.updateBackupDestination(dest.value, name)
                        app.prefs.updateBackupSince(if (existing) 0L else System.currentTimeMillis())
                        app.prefs.updateBackupEnabled(true)
                        CameraBackup.apply(app); CameraBackup.runNow(app)
                        env.snackbar.showSnackbar("Camera backup is on")
                    } catch (e: kotlinx.coroutines.CancellationException) { throw e
                    } catch (e: Throwable) { env.snackbar.showSnackbar("Couldn't set up the backup folder: ${e.friendly()}") }
                }
            }) { Text("Turn on") }
        },
        dismissButton = { TextButton(onClose) { Text("Cancel") } },
    )
}
