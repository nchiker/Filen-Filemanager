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

## Device testing (Pixel 4a, Android 13, arm64) — user-provided test phone
- `adb` = `$ANDROID_HOME/platform-tools/adb` (after `source env.sh`). The phone is signed in to the user's REAL Filen
  account: **privacy rules apply** (don't open personal-looking files, don't paste emails/filenames in reports, keep
  screenshots in the scratchpad and delete them; never sign out). Use cheaper subagents (haiku/sonnet) for device runs.
- Debug-only seeding hook `DebugUploadReceiver` (shell-only) uploads a local dir into a new Drive folder; see README.
- A Drive folder "Filen Files test (safe to delete)" holds test files (png/pdf/txt/subfolder) in the user's account.
- `FilenPerf` logcat tag logs SDK call timings; `FilenDebug` is the seeding hook.

## Verified on device
Sign-in + session restore; Drive and received-share browsing, badges, lock icon; favorites; search (local+Filen+shared);
download/decrypt/open for Filen and received-share files (image/PDF built-in, others Open With); new folder/upload
only offered in personal folders; local storage browsing and viewers.

## Also verified (rename/move/delete)
Local rename/move/delete (+cancel), Filen rename/move/trash/undo, no write entries on received shares. Two test folders
were left in the user's Filen account: "Filen Files test (safe to delete)" (still in the Drive) and
"Filen Files ops (safe to delete)" (in Filen trash).

## Reliability layer (large files, interrupted transfers, network, expiry, temp files)
- **Errors** (`filen/Errors.kt`): `failureKind()` classifies any throwable → AUTH / NETWORK / DISK_FULL / QUOTA / OTHER
  (via `FilenSdkException.kind()`/`serverCode()`); `friendlyOrNull()` gives the user sentence; `retryNetwork{}` retries
  idempotent calls (listDir, downloads) 3× with 1 s/2 s backoff. Uploads are NOT blindly retried (see below).
- **Session expiry**: every SDK failure in `FilenStorageProvider` goes through `io{}` → `session.noteError()`. An
  UNAUTHENTICATED error moves state to `State.Expired(email)`, drops the dead client + stored session, wipes decrypted
  caches; `AppRoot` navigates to the login screen (email prefilled, "session expired" notice). Not testable on the real
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
Writable received shares; outgoing shares (test account has none); session-expiry flow (can't invalidate the real
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
   restore/delete, so handled rows are hidden locally. Empty-trash confirm was deliberately NOT exercised on the real
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
Device-tested (Pixel 4a): persisted index (cold crawl once ≈2.5 min, dominated by ~58 shared listings, `index.personalRecursive` <1 s;
warm start loads the snapshot, 0% CPU, no crawl; in-place patching after rename/trash/restore survives restart; nested shared
folders open cold with no error), server-side favorites (file + folder star/unstar persist across restart, not stored in local
prefs), listing cache, Home 3-per-row shortcuts. Scroll jank, release build, cold local Camera folder (~50 images): list
17.8% → 9.8% janky frames after swapping `SubcomposeAsyncImage` for `AsyncImage`; grid measured 6.1% before / 11.8% after
(single runs, noisy; cold 12 MP JPEG decode is the remaining cost). Filen thumbnail scrolling was NOT measurable: the test
account has no large image folder. Unit tests: `./gradlew :filen:testDebugUnitTest` (SearchIndexStore round-trip/corruption).
Release builds need `-x lintVitalAnalyzeRelease -x lintVitalReportRelease -x lintVitalRelease` offline.
9. ~~Outgoing shares~~ — DONE and device-tested with the user's real contact (test items only, revoked afterwards).
   `SharingStorage` (core) / Filen provider: item menu "Share with a Filen contact…" (personal items; contact picker from
   `getContacts`, confirm dialog; "Add a contact by email" sends a contact request — NOT exercised on device) and
   "Stop sharing…" on top-level items in Shared by me (revokes every recipient via `removeSharedItem`). The SDK returns one
   outgoing entry per recipient, so `listShared(OUT root)` merges them by uuid (recipients joined in `sharedWith`) and
   keeps all underlying roots in `outRoots` for revoke. Shares run on `FilesApp.appScope`: a dialog-scoped launch was
   cancelled when the dialog closed, which silently dropped a folder share (found on device, fixed). Not built: revoking a
   single recipient, per-share write access, public links.

_Provider recheck: cold provider-only start = 0% CPU after the listing; UI start = one ~40 s index burst then idle; search OK._
