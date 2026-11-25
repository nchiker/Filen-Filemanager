# Plan: safe saving and related changes (2026-11-25)

Evidence: `SAFE_SAVE_INVESTIGATION.md`. SDK: filen-rs `c93cedd` (0.5.35). Also read: `filen-ts` `0d37e0dc`,
`filen-android-documents-provider` `e6f232c`, `filen-docs` `7928ad0`. Server behaviour below is **untested**.

## New since the investigation
- **Move may replace a file.** The SDK describes "move-with-replace": the replaced file is "gone for good"
  (`filen-sdk-rs/src/cache/state/event.rs`, `Archived`). Our Move (`ui/Operations.kt`, provider `moveDocument`)
  does not check the destination. Whether this happens through a plain move is unknown. Test it.
- **Filen's Android provider uses stable IDs** (commit `7ea102e`): documents are `stable/<id>`; older IDs resolve by
  a uuid fallback. This supports our ID design.
- It creates an empty file in `createDocument`, so an ID is final from the start (a choice for us in step 4; it does
  not work offline). On a writable close it calls `uploadFileIfChanged` (skips an unchanged hash, records a pending
  upload first). No version check was seen in the code read.
- Camera backup and cross-storage copy already pick unique names, ignoring case (`Names.unique`). A check against a
  listing can still race with another device or a lagging listing.

## Step 0: SDK update (done)
0.5.33 -> 0.5.35, bindings regenerated, no source changes. 78 unit tests passed; debug build succeeded.
**Not done:** release (R8) build, run on a phone. Rollback: check out `eb93d28` in `external/filen-rs`, rerun
`./build-rust.sh arm64-v8a`.

## Step 1: phone tests (pending)
Automated run blocked by auto mode on 2026-11-25.
**Owner's manual results (reported 2026-11-25, TEST phone, text editor):**
- Saving a second new `newfile.txt` in the same folder gave no error. One file remains, with the second text.
- Renaming `other.txt` to `newfile.txt` was refused: the filename already exists.
- Not yet reported: version history and versioning setting; different-case names; move; restore. Use only made-up files in "Filen Files save test (safe to delete)".
The app can turn versioning on but not off. Note the starting setting (item menu > Version history).
1. Upload `save-test.txt`; upload different content under the same name. If versioning is off, do this first, then
   turn it on and repeat. Note the number of rows, history, Trash.
2. Upload `SAVE-TEST.TXT` with different content. One file or two?
3. Rename `other.txt` to `save-test.txt`, then to `Save-Test.txt`. Refused? Both files intact?
4. Move a different `save-test.txt` onto the folder holding one. Both kept? Is either gone or only a version?
5. Trash a file, create the same name, restore the trashed one. What survives?
6. Restore the original versioning setting (turning off needs the Filen web app).
Later, needs a release-signed test build (not approved): stable/old ID lookups, interrupted upload, drive lock.

## Step 2: stop silent replacement (first code change)
**Uploads: done in code 2026-11-25, not run on a phone, not committed or released.** `core/SafeCreate.kt` and
`FilenStorageProvider.upload`: temporary name, then rename; a taken name becomes "name (1)". `findUploaded` was removed
instead of comparing hashes (we have no local Filen hash), so a retry after a lost reply or restart may leave a
duplicate. A cancelled or failed rename can leave a `.filen-upload-*` file in the folder. 87 unit tests and the debug
build passed.
**Upstream evidence** (`filen-cloud-files/src/drive/mod.rs`, "measured"): rename onto a taken name is refused with
`file_with_name_already_exists_at_destination`; **a file Move is not refused**, and the destination file survives only
as an older version of the moved one. So Move needs a name check (next); folder moves are refused (upstream tests).
Also check: a resumed cross-storage move (`CopyRunner`) skips a file that matches by name and size, then may delete the
original; that has the same false-match risk (from reading, not examined further).
**Phone checks needed:** same name from the app and from a text editor gives "(1)" with both texts; capitals; a file
without extension; type and modified date kept; no `.filen-upload-*` left; offline then retry; camera backup; add to zip.
- New files (in-app upload, provider create, queued uploads): upload under a unique temporary name, then rename;
  on "name taken" use "name (1)". Depends on test 3. Recover temporary names after a crash or lost reply.
- Move and restore: act on tests 4 and 5 (check first, then ask or rename).
- Fix `findUploaded` to compare the content hash.
- Show the versioning state in Settings with an on/off switch and a warning for off.

## Step 3: never lose unsent content
Move pending content from `cacheDir` to `filesDir` with a journal; confirm before Dismiss discards it.

