# Safe save-over: investigation (2026-11-25)

Requested by the Spreadsheet session (`~/Projects/Spreadsheet/docs/FILEN_REQUEST.md`, part 1).
Source: reading Filen Files and filen-rs `eb93d28`. Nothing was built or run. SDK tests and comments describe server
behaviour, but we have not observed it ourselves.

## SDK and server behaviour
- **No overwrite-by-ID call was found.** Uploading a file with the same name in the same folder replaces it. The name
  match ignores case (`hash_name` lowercases). The new file gets a new `uuid` and keeps the `stableUuid`
  (`socket_tests.rs`, `fs_tests.rs`, CLI docs in `transfer_cmds.rs`).
- **Versioning on:** the old content is kept as an earlier version.
- **Versioning off:** the old row is "trashed" with `newUUID`, and its row is "purged almost at once" (`socket.rs`).
  The sync engine says such an upload loses the old version for good. Assume it cannot be recovered.
  The test account had versioning off when last recorded in STATUS.md.
- **Interrupted upload:** the file exists only after the final `upload/done`, so a failure before it should leave the
  old file as it was. If only the answer to `upload/done` is lost, the new file is there although we saw an error.
- **No server precondition:** the upload request has no "replace only version X" field. `lockDrive()` is available in
  Kotlin and the upload reuses a lock the same client holds. It only guards against clients that take the same lock;
  whether the Filen web app does is unknown. It does not serialize writes inside our own process.

## API check (2026-11-25)
- The endpoint definitions in the SDK (`filen-types/src/api`, file/upload/lock/version routes) show no call that
  replaces a file's contents. The `upload/done`, `upload/empty`, `file/versions` and `file/version/restore` types and the
  `upload/` and `file/` searches show no expected-version or if-match field. `file/version/restore` takes `uuid` and
  `current`, but restores an existing version; whether the server rejects a stale `current` is unknown.
