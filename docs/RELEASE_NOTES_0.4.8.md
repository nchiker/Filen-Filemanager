Filen Files 0.4.8 (DRAFT, pairing OFF; build files are 0.4.8 / code 15)

- New: **Create a Filen account** from the sign-in screen. The app waits while you confirm your email, then continues by itself.
- New: Stack can ask you to pick a backup folder even if Filen is signed out. You sign in, then return to the dialog.
- New: **Folders now tell Stack too.** Moving, trashing or restoring a folder sends a notice for the files inside (up to a limit).
- Better: updates install in one tap. After the download is checked, Android's confirmation opens at once if installing updates is already allowed.
- Better: the update check compares version codes, not just names.
- Better: Filen explains notification and install permissions before Android asks.
- Better: shorter dialog and screen text.
- Not in this release: pairing with a second account stays off until it has been tested with two accounts and reviewed.

Install: download the APK on an arm64 Android 8.0+ phone and open it. Updates install over the previous release.

---
MAINTAINER ONLY, delete this block before `tools/release.sh` (it is not part of the release text). Test status as of 2026-11-23:
- Tested: unit tests and debug build (78 tests, 0 failures); folder trash sends the removed/trashed event for a file two levels down (TEST phone, Stack checks build); the new one-tap / permission-first update flow incl. cancel and retry, nothing in Download, ends at the new version (throwaway emulator, 0.4.6 -> real 0.4.7, not signed in).
- NOT tested on a device: Create account; GRANT_BACKUP_FOLDER while signed out; OPEN_SHARED; change notice; folder MOVE and RESTORE events (only trash was); the permission explanations for notifications; a failed checksum/signature or dropped download in the updater; the version-code update check against a real published 0.4.8; the shorter UI text across all screens.
- Pairing stays OFF (components disabled): not built into this release's behaviour, not tested with two accounts, no security review yet.
