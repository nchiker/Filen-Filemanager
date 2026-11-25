package app.filenfiles.transfer

import app.filenfiles.core.ItemId
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.concurrent.atomic.AtomicLong

enum class TransferState { QUEUED, PREPARING, UPLOADING, WAITING_NETWORK, DONE, FAILED, CANCELLED }

/** UPLOAD sends a picked document/staged file to [Transfer.parent]; COPY copies/moves [Transfer.source] (file or folder) there. */
enum class TransferKind { UPLOAD, COPY, ZIP, UNZIP,
    /** ADDZIP: [Transfer.source] is the zip, [Transfer.sources] the staged local files to add, [Transfer.prefix] the folder inside it. */
    ADDZIP,
    /** SAVE: another app saved [Transfer.file] over the existing Filen file [Transfer.lineage], edited from version [Transfer.base]. */
    SAVE }

data class Transfer(
    val id: Long, val name: String,
    /** The picked document (UPLOAD from the picker), as a Uri string; "" when there is none. */
    val uri: String,
    val parent: ItemId, val mime: String?,
    val size: Long, val state: TransferState = TransferState.QUEUED, val done: Long = 0, val error: String? = null, val attempt: Int = 1,
    /** Already-staged source (documents-provider writes): uploaded in place and deleted when finished/cancelled/dismissed. */
    val file: File? = null,
    /** Epoch millis the current/last attempt began (0 = never ran). */
    val startedAt: Long = 0,
    /** True once the picked Uri has been fully copied to the staging dir (so a restart doesn't need the Uri any more). */
    val staged: Boolean = false,
    val kind: TransferKind = TransferKind.UPLOAD,
    /** COPY only: what to copy, and whether to delete it once everything has been copied. */
    val source: ItemId? = null,
    val move: Boolean = false,
    /** ZIP only: the items to compress ([name] is the archive's file name). UNZIP uses [source] for the archive. */
    val sources: List<ItemId> = emptyList(),
    /** Documents-provider writes: the `filen-pending:N` id the other app was given, kept so its row survives a restart. */
    val docId: String? = null,
    /** ADDZIP only: the folder inside the archive the files go to ("" or "a/b/"). */
    val prefix: String = "",
    /** The Filen account that queued it (null: saved by an older build). It only ever runs under that account. */
    val account: String? = null,
    /** SAVE only: the file's whole-life id, and the version the other app's edit started from (null: unknown). */
    val lineage: String? = null,
    val base: String? = null,
    /** The uid of the app that wrote [file] through the documents provider (-1: none or unknown). */
    val writer: Int = -1,
) {
    /** Present-tense label for the panel/notification. */
    val verb: String get() = when {
        kind == TransferKind.UPLOAD -> "Uploading"
        kind == TransferKind.ZIP -> "Compressing"
        kind == TransferKind.UNZIP -> "Extracting"
        kind == TransferKind.ADDZIP -> "Updating zip"
        kind == TransferKind.SAVE -> "Saving"
        move -> "Moving"
        source != null && source.scheme != "local" && parent.scheme == "local" -> "Saving"
        else -> "Copying"
    }
    val active get() = state == TransferState.QUEUED || state == TransferState.PREPARING || state == TransferState.UPLOADING || state == TransferState.WAITING_NETWORK
}

/**
 * The transfer queue's state and the files it owns, with no Android in it (so it can be unit-tested): the list, its
 * persistence, the outbox of unsent content, and the rules for holding, resuming, cancelling and clearing.
 * [TransferManager] runs the work; this decides what is kept and what may be deleted.
 *
 * Unsent content (staged picks, documents other apps wrote) lives in [outbox] and is deleted only when its transfer
 * finished, was cancelled or dismissed by the user, or the user signed out of its account.
 */
