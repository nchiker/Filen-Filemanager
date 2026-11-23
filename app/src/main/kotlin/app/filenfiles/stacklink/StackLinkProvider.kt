package app.filenfiles.stacklink

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.net.Uri
import android.os.Binder
import android.os.Bundle
import android.provider.DocumentsContract
import app.filenfiles.FilesApp
import app.filenfiles.core.ItemId
import app.filenfiles.filen.FilenSession
import app.filenfiles.filen.StackLink
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.json.JSONObject

/**
 * `app.filenfiles.stacklink` (docs/STACK_LINK_CONTRACT.md in the Stack project). Only call(): Stack and Ami Pro may
 * ask, identified by kernel uid -> package -> certificate SHA-256 ([StackLink.callerAllowed]); everyone else is refused.
 *  - `locate(uri)`: the Filen document URI -> {uuid, path}, or {error: "not_filen"}.
 *  - `lookup(uuid)`: -> {exists, path, trashed, shared}.
 *  - `account()`: -> {email}, the signed-in Filen account (Stack puts it in the pairing QR).
 * Errors: `refused` (caller not pinned), `locked` (app lock on), `not_signed_in`, `unreachable` (Filen could not be asked).
 */
class StackLinkProvider : ContentProvider() {
    override fun onCreate() = true

    override fun call(method: String, arg: String?, extras: Bundle?): Bundle {
        val ctx = context ?: return err("unreachable")
        if (!StackLink.callerAllowed(ctx, Binder.getCallingUid())) return err("refused")
        val app = ctx.applicationContext as FilesApp
        app.awaitReady()
        if (app.appLock.isLockedForOthers) return err("locked")
        // Session restore runs in the background after a cold start.
        val deadline = System.currentTimeMillis() + 10_000
        while (app.session.state.value is FilenSession.State.Unknown && System.currentTimeMillis() < deadline) Thread.sleep(50)
        if (app.session.state.value !is FilenSession.State.LoggedIn) return err("not_signed_in")
        return try {
            when (method) {
                "locate" -> locate(app, arg)
                "lookup" -> lookup(app, arg)
                "account" -> Bundle().apply { putString("email", (app.session.state.value as FilenSession.State.LoggedIn).email) }
                else -> err("invalid")
            }
        } catch (_: Exception) { err("unreachable") }
    }

    private fun locate(app: FilesApp, arg: String?): Bundle {
        val uri = try { Uri.parse(arg ?: return err("not_filen")) } catch (_: Exception) { return err("not_filen") }
        if (uri.authority != "app.filenfiles.documents" || !DocumentsContract.isDocumentUri(context, uri)) return err("not_filen")
        val id = ItemId(DocumentsContract.getDocumentId(uri))
        if ((id.scheme != "filen" && id.scheme != "filen-in") || id.key == "root") return err("not_filen")
        val path = runBlocking(Dispatchers.IO) { runCatching { app.filen.stackPath(id) }.getOrNull() }
        return Bundle().apply { putString("uuid", id.key); putString("path", path ?: "") }
    }

    private fun lookup(app: FilesApp, arg: String?): Bundle {
        val uuid = arg?.trim().orEmpty().let { if (it.startsWith("{")) JSONObject(it).optString("uuid") else it }
        if (!UUID.matches(uuid)) return err("invalid")
        val r = runBlocking(Dispatchers.IO) { app.filen.stackLookup(uuid.lowercase()) }
        return Bundle().apply {
            putBoolean("exists", r.exists); putBoolean("trashed", r.trashed); putBoolean("shared", r.shared)
            putString("path", r.path ?: "")
        }
    }

    private fun err(code: String) = Bundle().apply { putString("error", code) }

    private companion object { val UUID = Regex("[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}") }

    // Not a data provider.
    override fun query(uri: Uri, p: Array<out String>?, s: String?, a: Array<out String>?, o: String?): Cursor? = null
    override fun getType(uri: Uri): String? = null
    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun delete(uri: Uri, s: String?, a: Array<out String>?) = 0
    override fun update(uri: Uri, v: ContentValues?, s: String?, a: Array<out String>?) = 0
}
