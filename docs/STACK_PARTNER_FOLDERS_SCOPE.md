# Scope: Filen Files support for Stack partner sharing (Stack slice 7 part S7-5)

Scoped 2026-11-22 by the orchestrating session on the owner's delegation ("best end-user experience").
Source design: ~/Projects/Stack/docs/STACK_LINK_SLICE7_DESIGN.md; UX survey findings: Stack docs/RUNBOOK.md sections 5 and 7.

## Target experience (the bar)
Pairing happens once, in person, with both phones together and online.
- **Owner:** taps "Pair with partner", then taps **one Allow** in Filen. After that, one switch per document.
- **Partner:** scans the owner's QR once, then taps **one Allow** in Filen.
- Nobody types an email address, browses folders, opens Filen's share dialog, or taps Refresh.
- Opening a partner document opens it in Filen. There is no dead end.

Today the flow is 2 folder pickers, 1 manual Filen share, and a dead-end "Open needs Filen Files". This scope removes all of them.

## Facts checked in the Filen code (2026-11-22)
- A Filen share needs the recipient to be an **accepted contact**. The SDK has `sendContactRequest`, `listIncomingContactRequests`, `acceptContactRequest`, `shareDir`.
- Received shares are already a documents root (`filen-in:root`, FilenDocumentsProvider.queryRoots). That answers Stack's "is it pickable": yes, the system-picker fallback can work.
- The SDK has socket events (`addEventListener`/`onEvent`) and `getUserEvents`. PinSyncWorker shows the periodic-WorkManager pattern.
- The provider already calls notifyChange for local edits only, not for remote changes.

## The handshake (contacts are the channel, so no email typing)
1. **Owner phone:** Stack asks Filen `call("account")` on content://app.filenfiles.stacklink (pinned callers only) and gets back `{email}`. Stack puts that email in the pairing QR.
2. **Partner phone:** after the scan, Stack starts `app.filenfiles.action.GRANT_RECEIVED_FOLDER` with extra `app.filenfiles.extra.OWNER_EMAIL`.
   - Filen shows one dialog: "Let Stack read documents <owner email> shares with you?" → Allow.
   - Filen sends a contact request to the owner, or accepts one if it is already pending.
   - The dialog then shows "Waiting for <owner> to finish on their phone…" and watches the socket for the received folder `Stack shared` from that owner.
   - When the folder appears, Filen returns a **read-only** persistable tree grant on it. Cancel and a 5-minute timeout return `RESULT_CANCELED` with error `waiting_for_owner`, and Stack can retry later without a new scan.
3. **Owner phone:** the Stack pairing screen gets a "Partner scanned it → Continue" button. Continue starts `app.filenfiles.action.GRANT_SHARE_FOLDER`. The extra `app.filenfiles.extra.FOLDER_NAME` is optional and defaults to "Stack shared".
   - Filen reads the incoming contact requests. With exactly one recent request, it shows: "Share Filen Drive › Stack shared with <partner email>? Stack can add and replace files in it." → Allow.
   - With several requests, the same dialog has a radio list. With none, it shows "Waiting for your partner to scan…" and watches the socket.
   - On Allow, Filen accepts the request, creates or reuses the folder, `shareDir`s it to the partner, and returns a READ|WRITE|PERSISTABLE|PREFIX tree grant, the same shape as GRANT_BACKUP_FOLDER.
4. The partner's waiting dialog sees the share arrive and returns its grant. Pairing is done.

Rules: the caller is identified by getCallingPackage plus the existing pin, never by extras. The Allow dialog always shows the other person's email in full. Nothing is shared without that Allow. If the app lock is on, the user unlocks first. Do not change GRANT_BACKUP_FOLDER or the locked-writer allowance. The shared tree must support createDocument, write, deleteDocument and renameDocument (Stack writes by delete-then-rename). The received tree needs read access only.

Error extras reuse GRANT_BACKUP_FOLDER's: not_signed_in | offline | user_cancelled | no_caller | failed, plus waiting_for_owner and waiting_for_partner.

## Open a partner document
`app.filenfiles.action.OPEN_SHARED`, extras `app.filenfiles.extra.UUID` (required) and `app.filenfiles.extra.PATH` (hint). These are **Stack's guessed names, adopted as-is**, so FilenOpen.java needs no change. Filen opens the file in the Shared-with-me view. If the file is gone, it shows "No longer shared by <owner>".

## Change notice (partner side)
- While Filen runs (socket connected): on a received-share change under a granted tree, call `notifyChange` on that tree's child-documents URI. Also send an explicit broadcast `app.filenfiles.action.SHARED_CHANGED` (extra `app.filenfiles.extra.TREE_URI`, a String holding the tree URI; confirmed by Filen 2026-11-22) to the granted package only.
- In the background: a periodic worker, about every 6 hours and network-required, using the PinSyncWorker pattern. It checks each granted received tree's modified time and sends the same broadcast when it changes. Don't promise instant delivery while Filen is not running. Stack's own import at app start and on return to the foreground stays in place.

## Build order (Filen session)
0. (Already in progress) the pinned-caller locate/lookup check with Stack's helper. It unblocks AmiPro.
1. OPEN_SHARED. It is the smallest piece and removes the dead end.
2. `call("account")`, GRANT_RECEIVED_FOLDER and GRANT_SHARE_FOLDER, built together because together they are the pairing.
3. The change notice: notifyChange and broadcast first, then the worker.

Stack session: add the owner email to the QR and the "Continue" step, call the new actions, and keep the system picker as the fallback when Filen Files is missing or too old (version check on the action).

## Verification limits
- One phone and one account cover parts 1 and 3 and the owner half of 2.
- The full handshake needs **two Filen accounts on two phones or profiles**. That is the existing S7-6 item, and the owner has to supply the second account.
- Security-sensitive (it shares data with another account): include it in the higher-tier review Stack has already asked for.
- Shipping it needs a new Filen Files release, and only the owner can give the go-ahead for that.
