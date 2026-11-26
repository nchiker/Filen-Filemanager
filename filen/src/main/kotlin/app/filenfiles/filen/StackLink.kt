package app.filenfiles.filen

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.net.Uri
import org.json.JSONObject
import java.security.MessageDigest

/**
 * The link to the Stack app (docs/STACK_LINK_CONTRACT.md in the Stack project). Two directions:
 *  - [StackLink.callerAllowed]: who may call our `app.filenfiles.stacklink` provider (pinned package + certificate);
 *  - [StackLink.moved] / [removed] / [unshared]: Filen-origin events sent to Stack's `org.stackdocs.app.link` provider.
 * Sending is best effort: Stack missing, locked or refusing is ignored (Stack's next stat catches up). Nothing here is
 * ever user-visible, and nothing is logged beyond a failure class (UUIDs and paths stay out of the log).
 */
object StackLink {
    const val STACK_PACKAGE = "org.stackdocs.app"
    /** TEST ONLY: a Stack checks build's package, set from a Gradle property; empty (the default) in every shipped build. */
    @Volatile var testPackage: String = ""

    /**
     * package -> accepted SHA-256 values of its release signing certificate (lowercase hex; public values, not keys). A list
     * so an app can rotate its signing key: add the new certificate here (keep the old one until the old key is retired).
     */
    private val PINNED: Map<String, Set<String>> = mapOf(
        STACK_PACKAGE to setOf("e873f2fd869005e59828db6d4f8f31d1b05a37988818a7dd94adc1ae1b07e311"),
        "app.amipro.editor" to setOf("9a387c70c8c19a3bb29e9b02f18840f4b1bb18ac291983d2d0ec1c684670868a"),
    )

    /** The packages Filen hands persisted folder grants to: the pinned apps and, in a test build, the test package. */
    fun pinnedPackages(): Set<String> = (PINNED.keys + testPackage).filter { it.isNotEmpty() }.toSet()

    /** True when [uid] belongs to exactly one package and that package + its one signing certificate are pinned. */
    fun callerAllowed(context: Context, uid: Int): Boolean {
        // A caller from another Android user (the shell, a work profile) makes this throw: not pinned, so refused.
        val pkg = try { context.packageManager.getPackagesForUid(uid)?.singleOrNull() } catch (_: SecurityException) { null } ?: return false
        if (testPackage.isNotEmpty() && pkg == testPackage) return true // TEST ONLY build flag, empty in shipped builds
        return certMatches(context, pkg)
    }

    private fun certMatches(context: Context, pkg: String): Boolean {
        val want = PINNED[pkg] ?: return false
        return certificateOf(context, pkg) in want
    }

    @Suppress("DEPRECATION")
    private fun certificateOf(context: Context, pkg: String): String? = try {
        val pm = context.packageManager
        val sigs = if (Build.VERSION.SDK_INT >= 28) {
            val s = pm.getPackageInfo(pkg, PackageManager.GET_SIGNING_CERTIFICATES).signingInfo ?: return null
            if (s.hasMultipleSigners()) return null
            s.apkContentsSigners
        } else pm.getPackageInfo(pkg, PackageManager.GET_SIGNATURES).signatures
        if (sigs == null || sigs.size != 1) null
        else MessageDigest.getInstance("SHA-256").digest(sigs[0].toByteArray()).joinToString("") { "%02x".format(it) }
    } catch (_: Exception) { null }

    // ---------- Filen -> Stack events ----------

    /** Whether the real Stack (or the test build's target) is installed; lets callers skip work that only feeds Stack. */
    fun available(context: Context): Boolean = try {
        val target = testPackage.ifEmpty { STACK_PACKAGE }
        context.packageManager.resolveContentProvider("$target.link", 0)?.packageName == target
    } catch (_: Throwable) { false }

    fun moved(context: Context, uuid: String, path: String) =
        send(context, "moved", JSONObject().put("contract", 1).put("filen_uuid", uuid).put("path", path))

    /** [reason] is "deleted" or "trashed". */
    fun removed(context: Context, uuid: String, reason: String) =
        send(context, "removed", JSONObject().put("contract", 1).put("filen_uuid", uuid).put("reason", reason))

    fun unshared(context: Context, uuid: String) =
        send(context, "unshared", JSONObject().put("contract", 1).put("filen_uuid", uuid))

    private fun send(context: Context, method: String, message: JSONObject) {
        if (testPackage.isNotEmpty()) android.util.Log.d("StackLinkTest", "$method $message") // test builds only
        try {
            // Only talk to the real Stack: the provider's package must be pinned (an app could register the authority first).
            val target = testPackage.ifEmpty { STACK_PACKAGE }
            val authority = "$target.link"
            val info = context.packageManager.resolveContentProvider(authority, 0) ?: return
            if (info.packageName != target || (testPackage.isEmpty() && !certMatches(context, STACK_PACKAGE))) return
            context.contentResolver.call(Uri.parse("content://$authority"), method, message.toString(), null as Bundle?)
        } catch (_: Throwable) {
            // Stack missing, locked, busy or refusing: never break a file operation.
        }
    }
}
