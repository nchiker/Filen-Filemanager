package app.filenfiles.ui

import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
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
    var dialog by remember { mutableStateOf<String?>(null) }
    val lockOptions = listOf(0L to "Immediately", 60_000L to "After 1 minute", 5 * 60_000L to "After 5 minutes", 30 * 60_000L to "After 30 minutes")
    val lockLabel = lockOptions.firstOrNull { it.first == prefs.lockTimeoutMs }?.second ?: "After ${prefs.lockTimeoutMs / 1000} s"
    val themeLabel = when (prefs.themeMode) { ThemeMode.SYSTEM -> "System · Like your phone"; ThemeMode.LIGHT -> "Light · Always light"; ThemeMode.DARK -> "Dark · Always dark" }

    Column(Modifier.fillMaxSize()) {
        AppBar("Settings", onBack = { env.nav.popBackStack() })
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 24.dp)) {
            Spacer(Modifier.height(8.dp))
            ActionRow(Icons.Outlined.Lock, Rings.softGreen, "App lock",
                if (prefs.appLock) "On · $lockLabel" else "Off · Fingerprint or screen lock") { dialog = "lock" }
            RowDivider()
            ActionRow(Icons.Outlined.PhotoCamera, Rings.softBlue, "Camera backup",
                if (prefs.backupEnabled) "On · ${prefs.backupFolderName ?: "Camera Uploads"}" + (if (prefs.backupWifiOnly) " · Wi-Fi only" else "")
                else "Off · New photos to Filen Drive") { if (prefs.backupEnabled) dialog = "camera" else showBackupSetup = true }
            RowDivider()
            ActionRow(Icons.Outlined.CleaningServices, Rings.softYellow, "Free up space", "Duplicates and large files") { env.nav.navigate("cleanup") }
            RowDivider()
            ActionRow(Icons.Outlined.Delete, Rings.softGreen, "Trash", "Restore or delete for good") { env.nav.navigate("trash") }
            RowDivider()
            ActionRow(Icons.Outlined.Palette, Rings.softBlue, "Theme", themeLabel) { dialog = "theme" }
            RowDivider()
            ActionRow(Icons.Outlined.SystemUpdate, Rings.softYellow, "Update check",
                when {
                    !prefs.autoUpdateCheck -> "Off · Look for new versions on GitHub"
                    prefs.updateVersion != null -> "On · Version ${prefs.updateVersion} is available"
                    prefs.lastUpdateCheck > 0 -> "On · Up to date · Checked ${formatDate(prefs.lastUpdateCheck)}"
                    else -> "On · Couldn't check yet"
                }) { dialog = "update" }
            Spacer(Modifier.height(24.dp))
            TextLink("About Filen Files") { dialog = "about" }
            Spacer(Modifier.height(24.dp))
        }
    }

    val close = { dialog = null }
    when (dialog) {
        "theme" -> StackDialog(close, "Theme", buttons = { TextButton(close) { Text("Close") } }) {
            ThemeMode.entries.forEach { m ->
                DialogChoice(when (m) { ThemeMode.SYSTEM -> "Like your phone"; ThemeMode.LIGHT -> "Always light"; ThemeMode.DARK -> "Always dark" }, prefs.themeMode == m) {
                    prefs.updateThemeMode(m); dialog = null
                }
            }
        }
        "lock" -> StackDialog(close, "App lock", buttons = { TextButton(close) { Text("Done") } }) {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                DialogSwitch("Ask to unlock", prefs.appLock, "Fingerprint, face or screen lock", ::setLock)
                if (prefs.appLock) {
                    Text("Lock", fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 12.dp))
                    lockOptions.forEach { (ms, label) -> DialogChoice(label, prefs.lockTimeoutMs == ms) { prefs.updateLockTimeout(ms) } }
                    prefs.lockedReadersAsked.sorted().forEach { pkg ->
                        val name = prefs.lockedReaderLabel(pkg) ?: appLabel(ctx, pkg)
                        val writes = pkg in prefs.lockedWriters
                        DialogSwitch(if (writes) "$name can back up while locked" else "$name can read while locked", pkg in prefs.lockedReaders,
                            if (writes) "Only its backup folder" else "Only the folder you gave it") { prefs.setLockedReader(pkg, it) }
                    }
                }
                DialogSwitch("Hide in recent apps", prefs.secureScreen, "Also blocks screenshots") { prefs.updateSecureScreen(it) }
            }
        }
        "camera" -> StackDialog(close, "Camera backup", buttons = {
            TextButton({ prefs.updateBackupEnabled(false); CameraBackup.apply(app); dialog = null }) { Text("Turn off") }
            TextButton({ CameraBackup.runNow(app); Toast.makeText(ctx, "Backing up when Wi-Fi and charging allow", Toast.LENGTH_SHORT).show() }) { Text("Back up now") }
            TextButton(close) { Text("Done") }
        }) {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                Row(Modifier.fillMaxWidth().heightIn(min = 48.dp).clickable { pickBackupFolder = true }, verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("Folder", fontSize = 16.sp)
                        Text("Filen Drive › ${prefs.backupFolderName ?: "Camera Uploads"}", fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Text("Change", color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Medium)
                }
                DialogSwitch("Wi-Fi only", prefs.backupWifiOnly) { prefs.updateBackupWifiOnly(it); CameraBackup.apply(app) }
                DialogSwitch("Only while charging", prefs.backupCharging) { prefs.updateBackupCharging(it); CameraBackup.apply(app) }
                DialogSwitch("Videos", prefs.backupVideos) { prefs.updateBackupVideos(it) }
                DialogSwitch("Screenshots", prefs.backupScreenshots) { prefs.updateBackupScreenshots(it) }
                val last = prefs.backupLastRun.takeIf { it > 0 }?.let { " · ${formatDate(it)}" }.orEmpty()
                Text((prefs.backupStatus ?: "Not run yet") + last, fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 8.dp))
            }
        }
        "update" -> StackDialog(close, "Update check", buttons = {
            TextButton(close) { Text("Close") }
            val v = prefs.updateVersion
            if (v != null) TextButton({ open(prefs.updateUrl ?: UpdateChecker.RELEASES_PAGE) }) { Text("Download $v") }
            else TextButton(enabled = !checking, onClick = {
                checking = true; updateStatus = "Checking…"
                app.checkForUpdate(force = true) { r ->
                    checking = false
                    updateStatus = when {
                        r == null -> "Couldn't reach GitHub"
                        prefs.updateVersion != null -> "Version ${prefs.updateVersion} is available"
                        else -> "Up to date (${app.installedVersion})"
                    }
                }
            }) { Text("Check now") }
        }) {
            DialogSwitch("Check daily", prefs.autoUpdateCheck, "Asks GitHub, never installs by itself") { prefs.updateAutoUpdateCheck(it) }
            Text(updateStatus ?: "Installed: ${app.installedVersion}", fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 8.dp))
        }
        "about" -> StackDialog(close, "Filen Files ${app.installedVersion}", buttons = {
            TextButton({ open("https://github.com/${UpdateChecker.REPO}/blob/main/LICENSE") }) { Text("Licence") }
            TextButton({ open("https://github.com/${UpdateChecker.REPO}") }) { Text("Source code") }
            TextButton(close) { Text("Close") }
        }) {
            Text("Free software (GNU AGPL v3 or later), built on the official Filen SDK.", fontSize = 16.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
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
                Text("New photos go to Filen in the background, one folder per month. Photos on the phone are never touched.")
                ListItem(
                    modifier = Modifier.clickable { picking = true },
                    headlineContent = { Text("Folder") },
                    supportingContent = { Text("Filen Drive › " + (if (picked != null) (pickedName ?: "…") else "Camera Uploads")) },
                    trailingContent = { Text("Change", color = MaterialTheme.colorScheme.primary) },
                )
                Row(Modifier.fillMaxWidth().clickable { existing = false }, verticalAlignment = Alignment.CenterVertically) {
                    RadioButton(!existing, { existing = false }); Text("New photos only")
                }
                Row(Modifier.fillMaxWidth().clickable { existing = true }, verticalAlignment = Alignment.CenterVertically) {
                    RadioButton(existing, { existing = true }); Text("Also photos already on the phone")
                }
                if (existing) Text("Can be a lot of data; stays on Wi-Fi.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
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
