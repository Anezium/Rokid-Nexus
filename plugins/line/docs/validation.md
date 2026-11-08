# LINE prototype validation

Date: 2026-11-08. Worktree: `scratchpad/line`, branch `feat/line-messaging`.
No pull request or merge is authorized in the current session.

## Observed checks

- Native Go tests: 11 tests cover exclusive session ownership, recovery of corrupt
  local sessions, ambiguous-send replay after reopening, cancellation of an active
  HTTP request, no plaintext send on group-key failure, private snapshot fields,
  removal of an unsent message from only the matching conversation, and email/PIN
  approval. The email tests simulate a phone PIN challenge, cancellation, an
  unsealed account and an approval that fails to return an authenticated token.
- `go vet ./mobile`: passed.
- `Build-Android.ps1` using the already installed Go 1.27.3 and NDK 27.1.12297006:
  passed; arm64 AAR generated (approximately 6.6 MB). No SDK/cache relocation or
  `local.properties` change was made.
- `:plugin-line:testDebugUnitTest :plugin-line:assembleDebug -PskipCxrGlobal=true`:
  9 Kotlin tests passed, APK assembled. Tests cover opaque page ids, disconnected
  composer authority, typed Send, old hubs, revoked accounts, escaped-text payload
  bounds, queued/ambiguous sends and one-shot credential consumption.

Observed Gradle output:

```text
> Task :plugin-line:testDebugUnitTest
> Task :plugin-line:assembleDebug
BUILD SUCCESSFUL in 8s
85 actionable tasks: 16 executed, 69 up-to-date
```

## Android device smoke test

Installed the debug APK on the connected Samsung phone (Android API 36).
`adb install -r` returned `Success`; starting `LineSettingsActivity` returned
`Status: ok`. After the owner unlocked the phone, UI inspection confirmed the
LINE settings title, signed-out status and the email/password form. UI inspection
of the final installed APK found two credential fields, `password=true` on the
password field, and no QR image. The final
method uses LINE on the same phone for PIN approval; no QR, browser login or LINE
app on the glasses is involved. The credential/PIN window includes `FLAG_SECURE`
(bit `0x2000`). Empty form submission is rejected locally without starting the
sign-in service. No account was linked and no message was sent.

## Not verified

The owner has no LINE account, so real email/password authentication, account
approval, PIN confirmation, persisted
authenticated reconnection, actual direct/group decrypt and send, and phone
notification behavior cannot be verified in this session. Opening the official
LINE app also requires it to be installed on the phone.
Unit tests and upstream reports do not replace those live checks.

Before a release, use a disposable LINE account with registered email/password
and Allow login enabled to complete same-phone PIN approval,
reopen after process death, receive and explicitly send text in a sealed direct
chat and sealed group, confirm unsend behavior, cancel/close pages, and verify
the native connection and foreground service end. Test revoked/expired tokens
and peers/groups without usable sealing keys: preserve drafts and block sending.
Verify that fields remain masked and unsaved, switching between LINE and Nexus
preserves only the bounded approval attempt, and phone notifications keep working.
