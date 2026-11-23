package app.filenfiles

import android.app.Application
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Where an in-app update is. The banner and the update dialog show this. */
sealed interface UpdateState {
    data object Idle : UpdateState
    data class Downloading(val version: String, val percent: Int) : UpdateState
    data class Ready(val version: String) : UpdateState
    /** Android's confirmation screen is up (or was); the result arrives from [InstallResultReceiver]. */
    data class Installing(val version: String) : UpdateState
    /** The person must allow this app to install apps (Android's own settings page was opened). */
    data class NeedsPermission(val version: String) : UpdateState
    data class Failed(val reason: String) : UpdateState
}

/**
 * Downloads the newest GitHub release, checks it, and hands it to Android's installer (same approach as the AmiPro app).
 * Runs only after a tap on the banner / Settings button: nothing installs by itself - Android shows its own confirmation and
 * refuses a file that was not signed with the key of the installed app. The file is checked first anyway (checksum when GitHub
 * publishes one, same package, higher versionCode, same signing certificate) so a bad file is never offered.
 */
class Updater(private val app: Application) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    var state by mutableStateOf<UpdateState>(UpdateState.Idle)
        private set
    private var release: UpdateChecker.Release? = null
    private val dir get() = File(app.cacheDir, "update")
    private val apk get() = File(dir, "update.apk")

    private val installedVersion: String
        get() = runCatching { app.packageManager.getPackageInfo(app.packageName, 0).versionName }.getOrNull() ?: "0"

    fun busy() = state is UpdateState.Downloading || state is UpdateState.Installing

    /** Looks up the newest release and downloads it; ends in [UpdateState.Ready] (then the person taps Install). */
    fun start() {
        if (busy()) return
        state = UpdateState.Downloading("", 0)
        scope.launch {
            state = try {
                val r = UpdateChecker.latest() ?: throw IllegalStateException("Could not reach GitHub")
                val url = r.apkUrl ?: throw IllegalStateException("The newest release has no app file")
                if (!UpdateChecker.isNewer(r, installedVersion, runCatching { androidx.core.content.pm.PackageInfoCompat.getLongVersionCode(app.packageManager.getPackageInfo(app.packageName, 0)) }.getOrNull())) throw IllegalStateException("You already have the newest version")
                release = r
                state = UpdateState.Downloading(r.version, 0)
                withContext(Dispatchers.IO) { fetchApk(r, url) }
                UpdateState.Ready(r.version)
            } catch (e: Exception) {
                dir.deleteRecursively()
                UpdateState.Failed("${e.message ?: "The download failed"}. Nothing was changed.")
            }
        }
    }

    fun reset() { if (!busy()) state = UpdateState.Idle }

    /** Starts Android's installer. If this app may not install apps yet, opens the setting that allows it and waits. */
    fun install(context: Context) {
        val version = when (val s = state) { is UpdateState.Ready -> s.version; is UpdateState.NeedsPermission -> s.version; else -> return }
        if (!app.packageManager.canRequestPackageInstalls()) {
            state = UpdateState.NeedsPermission(version)
            context.startActivity(
                Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${app.packageName}")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            )
            return
        }
        state = UpdateState.Installing(version)
        scope.launch {
            try {
                withContext(Dispatchers.IO) { startSession() }
            } catch (e: Exception) {
                state = UpdateState.Failed("Android could not start the installation: ${e.message ?: "unknown error"}. Nothing was changed.")
            }
        }
    }

    internal fun onInstallStatus(status: Int, detail: String?) {
        state = when (status) {
            PackageInstaller.STATUS_SUCCESS -> { dir.deleteRecursively(); UpdateState.Idle }
            PackageInstaller.STATUS_FAILURE_ABORTED -> (release?.version ?: installedVersion).let { UpdateState.Ready(it) }
            PackageInstaller.STATUS_FAILURE_CONFLICT, PackageInstaller.STATUS_FAILURE_INCOMPATIBLE ->
                UpdateState.Failed("Android refused the update because it does not match the installed app (${detail ?: "signature"}). Nothing was changed.")
            else -> UpdateState.Failed("Android could not install the update (${detail ?: "code $status"}). Nothing was changed.")
        }
    }

    private fun fetchApk(r: UpdateChecker.Release, url: String) {
        require(url.startsWith("https://")) { "Only secure addresses are used" }
        dir.deleteRecursively(); dir.mkdirs()
        val digest = MessageDigest.getInstance("SHA-256")
        val c = (URL(url).openConnection() as HttpURLConnection).apply { connectTimeout = 10_000; readTimeout = 20_000 }
        try {
            if (c.responseCode != 200) throw IllegalStateException("The download failed (${c.responseCode})")
            val total = c.contentLengthLong.takeIf { it > 0 } ?: r.apkSize
            var done = 0L
            var lastPercent = -1
            apk.outputStream().buffered().use { out ->
                c.inputStream.use { input ->
                    val buf = ByteArray(64 * 1024)
                    while (true) {
                        val n = input.read(buf); if (n < 0) break
                        out.write(buf, 0, n); digest.update(buf, 0, n); done += n
                        val pct = if (total > 0) (done * 100 / total).toInt().coerceAtMost(100) else 0
                        if (pct != lastPercent) { lastPercent = pct; state = UpdateState.Downloading(r.version, pct) }
                    }
                }
            }
        } finally { c.disconnect() }
        val sha = digest.digest().joinToString("") { "%02x".format(it) }
        if (r.sha256 != null && !sha.equals(r.sha256, ignoreCase = true)) throw IllegalStateException("The downloaded file does not match its published checksum")
        verifyApk()
    }

    /** Before offering Install: the file is this app, newer, and signed with the same key as the installed one. */
    private fun verifyApk() {
        val pm = app.packageManager
        val flags = PackageManager.GET_SIGNING_CERTIFICATES
        val file = pm.getPackageArchiveInfo(apk.path, flags) ?: throw IllegalStateException("The file is not an app Android can read")
        if (file.packageName != app.packageName) throw IllegalStateException("The file is for a different app (${file.packageName})")
        val mine = pm.getPackageInfo(app.packageName, flags)
        if (file.longVersionCode <= mine.longVersionCode) throw IllegalStateException("The file is not newer than the installed app")
        val theirs = file.signingInfo?.apkContentsSigners?.map { it.toByteArray().contentHashCode() }?.toSet().orEmpty()
        val ours = mine.signingInfo?.apkContentsSigners?.map { it.toByteArray().contentHashCode() }?.toSet().orEmpty()
        if (theirs.isEmpty() || theirs != ours) throw IllegalStateException("The file is not signed with the same key as this app (a debug build cannot take a release update)")
    }

    private fun startSession() {
        val installer = app.packageManager.packageInstaller
        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL).apply {
            setAppPackageName(app.packageName)
            if (Build.VERSION.SDK_INT >= 31) setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_REQUIRED)
        }
        val id = installer.createSession(params)
        installer.openSession(id).use { session ->
            apk.inputStream().use { input -> session.openWrite("update.apk", 0, apk.length()).use { out -> input.copyTo(out); session.fsync(out) } }
            val intent = Intent(app, InstallResultReceiver::class.java).setPackage(app.packageName)
            val pending = PendingIntent.getBroadcast(app, id, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE)
            session.commit(pending.intentSender)
        }
    }
}

/** Android's installer reports here; its confirmation screen is passed on to the user, the result goes to [Updater]. */
class InstallResultReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)
        if (status == PackageInstaller.STATUS_PENDING_USER_ACTION) {
            @Suppress("DEPRECATION")
            val confirm = if (Build.VERSION.SDK_INT >= 33) intent.getParcelableExtra(Intent.EXTRA_INTENT, Intent::class.java) else intent.getParcelableExtra(Intent.EXTRA_INTENT)
            confirm?.let { context.startActivity(it.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
            return
        }
        (context.applicationContext as FilesApp).updater.onInstallStatus(status, intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE))
    }
}
