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

## Media and standby follow-up

The settings screen's "LINE is not reachable right now" text was shown for
`offline`, which was also the state of an unopened saved session. A dormant
account now has an explicit `standby` state; `offline` is reserved for an
interrupted active connection. Phone UI inspection of the installed APK found
`Linked · on standby`. This status does not claim that a network request failed.

The media implementation follows Meta Messages' asynchronous conversation
thumbnails: the page returns immediately, downloads run one at a time for the
newest visible messages, and page revisions replace placeholders when ready.
Each image is bounded to 160 pixels and 12 KiB, with at most four images and
32 KiB of decoded images per page. Text and base64 images share a conservative
48 KiB serialization budget. Downloads have a 4 MiB ceiling, including chunked
responses, and use the session's cancellation context. Media keys and locators
stay native; only the media kind enters the snapshot. The cache is bounded and
cleared at session end. Stickers use a static frame, without animation or sound.

Encrypted photos authenticate their HMAC-SHA256 before AES-CTR plaintext is
returned. Native tests include a fixed ciphertext generated independently with
.NET cryptography, corrupted ciphertext/tag rejection, missing-key fail-closed
behavior, snapshot privacy, loaded-message scoping, download size limits and
redirect policy. Observed checks after the media change:

- 20 native Go tests passed; `go vet ./mobile` passed.
- The arm64 AAR was rebuilt using the existing provisioned toolchains.
- 12 Kotlin unit tests passed, including newest-image selection, oversized-image
  fallback and the combined escaped-text/image page budget.
- The APK and read-only instrumentation APK assembled successfully. The earlier
  network-denied Gradle attempt did not recur in the normal-cache elevated run.

Final Gradle output for this implementation:

```text
> Task :plugin-line:packageDebug
> Task :plugin-line:createDebugApkListingFileRedirect UP-TO-DATE
> Task :plugin-line:assembleDebug
BUILD SUCCESSFUL in 5s
114 actionable tasks: 10 executed, 104 up-to-date
```

Only the LINE plugin and its test APK were installed with `adb install -r`;
both returned `Success`. The owner's saved account reconnected, and the read-only
history check with `requireText=false` and `requireMedia=true` reported:

```text
decodedPhotoPreviews=0
decodedStickerPreviews=2
historyMessages=2
mediaMessages=2
readableTextMessages=0
OK (1 test)
```

Both stickers then rendered in the actual glasses conversation page. Private
evidence is `E:/Tools/Rokid/_research/line-device-2026-11-08/line-stickers.png`;
the test output is ignored `scratchpad/line-media-device.log`. No message was sent.
This is live sticker acceptance, not live acceptance of encrypted photos.

## Owner-sent photo acceptance

The owner then sent a photo from the official LINE app to the conversation named
LINE. The same read-only check, selecting that conversation with `requireText=false`
and `requireMedia=true`, reconnected the saved account and reported:

```text
decodedPhotoPreviews=1
decodedStickerPreviews=0
historyMessages=1
mediaMessages=1
readableTextMessages=0
OK (1 test)
```

Opening that conversation on the glasses initially showed `[Photo]`, then the
actual image after the asynchronous download. The settled screenshot is private
`E:/Tools/Rokid/_research/line-device-2026-11-08/line-photo.png`; the count-only
instrumentation output is ignored `scratchpad/line-photo-device.log`. No message
was sent by the test or agent, and no hub was changed. Nexus was closed afterwards.
This verifies live photo retrieval, decoding and rendering. It does not establish
whether this particular photo used Letter Sealing; authenticated photo decryption
is separately covered by the native cryptographic fixture and tamper tests.

## Not verified

Live Letter Sealing decryption of text/photos, outgoing messages, group decrypt/send, incoming live
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

## Device acceptance, 2026-11-08 (`b3147ba2`, debug APK, owner's account)

Run by an Opus 5.5 agent on the shared Samsung and glasses, captures and screen
recordings kept privately under `_research/line-device-2026-11-08/` (never in
the repository). Owner-authorised targets only: the owner's own group "yoyo" and,
for the refusal check, the "LINE" official account. Counts and states only.

Verified:

- Phone keyboard typing into the glasses composer, visible on the lens in ~1.5 s.
- Live sync: a message sent from the official app appears on the open glasses
  conversation in under 2 s.
- Dictation reaches "Glasses mic · listening" and ends in `no_speech` without
  audio; speech recognition itself was not exercised.
- Encrypted text send to the group: one message, arrived once, 0.67 s between
  `/page/action` and `/page/result`; the draft cleared itself.
- Encrypted photo send to the group: capture 4.6 s, Send to conversation back
  7.2 s (plugin 4.9 s), bubble in the official app within 10 s; the photo joins
  the history with its thumbnail. Recorded end to end from the launcher.
- Send to the "LINE" official account is refused (`not_sent`): no Letter Sealing
  key, no plaintext fallback. Expected.
- No plugin or hub error lines, no ANR, across five logcat captures.

Still not verified: a sealed direct chat with another person (text and photo
decryption on their side), reception from another account, unsend behaviour,
token expiry, Keep Memo (not part of the Chrome client's message boxes, so never
listed), and the group checks above with more than one member.