class TransferStore(
    private val filesDir: File,
    val outbox: File,
    /** Where 0.4.8 staged uploads (in the cache). Only adopted and swept now. */
    private val oldStaging: File,
    /** The signed-in account, or null. */
    private val account: () -> String?,
) {
    val items = MutableStateFlow<List<Transfer>>(emptyList())
    private val ids = AtomicLong(1)
    val storeFile = File(filesDir, "transfers.json")

    /** Loads the saved queue and tidies the outbox. Call once, before anything runs. */
    fun open() {
        load()
        adoptOldStaging()
        // Never hand out an id whose staging folder or ledger is still on disk.
        val names = outbox.list().orEmpty().toList() + File(filesDir, LEDGERS).list().orEmpty().toList()
        val used = names.mapNotNull { it.toLongOrNull() }.maxOrNull() ?: 0L
        ids.set(maxOf(ids.get(), used + 1))
        recoverOrphanWrites()
        sweep()
    }

    fun nextId() = ids.getAndIncrement()
    fun find(id: Long) = items.value.firstOrNull { it.id == id }
    fun update(id: Long, f: (Transfer) -> Transfer) = items.update { l -> l.map { if (it.id == id) f(it) else it } }
    /** A running job's own state change: ignored once the transfer was held, cancelled or failed from outside. */
    fun step(id: Long, f: (Transfer) -> Transfer) = update(id) { if (it.active) f(it) else it }
    fun fail(id: Long, msg: String) = step(id) { it.copy(state = TransferState.FAILED, error = msg) }

    fun otherAccount(t: Transfer): Boolean {
        val now = account() ?: return false
        return t.account != null && t.account != now
    }

    /** Adds new transfers, tagged with the signed-in account, and forgets completed and cancelled ones. */
    fun add(new: List<Transfer>) {
        val acc = account()
        new.forEach { ledgerFile(it.id).delete() } // ids can come round again; a leftover ledger is another job's
        items.update { l -> l.filter { it.active || it.state == TransferState.FAILED } + new.map { it.copy(account = acc) } }
    }

    fun providerUploads(): List<Transfer> =
        items.value.filter { it.kind == TransferKind.UPLOAD && it.docId != null && !otherAccount(it) && (it.active || it.state == TransferState.FAILED) }

    fun byDocId(docId: String): Transfer? = items.value.firstOrNull { it.docId == docId && !otherAccount(it) }

    /** How many unfinished or failed transfers a sign-out would throw away. */
    fun unfinishedCount(): Int = items.value.count { (it.active || it.state == TransferState.FAILED) && !otherAccount(it) }

    /** The newest unsent save of the file [lineage] (queued, running, waiting or failed): what a reader should get. */
    fun pendingSave(lineage: String): Transfer? =
        items.value.lastOrNull { it.kind == TransferKind.SAVE && it.lineage == lineage && !otherAccount(it) && (it.active || it.state == TransferState.FAILED) }

    private val ownSavesFile = File(filesDir, "own-saves")

    /**
     * Versions of [lineage] uploaded from saves the app [writer] made before transfer [before]: its next save may build
     * on those (Save, then Save again). Never on another app's save, which it may not have seen, nor on a later one (a
     * failed older save retried after a newer one landed).
     */
    @Synchronized fun ownSaves(lineage: String, writer: Int, before: Long = Long.MAX_VALUE): List<String> =
        lines(ownSavesFile).mapNotNull { l ->
            l.split('\t').takeIf { it.size == 4 && it[0] == lineage && it[3] == writer.toString() && (it[2].toLongOrNull() ?: Long.MAX_VALUE) < before }?.get(1)
        }

    @Synchronized fun recordOwnSave(lineage: String, uuid: String, byTransfer: Long, writer: Int) =
        write(ownSavesFile, lines(ownSavesFile) + "$lineage\t$uuid\t$byTransfer\t$writer")

    private val readBasesFile = File(filesDir, "read-bases")

    /**
     * The version of [lineage] the app [uid] was last given. A later save by that app is taken to be an edit of it:
     * Android doesn't tie a write to a read, so this is the best that can be known.
     */
    @Synchronized fun noteRead(uid: Int, lineage: String, version: String) {
        val key = "$uid\t$lineage\t"
        write(readBasesFile, lines(readBasesFile).filterNot { it.startsWith(key) } + (key + version))
    }

    @Synchronized fun readBase(uid: Int, lineage: String): String? {
        val key = "$uid\t$lineage\t"
        return lines(readBasesFile).lastOrNull { it.startsWith(key) }?.removePrefix(key)
    }

    /**
     * What a write by [uid] starts from. [copied]: the content it was given to edit, as (version uuid) or the unsent save
     * [copiedFrom]; both null for a write that replaces everything, which starts from what the app last read.
     * Null means unknown, and the save will be kept as a separate copy.
     */
    fun baseFor(uid: Int, lineage: String, copied: String?, copiedFrom: Transfer?): String? = when {
        copiedFrom != null -> if (copiedFrom.writer == uid) readBase(uid, lineage) ?: copiedFrom.base else null
        copied != null -> { noteRead(uid, lineage, copied); copied }
        else -> readBase(uid, lineage)
    }

    private fun lines(f: File) = runCatching { f.readLines() }.getOrDefault(emptyList()).filter { it.isNotBlank() }

    private fun write(f: File, all: List<String>) {
        val tmp = File(f.path + ".part"); tmp.writeText(all.takeLast(1000).joinToString("\n") + "\n")
        if (!tmp.renameTo(f)) tmp.delete()
    }

    fun ledgerFile(id: Long) = File(filesDir, "$LEDGERS/$id")
    fun stagingDir(id: Long) = File(outbox, "$id")

    /** Deletes what [t] owns on disk: its staged content, its ledger. Only for finished, cancelled or dismissed work. */
    fun discardSource(t: Transfer) {
        ledgerFile(t.id).delete()
        t.file?.parentFile?.let { File(it.path + META).delete(); it.deleteRecursively() }
        stagingDir(t.id).deleteRecursively()
    }

    /**
     * After a job stopped: what it owned is deleted only if it finished, or the user cancelled or dismissed it. A failed
     * or held transfer keeps everything for Retry. [snapshot] is the transfer as the job started it.
     */
    fun afterJob(snapshot: Transfer) {
        val now = find(snapshot.id)
        if (now == null || now.state == TransferState.DONE || now.state == TransferState.CANCELLED) discardSource(now ?: snapshot)
    }

    /**
     * The user cancelled [id]. Its content is deleted at once only when nothing is working on it ([running] false);
     * otherwise [afterJob] does it once the job has stopped. Returns the transfer if it was active.
     */
    fun cancel(id: Long, running: Boolean): Transfer? {
        val t = find(id)?.takeIf { it.active } ?: return null
        update(id) { it.copy(state = TransferState.CANCELLED, error = null) }
        if (!running) discardSource(t)
        return t
    }

    /** The user dismissed a finished or failed [id]. Same rule as [cancel] for content a job is still using. */
    fun dismiss(id: Long, running: Boolean) {
        val t = find(id)?.takeIf { !it.active } ?: return
        items.update { l -> l.filterNot { it.id == id } }
        if (!running) discardSource(t)
    }

    /**
     * The job finished. A cancel that came too late (the upload was already being named, which is not interrupted) is
     * reported as what happened: the file is in Filen.
     */
    fun finish(id: Long, note: String? = null) = update(id) {
        it.copy(state = TransferState.DONE, done = it.size,
            error = listOfNotNull(note, "Finished before it could be cancelled: the file is in Filen.".takeIf { _ -> it.state == TransferState.CANCELLED })
                .joinToString(" ").ifEmpty { null })
    }

    /** The user retries [id]; false when it can't be retried. */
    fun retry(id: Long): Boolean {
        val t = find(id) ?: return false
        if (t.active || t.state == TransferState.DONE || otherAccount(t)) return false
        // A retried copy resumes from its ledger; a retried upload starts over (reusing its staged copy).
        update(id) { it.copy(state = TransferState.QUEUED, done = 0, error = null, attempt = 1, startedAt = if (it.kind == TransferKind.COPY) it.startedAt else 0) }
        return true
    }

    /**
     * Signed in: work for another account is held; work held for this account (or by an unexpected sign-out) carries on.
     * Returns the ids that are now queued.
     */
    fun resumeStates(): List<Long> {
        val acc = account()
        items.update { l ->
            l.map {
                when {
                    it.active && otherAccount(it) -> it.copy(state = TransferState.FAILED, error = OTHER_ACCOUNT)
                    // (An "add to zip" held before its files were staged has nothing to add: it stays failed.)
                    it.state == TransferState.FAILED && (it.error == SIGNED_OUT || it.error == OTHER_ACCOUNT) && !otherAccount(it) && acc != null &&
                        !(it.kind == TransferKind.ADDZIP && it.sources.isEmpty()) ->
                        it.copy(state = TransferState.QUEUED, error = null, attempt = 1, done = 0)
                    else -> it
                }
            }
        }
        return items.value.filter { it.state == TransferState.QUEUED }.map { it.id }
    }

    /** Signed out without the user asking: every active transfer is held (nothing is deleted). Returns the held ids. */
    fun hold(): List<Long> {
        val running = items.value.filter { it.active }.map { it.id }
        items.update { l -> l.map { if (it.active) it.copy(state = TransferState.FAILED, error = SIGNED_OUT) else it } }
        return running
    }

    /**
     * The user signed out of [leaving]: its work and content are forgotten (the user was told how many). Work held for
     * another account stays, with its files. Returns the transfers that were active, for their jobs to be stopped.
     */
    fun clearFor(leaving: String?): List<Transfer> {
        val (kept, gone) = items.value.partition { it.account != null && leaving != null && it.account != leaving }
        items.value = kept
        gone.forEach(::discardSource)
        val keep = kept.flatMap { listOfNotNull(it.id.toString(), it.file?.parentFile?.name) }.toSet()
        listOf(outbox, oldStaging).forEach { root ->
            root.listFiles()?.filter { it.name.removeSuffix(META) !in keep }?.forEach { it.deleteRecursively() }
        }
        if (kept.isEmpty()) { File(filesDir, LEDGERS).deleteRecursively(); storeFile.delete() } else save()
        return gone.filter { it.active }
    }

    @Synchronized
    fun save() {
        runCatching {
            val a = JSONArray()
            items.value.filter { it.active || it.state == TransferState.FAILED }.forEach { t ->
                a.put(JSONObject().put("id", t.id).put("name", t.name).put("uri", t.uri).put("parent", t.parent.value)
                    .put("mime", t.mime ?: JSONObject.NULL).put("size", t.size).put("state", if (t.active) "QUEUED" else "FAILED")
                    .put("error", t.error ?: JSONObject.NULL).put("file", t.file?.absolutePath ?: JSONObject.NULL)
                    .put("startedAt", t.startedAt).put("staged", t.staged)
                    .put("kind", t.kind.name).put("source", t.source?.value ?: JSONObject.NULL).put("move", t.move)
                    .put("sources", JSONArray(t.sources.map { it.value })).put("docId", t.docId ?: JSONObject.NULL).put("prefix", t.prefix)
                    .put("account", t.account ?: JSONObject.NULL).put("lineage", t.lineage ?: JSONObject.NULL).put("base", t.base ?: JSONObject.NULL).put("writer", t.writer))
            }
            if (a.length() == 0) { storeFile.delete(); return }
            val tmp = File(storeFile.parentFile, storeFile.name + ".part")
            tmp.writeText(JSONObject().put("v", 1).put("items", a).toString())
            if (!tmp.renameTo(storeFile)) tmp.delete()
        }
    }

    /** Anything that was pending when the process died comes back as QUEUED (a failed one stays failed, retryable). */
    private fun load() {
        if (!storeFile.isFile) return
        val list = runCatching {
            val a = JSONObject(storeFile.readText()).getJSONArray("items")
            (0 until a.length()).mapNotNull { i ->
                val o = a.getJSONObject(i)
                val file = o.optString("file").takeIf { !o.isNull("file") && it.isNotEmpty() }?.let(::File)
                if (file != null && !file.isFile) return@mapNotNull null // staged source vanished: nothing to resume
                val failed = o.getString("state") == "FAILED"
                Transfer(
                    o.getLong("id"), o.getString("name"), o.optString("uri", ""), ItemId(o.getString("parent")),
                    o.optString("mime").takeIf { !o.isNull("mime") }, o.getLong("size"),
                    state = if (failed) TransferState.FAILED else TransferState.QUEUED, error = o.optString("error").takeIf { failed && !o.isNull("error") },
                    file = file, startedAt = o.optLong("startedAt"), staged = o.optBoolean("staged"),
                    kind = runCatching { TransferKind.valueOf(o.getString("kind")) }.getOrDefault(TransferKind.UPLOAD),
                    source = o.optString("source").takeIf { !o.isNull("source") && it.isNotEmpty() }?.let(::ItemId),
                    move = o.optBoolean("move"),
                    sources = o.optJSONArray("sources")?.let { a -> (0 until a.length()).map { ItemId(a.getString(it)) } } ?: emptyList(),
                    docId = o.optString("docId").takeIf { !o.isNull("docId") && it.isNotEmpty() },
                    prefix = o.optString("prefix", ""),
                    account = o.optString("account").takeIf { o.has("account") && !o.isNull("account") && it.isNotEmpty() },
                    lineage = o.optString("lineage").takeIf { o.has("lineage") && !o.isNull("lineage") && it.isNotEmpty() },
                    base = o.optString("base").takeIf { o.has("base") && !o.isNull("base") && it.isNotEmpty() },
                    writer = o.optInt("writer", -1),
                )
            }
        }.getOrNull()
        if (list == null) {
            // Unreadable: keep it and every staged file; without the list nobody knows what is garbage.
            storeFile.renameTo(File(filesDir, "${storeFile.name}$UNREADABLE-${System.currentTimeMillis()}"))
            return
        }
        items.value = list
        ids.set((list.maxOfOrNull { it.id } ?: 0L) + 1)
    }

    /** A queue saved by 0.4.8 staged in the cache: move what it still needs into the outbox. */
    private fun adoptOldStaging() {
        outbox.mkdirs()
        val needed = items.value.flatMap { t -> listOfNotNull(t.id.toString(), t.file?.parentFile?.takeIf { it.parentFile == oldStaging }?.name) }
        val moved = needed.filter { name -> File(oldStaging, name).isDirectory && !File(outbox, name).exists() && File(oldStaging, name).renameTo(File(outbox, name)) }
        if (moved.isEmpty()) return
        fun relocate(path: String): String =
            moved.firstOrNull { path.startsWith("${File(oldStaging, it).path}/") }?.let { outbox.path + path.removePrefix(oldStaging.path) } ?: path
        items.value = items.value.map { t ->
            t.copy(file = t.file?.let { File(relocate(it.path)) },
                sources = t.sources.map { if (it.scheme == "local") ItemId("local:" + relocate(it.key)) else it })
        }
        save()
    }

    /** Written beside a documents-provider staging folder, so a write that never reached the queue can be found again. */
    fun describeWrite(dir: File, docId: String, parent: ItemId, name: String, mime: String, lineage: String? = null, base: String? = null, writer: Int = -1) {
        runCatching {
            File(dir.path + META).writeText(JSONObject().put("docId", docId).put("parent", parent.value).put("name", name)
                .put("mime", mime).put("account", account() ?: JSONObject.NULL)
                .put("lineage", lineage ?: JSONObject.NULL).put("base", base ?: JSONObject.NULL).put("writer", writer).toString())
        }
    }

    /**
     * The process died while another app was writing, or just after it closed. We can't tell which, so the file is never
     * uploaded by itself: it is listed as failed, and the user retries or dismisses it.
     */
    private fun recoverOrphanWrites() {
        val keep = items.value.mapNotNull { it.file?.parentFile?.name }.toSet()
        val dirs = outbox.listFiles()?.filter { it.isDirectory && it.name.startsWith("doc-") && it.name !in keep } ?: return
        val found = dirs.mapNotNull { dir ->
            runCatching {
                val o = JSONObject(File(dir.path + META).readText())
                val file = File(dir, o.getString("name"))
                if (!file.isFile || file.length() == 0L) return@runCatching null
                val lineage = o.optString("lineage").takeIf { o.has("lineage") && !o.isNull("lineage") && it.isNotEmpty() }
                Transfer(nextId(), file.name, "", ItemId(o.getString("parent")), o.optString("mime"), file.length(),
                    state = TransferState.FAILED, error = INTERRUPTED, file = file, docId = o.getString("docId").takeIf { lineage == null },
                    account = o.optString("account").takeIf { !o.isNull("account") && it.isNotEmpty() },
                    kind = if (lineage != null) TransferKind.SAVE else TransferKind.UPLOAD, lineage = lineage,
                    base = o.optString("base").takeIf { o.has("base") && !o.isNull("base") && it.isNotEmpty() }, writer = o.optInt("writer", -1))
            }.getOrNull()
        }
        if (found.isNotEmpty()) { items.value = items.value + found; save() }
    }

    /** Staging that no transfer refers to is leftover; a "doc-" folder with anything written in it is never deleted unasked. */
    private fun sweep() {
        // While an unreadable queue is kept, nobody knows what is garbage.
        if (filesDir.list()?.any { it.startsWith(storeFile.name + UNREADABLE) } == true) return
        val keep = items.value.flatMap { listOfNotNull(it.id.toString(), it.file?.parentFile?.name) }.toSet()
        listOf(outbox, oldStaging).forEach { root ->
            root.listFiles()?.filter { it.name.removeSuffix(META) !in keep }
                ?.filter { f -> !f.name.startsWith("doc-") || (f.isDirectory && f.walk().none { it.isFile && it.length() > 0 }) || (f.isFile && !File(root, f.name.removeSuffix(META)).exists()) }
                ?.forEach { if (it.isDirectory) File(it.path + META).delete(); it.deleteRecursively() }
        }
    }

    companion object {
        const val META = ".meta"
        const val UNREADABLE = ".unreadable"
        const val LEDGERS = "copy-ledger"
        const val INTERRUPTED = "Interrupted while another app was saving. It may be incomplete: retry to upload it, or dismiss it."
        const val SIGNED_OUT = "Signed out. Sign in again to finish this."
        const val OTHER_ACCOUNT = "Queued by another Filen account. Sign in to it to finish this."
    }
}
