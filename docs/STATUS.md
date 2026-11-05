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

## Not verified
Writable received shares; outgoing shares (test account has none); the upload file picker flow (seeded via hook).

## Roadmap (in order)
1. ~~Rename / move / delete~~ — DONE (Operations.kt, `WritableStorage` in :core; Filen trash has Undo, local delete is
   permanent with confirmation; received shares read-only). Device-tested.
2. ~~Image thumbnails in lists/grids~~ — DONE. `StorageProvider.thumbnail()` (default null); Filen uses SDK
   `makeThumbnailInMemory` (WebP, cached in `cacheDir/thumbs/<scheme>/<id>-<size>-<px>.webp`); local images are decoded by
   Coil at thumbnail size. UI: `ItemThumb` in Components.kt (list 44dp, grid 96dp; falls back to type icon).
   Device-tested (Filen + local, list + grid, no errors). Debug build showed ~30-38% janky frames while fast-scrolling a
   big Camera folder (UI thread; debug exaggerates) — re-check on a release build; if still bad, add a size-bounded
   thumbnail request queue / `remember` the resolved path per item.
3. Android DocumentsProvider so other apps can browse Filen. Filen's reference
   (`external/filen-android-documents-provider`, in `filen-android-documents-provider`) uses a *different* native lib
   (`filen-mobile-native-cache`, personal drive only, own TLS init class `FilenProviderTls`) → needs separate
   packaging or a provider written against `filen-sdk-rs`.
4. Trash screen (browse/restore/empty), server-side favorites write-back (`setFavorite`), outgoing-share management, x86_64 build for emulators.
5. Known UX nits: storage shortcut row cut at right edge on Home; large shared folders take 3–12 s (SDK time).
