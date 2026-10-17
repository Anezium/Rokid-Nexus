# Reddit glasses device validation — 2026-10-08

The approved design is implemented as a patch of the official Reddit 2026.14.0
APK, using the native authenticated session and repositories. The phone keyboard
is Nexus' existing system-wide remote IME. This is a local debug preview; no
commit, release, or real Reddit post/comment/vote/save was made.

## Confirmed on the connected glasses

- Native login completed through Nexus' actual phone keyboard. Read-only native
  inspection reported `session-is-logged-in=true` and
  `session-is-token-invalid=false`; the auth activity closed. The session survived
  the preview10 and preview11 replacement installs.
- Home and Popular loaded actual native feed results. Repository `G` returns a
  success-wrapped Listing, whose children are resolved in feed order. Promoted
  feed units are excluded before link resolution, retaining the native ad filter
  context that a resolved link alone can lose.
- Images rendered on the 480×640 black HUD, including actual portrait and cat
  images. An image in preview11 was exercised after an APK replacement.
- An actual Popular video prepared with a 32,000 ms duration. Its engine position
  advanced from 3,413 to 6,994 ms, and the visible frames changed. Forward 15
  seconds reached 15,000 ms. A subsequent menu transition exposed a position-reset
  defect; final regression evidence belongs in the section below.
- A real GIF in a three-item gallery decoded to AnimatedImageDrawable. Two native
  screenshots taken 1.7 seconds apart differed. Pause reported
  `media-animation-running=false`; Play reported it true again. Next/previous
  gallery navigation changed the item index 0 → 1 → 0 and restarted the GIF.
  On preview12, animation-running again changed true → false → true across
  Play/Pause, and a different gallery image at index 1 visibly rendered on the
  black HUD. Those public screenshots are `device-preview12-gif-paused.png`
  and `device-preview12-gallery-image.png` in the lab.
- A real thread loaded 100 comments, with nested depth 2. A comment reply and a
  post reply both accepted a four-character draft from the actual Futo soft
  keyboard through Nexus. Read-only inspection confirmed the draft's post and
  exact parent match the selected native model. Both reached preview; the post
  preview selected Read reply by default, with Send a separate action.

No Send action was activated. Success, failure, rate limits and reconciliation
after a real server-side reply remain unverified on device. Native create-post,
inbox/profile/saved, moderation and vote/save operations are not established by
these checks. Physical R08 hardware was not exercised.

## Final video regression

Preview11 still reset a paused 15,000 ms position to zero when only the media
action menu opened. The native player can release its surface before the parent
view's detach callback, so capturing in that callback or a child detach hook is
too late. Preview12 calls `prepareForDetach()` before the HUD removes MediaView;
it captures the live position, invalidates stale callbacks, releases playback,
and preserves playback intent for reattachment.

The preview12 device regression passed. The paused engine and stored
position were 15,000 ms before the menu; the detached menu retained 15,000 ms;
reattachment again reported 15,000 ms. Playback then reported an advancing engine
at 13,491 ms, and Pause retained 14,639 ms in two consecutive inspections. Native
video seeking uses keyframes; restarting after a seek can land before the
requested timestamp. It no longer resets to zero. The actual current Reddit
process reported zero fatal exceptions in the crash buffer.

Preview13 additionally refreshes the header when switching gallery items and
when a GIF finishes decoding, without detaching the media view. Actual device
screenshots confirm IMAGE 2/3 and GIF 1/3 on return; the animation is running.
The preview13 video smoke check also retained 15,000 ms in the detached action
menu and in the reattached, prepared engine. The final authenticated session
remained valid before task-only cleanup.

Final preview13 source SHA-256:
`ba69e714e61bf2f6b0eddb5fd4d1bb3f786025ba07176e56fc6111137ca302ef`.
The Android-prepared bundle SHA-256 is
`a0799e5f1cad673f8190faaef85b2b90ad43e6ee3685cc0d826bb1a0922762ce`.
The installed debug Reddit APK SHA-256 is
`7a713829cedcd1050442601054500f32b598358a8a185934bda6a991bd46db2d`.
The engine's intermediate test-signed APK SHA-256 is
`ea7493a54ba062af2b83483aa5a1bfc0241c7798d3dfbd624afc82884513c6f4`.
That intermediate artifact is not the installed device APK or a production
Patcher output signed with the phone's own key. The authenticated session also
survived the preview13 update.

## Nexus phone corrections and deployment

The phone source now forwards printable IME sendKeyEvent input, including digits,
and handles composing text and Unicode deletion. An idempotent core bridge start
also repairs remote navigation after enabling a retained hub service. The full
phone suite reported 721 tests, zero failures/errors/skips, and a debug APK built.

Those corrections are not installed over the phone's existing release-signed
hub. Its certificate differs from the local debug certificate. Login on that
release used the installed Samsung keyboard temporarily because Futo's password
digit events were lost; the original Futo IME was restored immediately afterward.
Regular reply text was then verified through Futo. The installed phone Patcher
and its private output signing key were preserved.

A cold Reddit process may first offer Open Home/Open Popular to initialize the
official feed repository. Activating that visible action and reopening the HUD
feed worked. Drafts remain in memory and do not survive process death or an APK
replacement. Native authentication keeps its secure-window protection.

Phone keyboard input is confirmed; direct UP/DOWN/SELECT phone remote presses
did not advance/open the media viewer in an extra preview12 check, including
after recreating the phone hub process. Do not count the accepted phone taps as
successful navigation. Media navigation here used the glasses' ADB compatibility
key events. The installed release's accessibility navigation path needs a
separate device check; this is not evidence of physical R08 support.

## Observed build receipts

The log files live in `E:/Tools/Rokid/reddit-patch-lab` unless stated otherwise.
The YouTube source bundle remains unchanged, SHA-256
`d2b7de48fe7d58b04027754ad7bbd79f0cdff5b2b61364d652b2f4684185adf7`.

```text
morphe-resume-preview13.log
:patches:test :patches:jar
BUILD SUCCESSFUL in 26s
211 actionable tasks: 7 executed, 204 up-to-date

nexus-resume-preview13.log
:plugin-patcher:testDebugUnitTest :plugin-patcher:assembleDebug
-PskipCxrGlobal=true
-PpatchBundleInput=.../patches-1.39.1-rokid.3.mpp
-PredditPatchBundleInput=.../reddit-source-preview13.mpp
-PredditStockApkm=.../reddit-2026.14.0-2614001.apkm
-PredditTestOutput=.../reddit-preview13-nexus.apk
BUILD SUCCESSFUL in 3m 21s
87 actionable tasks: 12 executed, 75 up-to-date

nexus-full-checks-resume-20261008.log
:phone-hub:testDebugUnitTest :phone-hub:assembleDebug
:plugin-patcher:testDebugUnitTest :plugin-patcher:assembleDebug
(no skipCxrGlobal; no stock-APKM property)
BUILD SUCCESSFUL in 1m 18s
160 actionable tasks: 3 executed, 157 up-to-date
```

The final preview13 Patcher XML contains 141 tests, zero failures/errors, and 11
existing opt-in skips: 130 executed, including the actual complete stock APKM
through Nexus' patch engine. The separate phone/Patcher run had 12 Patcher skips
because its real-APKM test was not selected. The unchanged glasses suite's prior
683 passing tests were not rerun during this continuation.

No local.properties, SDK path, Gradle home, dependency-cache configuration or
production signing material was changed.
