# LINE prototype validation

Date: 2026-11-08. Worktree: `scratchpad/line`, branch `feat/line-messaging`.
No pull request or merge is authorized in the current session.

## Observed checks

- Native Go tests: 14 tests cover exclusive session ownership, recovery of corrupt
  local sessions, ambiguous-send replay after reopening, cancellation of an active
  HTTP request, no plaintext send on group-key failure, private snapshot fields,
  removal of an unsent message from only the matching conversation, and email/PIN
  approval. The email tests simulate a phone PIN challenge, cancellation, an
  unsealed account and an approval that fails to return an authenticated token.
  Inbox regressions cover modern uppercase 44-character conversation identifiers,
  contact name resolution and the blocked-contact fallback for an empty inbox.
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
sign-in service. That initial form check linked no account and sent no message.

## Authenticated read-only acceptance

The owner subsequently signed in and sent a first media message in the official
LINE app. A device instrumentation test then reconnected the saved session,
retrieved the owner-selected conversation and fetched its history. The owner
confirmed the message was a sticker, photo or voice message. The prototype
represents that unsupported content by its phone-only placeholder; this does not
verify text or media decryption. No test message was sent, and no content or identifiers
were printed or recorded in this document.

This exposed a real inbox bug: the prototype accepted only legacy lowercase,
33-character hexadecimal IDs. LINE returned uppercase-prefixed IDs with 44
characters. The parser now accepts bounded opaque IDs in both formats, preserves
their exact case on the wire and classifies user/group/room prefixes without case
sensitivity. The named contact is resolved through the existing typed API.

The repeatable check takes a unique name fragment selected by the owner:

```sh
adb -s PHONE_SERIAL shell am instrument -w -r \
  -e class com.anezium.rokidbus.plugin.line.LineAccountReadOnlyTest \
  -e conversationLabel OWNER_SELECTED_NAME_FRAGMENT \
  -e requireText false \
  com.anezium.rokidbus.plugin.line.test/androidx.test.runner.AndroidJUnitRunner
```

For a text acceptance check, omit `requireText false`; the default requires a
non-empty readable text message. The test reports only counts, never contents.
Observed media-history result: `OK (1 test)`. It cancels/closes its engine afterwards
and fails rather than selecting an ambiguous contact. It needs the debug target
and the APK produced by `:plugin-line:assembleDebugAndroidTest` installed.

## Not verified

Text and media decryption, outgoing messages, group decrypt/send, incoming live updates, glasses rendering
of this conversation, notification behavior and token expiry remain unverified.
The successful direct-chat lookup/history fetch is not evidence of text decryption,
delivery or sending.
The owner completed the email/PIN login; the read-only test verified that its
saved session reconnects. It did not exercise a new credential/PIN exchange.

Before a release, use a disposable LINE account with registered email/password
and Allow login enabled to complete same-phone PIN approval,
reopen after process death, receive and explicitly send text in a sealed direct
chat and sealed group, confirm unsend behavior, cancel/close pages, and verify
the native connection and foreground service end. Test revoked/expired tokens
and peers/groups without usable sealing keys: preserve drafts and block sending.
Verify that fields remain masked and unsaved, switching between LINE and Nexus
preserves only the bounded approval attempt, and phone notifications keep working.
