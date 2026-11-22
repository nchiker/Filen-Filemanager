# Filen Files — handoff / status

Native Android file manager (Kotlin, Compose, Material 3) on the **official Filen Rust SDK** (`filen-sdk-rs`,
`uniffi` feature). README.md has setup/build; this file has what a new session needs to keep going.

## Handoff summary (2026-11-22, end of session)
- **Released:** v0.4.5 (SDK 0.5.33, search filters, quota bar, offline pinning) and v0.4.6 (public links, in-app updater, explicit ascending/descending sort,
  link-dialog fix) on github.com/nchiker/Filen-Filemanager. `main` is pushed up to the 0.4.6 prep commit; any later docs-only commits are local until
  `tools/publish.sh`. The next release needs **versionCode >= 14**.
- **Test phone** (Pixel 4a, serial in the maintainer's private notes): runs the released 0.4.6 / code 13, real account signed in (never sign out). It is shared with
  the Stack session: ask `stack-00` (SendMessage) for the phone and wait for its "go"; never touch Stack's packages or the production phone; tell it when done.
  Device runs: use a cheaper subagent, check the foreground package before every tap, never press Back from Home (one run once strayed into another app).
- **Left in the owner's Drive:** folder "Filen Files link test (safe to delete)" (owner deletes it); public links need a paid Filen plan (the account has one now).
- **Not device-tested:** the sort menu change; folder public links; password-protected link opened in a browser; a failed updater signature check / dropped download;
  sign-out wipe of pins; the 12 h periodic pin sync; multi-GB pins; unpin while offline.
- **Possible next items:** updater also comparing versionCode (it only compares the name), built-in video/audio/text viewers, archive browser on `ArchiveReader`,
  cleanup upgrades, public-link list/QR niceties. **Parked:** Stack-link slice 5 (the `filen` locator, `moved`/`removed`/`unshared` events, lookup by UUID) until
  the owner says Stack's side is ready. Do not rename `GRANT_BACKUP_FOLDER` or narrow the locked-writer allowance (Stack depends on them).
- **Signing/release rules:** key lives outside the repo (`~/APK-Keys/filen`, see private notes); never push, tag, release or sign without the owner's explicit go-ahead;
  `tools/release.sh --notes "$(cat docs/RELEASE_NOTES_<version>.md)"` publishes.

## Where things are
- Repo: `~/Projects/Filen`, committed inside the enclosing `~/Projects` git repo (only `Filen/` is staged by us;
  sibling projects there are deliberately untracked). Commit with `git add Filen && git commit -- Filen`.
- Modules: `:core` (Model.kt: StorageProvider, WritableStorage, StorageRouter) · `:filen` (FilenSession,
  FilenStorageProvider, `io.filen.net.FilenTls`, generated UniFFI bindings) · `:local` · `:app` (Compose UI).
- Toolchain is per-user, nothing system-wide: `source env.sh` (JDK 21 `~/.local/share/filen-jdk21`, Android SDK
  `~/.local/share/filen-android-sdk` with platform 37 + NDK 29, cargo-ndk in `~/.claude/.cargo/bin`).
- `./build-rust.sh arm64-v8a` builds the SDK (~5 min cold) and regenerates + **patches** the Kotlin bindings
  (`tools/patch_uniffi_kotlin.py`). Generated bindings and `jniLibs` are gitignored; rerun the script on a fresh clone
  (needs `git clone --depth 1 https://github.com/FilenCloudDienste/filen-rs external/filen-rs`).
- `./gradlew :app:assembleDebug` → `app/build/outputs/apk/debug/app-debug.apk`.

## Gotchas learned the hard way
- Android TLS **fails closed** until `FilenTls.init(context)` runs (calls the SDK's JNI `nativeInit`). Already wired in
  `FilenSession`; any new entry point that touches the client must go through the session.
- UniFFI's Kotlin output doesn't compile as emitted (sealed variants shadow top-level types) → the patch script.
- filen-rs `.cargo/config.toml` pins host linker `clang`+`wild`; `build-rust.sh` substitutes the NDK clang + an
  `ld.lld` shim via env vars. Don't edit the upstream checkout.
- Delta callbacks: SDK progress callbacks report **bytes since last call**, not totals.
- Concurrent shared-folder listings make the SDK slower, not faster; the shared-tree search crawl is sequential.
- Shared (received) items are resolved from an in-memory registry in `FilenStorageProvider` (needs the sharing role);
  after process death nested shared folders must be reopened from the Shared tab.
- Item identity schemes: `filen:` personal (`filen:root`), `filen-in:` received shares, `filen-out:` outgoing,
  `local:` local paths. Never mix these namespaces; received shares are read-only in the UI.
- Newer AndroidX/Coil need compileSdk 37 (installed); targetSdk stays 36.

## Verified on device
Sign-in + session restore; Drive and received-share browsing, badges, lock icon; favorites; search (local+Filen+shared);
download/decrypt/open for Filen and received-share files (image/PDF built-in, others Open With); new folder/upload
only offered in personal folders; local storage browsing and viewers.

## Also verified (rename/move/delete)
Local rename/move/delete (+cancel), Filen rename/move/trash/undo, no write entries on received shares.

## Reliability layer (large files, interrupted transfers, network, expiry, temp files)
- **Errors** (`filen/Errors.kt`): `failureKind()` classifies any throwable → AUTH / NETWORK / DISK_FULL / QUOTA / OTHER
  (via `FilenSdkException.kind()`/`serverCode()`); `friendlyOrNull()` gives the user sentence; `retryNetwork{}` retries
  idempotent calls (listDir, downloads) 3× with 1 s/2 s backoff. Uploads are NOT blindly retried (see below).
- **Session expiry**: every SDK failure in `FilenStorageProvider` goes through `io{}` → `session.noteError()`. An
  UNAUTHENTICATED error moves state to `State.Expired(email)`, drops the dead client + stored session, wipes decrypted
  caches; `AppRoot` navigates to the login screen (email prefilled, "session expired" notice). Not testable on a live
  account without invalidating it — only code-reviewed, unit-less. Network errors never expire the session.
- **Downloads** (`materialize`): free-space check first, `.part` file restarted per attempt (SDK has no resume), size
  verified before the rename so a truncated file is never cached, `.part` removed in `finally` (failure/cancel).
- **Uploads** (`transfer/TransferManager`, app-scoped, sequential queue; `TransferService` foreground service
  dataSync keeps the process alive; `ui/TransfersPanel` above the bottom bar): stages the picked Uri into
  `cache/upload/<id>/` (space-checked), shows progress, Cancel, Retry/Dismiss. After a NETWORK failure it waits for a
  validated network (up to 10 min), checks the parent listing for a same-name/same-size file created since the attempt
  started (avoids duplicates when the upload actually landed), then retries (max 4 attempts). The queue lives in memory:
  if Android kills the process mid-upload the queue is lost (staging dir is swept on next start).
- **Temp / decrypted files** (`filen/LocalCache`): `cache/open` (1 GiB LRU cap, 7-day max age), `cache/thumbs`
  (100 MiB cap), `cache/upload`. `sweepAtStartup()` deletes `.part` files and the upload staging dir; `trim()` runs after
  each download/thumbnail; `wipeDecrypted()` runs when the session goes LoggedOut/Expired.
- Device-tested (400 MB uploads, mid-upload network loss → "Waiting for network…" → resumed with attempt 2 and no
  duplicate, cancel, interrupted/killed downloads). Bugs found and fixed: foreground-service start/stop race crash
  (the service now stops itself; the manager never calls stopService), panel hidden behind the system nav bar on
  screens without the bottom bar, "N of M" counter not resetting, POST_NOTIFICATIONS never requested (now asked when
  the first upload is queued), SDK `.filendl` scratch files not swept (now treated like `.part`).
  Not exercised: the "Couldn't upload" failure card (Retry/Dismiss) — needs a non-network failure.
- Image viewer decodes at view size via Coil; PDF page bitmaps are height-capped (6000 px).

## Documents provider (other apps can browse Filen)
- `docs/FilenDocumentsProvider.kt`, authority `app.filenfiles.documents`, exported behind `MANAGE_DOCUMENTS` (only the
  system picker/Files app can bind it; apps get per-URI grants). Runs in-process on the existing `StorageRouter`/session
  — no second native library was needed (the plan to package Filen's `filen-mobile-native-cache` was dropped).
- Roots: `filen` ("Filen Drive": browse/open/thumbnail/search/create folder/create new files/rename/move/delete→Filen
  trash) and `filen-in` ("Filen – Shared with me": read-only). Outgoing shares (`filen-out`) and local storage are NOT
  exposed. Document ids are our `ItemId` strings; no roots are listed when signed out, and an expired/signed-out session
  raises `AuthenticationRequiredException` (opens the app).
- Providers are created BEFORE `Application.onCreate`, so the provider only touches `FilesApp` lazily and waits up to
  10 s for the background session restore.
- Writes: `createDocument` for a file returns a virtual `filen-pending:<n>` id + temp file under `cache/upload/doc-*`;
  when the writer closes cleanly the file is handed to `TransferManager.enqueueFile` (same queue/panel/retry as the UI;
  the temp file is deleted on DONE/cancel/dismiss, kept for Retry on failure). **Existing files are read-only**: we have
  no verified overwrite-in-place on Filen (same-name upload behaviour unknown), so `FLAG_SUPPORTS_WRITE` is only set on
  pending docs.
- Reads call `materialize()` (full download into `cache/open`) before returning the fd — big files block until fully
  downloaded; `CancellationSignal` cancels the coroutine. `isChildDocument` uses a parent map learned from listings,
  falling back to a folder's breadcrumb trail. Nested received-share folders still can't be resolved cold after process
  death (SDK registry is in memory) → FileNotFound until the user reopens them in the app.
- Child listings call `setNotificationUri`; the app calls `docsChanged(folder)` after uploads and the roots URI on
  sign-in/out. UI-initiated rename/move/delete in the app don't push document-change notifications (pull to refresh).

- Device-tested via the system Files app (browse, open, thumbnails, search, create folder, copy in/out with matching
  md5, rename, move, delete→trash, read-only shared root, picker). Findings fixed: cold-start race (provider ran before
  `Application.onCreate` finished → now `FilesApp.awaitReady()`), and the Filen search-index crawl (~190% CPU for 1-2 min
  after every process start, likely behind a ContentProvider ANR) now starts only from the UI (`MainActivity.onStart` →
  `warmUpSearchIndex()`, 3 s delay) and on demand from search — never for a provider-only process.
  Known: a folder created at the drive root via the picker can need a manual refresh before it shows (the SDK listing
  can lag a just-created item, same as the trash screen); cold folder listings take 3-8 s.

## Not verified
Writable received shares; outgoing shares ; session-expiry flow (can't invalidate a live
account); Empty trash on a populated trash.

## Roadmap (in order)
1. ~~Rename / move / delete~~ — DONE (Operations.kt, `WritableStorage` in :core; Filen trash has Undo, local delete is
   permanent with confirmation; received shares read-only). Device-tested.
2. ~~Image thumbnails in lists/grids~~ — DONE. `StorageProvider.thumbnail()` (default null); Filen uses SDK
   `makeThumbnailInMemory` (WebP, cached in `cacheDir/thumbs/<scheme>/<id>-<size>-<px>.webp`); local images are decoded by
   Coil at thumbnail size. UI: `ItemThumb` in Components.kt (list 44dp, grid 96dp; falls back to type icon).
   Device-tested (Filen + local, list + grid, no errors). Debug build showed ~30-38% janky frames while fast-scrolling a
   big Camera folder (UI thread; debug exaggerates) — re-check on a release build; if still bad, add a size-bounded
   thumbnail request queue / `remember` the resolved path per item.
3. ~~Android DocumentsProvider~~ — DONE, see the "Documents provider" section. (Original notes:) Filen's reference
   (`external/filen-android-documents-provider`, in `filen-android-documents-provider`) uses a *different* native lib
   (`filen-mobile-native-cache`, personal drive only, own TLS init class `FilenProviderTls`) → needs separate
   packaging or a provider written against `filen-sdk-rs`.
4. ~~Trash screen~~ — DONE and device-tested (`TrashStorage` in :core, `TrashScreen.kt`, entry = "Trash" row under
   Files > Filen; restore / delete forever / empty with confirmations). The server's trash listing lags behind
   restore/delete, so handled rows are hidden locally. Empty-trash confirm was deliberately NOT exercised on a live
   account. Remaining: server-side favorites write-back (`setFavorite`), outgoing-share management. Deferred (not needed): x86_64 build for emulators.
5. ~~Search-index crawl~~ — DONE (device results below). The index is persisted (`SearchIndexStore`, `cache/index/search.idx`,
   account-bound, wiped with the other decrypted caches and by `FilenStorageProvider.onSessionEnded()`), loaded lazily, and
   patched in place by our own upload/new-folder/rename/move/trash/restore (entries carry their parent id so a trashed folder
   drops its subtree; restoring a folder marks the index dirty because its subtree returns). A full re-crawl runs only when
   missing, dirty, or past TTL (personal 6 h, shared 24 h); TTL-only refreshes are skipped on metered networks / battery
   saver. Searches answer from the existing index immediately and only wait (≤5 s) when there is none. A failed crawl keeps
   the previous index. The SDK has no change feed, so refreshes are still full crawls.
   Parent ids of shared items are persisted too → `resolveShared()` re-lists the path down to a nested received-share folder
   after a process restart (fixes the old "reopen from the Shared tab" limitation, also for the DocumentsProvider).
6. ~~Server-side favorites~~ — DONE. `FavoriteStorage` (core) implemented by the Filen provider via SDK `setFavorite`
   (personal items only; received shares and local files keep the local Prefs star). `FilesApp.serverFavIds` is the app's
   view (filled from `listFavorites` on sign-in and when the Favorites list loads); toggles apply optimistically, are rolled
   back with a toast on failure, and win over a lagging server listing for 30 s. Legacy local stars on Filen items still
   show and are cleared when toggled. No automatic migration of old local stars to the server.
7. Thumbnail jank mitigation: `ItemThumb` now requests Filen thumbnails through a 3-permit semaphore (cells that scroll away
   cancel while queued) and keeps resolved paths in memory. Still needs a release-build jank measurement.
8. UX: Home storage shortcuts wrap (FlowRow) instead of scrolling off the edge; folder screens show the last in-memory
   listing instantly (`FilesApp.listingCache`, LRU 48, cleared on sign-out) while the fresh one loads.
Device-tested: persisted index (cold crawl once ≈2.5 min, dominated by ~58 shared listings, `index.personalRecursive` <1 s;
warm start loads the snapshot, 0% CPU, no crawl; in-place patching after rename/trash/restore survives restart; nested shared
folders open cold with no error), server-side favorites (file + folder star/unstar persist across restart, not stored in local
prefs), listing cache, Home 3-per-row shortcuts. Scroll jank, release build, cold local Camera folder (~50 images): list
17.8% → 9.8% janky frames after swapping `SubcomposeAsyncImage` for `AsyncImage`; grid measured 6.1% before / 11.8% after
(single runs, noisy; cold 12 MP JPEG decode is the remaining cost). Filen thumbnail scrolling was NOT measurable: the test
account has no large image folder. Unit tests: `./gradlew :filen:testDebugUnitTest` (SearchIndexStore round-trip/corruption).
Release builds need `-x lintVitalAnalyzeRelease -x lintVitalReportRelease -x lintVitalRelease` offline.
9. ~~Outgoing shares~~ — DONE and device-tested with a real contact (test items only, revoked afterwards).
   `SharingStorage` (core) / Filen provider: item menu "Share with a Filen contact…" (personal items; contact picker from
   `getContacts`, confirm dialog; "Add a contact by email" sends a contact request — NOT exercised on device) and
   "Stop sharing…" on top-level items in Shared by me (revokes every recipient via `removeSharedItem`). The SDK returns one
   outgoing entry per recipient, so `listShared(OUT root)` merges them by uuid (recipients joined in `sharedWith`) and
   keeps all underlying roots in `outRoots` for revoke. Shares run on `FilesApp.appScope`: a dialog-scoped launch was
   cancelled when the dialog closed, which silently dropped a folder share (found on device, fixed). Not built: revoking a
   single recipient, per-share write access, public links.

_Provider recheck: cold provider-only start = 0% CPU after the listing; UI start = one ~40 s index burst then idle; search OK._

## Done since the sharing work (all device-tested)
- **Persistent transfer queue** (`TransferManager`): jobs persist in `files/transfers.json`, survive a killed process (the foreground
  service is START_STICKY, the app re-queues on sign-in), a restarted upload checks for a duplicate first. Kinds: UPLOAD, COPY,
  ZIP, UNZIP. Cancelled/finished jobs are not persisted; sign-out clears everything.
- **Save to device / copy / move across storages** (`CopyRunner`): files and whole folders between Filen, received shares and the
  device; name clashes become "name (1)"; a resumed copy skips what already arrived; a move deletes the original only after a
  complete copy. Destination picker = `FolderPickerDialog`.
- **Multi-select + batch actions** (`Selection`, `SelectionBar`): long-press, tap to toggle, Back/navigation clears.
  Copy/Move/Delete (one Undo for the batch), favorites, compress, save to device, share with a contact.
- **Code shrinking (R8)** with keep rules in `app/proguard-rules.pro` (UniFFI/JNA bridge, JNI TLS init, Tink, WorkManager/Room).
  Release APK 51.6 -> ~39 MB. Any new reflection-based dependency needs a smoke launch of a *release* build.
- **Home storage layout toggle** (cards / icons+text / icons / text), persisted.
- **Zip / unzip** (`ArchiveRunner`): works on any storage; extraction rejects unsafe paths (zip slip); a zip that already has one
  top-level folder is not wrapped twice. Password-protected archives are not supported.
- **File version history** (`VersionStorage`): lists earlier versions with restore/delete; the dialog explains when the account has
  versioning switched off and offers to turn it on (confirmation). On the test account versioning is off, so restore/delete of a
  real earlier version has NOT been exercised on a device.
- **Camera backup** (`backup/CameraBackup`, WorkManager): periodic (30 min) + "Back up now"; Wi-Fi only / charging / videos /
  screenshots options; only photos taken after switch-on by default; per-month folders; dedupes by name+size and remembers
  uploaded files; stops and clears on sign-out. Needs All files access.
- **Settings screen**, **app lock** (BiometricPrompt, fingerprint/face with screen-lock fallback; also blocks the documents provider
  while locked), **hide contents in recent apps** (FLAG_SECURE, verified), **update check** (GitHub latest release, daily,
  switchable, notice on Home; never installs anything). The unlock path can't be automated (needs the phone PIN); the owner verified
  enabling and unlocking by hand on their phone with 0.2.0.
- **License** AGPL-3.0-or-later (forced by the AGPL Filen SDK), `NOTICE.md`, **CI** (`.github/workflows/build.yml`, builds the
  Rust SDK + runs unit tests + debug APK; not yet run on GitHub), unit tests in `:core` and `:filen`.

- **Free up space** (`CleanupScreen`, `Dedupe` in :core, `CleanupStorage` on the Local and Filen providers): scans This device (skips
  hidden folders and `Android/`) or Filen Drive and lists duplicate sets and the biggest files (>= 50 MB). Device duplicates: same size
  -> quick SHA-256 of the first+last 64 KB -> full SHA-256; Filen duplicates use the content hash Filen stores in file metadata (no
  download; older files without a hash are counted and skipped). Pre-selects all but one copy per set (keep oldest / newest /
  shortest path), never lets the last copy be ticked, re-checks that at delete time. Device deletes are permanent (confirmation
  says so), Filen deletes go to the Filen trash. Device-tested incl. same-ends/different-middle decoys; 8 unit tests.

- **Cloud storage through Android document providers** (`:saf` module, `SafStorageProvider`, `Origin.EXTERNAL`): "Add cloud storage…"
  on the Files tab opens the system folder picker (Google Drive, Dropbox, OneDrive, Nextcloud...); the chosen tree is kept with a
  persistable permission and appears as a storage (Files, Home shortcuts, destination pickers). Browse, open, thumbnails (when the
  provider offers them), copy/move/save, zip, multi-select, rename, delete (confirmation, no undo) go through the same abstractions as
  Filen/local. Google Docs/Sheets/Slides are virtual documents: shown as read-only exports (PDF by default). No cloud search.
  Device-tested against Google Drive (a throwaway folder; the picker names the root "Drive"): connect/remove/re-add, browse, copy files
  and whole folder trees both ways (md5 identical), name clashes, rename, move, delete, zip/extract, cross-copy with Filen, permission
  surviving a restart. Fixed on the way: a just-created cloud folder wasn't known to accept files (copy/extract of folders failed).
  Unexplained: ONCE a first 3-file copy into a fresh cloud folder reported complete but the files never showed up; it did not recur in
  a fresh-folder retest. `FilenSaf` logcat lines record each upload. Drive reports size 0 right after a write, so the post-upload size
  check only fires for non-zero mismatches.

- **Documents-provider uploads from other apps** (requested by the Stack session; device-tested on the 4a by both sessions: cold background start -> WorkManager fallback, offline listing, process kill with a queued upload, recovery, 60 MB file): if
  `startForegroundService` is refused (provider process started in the background) `TransferManager.startService()` logs a
  `FilenDebug` line and enqueues `TransferResumeWorker` (expedited WorkManager job, `RUN_AS_NON_EXPEDITED_WORK_REQUEST`) that runs
  `transfers.resume()` until the queue is empty; `resume()` at process start does the same for persisted transfers. Provider rows
  for files still on their way to Filen come from the persisted queue (so they survive a process restart, ids are
  `filen-pending:<millis>-<n>`) and carry `COLUMN_SUMMARY` = "Uploading…" / "Waiting for network…" / "Upload failed: <reason>"
  (FAILED rows stay listed until dismissed in the app). `RecentUploads` keeps a receipt of each finished upload for ~2 minutes, so
  a folder listing never has a gap between the pending row disappearing and the real row showing. Offline, `queryChildDocuments`
  answers from the last listing it saw (`FilesApp.listingCache`) plus the queue, so the "Waiting for network…" status is visible. No provider `call()` API
  (MANAGE_DOCUMENTS gates it). With App lock on and locked, the provider refuses other apps (AuthenticationRequiredException).
  **Read while locked (0.4.1, versionCode 7)**: an app the owner allowed (Settings > Security, or the one-time "Let X read while
  locked?" dialog shown after unlocking when that app was refused) can still `queryDocument` / `queryChildDocuments` /
  `openDocument("r")` / `isChildDocument` inside a folder it holds a PERSISTED grant for (`getOutgoingPersistedUriPermissions` +
  `checkUriPermission` on the caller's uid/pid; MANAGE_DOCUMENTS holders = picker/Files app never pass). Everything else stays
  refused while locked (roots empty, search, thumbnails, writes). The question is set in memory by the provider
  (`AppLock.askReader`), never from an Intent. Asked for by the Finance phone viewer (`app.finance.viewer`, `.debug`).
  While locked, `queryRoots` still lists "Filen Drive" (title only, CREATE+IS_CHILD flags, no account/search) so pickers offer it;
  opening it throws AuthenticationRequiredException → the picker's unlock button. The provider asks `AppLock.isLockedForOthers`:
  the lock time also counts while the app is in the background (before 0.4.1 it was only checked on reopen, so other apps saw
  it unlocked in the background), with a 60 s minimum so "Immediately" doesn't re-lock before the owner returns to the picker.
  While locked `queryDocument("filen:root")` still returns the root row: DocumentsUI loads the root document first and only shows
  its unlock ("sign in") button when `queryChildDocuments` throws AuthenticationRequiredException.
  `isChildDocument` walks up the ancestor chain, asking Filen for unknown parents (`FilenStorageProvider.parentOf`), so a file
  several levels below a granted tree resolves after a cold start (found in e2e: grandchild of the tree failed enforceTree).
  The allow covers EVERY persisted grant the app holds: an app that also holds a grant on `filen:root` can read the whole drive.
  E2E with Finance (test) on the Pixel 4a, synthetic folder `FinanceE2E-synthetic`: picker unlock button, pick, Allow dialog and
  allowed locked listings, locked refresh with no unlock, and a cold+locked refresh (fresh process) all verified on 2026-11-11.
  The ancestor walk itself was not strictly exercised (Finance now lists the tree root first, which teaches the parents);
  a client that opens a grandchild cold would exercise it. Synthetic test folder `FinanceE2E-synthetic` is still in the owner's
  Drive (cleanup is the owner's call). Seq-41 "new file appears while locked" step was not run.

## Stack look, zip browsing, add-to-zip, viewer actions (0.4.0, versionCode 6 - shipped in a later release)
- **Stack-like UI**: fixed Stack palette in `ui/Theme.kt` (`StackColors`: white paper / #202124 ink / #1A73E8 blue / #DADCE0 outlines; dark variant
  #17181A; no wallpaper-derived colours), Home header is Stack's raised search pill with settings + account avatar (`StackSearchBar`,
  `AccountAction`), outlined cards, blue-only bottom nav. Scaffold uses `contentWindowInsets = WindowInsets.navigationBars` because every
  screen's own bar already clears the status bar (it used to be doubled). Follows the system light/dark setting (Stack itself is always light).
- **Open zips** (`ui/ArchiveScreen.kt`, route `archive/{id}`): tapping a .zip browses it like a folder (breadcrumbs, back goes up a level);
  a file is unpacked alone into `cache/open/zip-*` and shown (image/PDF built-in, else Open With); toolbar "Extract all" = old Extract flow.
- **Add files to a zip** (`ArchiveRunner.addToZip`, `Kind.ADDZIP` in TransferManager, persisted incl. `prefix`): picked files are staged in
  `cache/upload/<id>/`, a NEW zip is written first, the original is replaced only when that is complete. Device zips: temp file + rename.
  Filen/cloud zips: upload as "<name> (updating).zip", trash/delete the old one, rename the new one back (if the rename fails the updated
  zip stays under the "(updating)" name). Name clashes inside the zip become "a (1).txt". Needs the zip's parent folder:
  `StorageRouter.parentOf` (device: from the path; others: remembered from listings in this session), so a zip reached only through
  search/recents/favorites on Filen has no "Add files" button until its folder has been opened. Not offered on read-only shares.
  Device-tested on a device zip by an agent; the Filen path (upload / trash / rename-back) was tried by the owner on their real account and worked (reported 2026-11-07, no details recorded).
- **Viewer actions**: bottom bar Copy / Move / Delete / Share / Open with (`ViewerScreen`); Move/Delete close the viewer afterwards via
  `Op.onDone` (Delete waits 400 ms so the Undo snackbar isn't cleared by the navigation). Device-tested (copy, move, delete).
- **Update banner fix**: the remembered "update available" answer is now dropped at startup when the installed version has caught up with it
  (it used to linger up to 24 h after installing the update). Device-verified.
- **Follow-up polish (device-tested, local files only)**: settings button is a gear again; Home has a "Tools" section ("Free up space", "Trash") drawn by the same `StorageShortcuts`/`Shortcut`
  component as the storages, so it follows the four-way layout toggle (both also under Settings > Storage); Settings > Appearance > Theme (System / Light / Dark, `Prefs.themeMode`, status-bar icon style follows it;
  default System); round white "+" FAB in writable folders (Upload files / New folder) replaces the top-bar icons; "Add to a zip…" in the file
  menu and in the multi-select menu (`Op.AddToZip` + `ZipPickerDialog`, `TransferManager.enqueueAddItemsToZip`; sources can be any storage's files);
  cards and the search field are outlined/rounded like Stack. Adding to a zip that lives on Filen was then tested by the owner by hand and worked (the permission classifier had blocked an agent from creating the Drive test folder).
- The phone's installed copy is signed with the **debug** key (not the release key): `adb install -r` of the release APK is refused;
  install the debug APK to upgrade in place without losing the login.

## 0.4.0 prep notes
- Zip logic now lives in `:core` (`ZipEdit`: archive listing, add-with-numbering rewrite) with 11 unit tests (`ZipEditTest`); `ArchiveRunner` only handles storage/swap.
- README brought up to date (several "not done" limits there were long fixed). Feature comparison with Google Drive / Files by Google: `docs/ROADMAP.md`.
- Version bumped to 0.4.0 (versionCode 6); NOT yet tagged/released. Release build compiles (R8 on); release APK was not installed (phone has the debug-signed copy).
- Add-to-zip on a Filen-hosted zip: verified by the owner on their real account.

## Ideas not done yet
Built-in video/audio/text viewers, per-recipient revoke / public links,
x86_64 build, camera backup content-trigger (instead of the 30-minute poll).

## Release signing key (never in git)
Releases are signed with a private key kept outside the repository and backed up by the maintainer (see README, "Releasing").
Never commit it; `*.jks` / `keystore.properties` are gitignored. Machine-specific locations and test-device notes live in the
maintainer's private notes file (`docs/PRIVATE_NOTES.md`, gitignored, not published).

## Device testing
Done on a physical Android 13 arm64 phone with a live Filen account. Use cheaper subagents for device runs; follow the
privacy rules in the private notes if present (don't open personal files, no addresses/filenames in reports, never sign out).
The debug-only `DebugUploadReceiver` seeds a Drive folder from a local dir (see README); logcat tags: `FilenPerf`, `FilenDebug`.

## Backup folder for other apps (0.4.2, versionCode 8)
- `GrantFolderActivity`, action `app.filenfiles.action.GRANT_BACKUP_FOLDER` (asked for by Stack, `org.stackdocs.app`): the caller uses
  startActivityForResult, optional `app.filenfiles.extra.FOLDER_NAME` (default "<label> backups"). One dialog with the caller's real
  label/icon (PackageManager via getCallingPackage, never extras); unlock first if the app lock is locked. Allow → top-level Filen
  Drive folder reused by name or created → RESULT_OK with the tree URI + READ|WRITE|PERSISTABLE|PREFIX (the caller takes the
  persistable permission). Cancel → `app.filenfiles.extra.ERROR` = not_signed_in | offline | user_cancelled | no_caller | failed
  (+ `app.filenfiles.extra.MESSAGE`).
- Allow also adds the app to `lockedReaders` + `lockedWriters`: while locked it may list/read and create FILES / delete (Filen trash)
  inside that tree (persisted WRITE grant required). Rename/move/new folder stay refused while locked. One Settings switch per app.
- Uploads from a background provider call fall back to the WorkManager job when Android refuses the foreground service.
- Device-tested 2026-11-11 on the Pixel 4a with Stack: (a) dialog with Stack's real label, owner unlocked + Allow, persistable
  READ|WRITE tree grant taken, 217 MB first backup uploaded; (b) second Allow returned the same folder (no duplicate);
  (c) unattended weekly job with Filen Files cold + locked: Android refused the upload foreground service, the WorkManager
  fallback uploaded the new backup, Stack's pruning deleted an old Stack-auto-* file to the trash, no lock refusals.
- **Stack depends on this** (GRANT_BACKUP_FOLDER + the locked-writer allowance, from 0.4.2 on); Stack falls back to the system
  folder picker when the action is missing. Don't rename the action/extras or narrow the writer allowance without telling Stack.

## Stack look, round 2 (0.4.3, versionCode 9 - shipped in a later release)
Reviewed against Stack 0.13.0 by the Stack session (Ui.java / StackStyle.java). Owner chose to KEEP dark mode (same layout).
- `ui/StackUi.kt`: `Rings` (Stack pastels + soft settings set), `RingBadge`, `ActionRow` (56dp ring badge, 16/13sp, chevron),
  `RowDivider` (#EEEEEE), `TextLink`, `StackSectionHeader` (14sp medium + blue text action), `StackDialog` (8dp, 20sp title),
  `DialogSwitch` / `DialogChoice`, `TightText` (no font padding).
- Settings = six action rows that open small dialogs (App lock, Camera backup, Free up space, Trash, Theme, Update check) +
  "About Filen Files" link. No inline switches on the list.
- Home: search bar 52dp / 8dp corners / #E8EAED outline; 14sp ink headers; "Layout" text action; new default storage style
  RINGS (74dp rings, line icons, 4 per row; applied once to existing users via `ringsDefaultApplied`).
- Theme: letterSpacing 0 everywhere, headlineSmall 20sp (dialog titles), shapes.extraLarge 8dp. Toolbar 56dp / 18sp title.
  Bottom nav: outline icons, 14sp labels, 1dp top divider.

## SDK bump to filen-sdk-rs 0.5.33 (0.4.4, versionCode 10 - shipped in v0.4.5)
- `external/filen-rs` moved from 0.5.21 to 0.5.33 (`eb93d28`, 2026-11-19); `libfilen_sdk_rs.so` 36.5 -> 39.4 MB (arm64). Brings ICO/APNG
  thumbnails (thumbnails are requested for any `image/*` MIME, so nothing app-side gates them) and the webpki-roots/rustls-webpki update.
- The SDK's `archive` feature is a default feature, so `build-rust.sh` already builds it: `ArchiveReader` / `openArchiveReader` and the
  `ListUpdate.reading` field are in the generated Kotlin but NOT used yet (see ROADMAP: archive browser).
- `tools/patch_uniffi_kotlin.py` now also drops the generated `AutoCloseable.close()` override on classes that have their own Rust
  `close()` method (ArchiveReader); without it the bindings fail with CONFLICTING_OVERLOADS.
- Checked: unit tests (:core :filen :app), R8 release build, debug build on the test phone (login kept, browse, search crawl, Filen
  thumbnails written). Seeded a PNG, an ICO and an APNG into the Drive: all three got correct 128px thumbnails (ICO/APNG are new in
  this SDK), upload worked, and the downloaded PNG was md5-identical. The R8 release APK (signed with ~/APK-Keys/filen) was then
  installed on the test phone (uninstalling the debug build first; the phone now carries the release key): sign-in, Drive listing and
  opening a downloaded image work, no reflection/JNA crashes. Release thumbnails were not inspected (release builds aren't debuggable,
  so the cache can't be read with run-as).
- Android's official app only moved to 0.5.32 (0.5.33 was web/desktop); if 0.5.33 misbehaves on a device, 0.5.32 is the known-good fallback.


## Search filters + storage quota bar (released in v0.4.5; device-tested, see below)
- **Search filters**: `core/SearchFilter.kt` (`TypeFilter` any/folders/images/video/audio/documents/archives, `DateFilter` 24 h/week/month/year,
  `SizeFilter` <1 MB / 1-100 MB / >100 MB, `SearchFilters`), applied client-side to the results in `ui/Search.kt` next to the existing
  source chips (All / Filen / Shared / This device). Three dropdown chips + "Clear filters"; selection survives rotation. Unknown
  date/size never matches an active date/size filter; size filters exclude folders; document/archive types fall back to the file extension
  when the MIME is missing. Empty-result text mentions active filters. Unit tests: `SearchFilterTest`.
- **Storage quota bar**: SDK `client.getUserInfo()` has `storageUsed` / `maxStorage` (verified in the generated bindings). New `QuotaStorage`
  + `Quota` in :core (`StorageRouter.quota()`, errors -> null), implemented by `FilenStorageProvider`. Home shows a thin bar with
  "X of Y used on Filen" under the Storage tiles when signed in; hidden until the SDK answers or if it fails; refreshes with `changeCounter`.
  Also shown at the top of Settings. Unit test: `QuotaTest`.
- Checked: `:core:test :filen:testDebugUnitTest :app:assembleDebug` pass on the host. To check on a device: filter chips with real
  results (esp. date/size on Filen vs. local items), bar value vs. the Filen web app, bar after an upload.
- Review follow-up (code-review of 0b4ac61, all fixed, host-tested only): quota bar keeps its last good value across refreshes and hides when the plan size is 0;
  with a filter active the search asks providers for up to 1000 matches instead of 100; archive/document MIME matching is explicit instead of substring;
  0-byte files match "Under 1 MB"; `quota()` / `searchProgressive` no longer swallow CancellationException.

## Offline pinning ("Make available offline") - released in v0.4.5; device results further down
Design reviewed by a stronger agent before building (key points adopted: stable ids, own directory, pin store consulted first, background job).
- **Pure logic** `core/Pins.kt` (+ `PinsTest`): `PinState` (user's choices: direct file keys + pinned folder uuids; plus what is on the device),
  `Pins.reconcile` (wanted remote files vs. copies -> download / local rename / delete / orphaned), `PinStore` (binary state file, atomic write,
  corrupt/other-account reads as empty), `PinStorage` interface (router: `pinStorage()`).
- **I/O** `filen/PinManager.kt`: copies live in `filesDir/pinned/files/<stableUuid>/<name>` (not `cache/`, so the 1 GiB LRU cap / 7-day limit / OS cache
  clearing never touch them; allowBackup is off). Identity = Filen `stableUuid` (survives edits and version restores), stale = current version uuid
  differs from the downloaded one. Folder pins are rules: each sync re-lists the folder recursively, downloads new/changed files and deletes ones that
  left it. Renames are done locally (no re-download). A file deleted/trashed elsewhere keeps its copy ("orphaned") until unpinned. `.part` + size check +
  atomic rename; stray directories nothing refers to are swept. A failed listing changes nothing. Wiped on sign-out/expiry (`onSessionEnded`).
- **Reads**: `FilenStorageProvider.materialize` and `stat` consult the pin store first, so pinned files open with no network (also through the
  DocumentsProvider). `file_paths.xml` exposes `pinned/files/` to the FileProvider (Open With / Share).
- **Sync** `transfer/PinSyncWorker` (WorkManager, deliberately NOT the sequential TransferManager queue): one-time after pin/unpin/"Sync now" (any
  network), periodic every 12 h on unmetered + battery-not-low, scheduled on sign-in, cancelled on sign-out. Retries up to 3x.
- **UI**: item menu "Make available offline…" / "Remove from offline"; multi-select entry; confirm dialog with size (recursive for folders), free space,
  >2 GiB warning and the privacy note (copies are plaintext on the phone, Android storage encryption only, not the app lock); badge on list rows and grid
  cells; Home > Tools > "Offline" screen (built only from the pin store: files, pinned folders, total size, Sync now, last error, unpin).
- **Not done / known limits**: personal drive only (no received shares: they have no stableUuid); unpin deletes at once only what is certainly unwanted (no pinned folder can still reach it, as far as the last sync in this
  process knows), the rest at the next sync, which needs a connection; session expiry wipes all pins (re-pin after login); folder pins don't show the file tree offline (only the flat Offline list); no
  content-hash cross-check; no encryption at rest; offline thumbnails of pinned images come from the pinned file only where Coil loads it directly.
- **To check on a device**: pin a file and a folder, airplane mode, open from Offline and from Files; add/remove a file in the pinned folder on the web
  and Sync now; edit a pinned file on the web (re-download); rename; unpin; sign-out wipes `files/pinned`; Open With on a pinned file.
- Review follow-up (code-review of 225b758, fixed, host-tested only): pin/unpin no longer touch the network (offline pinning used to throw out of
  `appScope`); a pin made before its first sync is recognised (stable key learned from listings, swapped in at sync); sync no longer holds the state lock
  across downloads (pin/unpin never wait; state is mutated atomically per step, a pin made mid-sync is picked up by the queued second pass);
  `wipe()` bumps a generation so a running sync can't write after sign-out; the Offline list has a remove button for directly pinned files and marks
  files that vanished from Filen ("No longer in Filen"); save errors of the state file surface in `lastError`; unused `Pins.withoutNested` removed.
  Known and accepted: nested folder pins list the subtree twice; a pinned folder under a trashed ancestor keeps syncing; rows scan the file list
  linearly (fine for hundreds of files); session expiry wipes the pins (dialog says so).
- **Host checklist tests** (`filen/src/test/.../PinManagerTest`, 16 tests): `PinManager` takes a small `PinRemote` interface (`FilenPinRemote` is the SDK
  implementation) so the device checklist is replayed against a fake Filen with no SDK or phone: pin + open with no network, pin while offline, folder
  follows add/remove/trash, web edit re-download, local rename (no download), unpin offline, unpin vs. a pinned folder, deleted-in-Filen orphan, failed /
  wrong-size / low-space downloads, pin during a running sync, sign-out during a sync, other account, stray sweep, folder size. They do not cover the UI,
  WorkManager scheduling, Open With / FileProvider or real SDK behaviour (those still need the device).
- **Device-tested 2026-11-22** (Pixel 4a test phone, release-signed 0.4.4 built from the working tree and installed over the existing app, login kept;
  a Sonnet agent drove the UI, Stack session coordinated the phone): quota bar on Home and Settings (value not compared with the web app), search
  filter chips (Type: Images, Size: Over 100 MB -> empty state with filters, Clear filters), pin a single file and a folder (dialog with size and privacy
  text, badge within seconds, Home > Tools > Offline lists 4 files / total size / pinned folder), png and pdf open from the Offline screen both online
  and on a COLD START IN AIRPLANE MODE, Open With chooser on a pinned file, multi-select entry, removing the single pin keeps files held by the folder,
  removing the folder pin empties the Offline list and clears the badges, no crashes in logcat, `PinSyncWorker` runs finish with SUCCESS.
  Test data: synthetic files in the Drive folder "Filen Files pin test (safe to delete)" (owner deletes it). The notification permission prompt was answered
  "Don't allow" (change in Android settings if wanted).
  Still NOT tested on a device: sign-out wipe (never signed out), periodic 12 h sync, multi-GB folders, unpin while offline, DocumentsProvider reads of pinned files, quota value vs. the web app, date/size filter results with real data.
- **Web-side changes while pinned, device-tested later the same day** (owner edited/renamed/deleted on the Filen web app, the phone synced with "Sync now"):
  an edit and a rename were followed (the pin kept its place; the copy was re-downloaded with the new text; the Offline row and the Drive row show the
  new name with the pin icon); a file deleted/trashed on the web stayed offline marked "No longer in Filen", still opened, vanished from the folder
  listing, and its X emptied the Offline list. A first agent run strayed into another app on the phone (Back pressed from Home) without opening
  or changing anything; later runs check the foreground package before every tap.

## 0.4.5 prep (versionCode 11) - RELEASED as v0.4.5
Version bumped, README feature list updated, release notes in `docs/RELEASE_NOTES_0.4.5.md`. To release (owner's go-ahead needed):
`tools/release.sh --notes "$(cat docs/RELEASE_NOTES_0.4.5.md)"` (it runs `tools/publish.sh` = pushes main, builds the signed APK, creates GitHub release v0.4.5).

## Public links - released in v0.4.6; device-verified for files (see the evening results)
- `core/PublicLinks.kt`: `LinkExpiry` (SDK presets), `LinkSettings`, `PasswordChange` (Keep / Remove / Set), `LinkInfo`, `LinkStorage`, and `LinkUrl.build`
  (`https://app.filen.io/#/d|f/<uuid>%23<hex of key string>`; golden vectors from the official filen-cli in `LinkUrlTest`).
- Filen provider implements `LinkStorage` (`getFile/DirLinkStatus`, `publicLinkFile/Dir`, `updateFile/DirLink`, `removeFile/DirLink`, `listLinkedItems`).
  File URL key = `DecryptedFileMeta.key`, folder URL key = `DirPublicLinkRw.linkKey`. UNCONFIRMED on a device that these are what the web app expects:
  open one created link in a browser to confirm.
- UI: item menu "Public link…" (personal drive items) -> `LinkDialog` (expiry, optional password, allow downloads, Create/Save, Copy, Remove; default expiry 1 week,
  never "never"); Home > Tools > "Links" lists everything with a link (tap to copy/change/remove). Copy flags the clipboard as sensitive; URLs are never logged.
  Link changes run on `appScope` so closing the dialog doesn't cancel them. A folder link publishes the whole subtree (the dialog says so).
- Not done: multi-select, per-link statistics, showing an existing password (the SDK only returns a hash), expiry in absolute dates.
- **Public links, first device run (2026-11-22, release build, throwaway folder "Filen Files link test (safe to delete)" with one synthetic file):** the menu
  entry and dialog render as designed (the link text is shown, selectable, in the dialog), but the server answered "Please upgrade your account to use this
  feature." as soon as the dialog opened (the link-status call) and on "Create link" - so NO link was created and the URL format is still unverified in a
  browser. Cause not established: either the account's plan has no public links, or a call/option needs a plan the account lacks. To settle it: try
  creating a link for that file in the Filen web app (right-click > Public link). The error text is shown as the SDK/server gave it; logcat has no
  fatal errors and never contains `app.filen.io`. The test folder is still in the owner's Drive.

## In-app updates (ported from the AmiPro app's Updater) - released in v0.4.6; device-verified
- `Updater.kt` (+ `InstallResultReceiver`): the banner/Settings button is now Update -> (download with progress, SHA-256 against GitHub's asset digest when
  given, same package, higher versionCode, same signing certificate) -> Install -> Android's `PackageInstaller` confirmation. First use sends the person
  to Android's "install unknown apps" page for Filen Files. Failures end in a plain message + "Try again" and always say nothing was changed. The daily check
  and the Home banner are unchanged (`UpdateChecker` now also returns asset size, digest and the release notes). Manifest: `REQUEST_INSTALL_PACKAGES` +
  the receiver. A debug build cannot take a release update (the message says so). Play Protect may offer a scan of the update ("Install without
  scanning" uploads nothing).
- To test on a device (needs the owner's go-ahead because it publishes): publish a throwaway release with a higher versionCode, tap Update on the banner,
  confirm, then delete the throwaway release and tag. Not tested: a failed signature check, a dropped connection mid-download.
- **Public links need a paid Filen plan (confirmed 2026-11-22):** the Filen web app gives the same "upgrade your account" answer for this account, so the app's
  message is correct and nothing is broken. The feature stays built but UNVERIFIED (URL key strings not checked in a browser) and should not be released as
  "working" until it has been tried once on a plan that includes links.

## Device results, 2026-11-22 (evening): public links and in-app updater
- **Public links (paid plan now on the account): VERIFIED.** A link made in the app for a synthetic file opens in a private browser window without a Filen
  login (owner checked): the URL format and both keys (`DecryptedFileMeta.key`, `DirPublicLinkRw.linkKey` for folders - folders themselves not exercised)
  are right. On the phone (release build): dialog defaults (1 week, no password, downloads on), Create, URL line, Links screen with expiry, Save with a
  password + 1 day (URL unchanged, lock icon), "Remove the password", Remove link (Links screen empty); logcat clean and never contains `app.filen.io`.
  Not exercised: a folder link, the Copy toast (could not be seen in a dump), opening a password-protected link in a browser, a link with downloads off.
  Fixed afterwards: the dialog stayed busy until the "Public link removed" snackbar timed out.
- **In-app updater: VERIFIED on the phone.** 0.4.5 (code 11) -> throwaway GitHub release v0.4.6 (code 12; public ~3 min, then deleted with its tag; `latest` is
  v0.4.5 again): Settings > Check now and the Home banner both offered it, Update downloaded (progress shown, SHA-256 digest from GitHub), Install opened
  Android's "install unknown apps" page for Filen Files (switched on for the test, reset to default afterwards), "Do you want to update this app?" and a Play
  Protect "App scan recommended" screen ("Install without scanning" is a link under "More details"); login and data kept. Not tested: a failed signature
  check, a dropped connection mid-download.
- **The test phone now runs 0.4.6 (versionCode 12) from the throwaway build, so the next real release must use versionCode >= 13** (Android refuses downgrades;
  main still says 0.4.5 / 11). The Drive test folder "Filen Files link test (safe to delete)" is still in the owner's Drive.
- **Sort menu**: ascending/descending are now explicit menu items (checkmarks) under Name / Date / Size; picking a key keeps the current direction (before, tapping the
  active key flipped the direction and picking another key reset it to ascending). Host build only, not device-tested. Applies to every file list (same `Prefs.sort`).

## 0.4.6 prep (versionCode 13) - RELEASED as v0.4.6
Version bumped (13, because the test phone carries a throwaway 0.4.6 / code 12 and Android refuses downgrades), README + `docs/RELEASE_NOTES_0.4.6.md`.
To release (owner's go-ahead needed): `tools/release.sh --notes "$(cat docs/RELEASE_NOTES_0.4.6.md)"`. The first release that contains the in-app updater is this
one, so phones on <= 0.4.5 still update through the browser once.
