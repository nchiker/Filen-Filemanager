# Filen Files

A native Android file manager (Kotlin, Jetpack Compose, Material 3) with [Filen](https://filen.io) as its
primary cloud provider, alongside local Android storage. The goal is a Google Files / Samsung My Files
replacement, not just another Filen client.

## What works

- **Home** – storage shortcuts, favorites, recent files (Filen personal, received shares, local).
- **Files** – Filen Drive and local folders (Downloads, Documents, Pictures, Camera, Music, Movies, internal storage).
  Breadcrumbs, list/grid view, sorting (name / date / size), per-item menu (open, open with, share, favorite, details).
- **Shared** – *Shared with me* and *Shared by me* are separate namespaces (`filen-in:` / `filen-out:`), never merged
  into personal files. Items carry a visible badge ("Shared by …", "Shared with …", read-only lock) in lists,
  search results, recents and favorites. Write permission of a received share is tracked per item.
- **Favorites** – app-level favorites for any location (Filen, shared, local), plus Filen's server-side favorites.
- **Search** – across Filen personal, shared and local files, with scope chips.
- **Viewers** – built-in image (pinch-zoom) and PDF viewers; everything else goes to Android's Open With.
  Filen files are downloaded and decrypted on demand into the app cache (no manual download step).
- Light/dark theme (Material You colours on Android 12+).

## Architecture

```
:core    Model + StorageProvider/StorageRouter interfaces (no Android UI, no Filen)
:filen   Filen engine: official Rust SDK via UniFFI + FilenStorageProvider, session (encrypted at rest)
:local   Local storage provider (java.io.File + MediaStore)
:app     Compose UI, navigation, prefs, viewers
```

The UI only talks to `StorageRouter`; `:filen` and `:local` never depend on each other or on the UI.

### Filen integration path

Filen's own `filen-mobile-native-cache` crate (used by their DocumentsProvider) only covers the personal drive;
it has no shared-with-me support. So this app uses the **official `filen-sdk-rs` crate with its `uniffi`
feature**, which exports the full client (login, listings, `listInSharedRoot`, `listOutShared`,
`listSharedDir`, downloads, favorites) to Kotlin. No crypto or API logic is reimplemented here.

Two upstream-integration details worth knowing:

1. **TLS init.** On Android the SDK fails every TLS handshake closed until the app hands it the JVM via
   `io.filen.net.FilenTls.nativeInit(context)` (see `filen/src/main/kotlin/io/filen/net/FilenTls.kt`).
2. **Generated bindings need a patch.** UniFFI emits sealed-class variants such as
   `AnyFile.File(val v1: File)` that shadow the top-level type and don't compile.
   `tools/patch_uniffi_kotlin.py` fully qualifies those references; `build-rust.sh` runs it automatically.

## Build

Prerequisites (all installed per-user, nothing system-wide):

| Need | Used here |
| --- | --- |
| JDK with `javac` | Temurin 21 (`~/.local/share/filen-jdk21`) |
| Android SDK | platform 37, build-tools 36, NDK 29 (`~/.local/share/filen-android-sdk`) |
| Rust | the nightly pinned by `external/filen-rs/rust-toolchain.toml` + targets `aarch64-linux-android`, `x86_64-linux-android` |
| `cargo-ndk` | `cargo install cargo-ndk` |

```bash
git clone --depth 1 https://github.com/FilenCloudDienste/filen-rs external/filen-rs
source env.sh                  # JAVA_HOME / ANDROID_HOME / PATH (edit paths for your machine)
./build-rust.sh arm64-v8a      # Rust SDK -> filen/src/main/jniLibs + Kotlin bindings (~5 min cold)
./gradlew :app:assembleDebug   # -> app/build/outputs/apk/debug/app-debug.apk
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

`build-rust.sh` substitutes the NDK's clang for the host linker (the upstream `.cargo/config.toml` pins
`clang` + `wild`, which most machines don't have) through environment variables, so the upstream checkout stays
unmodified. Pass `x86_64` as well to build for an emulator.

First launch: sign in to Filen (or "Skip" for local-only) and grant **All files access** when prompted
so local folders are browsable.

## Limitations / not done yet

- Verified on a Pixel 4a (Android 13) with a real account: sign-in and session restore, Filen Drive and received-share
  browsing, badges, favorites, search, local storage, Filen download+decrypt into the image/PDF viewers and Open With,
  upload/new-folder visibility rules (offered in personal folders only, never in received shares). **Not verified:**
  writable received shares and outgoing shares (the test account had none); the upload picker flow itself (uploads were
  seeded through the debug hook below).
- **Writes are limited to your own storage.** Create folder, upload (system picker), rename, move, and delete work in your
  personal Filen drive (delete = Filen trash, with Undo) and on local storage (delete is permanent, with confirmation).
  Received shares are never written to. There is no trash screen yet to browse/restore/empty Filen's trash.
- **Favorites** are stored on the device; toggling them does not write Filen's server-side favorite flag
  (server favorites are shown, read-only).
- **Search** over Filen is a client-side index of names (the SDK exposes no server search). It is built in the
  background right after sign-in and refreshed every 5 minutes. The personal drive indexes in under a second; shared
  trees are crawled one folder at a time (parallel listings made the SDK back off), capped at 3,000 items / 2 minutes,
  taking roughly 45 s on a large share. A search answers within 5 s using whatever is indexed so far, and local results
  appear immediately.
- **Large shared folders are slow to open** (3–12 s for folders with thousands of entries); this is SDK/server time,
  not UI time (timings are logged under the `FilenPerf` tag).
- **Shared folders after a process restart:** nested shared directories are resolved from an in-memory map; if
  the app was killed, re-open the folder from the Shared tab.
- **Android DocumentsProvider** (letting other apps browse Filen) is not integrated. Filen's reference
  provider (`external/filen-android-documents-provider`) is built on `filen-mobile-native-cache`, a separate
  native library with its own TLS init, so it needs its own packaging step.
- **No thumbnails** for images in lists; no media/audio/video built-in viewer (they open via Open With).
- **arm64-v8a only** by default (36 MB native library). The release build type is debug-signed.
- Uses `MANAGE_EXTERNAL_STORAGE`, which Google Play restricts to file managers via a declaration form.
- Local search walks the filesystem (bounded to 60k entries), not MediaStore.

## Testing on a device

Debug builds include `DebugUploadReceiver`, a seeding hook that only the adb shell can call (it requires the `DUMP`
permission and is not in release builds). It creates a folder in the Filen drive root and uploads a local directory into it:

```bash
adb push seed /sdcard/Download/filen-seed
adb shell "am broadcast -n app.filenfiles/.debug.DebugUploadReceiver --es dir /sdcard/Download/filen-seed --es folder 'My test folder'"
adb logcat -s FilenDebug:V      # progress and DONE/FAILED
```