- Public docs (read through a fetch agent): the [files guide](https://docs.filen.io/docs/api/guides/files/) says to make
  a new UUID "even if you're overwriting an existing file". [upload/done](https://docs.filen.io/docs/api/specs/mark-an-upload-completed)
  lists no precondition. [file/exists](https://docs.filen.io/docs/api/specs/check-if-file-exists-by-name) returns only
  `exists` and `uuid`. The [specs page](https://docs.filen.io/docs/api/specs/) lists versioning and `user/lock` (60 s) as
  undocumented. The pages read do not mention `stableUUID` and say empty files can't be uploaded, although the SDK has
  `upload/empty`, so they seem older than the API in use. The linked OpenAPI URL answered `invalid_endpoint`.
- Not read: Filen's TypeScript SDK or web client (would show whether the web app takes the drive lock).

## Upstream check (2026-11-25, later)
Read from fresh clones in the session scratch folder; `external/filen-rs` was not changed.
- Ours: filen-rs `eb93d28`, 0.5.33. Upstream `main`: `c93cedd` (2026-11-25), crate 0.5.35. `filen-types/src/api` is
  unchanged between them. `sync/mod.rs` changes how JS/wasm holds the lock handle. Much of the rest is a new Windows
  crate, `filen-cloud-files`. Not upgraded.
- Current TypeScript code is the `filen-ts` monorepo (web and mobile; mobile pins `@filen/sdk-rs` 0.5.35).
  `filen-docs` is the docs source; its files guide has the same overwrite sentence. The old `filen-sdk-ts` / `filen-web`
  repos were last pushed in September / July.
- **Filen confirms there is no server check.** `filen-cloud-files/src/local/jobs.rs`, `upload_version`: "The server
  takes a version with no check of the one it follows"; a version uploaded elsewhere in the window becomes an older one
  "(or, with versioning off, is gone)"; "nothing on the client closes it". So do not count on the drive lock until a
  test shows it helps. This replaces the lock-based guarantee implied under "Proposed design".
- **Web PDF editor** (`features/pdf/save/remoteChange.ts`, `session/pdfEditActions.ts`): before saving a version it
  looks up the head by `stableUUID`; if the uuid changed it asks the user (can save a copy). No lock call found in the
  web or shared TypeScript source (the SDK takes the drive lock itself around `upload/done`). Which web source
  app.filen.io currently runs was not checked.
- **Safe creation pattern** (`jobs.rs`, `create`): upload under a temporary unique name, then rename to the wanted
  name; their comment says the server refuses the rename while another file holds it. If a test confirms this, use it
  for problem 1 and for conflict copies: it avoids the check-then-upload race. An interrupted rename must be recovered.
- Their cloud-files engine keeps both sides on conflict, and keeps unsent edits and file lineage across restarts.
- The Android documents provider submodule in `filen-ts` was not fetched or read in this check.

## Problems in the current code (from reading; to confirm)
1. **Same name replaces silently.** `createDocument` and the upload queue never check for an existing name. "Save as"
   or an in-app upload onto an existing name replaces that file; with versioning off the old content is probably lost.
2. **Old IDs go stale after an edit anywhere.** IDs are `filen:<uuid>`. `stat` answers from the `personalFiles`
   cache, then `getFileOptional(uuid)`. With versioning on, the SDK still returns a superseded uuid, so a saved
   reference can show old content. With versioning off it will probably stop resolving or look trashed.
   `lookup` has the same problem, so Stack could get a stale path or think the file was deleted.
3. **`findUploaded` is unsafe for save-over.** It accepts same name + same size + modified within 60 s before the
   attempt. The old file, or the previous save, can match, and the new content would be marked uploaded and deleted.
   It must compare the stored content hash.
4. **Unsent content is in `cacheDir`** (`cache/upload`), which Android or "Clear cache" can delete. Dismissing a
   failed upload also deletes it.
5. **Ami Pro:** `EditorViewModel.save()` writes with `openOutputStream(uri, "wt")`. Our provider refuses that for
   existing files and for a second write, so plain Save should fail there.

## Proposed design (part 1)
- **Versioning required.** Offer save-over only when the account has versioning on (check at write time; the app can
  already turn it on, with confirmation). Otherwise refuse as today. Old versions use storage quota.
- **IDs.** Hand out `filen:<stableUuid>` and never change an ID an app holds. For a never-edited file it equals
  today's ID. Resolve through `getFileByStableUuidOptional`, bypassing the cache. Old IDs that are a later version's
  uuid: `getFile(id)` -> its `stableUuid` -> current file (works with versioning on; with it off, may be unresolvable).
  `locate`/`lookup` need the same rule, agreed with Stack. Check other users of `uuid`: pins, favorites, search index,
  tree grants, parent map.
- **Local write.** For `rw`/`wa`, start from a copy of the current content. A writer that closes with an error is
  discarded. A finished write is moved to `filesDir` and journalled before it is queued; it is deleted only after a
  verified upload or an explicit "discard".
- **Change elsewhere.** Remember which version each app last read or saved. Before upload, take the drive lock, fetch
  the current file by stableUuid, and require that version, not trashed. Upload to its **current** name and folder
  (the upload targets a name, so a stale name would create or replace another file). On a mismatch, a trashed file or
  an unknown base, upload as "name (conflict ...)" beside it and report it. Never replace.
  After upload, check the version list: if something landed between the base and ours, report it (both are kept).
  Android does not link a read to a later write, so the base is a best guess per app and document.
- **Save, Save again.** One queue per file; a later save builds on the earlier save's result. Keep every unsent save
  until a newer one is verified. Reads during upload serve the newest local content.
- **Status.** Same ID throughout, with `COLUMN_SUMMARY` = Uploading / Waiting / Failed / Conflict. For trusted apps,
  add a `saveStatus(uri)` call; the summary column alone is a weak signal.
- Writes while the app is locked stay refused.

## To test before building (TEST phone, made-up files)
1. Same-name upload with versioning on, then off: version history, Trash, and a case-only name difference.
2. What `getFile(oldUuid)` and `getFileByStableUuidOptional` return in both modes.
3. Upload cut off mid-way, and process kill: old content intact.
4. Whether a web-app edit waits for our drive lock.

Test 1 partly works with the released app's own upload and Versions screens. Tests 2-4 need a probe build signed with
the release key (the phone's app has that signature). Toggling versioning changes the whole account.

## Part 3
- **Two APKs, confirmed in code:** v0.4.8 has two `.apk` assets and Stack's `Companions.parse` throws `NO_APK` on a
  second one. Fix `tools/release.sh`; changing the published release needs the owner's approval. First check what
  depends on each asset name.
- Tree grants with the picker and `locate`: not looked at.
