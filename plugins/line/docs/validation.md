# LINE prototype validation

Date: 2026-11-08. Worktree: `scratchpad/line`, branch `feat/line-messaging`.
No pull request or merge is authorized in the current session.

## Observed checks

- Native Go tests: 7 tests cover exclusive session ownership, recovery of corrupt
  local sessions, ambiguous-send replay after reopening, cancellation of an active
  HTTP request, no plaintext send on group-key failure, private snapshot fields,
  and removal of an unsent message from only the matching conversation.
- `go vet ./mobile`: passed.
- `Build-Android.ps1` using the already installed Go 1.27.3 and NDK 27.1.12297006:
  passed; arm64 AAR generated (approximately 6.6 MB). No SDK/cache relocation or
  `local.properties` change was made.
- `:plugin-line:testDebugUnitTest :plugin-line:assembleDebug -PskipCxrGlobal=true`:
  8 Kotlin tests passed, APK assembled. Tests cover opaque page ids, disconnected
  composer authority, typed Send, old hubs, revoked accounts, escaped-text payload
  bounds and queued/ambiguous sends.

Observed Gradle output:

```text
> Task :plugin-line:testDebugUnitTest
> Task :plugin-line:assembleDebug
BUILD SUCCESSFUL in 6s
85 actionable tasks: 13 executed, 72 up-to-date
```

## Android device smoke test

Installed the debug APK on the connected Samsung phone (Android API 36).
`adb install -r` returned `Success`; starting `LineSettingsActivity` returned
`Status: ok`. After the owner unlocked the phone, UI inspection confirmed the
LINE settings title, signed-out status and enabled Link account button.

An explicit Link account tap reached the real LINE QR endpoint, displayed
**Approve the connection in LINE** and a QR image, with a cancellable pairing
service. The window flags included `FLAG_SECURE` (bit `0x2000`). The test then
cancelled pairing; the pairing service stopped. No account was linked and no
message was sent. No credentials, QR image, pairing URL or PIN are included here.

## Not verified

The owner has no LINE account, so account approval, PIN confirmation, persisted
authenticated reconnection, actual direct/group decrypt and send, and phone
notification behavior cannot be verified in this session. The same-phone Open
LINE deep link and scanning the QR on the glasses also remain unverified.
Unit tests and upstream reports do not replace those live checks.

Before a release, use a disposable LINE account to complete QR/PIN approval,
reopen after process death, receive and explicitly send text in a sealed direct
chat and sealed group, confirm unsend behavior, cancel/close pages, and verify
the native connection and foreground service end. Test revoked/expired tokens
and peers/groups without usable sealing keys: preserve drafts and block sending.
Verify QR readability on the glasses and phone notifications while connected.
