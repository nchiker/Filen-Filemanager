# Roadmap - feature comparison with Google Drive and Files by Google

Compiled 2026-11-07 (after 0.4.0 work) from Google Help pages, news reports, the Filen docs and the filen-rs README. Google's
Play Store pages did not load, so "what's new" lists were not used and the Android Drive help pages are 3-4 years old. Rows marked
"?" were not verified. Treat this as a starting point, not a spec.

## Where we already match or beat both
- End-to-end encrypted drive with a client-side name index and no analytics; AGPL, no accounts or ads.
- One app for Filen, received/outgoing shares, device storage and any Android cloud provider (Drive, Dropbox, OneDrive, Nextcloud...),
  with cross-storage copy/move of folders.
- DocumentsProvider (Filen inside the system picker and other apps' save dialogs) with queued uploads that survive process death.
- Hash-based duplicate finder on Filen files without downloading them; in-app file version history (Files by Google has none).
- Persistent, resumable transfer queue with duplicate check after network loss; zip browsing and add-to-zip across storages.
- Trash screen, dark/light/system theme, app lock.

## Address soon (high value, feasible)
1. **Offline pinning** ("Available offline", exempt from the 1 GiB LRU cache) - M. Reuse `materialize`; no SDK dependency. Biggest practical gap.
2. **Built-in video/audio and text viewers** (Media3 over the materialised file) - M. Removes the Open With bounce for common types.
3. **Search filters** (type, date, size, source) - S. Works on the existing index.
4. **Public links** (create, copy, revoke) - M. Check whether filen-sdk-rs exposes password/expiry (the TS SDK does); otherwise plain links with a warning.
5. **Storage quota bar** on Home/Settings - S. Needs an SDK account-info call (not verified).
6. **Cleanup upgrades**: junk/cache sizes, old screenshots, downloaded APKs, per-folder size view - M. Local only.

## Roadmap
- One-way scheduled folder sync (upload or download) - L.
- Camera backup via content trigger instead of the 30-minute poll - S.
- Nearby handoff through the Android share sheet / Quick Share - S.
- Home-screen shortcuts and widgets (Upload, Back up now) - S/M.
- Per-recipient revoke and write permission on shares - M (SDK support unverified).
- Localisation (string extraction + a few languages) - M.
- Tablet/large-screen layout and an accessibility audit (TalkBack, font scale) - M.
- Share target for scans handed over from the Stack app - S.
- Also still open from earlier notes: "Add to a zip…" for zips reached only through search (needs a parent lookup), password-protected zips.

## Deliberately skip
- Server-side content/OCR search and server thumbnails: impossible under zero-knowledge (thumbnails are made on the device).
- Safe folder: Filen is already E2E; a second PIN adds little.
- Gemini/AI features and real-time co-editing: unavailable to third parties and at odds with the privacy model.
- Chat and Notes: not in filen-sdk-rs yet; revisit when the SDK adds them.
- Built-in photo editing and scanning: Stack covers scanning.

## Could not verify
Drive Android: sort options, recent searches, app lock, upload resume, widgets beyond scan, quota display, phone-backup path.
Files by Google: search filters, large-screen layout, widgets, Quick Share for folders. Both: accessibility support.
Filen: SDK support for link password/expiry, per-recipient revoke, account quota, a change feed; tablet support of the official apps;
whether this app's login handles 2FA (the login screen does have a 2FA code field).