## Step 4: stable IDs (done differently in code 2026-11-25, see the last section; unit-tested only in part)
Original plan: hand out `filen:<stableUuid>`; resolve old IDs; bypass the stale cache. Update `locate`/`lookup` (agree with Stack),
pins, search index, parent map and grants. Test existing Ami Pro and Stack references.

## Step 5: save-over (in code 2026-11-25, see the last section; NOT run anywhere)
Only with versioning on. `rw`/`wt`/`wa` on existing files; per-file queue; check the head before upload and the
version list after; on conflict keep both; `COLUMN_SUMMARY` and `saveStatus`. A conflict during the upload can be
reported, not prevented. Then test with Ami Pro and tell the Spreadsheet session.

## Step 6: trust and release
Pin the spreadsheet app once its package and key exist. Remove the second APK asset in `tools/release.sh` after
checking what uses each name. R8 build and phone smoke test. Releases need the owner's go-ahead.

## Other features (separate from safe save)
| Item | Finding | Suggestion |
|---|---|---|
| Share into Filen | No `ACTION_SEND` filter in our manifest; official mobile has incoming share | Add after step 2 |
| Server-side copy | SDK has `copyItems`; our `CopyRunner` does not use it | Use for Filen-to-Filen copies; check name clashes first |
| Archives | SDK has `openArchiveReader`, `extractArchive`, `compressItems` | Already on roadmap; extract/compress could avoid downloads |
| Video, audio, text viewers | Roadmap item; official apps have audio | Keep on roadmap |
| Open a Filen public link | SDK has `getLinkedFile`, `listLinkedDir` | Later |
| Folder size, folder colour, blocked contacts | In SDK | Small, later |
| Notes and chats | Now in the SDK; `ROADMAP.md` says they are not | Correct the roadmap; large, not recommended now |
Only feature folders and function names were compared, so check each before building.

