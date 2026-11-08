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

## Glasses page-opening investigation

The installed plugin at `eff651f6` was tested against the existing 2.2.2 hubs
from `feat/glasses-larger-text` (`a0898426`). Neither hub was reinstalled or
restarted, and no application data, pairing or Bluetooth settings were changed.

The reported Opening-to-Rokid-home failure was not reproduced using the actual
touchpad path: triple tap, Applications, LINE Messages, then the owner-selected
conversation. A loading page was followed by the conversation list and a working
conversation page. Returning through Applications to Meta Messages also opened
its service list; returning to LINE opened its list again. No message was sent.

Simultaneous phone and glasses logcat captures establish the initial sequence
(device log clock, 2026-11-08):

| Event | Time |
| --- | --- |
| Phone receives `/page/request` | 14:45:23.356 |
| LINE registration approved | 14:45:23.850 |
| Phone sends first `/page/response`, result 0 | 14:45:24.922 |
| Glasses receive first response and send visibility | 14:45:25.188 / .193 |
| Glasses receive loaded-list updates | 14:45:29.586–29.808 |
| Conversation requested / first response received on glasses | 14:48:10.598 / .696 |

The provider responded before its deadline, then published the loaded data.
No plugin exception or permission rejection appeared in the captured tags.
The existing provider already schedules engine work off the page callback and
returns a live page immediately; no production change was supported by this run.

Earlier automated attempts to inspect the glasses with `uiautomator dump`
coincided with `AccessibilityService destroyed` (14:39:59.979 and 14:43:33.329),
followed by reconnection roughly two seconds later. Suppression of Nexus's
accessibility service removes its launcher overlay, explaining the return home
seen during those attempts. This identifies a test-method fault; it does not
establish the cause of any separate failure without hierarchy inspection.

For future device checks, navigate with the physical touchpad or the established
low-level input helper and use `adb shell screencap -p` plus `adb pull` for visual
evidence. **Do not run `uiautomator dump` on the glasses** while validating Nexus
launcher pages. It changes the accessibility state being tested. Phone hierarchy
inspection is a separate operation.

Private evidence remains outside Git in the owner-requested directory
`E:/Tools/Rokid/_research/line-device-2026-11-08/`: `phone.log`, `glasses.log`,
`line-list.png` and `line-conversation.png`. Screenshots show the list and the
selected conversation with unsupported-media placeholders; they do not verify
media decoding. The glasses were returned to native Rokid home with Nexus closed.

The requested Gradle command was attempted again in this investigation:

```text
./gradlew.bat :plugin-line:testDebugUnitTest :plugin-line:assembleDebug -PskipCxrGlobal=true
Fetching distribution.
Downloading https://services.gradle.org/distributions/gradle-9.5.1-bin.zip

Attempt 1/1 failed. Reason: Permission denied: getsockopt
```

The command exited 1 before compilation or tests. Its actual final stack lines:

```text
    at org.gradle.wrapper.Install.createDist(SourceFile:24)
    at org.gradle.wrapper.GradleWrapperMain.lambda$prepareWrapper$0(SourceFile:2)
    at org.gradle.wrapper.GradleWrapperMain.main(SourceFile:2)
```

The complete output is in ignored `scratchpad/line-gradle-2026-11-08.log`.
The earlier successful suite above remains a previous observation; this attempt
does not constitute another passing build. No build configuration was changed.

## Not verified

Text and media decryption, outgoing messages, group decrypt/send, incoming live
updates, notification behavior and token expiry remain unverified.
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
