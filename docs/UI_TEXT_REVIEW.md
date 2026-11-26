# UI text review (2026-11-22)

Rules: title 6 words or fewer; body at most 2 short sentences (~25 words), one idea; specific button verbs; no internal words (grant, tree, URI, UUID, provider, pinned); warnings state the consequence once; the full partner email and the code match stay on the sharing dialogs.

> **Update 2026-11-26:** the rows marked (pairing) below describe dialogs that were removed (superseded by the folder-sharing design,
> `~/Projects/Stack/docs/FOLDER_SHARING_INTERFACES.md`). Current partner texts (`PartnerActivities.kt`), same rules:
> "Add your partner" / "Accept [name]" / "Send request"; "Let [app] read shared files?"; "Share with [name]?" / "Stop sharing with [name]?";
> "Add a partner first". Rules above still apply to everything else.

| Where | Before | After |
|---|---|---|
| Sign-in dialog (pairing) | Open Filen Files and sign in, then try again from $callerLabel. | Open Filen Files and sign in, then try again. |
| Partner dialog title | Let $it read documents $owner shares with you? | Let $it read $owner's folder? |
| Partner dialog body | $callerLabel can then read the folder “$FOLDER” that $owner shares with you in Filen. It cannot change it, also while Filen Files is locked. | $callerLabel can read “$FOLDER” from $owner, but not change it. |
| Partner waiting | Waiting for $owner to finish on their phone… | Waiting for $owner… |
| Partner code line | ⏎ ⏎ Your code: $it. $owner will see the same code; it must match. | ⏎ ⏎ Your code: $it. It must match $owner's screen. |
| Owner dialog title | Share Filen Drive › $folderName with ${it.email}? | Share “$folderName” with ${it.email}? |
| Owner waiting title | Waiting for your partner to scan… | Waiting for your partner… |
| Owner dialog body | $callerLabel can then add and replace files in that folder, also while Filen Files is locked. ${c.email} will be able to read it. | $callerLabel can edit it; ${c.email} can read it. |
| Owner code line | ⏎ ⏎ Code ${it}: check it matches your partner's screen. If it does not, tap Cancel. | ⏎ ⏎ Code $it must match your partner's screen. If not, tap Cancel. |
| Owner waiting body | Your partner scans the code on their phone; this will continue by itself. | Your partner scans the code on their phone. |
| Several requests | Several people asked to connect. Who is your partner? | Which one is your partner? |
| No account error | No account to connect to | No account to connect |
| Backup: sign-in body | $label needs a Filen account to save backups. Sign in, and you will come straight back here. | $label needs a Filen account. You'll come back here after signing in. |
| Backup: confirm title | Let $label save backups in Filen Drive › $folder? | Let $label back up to Filen? |
| Backup: confirm body | $label can then add and remove files in that folder, also while Filen Files is locked. | $label can add and remove files in “$folder”, even when Filen Files is locked. |
| Install permission body | To install an update, Android needs you to allow Filen Files to install apps. The next screen is Android's setting for that; turn it on, then come back and tap Install again. Android still asks you to confirm each update. | Android needs your OK once. Turn it on, then tap Install again. |
| Install permission button | TextButton({ explain = false; u.install(ctx) }) { Text("Continue") } | TextButton({ explain = false; u.install(ctx) }) { Text("Open settings") } |
| Notification permission body | Uploads keep running in the background and show their progress in a notification. Android will ask whether Filen Files may show notifications. Uploads work either way; without it you just won't see the progress outside the app. | Uploads show their progress in a notification. Android asks first. |
| Check your email body | Filen sent a confirmation link to $email. Open it on this phone or any device; this screen carries on by itself once it is confirmed. | Open the link Filen sent to $email. This continues by itself. |
| Re-ask password | The password is needed to continue after you confirm: | Enter your password to continue. |
| Not confirmed note | Not confirmed yet. Open the link in the email first. | Not confirmed yet. |
| Password label | Password (at least 10 characters) | Password (10+ characters) |
| Password warning | Filen encrypts your files with this password. It cannot be reset: if you forget it, your files cannot be recovered. Write it down somewhere safe. | Filen can't reset this password. If you forget it, your files are lost. |
| Terms link | Filen's terms apply (filen.io/terms) | Filen's terms |
| Mismatch | The passwords do not match. | Passwords don't match. |
| Open shared, signed out | Sign in to Filen to open this document | Sign in to open this |

Unchanged (already short): "Sign in to Filen first", "Check your email", "Create a Filen account", "Show upload progress?", "Allow installing updates", "No longer shared with you", button labels (Allow, Cancel, Not now, Continue, Sign in, I've confirmed, Send again).

Not changed: "Learn more" links were not added; the rare detail (lock-screen behaviour, Android setting steps) was cut instead of hidden.
