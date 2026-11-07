# Filen Files — handoff / status

Native Android file manager (Kotlin, Compose, Material 3) on the **official Filen Rust SDK** (`filen-sdk-rs`,
`uniffi` feature). README.md has setup/build; this file has what a new session needs to keep going.

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

## Stack look, zip browsing, add-to-zip, viewer actions (unreleased, after 0.3.1)
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
  **Device-tested on a device zip only. The Filen path (upload/trash/rename) is NOT tested on a real account** (the old test folder is gone).
- **Viewer actions**: bottom bar Copy / Move / Delete / Share / Open with (`ViewerScreen`); Move/Delete close the viewer afterwards via
  `Op.onDone` (Delete waits 400 ms so the Undo snackbar isn't cleared by the navigation). Device-tested (copy, move, delete).
- **Update banner fix**: the remembered "update available" answer is now dropped at startup when the installed version has caught up with it
  (it used to linger up to 24 h after installing the update). Device-verified.
- **Follow-up polish (device-tested, local files only)**: settings button is a gear again; Home has a "Tools" section ("Free up space", "Trash") drawn by the same `StorageShortcuts`/`Shortcut`
  component as the storages, so it follows the four-way layout toggle (both also under Settings > Storage); Settings > Appearance > Theme (System / Light / Dark, `Prefs.themeMode`, status-bar icon style follows it;
  default System); round white "+" FAB in writable folders (Upload files / New folder) replaces the top-bar icons; "Add to a zip…" in the file
  menu and in the multi-select menu (`Op.AddToZip` + `ZipPickerDialog`, `TransferManager.enqueueAddItemsToZip`; sources can be any storage's files);
  cards and the search field are outlined/rounded like Stack. Still NOT exercised on the real Filen account: adding to a zip that lives on Filen
  (the permission classifier blocked the agent's "New folder" tap in the Drive; create a test folder by hand first).
- The phone's installed copy is signed with the **debug** key (not the release key): `adb install -r` of the release APK is refused;
  install the debug APK to upgrade in place without losing the login.

## Ideas not done yet
Built-in video/audio/text viewers, storage cleanup/analyzer, offline pinning, search filters, per-recipient revoke / public links,
x86_64 build, camera backup content-trigger (instead of the 30-minute poll).

## Release signing key (never in git)
Releases are signed with a private key kept outside the repository and backed up by the maintainer (see README, "Releasing").
Never commit it; `*.jks` / `keystore.properties` are gitignored. Machine-specific locations and test-device notes live in the
maintainer's private notes file (`docs/PRIVATE_NOTES.md`, gitignored, not published).

## Device testing
Done on a physical Android 13 arm64 phone with a live Filen account. Use cheaper subagents for device runs; follow the
privacy rules in the private notes if present (don't open personal files, no addresses/filenames in reports, never sign out).
The debug-only `DebugUploadReceiver` seeds a Drive folder from a local dir (see README); logcat tags: `FilenPerf`, `FilenDebug`.
