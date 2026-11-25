Filen Files 0.4.9

**Please update: this fixes ways files could be lost.**
- Fixed: uploading or saving a new file under a name already in use replaced the old file. The new one is now named "name (1)".
- Fixed: moving between the phone, Filen and other cloud apps could delete originals that were not copied. Only copied, unchanged files are removed now.
- Fixed: moving or restoring a file onto a name already in use is now refused.
- Better: files waiting to upload are kept safer, including through an unexpected sign-out.
- New: other apps can **save over a Filen file** (Save, then Save again). File version history must be on. If the app can't tell that its copy is current, or the file changed elsewhere, you get a separate "conflict" copy. Reopen the file before editing again. Any app you let write to a file can save over it; earlier content stays in version history.
- Updated Filen SDK (0.5.35).

Good to know: a lost connection or restart can leave an extra numbered copy. A file whose name starts with ".filen-upload-" is an unfinished upload; check it before deleting it. Apps from other cloud services keep their (empty) folders after a move.

Install: download Filen-Files.apk on an arm64 Android 8.0+ phone and open it. Updates install over the previous release.
