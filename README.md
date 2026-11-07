# Filen Files

[![Build](https://github.com/nchiker/Filen-Filemanager/actions/workflows/build.yml/badge.svg)](https://github.com/nchiker/Filen-Filemanager/actions/workflows/build.yml)
[![Latest release](https://img.shields.io/github/v/release/nchiker/Filen-Filemanager?label=latest%20release)](https://github.com/nchiker/Filen-Filemanager/releases/latest)

## [⬇ Download the APK](https://github.com/nchiker/Filen-Filemanager/releases/latest/download/Filen-Files.apk)

Android 8.0+ (arm64). Open the downloaded file to install it - [install steps](#install) ·
[all releases](https://github.com/nchiker/Filen-Filemanager/releases)

A native Android file manager (Kotlin, Jetpack Compose, Material 3) with [Filen](https://filen.io) as its
primary cloud provider, alongside local Android storage. The goal is a Google Files / Samsung My Files
replacement, not just another Filen client.

## Install

Download the latest APK ([direct link](https://github.com/nchiker/Filen-Filemanager/releases/latest/download/Filen-Files.apk),
or the [Releases page](https://github.com/nchiker/Filen-Filemanager/releases/latest)) and open it on an Android 8.0+ phone (arm64; allow installs from your browser/file manager when asked). Sign in
to Filen, or skip for local-only, and grant **All files access**. Later releases install over the previous one
(they are signed with the same key). The app is not on any store.

## What works

- **Home** – Stack-style search bar, storage shortcuts, a *Tools* section (Free up space, Trash) that follows the same
  four-way layout toggle, favorites, recent files (Filen personal, received shares, local).
- **Files** – Filen Drive and local folders (Downloads, Documents, Pictures, Camera, Music, Movies, internal storage).
  Breadcrumbs, list/grid view, sorting (name / date / size), per-item menu (open, open with, share, favorite, details),
  and a round **+** button for uploading files and creating folders where the folder accepts them.
- **Shared** – *Shared with me* and *Shared by me* are separate namespaces (`filen-in:` / `filen-out:`), never merged
  into personal files. Items carry a visible badge ("Shared by …", "Shared with …", read-only lock) in lists,
  search results, recents and favorites. Write permission of a received share is tracked per item.
- **Favorites** – app-level favorites for any location (Filen, shared, local), plus Filen's server-side favorites.
- **Search** – across Filen personal, shared and local files, with scope chips.
- **Viewers** – built-in image (pinch-zoom) and PDF viewers; everything else goes to Android's Open With.
  Filen files are downloaded and decrypted on demand into the app cache (no manual download step). While a file is
  open, a bottom bar offers **Copy, Move, Delete, Share and Open with**.
- **Zip files** – tapping a `.zip` browses it like a folder (tap a file to unpack and open just that one, *Extract all* in
  the toolbar). **Add files to a zip** from the zip screen (system picker) or with *Add to a zip…* in a file's menu or the
  multi-select menu. The new zip is written completely before it replaces the old one; zips on Filen or cloud storage are
  re-uploaded, the old one is trashed and the new one renamed back. Password-protected zips are not supported.
- **Copy, move and save to device** across Filen, shared and local storage (folders too); **zip / unzip**; **multi-select**
  with batch actions; **file version history**; **share with Filen contacts**; **trash**; an upload/copy queue that survives
  the app being killed.
- **Cloud storage** (Google Drive, Dropbox, OneDrive, Nextcloud...) shown next to Filen and the phone, through Android's own document
  providers - no extra accounts or keys; copy files and folders between any of them.
- **Free up space**: find duplicate and large files on the phone or in Filen Drive.
- **Camera backup** to a Filen folder (Wi-Fi only by default, new photos only, sorted by month).
- **App lock**, hide contents in recent apps, and an optional update check (Settings).
- Same look as the Stack app (white paper, one blue accent, outlined cards). **Settings > Appearance > Theme**: follow the
  system, light or dark.

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

## Other apps and Filen (documents provider)

Filen Drive and "Shared with me" appear in Android's file picker and in other apps' "save to" dialogs, so a backup app can save into Filen.
What to expect when an app writes a file there:

- The file shows up in the folder right away as an in-progress entry whose summary reads "Uploading…", "Waiting for network…" or
  "Upload failed: <reason>". Failed entries stay until dismissed in Filen Files.
- Uploads are kept in a persisted queue, so they survive the app being closed or restarted. If Android refuses a background
  foreground-service (the app was started by another app in the background), an expedited WorkManager job finishes the queue;
  unfinished uploads also resume the next time the app starts.
- A finished upload is shown in the folder listing straight away (a short-lived receipt bridges the delay before Filen's own listing
  shows it). While the phone is offline, listing a folder shows its last known contents plus pending uploads instead of an error.
- To verify an upload from the writing app: list the folder; a row whose id does not start with `filen-pending:` and whose name and
  size match is uploaded; a `filen-pending:` row is still in progress.
- Existing Filen files are read-only through the provider. If **App lock** is on and the app is locked, other apps are refused
  (the app opens to unlock), so unattended scheduled backups need the lock off or the app unlocked.

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

- Verified on a physical Android 13 phone with a live account: sign-in and session restore, Filen Drive and received-share
  browsing, badges, favorites, search, local storage, Filen download+decrypt into the image/PDF viewers and Open With,
  upload/new-folder visibility rules (offered in personal folders only, never in received shares). **Not verified:**
  writable received shares and outgoing shares (the test account had none); the upload picker flow itself (uploads were
  seeded through the debug hook below).
- **Writes are limited to your own storage.** Create folder, upload (system picker), rename, move, and delete work in your
  personal Filen drive (delete = Filen trash, with Undo) and on local storage (delete is permanent, with confirmation).
  Received shares are never written to. Filen's trash has its own screen (restore, delete forever, empty).
- **Favorites** of your own Filen items are written to Filen's server-side favorite flag; received shares and device
  files keep a local star.
- **Search** over Filen is a client-side index of names (the SDK exposes no server search). It is built in the
  background, saved on the device and refreshed after 6 h (personal) / 24 h (shared). The personal drive indexes in under a second; shared
  trees are crawled one folder at a time (parallel listings made the SDK back off), capped at 3,000 items / 2 minutes,
  taking roughly 45 s on a large share. A search answers within 5 s using whatever is indexed so far, and local results
  appear immediately.
- **Large shared folders are slow to open** (3–12 s for folders with thousands of entries); this is SDK/server time,
  not UI time (timings are logged under the `FilenPerf` tag).
- **Android DocumentsProvider:** other apps can browse and open Filen (and add files) through the system picker; existing
  files are read-only through it (no verified overwrite-in-place on Filen).
- **No built-in text, audio or video viewer** (they open via Open With).
- **arm64-v8a only** by default (36 MB native library). Release APKs are signed with the maintainer's private key (see
  "Releasing"); a debug build is signed with the debug key and cannot update a release install (or the other way round).
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

## Releasing (maintainer)

This folder is published on its own from a larger local repo, so releases go through two scripts:

```bash
tools/publish.sh     # commit first; pushes only this folder's history to GitHub main (git subtree split)
tools/release.sh     # builds the signed, shrunk release APK and attaches it to a GitHub Release (v<versionName>)
```

Bump `versionCode`/`versionName` in `app/build.gradle.kts` for every release. Release builds are signed with a
private key that lives **outside the repository**: a `keystore.properties` file (store path, alias, passwords)
whose location is given by `FILEN_KEYSTORE_PROPS`. It must never be committed - `*.jks` and `keystore.properties`
are gitignored - and it must be backed up: if it is lost, users have to uninstall the app before they can update it.

## License

GNU Affero General Public License v3.0 or later - see [LICENSE](LICENSE) and [NOTICE.md](NOTICE.md). The Filen SDK
this app is built on is AGPL-3.0, which is why the app is too.

## Privacy

The app talks to Filen's servers (your files stay end-to-end encrypted) and, optionally, to GitHub once a day to
see whether a newer release exists (Settings > Updates; an anonymous request, switch it off if you prefer). There
is no analytics or crash reporting. Opening recent apps can be blanked, and an app lock (fingerprint, face or screen
lock) can be switched on in Settings.
