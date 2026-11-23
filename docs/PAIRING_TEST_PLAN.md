# Two-account test plan: partner pairing, OPEN_SHARED and the change notice (for 0.4.8)

Pairing (`account`, GRANT_RECEIVED_FOLDER, GRANT_SHARE_FOLDER) is built but OFF in 0.4.7. Build the test APK with `-PpairingEnabled=true` and, for a Stack checks build,
`-PstackTestPackage=<checks package>`. Never release with either flag. Sign for the test phone only with the release key (owner's go-ahead needed); never publish the test build.

## What the owner must set up
1. A **second Filen account** (a free one is enough; sharing needs no paid plan). Call it B; the real account is A. Do not use anyone else's account.
2. **Two phones, or one phone with a second user/profile**, each with Filen Files (test build) and Stack (checks build), both online. The TEST phone is A. B can be a second Android user (Settings > System > Users) or an emulator/second phone.
3. Sign in to A on one, B on the other. B must not be a contact of A yet (remove the contact on both sides first so the request path is tested).
4. The Stack session confirms its side sends `PAIR_NONCE` (32 hex) and passes the same value to both actions.

## Steps (expected result in brackets)
**Pairing handshake**
1. A: Stack pairing screen shows the QR. Stack calls `account` [Filen returns A's email; with the flag off it returns `unsupported`].
2. B: scan the QR; Stack starts GRANT_RECEIVED_FOLDER [dialog names A's email in full; Allow tap required; nothing is sent before it].
3. B: Allow [contact request goes to A; dialog changes to "Waiting for A to finish on their phone…" and shows "Your code: NNNN"].
4. A: tap Continue; Stack starts GRANT_SHARE_FOLDER [dialog "Share Filen Drive › Stack shared with B?" with the code beside B's email; the same NNNN as B's screen].
5. A: Allow [request accepted, "Stack shared" created or reused and shared with B; RW tree grant returned to Stack].
6. B: [waiting dialog finishes by itself; read-only tree grant returned; Stack can list the folder, cannot create/delete/rename in it].
**Code check (security)**
7. Repeat 1-4 with a third account C that also sends A a contact request [A's dialog lists B and C with different codes; picking the one that matches B's screen; C gets nothing].
8. Repeat with a wrong nonce on A's side [codes differ; A cancels; nothing shared].
9. Cancel at each dialog and let the 5-minute wait time out [RESULT_CANCELED with user_cancelled / waiting_for_owner; no share created; retry works without a new scan].
10. Call both actions from an app that is not Stack [no_caller, no dialog].
11. App lock on and locked [unlock screen first; same flow after].
**Write access on the shared tree (A, Stack)**
12. Stack writes "Stack shared.stackshare" by temp name, delete old, rename [works; the pending id resolves after upload].
**OPEN_SHARED**
13. B: Stack starts OPEN_SHARED with a file uuid inside the received folder [file opens in the Filen viewer]; with a uuid that A removed [snackbar "No longer shared with you"]; signed out [snackbar asking to sign in].
**Change notice**
14. B with Filen running: A adds, renames and deletes a file in "Stack shared" [within ~5-10 s B's Filen sends SHARED_CHANGED (TREE_URI extra) to Stack; Stack re-imports; notifyChange refreshes a picker open on the tree].
15. B with Filen closed: change a file as A, wait up to 6 h (or run the worker with `adb shell cmd jobscheduler run -f app.filenfiles <job id>`) [broadcast arrives once; no broadcast when nothing changed; the first run after a grant only records a baseline].
16. Revoke B's grant in Stack [fingerprint entry is dropped; no more broadcasts].
**Stack events with a shared file**
17. A trashes/renames/removes sharing for a file in the shared folder [Stack on A shows moved/removed/unshared; B's copy goes to Recently deleted with "no longer shared" after its next import].

## Clean up
Delete the test shares and the "Stack shared" folder from A, remove the contact on both accounts, remove the test grants in Stack, sign B out.

## Gate for enabling pairing in 0.4.8
All steps pass; the higher-tier security review of PairingActivities.kt and SharedWatcher.kt has no open finding; the owner gives the release go-ahead.
