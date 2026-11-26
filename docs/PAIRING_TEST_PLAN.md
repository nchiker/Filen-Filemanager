> **SUPERSEDED (2026-11-26).** The pairing flow below (QR, `GRANT_SHARE_FOLDER`, `GRANT_RECEIVED_FOLDER`, `-PpairingEnabled`) was replaced by
> the partner screens and the shared-folder reads of the folder-sharing design: see `~/Projects/Stack/docs/FOLDER_SHARING_INTERFACES.md`
> (I4 F1-F10), `FOLDER_SHARING_DESIGN.md` and `FOLDER_SHARING_PHASE0.md` (device results). The code and the flag are removed. Kept for history only.

# Two-account test plan: partner pairing, OPEN_SHARED and the change notice (for 0.4.8)

Pairing (`account`, GRANT_RECEIVED_FOLDER, GRANT_SHARE_FOLDER) is built but OFF in 0.4.7. Build the test APK with `-PpairingEnabled=true` and, for a Stack checks build,
`-PstackTestPackage=<checks package>`. Never release with either flag. Sign for the test phone only with the release key (owner's go-ahead needed); never publish the test build.

## Accounts and where each one lives
Three free **test** Filen accounts supplied by the owner (their addresses are in the gitignored `docs/PRIVATE_NOTES.md`, never in this public repo, logs or commit messages). The owner's real account is not involved anywhere in this test.

| Role | Account | Where | What it does |
|---|---|---|---|
| A (owner side) | test 1 | **x86_64 emulator** (window on the owner's screen) | shows the pairing QR, receives B's contact request, shares "Stack shared" |
| B (partner side) | test 2 | **TEST phone, second Android user** | scans the QR, waits, receives the read-only grant |
| C (impostor) | test 3 | Filen **web app** in the owner's browser (no device) | only for step 7: sends A a second contact request |

Steps that need C: **7 only** (two pending contact requests, A must pick the one whose code matches B). Everything else is A + B. Send C's request after step 3 and before A taps Continue in step 4.

## Where B lives on the phone (which keeps the owner's sign-in safest)
1. **Second Android user (recommended).** `pm create-user` (see docs/SECOND_PROFILE_TEST.md). Filen Files and Stack keep separate data per user, so user 0 (the owner's sign-in, Stack's and Ami Pro's state) is never touched; the junk profile holds only test 2. Cost: the screen of user 0 is hidden while the profile is in front, so nobody else may run device checks until I switch back. Removing the profile afterwards restores the phone exactly.
2. Sign out of the owner's account on the phone: **not recommended**, it puts the real sign-in (and the offline pins/wipe-on-sign-out behaviour) at risk and the owner would have to log in again.
3. The planned weekend wipe: **not needed** and worse (it also erases Stack's and Ami Pro's test state). If the owner wipes anyway, finish and remove the profile before.
Open point for the Stack session: does Stack's checks build install per user, and can a user-N Stack take part (it must be the partner-side Stack)?

## What the owner must supply or approve (each in my window, not by a peer's message)
- Account creation: the owner types each password and clicks each email link; I never see or store passwords. Create **A** and **B** through the in-app "Create account" (this also tests that feature); **C** in the web app.
- The weekend desk time: the QR is shown on the emulator window and scanned with the phone's camera (ask the Stack session whether Stack also has a paste-code fallback; if so no camera/desk is needed).
- Downloads: a throwaway emulator again (Android 13 x86_64, about 1.4 GB; the one used for the update test was deleted). Keeping an emulator for pairing needs the owner's new word.
- Builds: `-PpairingEnabled=true` and `-PstackTestPackage=<checks package>` test builds, **release-key signed, test only, never published, never committed with the flags**; two APKs: arm64 for the phone, and an **x86_64 build** for the emulator (the Rust SDK has only an arm64 library, so `./build-rust.sh x86_64` (~5 min) is needed or sign-in on the emulator cannot work; the update test never signed in, so it did not need it). Each signing and the pairing flag need the owner's explicit go-ahead.
- Stack: a checks build of Stack that runs on x86_64 (or its own emulator build) and one for user N on the phone; the Stack session confirms it sends `PAIR_NONCE` (32 hex) and passes the same value to both actions.
- B must not be a contact of A before step 1 (remove the contact on both sides first).

## Order
1. Second-profile plan, parts 1-4 (create user, test 2 created in-app, signed-out GRANT_BACKUP_FOLDER); keep the profile.
2. Emulator + A (in-app Create account), x86_64 build, install Stack checks build there.
3. This plan, steps 1-17, then clean up. The pairing drill **replaces** step 6 of the second-profile plan (no separate drill).
4. Release gate below. Remove the profile (`pm remove-user N`) only after the drill and any partner-journey drill, and say so to the owner.
Weekend: the real partner setup (2026-11-28/29) happens after this, with the owner's real accounts and the released build; the test profile and emulator are gone by then.

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