## Phone results, 2026-11-25 (signed test build, APK sha256 66139596..., installed on the TEST phone; versioning on)
In-app Upload in the Drive folder `safetest-claude` (Claude's, left in the Drive): `safetest.txt` (4 B), the same name
again (12 B) became `safetest (1).txt`, then `SAFETEST.TXT` (24 B) became `SAFETEST (2).TXT`. The first file's version
history showed no earlier versions. No `.filen-upload-*` file seen, no fatal exception, still signed in.
Not tested: another app's new file (provider path), file contents opened (told apart by size), MIME/date, offline
retry, cancel, invalid names, camera backup, add to zip, Move, restore.

## Independent review, 2026-11-25 (Opus, read-only)
No path found where the new upload replaces a user's file (apart from a leftover temp file of the same upload).
**Fix before committing/releasing the upload change**
- A name the SDK refuses (`: * ? " < > | \`, trailing dot/space, over 255 bytes) now fails only after the upload and
  leaves a `.filen-upload-*` file; retries upload again. Check names first.
- Cancel between upload and rename deletes the local copy and leaves only the temp file. Process death also leaves it.
- Shared/linked folders: a failure after the rename may leave recipients seeing the temp name (plausible).
- Callers that assume the requested name: `ArchiveRunner` message, `CameraBackup`, `CopyRunner`, pending rows.
- Also: digest could include a per-install salt; hashing is not cancellable; `uploadLocks` grows. Release notes must
  say re-uploading a name now makes "(1)".
**Older bugs (in 0.4.8)**
- High: a resumed cross-storage Move can skip a file on name+size, or reuse an unrelated same-name folder, then delete
  the original (`CopyRunner.kt`; local delete is permanent). Checked in code by Claude too.
- High: Move deletes the whole source folder, including files added after it was scanned (`CopyRunner.run`). Checked.
- High: Move within Filen onto a taken name (see step 2; upstream's measurement).
- Medium-high: a session that fails to restore becomes LoggedOut, and `transfers.clearAll()` deletes unsent uploads
  (code path checked; SDK session-format compatibility matters on upgrades. This phone stayed signed in).
- Medium: a provider write can be lost if the process dies before the debounced queue save (plausible).
- Medium: restore from trash / Undo onto a taken name is unchecked; server behaviour unknown.
- Low: device download's final rename can overwrite; Filen add-to-zip swap leaves links/history on the trashed file.
**Corrections to these documents**
- "We have no local Filen hash" is misleading: Filen metadata has a BLAKE3 hash; we lack a local BLAKE3 function.
  With one, "did it land?" and resume checks can be safe.
- Save-over cannot "never replace": a same-name upload replaces whatever holds the name then. It also needs its own
  upload path, since `upload` now never replaces. Versioning can be turned off between the check and the upload.
- Stable IDs: received shares and public links have none; a move-with-replace retires the replaced file's ID.
- Move-with-replace: "measured" means upstream's tests; we have not observed it.

## Fixes after the review (2026-11-25; uncommitted, unreleased, NOT on the phone)
The phone has the earlier upload-only test build (APK sha256 66139596...).
- Moves: `core/CopyEngine` + `CopyLedger`. Resume skips only what the ledger shows this transfer made and that is
  unchanged; a move removes only copied, unchanged originals. Only an untouched Filen tree is trashed whole. Folders in
  other cloud apps are left behind empty. Filen sources are checked with `freshStat`.
- Uploads: names checked first (`FilenNames`), provider names sanitized, naming runs under `NonCancellable`, per-install
  salt. Filen file Move refuses a taken name (check-then-move; a race remains).
- Unsent content: `files/outbox`; 0.4.8 staging adopted; queue saved at once; interrupted app writes are listed as
  failed (never auto-uploaded); unexpected sign-out holds the queue; transfers are tied to their account; explicit
  sign-out discards only that account's; discard confirmations.
- Opus reviewed the first round of fixes: nothing found that had to block a phone install; findings 1-4 had to block a
  commit. Its findings 1-7 and the stale-name part of 8 are then fixed. **That last round is not independently
  reviewed.** 103 unit tests and the debug build pass. TransferManager itself has no unit tests.
- Still open: restore/Undo onto a taken name; another cloud app's partial listing can leave a copy incomplete without
  saying so (originals stay); Cancel during naming still lets the upload finish; one invalid name stops a folder copy;
  provider folder names are not sanitized; temp file can remain after a crash or failed rename.
- Phone checks needed: move phone <-> Filen (including a file added during the move, and resume after killing the
  app), upgrade with a queued upload, Filen move onto a taken name, text-editor save with a taken name, offline retry,
  discard dialogs. Do not test sign-out on the owner's account.

## Round 3 (2026-11-25, Opus with edit rights; uncommitted, unreleased, NOT on a phone, NOT independently reviewed)
Upstream filen-rs `main` is still `c93cedd`. Checks: `:core`, `:filen`, `:app` unit tests (88 + 18 + 20) and
`:app:assembleDebug` pass. Nothing below was run on a device or against the server.

**Review of round 2, fixed here**
- A held or failed upload deleted its staged copy of a picked file (the job's `finally`); after a sign-in again the Uri
  could be unreadable. Staged copies now stay until the transfer finished, was cancelled or dismissed.
- Cancel/dismiss deleted the content while the job still ran; now only after the job stopped (`TransferStore.afterJob`).

**a-g (separable from h; snapshot of this state kept outside the repo)**
- a. Restore from trash and Undo (`FilenStorageProvider.checkRestorable`): refused when the original folder now holds
  an item with that name (ignoring case), when that folder is gone or trashed, or when the trash entry does not say
  where it came from. Relies on the SDK's `ParentUuid.Trash(originalParent)` from the trash listing (source read, not
  observed). A race with another device between check and restore remains. **Phone: restore must still work at all.**
- b. Another app's still-loading listing (`Listing.partial`, set by `SafStorageProvider.list`): a copy or move asks
  again up to 10 times (2 s apart), then stops with an error; nothing is deleted on a partial listing. Unit-tested.
- c. Cancel during the naming step: the row ends as Done with "Finished before it could be cancelled: the file is in
  Filen", and the local copy is deleted only after the job stopped. Unit-tested (store only).
- d. Leftover `.filen-upload-*` files: an on-disk journal (`core/UploadJournal`, `files/upload-names`) is written
  before each upload. When the queue is idle after sign-in, `recoverUploads` gives each leftover its real name
  ("name (1)" if taken). It never deletes; a file it can't name stays and is tried again. A cancelled upload that had
  already reached Filen will therefore appear under its name. Journal unit-tested; recovery not run.
- e. One bad name no longer stops a folder copy/move: such items are skipped, everything else is copied, the transfer
  ends failed with the names, and skipped originals stay. Filen uses the SDK rules (`FilenNames`), the phone the FAT
  rules (`LocalStorageProvider.nameProblem`). Unit-tested with the fake.
- f. Folder names from other apps are sanitised like file names.
- g. `transfer/TransferStore.kt` holds the queue's state and file rules with no Android; `TransferStoreTest` covers
  hold, resume, accounts, cancel/dismiss timing, sign-out, orphan writes, sweep, unreadable queue, 0.4.8 adoption.
  `TransferManager` (jobs, services, network waits) is still untested. `Transfer.uri` is now a String.

**h. Stable IDs and save-over (separable: see the file list in the hand-off report)**
- IDs keep their format (`filen:<uuid>`), so nothing Ami Pro or Stack holds changes. Any uuid of a file's versions now
  resolves to the current version (`currentHead`: uuid -> stableUuid -> current), asked of Filen each time, with the
  cached item offline. Used by queryDocument (answers with the id the caller asked for), openDocument, delete, rename,
  move, the tree-grant parent walk, and Stack `lookup`. `locate` and the events are unchanged.
- Save-over: `openDocument(existing, "w"/"wt"/"rw"/"wa")` works only with versioning on (checked at open). The app
  writes a private copy in the outbox; a clean close queues a SAVE (a close with an error, or an "rw" open that
  changed nothing, is discarded). Just before uploading, `SaveOver.plan`: versioning still on, the file not trashed,
  and its current version is the one the edit started from or one this app saved earlier for an earlier save. Then it
  uploads under the file's current name and folder (a new version); otherwise it uploads a separate
  "name (conflict date time).ext" next to it and says so. Afterwards the version list is read and an in-between upload
  is reported (both kept). Readers get the newest unsent save. The file row shows the save's status in
  `COLUMN_SUMMARY`. Unit-tested: the decision rules and the store; the upload path itself is not.
- Known limits: no server precondition exists, so another device can still upload in the instant between our check and
  ours (both kept as versions, reported). Versioning can be switched off in that instant too (then the old content is
  gone: the check can't close that). A lost reply to a successful save, then a retry, gives a needless conflict copy.
  "Save again" on a NEW file still uploading (a `filen-pending:` id) is refused until that upload finished.
  queryDocument/openDocument now make one or two extra Filen requests each.

**Phone tests needed (TEST phone, made-up files, versioning on)**
1. Everything listed under round 2, plus restore and Undo (free name, taken name, case-only clash).
2. Ami Pro: open a Filen file, edit, Save, Save again; check content, version history, and that the doc still opens
   from Ami Pro's recent list and from Stack. Then edit the same file on the web between two saves: a conflict copy.
3. Versioning off on a test account: a write open is refused with the message.
4. Kill the app right after a save, before upload: it comes back as a failed save; Retry uploads it.
5. A move out of a cloud app (Drive/Nextcloud) folder that is still loading.
6. Cancel an upload right at its end; leftover `.filen-upload-*` after a kill gets its name after the next sign-in.

## Claude's review of round 3, and phone results (2026-11-25)
**Fixed in review (unit-tested only; Opus has not seen this fix):** a save's base was the version current when the
write opened, so a change made elsewhere after the app read the file would be saved over without a conflict; and one
app's save counted as "own" for every app. Base is now the version that app (uid) was last given (`read-bases`);
unknown base = conflict copy; own saves are per app. Also: the save looks the file up by its lineage id; an unknown
folder fails the save for Retry instead of guessing; an old ID still resolves if its first version row is gone.
Consequence: after a conflict copy, further saves make more copies until the app reopens the file. Reading a file
again without reloading it can still advance the base (Android doesn't link reads to writes).
128 unit tests and the debug build passed; signed test build (sha256 5d38a16f..., pairing off) installed with `-r`.

**Phone, in the app, `safetest-claude` only, versioning on. All passed, no fatal exceptions:**
1. Trash then Restore (name free): restored (so the trash listing does carry the original folder).
2. Restore onto a taken name: refused with a message; the item stayed in Trash.
3. Move a file onto a taken name in Filen: refused; the file stayed.
4. Move a phone folder (two files and a hidden file) to Filen: all three arrived with matching sizes, then the phone
   folder was removed. A neighbouring file stayed. Contents were not compared.
Left in the Drive: `safetest-claude` (with `safetest-sub`, `safetest-move`) and, in Trash, nothing known beyond what
was restored or refused above (`safetest.txt`, 4 B, is still in Trash).

**NOT tested on a phone:** save-over (Save, Save again, conflict), another app creating a file, old IDs in Ami Pro or
Stack, Filen-to-phone moves, files added or changed during a move, resume after a kill, leftover temp-file recovery,
offline retry, invalid names, discard dialogs, queued-upload upgrade, camera backup, add to zip. Sign-out: not to be
tested on the owner's account.
