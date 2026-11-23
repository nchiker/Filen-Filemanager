# Second profile on the TEST phone (junk account tests)

Authorised by the owner for the TEST phone only (serial in the private notes), for the account <test account address>. Use only while holding the phone (announce to Stack and AmiPro first). Switching users hides the main profile's screen, so nobody else may run UI or device checks until the profile is removed.

Set `S=<test phone serial>` and `ADB=~/.local/share/filen-android-sdk/platform-tools/adb`.

1. Create: `$ADB -s $S shell pm create-user junk-test` prints "Success: created user id N" (N is probably 10; use what it prints).
2. Install the test build and what a pinned caller needs into that user (no new APK download; the packages are already on the phone for user 0):
   `$ADB -s $S shell pm install-existing --user N app.filenfiles` and `... org.stackdocs.app.checks.linkcontract`.
   If the install-existing is refused, `$ADB -s $S install --user N -r <apk>` with the same signed test build.
3. Skip the new user's setup wizard and switch: `$ADB -s $S shell settings --user N put secure user_setup_complete 1` (if refused, `cmd settings` or finish the wizard by hand), then `$ADB -s $S shell am switch-user N`. Check `dumpsys window | grep mCurrentFocus` shows the launcher of user N.
4. Tests in user N (Filen Files is signed out there):
   a. Create account: open Filen Files, Create a Filen account, email <test account address>, a throwaway password (never printed or stored in files). The confirmation email is sent when "Create account" is tapped; tell the orchestrator then so the owner can click the link. The "Check your email" screen should continue by itself within about 8 s of the click.
   b. Signed-out GRANT_BACKUP_FOLDER: with the account signed out, the backup-folder dialog is started by Stack's checks build (its own instrument/UI path; ask the Stack session). Expect "Sign in to Filen first" > Sign in > Filen's sign-in screen > sign in (or create) > back in the dialog. A shell `am start` shows no_caller, so it cannot stand in for Stack.
5. Clean up: `$ADB -s $S shell am switch-user 0`, then `$ADB -s $S shell pm remove-user N`; confirm `pm list users` shows only user 0. Sign out of nothing in user 0.
6. Pairing (0.4.8, later): main profile = owner (signed in as the owner's account), user N = partner (junk account); build both with `-PpairingEnabled=true`; follow docs/PAIRING_TEST_PLAN.md.
