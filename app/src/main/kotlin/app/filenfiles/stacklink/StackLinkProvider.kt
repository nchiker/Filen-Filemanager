package app.filenfiles.stacklink

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.net.Uri
import android.os.Binder
import android.os.Bundle
import android.provider.DocumentsContract
import app.filenfiles.LockedAccess
import app.filenfiles.FilesApp
import app.filenfiles.core.ItemId
import app.filenfiles.filen.FilenSession
import app.filenfiles.filen.FailureKind
import app.filenfiles.filen.Partner
import app.filenfiles.filen.PartnerState
import app.filenfiles.filen.ShareAnswer
import app.filenfiles.filen.failureKind
import app.filenfiles.filen.StackLink
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.json.JSONObject

/**
 * `app.filenfiles.stacklink` (docs/STACK_LINK_CONTRACT.md in the Stack project). Only call(): Stack and Ami Pro may
 * ask, identified by kernel uid -> package -> certificate SHA-256 ([StackLink.callerAllowed]); everyone else is refused.
 *  - `locate(uri)`: the Filen document URI -> {uuid, path}, or {error: "not_filen"}.
 *  - `lookup(uuid)`: -> {exists, path, trashed, shared}.
 *  - `account()`: -> {account_id, legacy_account_id}, an opaque id of the signed-in account (HMAC with a per-install secret; not the email); legacy_account_id is the id 0.5.0 and earlier gave, so an app can move its saved id once, or {error: "signed_out"}.
 *  - `share_state()` with extra FILEN_UUID: -> {shared} (shared with the saved partner) plus {shared_via} (name of the nearest shared folder above it, when the item itself is not shared), or {error: not_found | signed_out | unknown}.
 *  - `contract()`: -> {level} (int, 1 = folder sharing F1-F11); open to pinned callers even when locked or signed out. No call = level 0.
 *  - `partner_status()`: (also answered while locked) -> {state: none|requested|incoming|connected, name, partner_id}; `partner_id` is an opaque stable id of the saved partner contact (only when connected); `name` is a display name, never an address.
 * Errors: `refused` (caller not pinned), `locked` (app lock on), `not_signed_in`, `unreachable` (Filen could not be asked).
 */
class StackLinkProvider : ContentProvider() {
    override fun onCreate() = true

    override fun call(method: String, arg: String?, extras: Bundle?): Bundle {
        val ctx = context ?: return err("unreachable")
        if (!StackLink.callerAllowed(ctx, Binder.getCallingUid())) return err("refused")
        // The contract level is a fact about this build, not about the account: answered even when locked or signed out.
        if (method == "contract") return Bundle().apply { putInt("level", StackLink.CONTRACT_LEVEL) }
        val app = ctx.applicationContext as FilesApp
        app.awaitReady()
        // partner_status is opaque ids and a display name, no file access: answered while locked too.
        if (app.appLock.isLockedForOthers && !LockedAccess.answeredWhileLocked(method)) return err("locked")
        // Session restore runs in the background after a cold start.
        val deadline = System.currentTimeMillis() + 10_000
        while (app.session.state.value is FilenSession.State.Unknown && System.currentTimeMillis() < deadline) Thread.sleep(50)
        if (app.session.state.value !is FilenSession.State.LoggedIn) return err(if (method == "account" || method == "share_state") "signed_out" else "not_signed_in")
        return try {
            when (method) {
                "locate" -> locate(app, arg)
                "lookup" -> lookup(app, arg)
                "account" -> app.filen.accountId?.let { id -> Bundle().apply { putString("account_id", id); app.filen.legacyAccountId?.let { putString("legacy_account_id", it) } } } ?: err("signed_out")
                "partner_status" -> partnerStatus(app)
                "share_state" -> shareState(app, extras?.getString("app.filenfiles.extra.FILEN_UUID") ?: arg)
                else -> err("invalid")
            }
        } catch (_: Exception) { err("unreachable") }
    }

    private fun locate(app: FilesApp, arg: String?): Bundle {
        val uri = try { Uri.parse(arg ?: return err("not_filen")) } catch (_: Exception) { return err("not_filen") }
        if (uri.authority != "app.filenfiles.documents" || !DocumentsContract.isDocumentUri(context, uri)) return err("not_filen")
        var docId = DocumentsContract.getDocumentId(uri)
        if (docId.startsWith("filen-pending:")) { // an app that saved through the provider still holds the pending id
            docId = app.transfers.docIds.get(docId) ?: return err("pending") // upload not finished yet: ask again later
        }
        val id = ItemId(docId)
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

    private fun shareState(app: FilesApp, raw: String?): Bundle {
        val uuid = raw?.trim()?.lowercase().orEmpty()
        if (!UUID.matches(uuid)) return err("invalid")
        return try {
            when (val r = runBlocking(Dispatchers.IO) { app.filen.shareState(uuid, app.prefs.partnerFor(app.filen.accountId)) }) {
                is ShareAnswer.Known -> Bundle().apply { putBoolean("shared", r.shared); r.via?.let { putString("shared_via", it) } }
                ShareAnswer.NotFound -> err("not_found")
            }
        } catch (e: Exception) { err(if (e.failureKind() == FailureKind.AUTH) "signed_out" else "unknown") }
    }

    private fun partnerStatus(app: FilesApp): Bundle {
        val st = runBlocking(Dispatchers.IO) { app.filen.partnerStatus(app.prefs.partnerFor(app.filen.accountId)) }
        return Bundle().apply {
            putString("state", st.state.wire)
            st.who?.let { putString("name", Partner.displayName(it)) }
            if (st.state == PartnerState.CONNECTED) st.who?.contactUuid?.let { putString("partner_id", app.filen.partnerId(it)) }
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
