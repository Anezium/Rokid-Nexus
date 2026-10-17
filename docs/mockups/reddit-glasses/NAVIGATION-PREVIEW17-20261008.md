# Reddit preview17 native navigation recording - 2026-10-08

## Scope and artifacts

The user requested a video of actual Reddit navigation and broad functional checks,
excluding publication of comments or threads. They additionally requested the same
per-app automatic Nexus phone keyboard toggle as YouTube.

The installed Reddit APK remained preview17 throughout these checks (SHA-256
`b940cbc049486077fc5325042ba3a1d5a75d495b73b3eb6eed6a0761f000fc47`). No Android or Morphe
source was changed during this QA task, no APK was replaced, and no Reddit content,
vote, save, join or moderation action was sent. Only the task-created local reply
draft was discarded after an explicit confirmation. Text size was restored to Standard.

- Final edited video: `E:/Tools/Rokid/reddit-patch-lab/navigation-preview17-20261008/reddit-navigation-preview17-20261008.mp4`
- Duration: 596.270 seconds (9 minutes 56 seconds), H.264, 15 fps, no audio.
- Canvas: 480 x 704. The complete native 480 x 640 HUD is retained below a 64-pixel
  review caption band; app pixels are not cropped or reconstructed.
- The video consists of actual Android screenrecord captures with cuts to remove
  waits. Playback was not sped up. Captions describe observed results, including failures.
- The reply-preview account line is masked. Account-identification setup, phone
  screenshots and an all-black recorder attempt are excluded from the final video.
- Sources, timestamps and edit decisions: `chapters.json`, `events.jsonl`, `edit_video.py`
  in the recording directory. Raw captures contain private setup and are not delivery artifacts.

Final video SHA-256: `28b6e1bbe43f7cc1e5d4c83e409eb89791025a886b1bc6ebc5c1c5b011fe7606`.
The entire final video decoded successfully through FFmpeg (exit 0, no decode errors).
FFprobe confirmed the dimensions, codec, frame rate and duration above. Extracted
frames were visually checked for feed selection, animated gallery, redaction and
native-route failure. GIF frames from separate moments show actual animation.

## Observed functional results

| Area | Actual result |
| --- | --- |
| Home and Popular | Feed navigation advances posts; selection follows scrolling and returns to its original bounds. |
| Text reading | Long post reading paginates separately; paired right/down and left/up aliases advance once. Actions and Back preserve the originating reading/feed state. |
| Images | Selected-row preview, inline post preview, full-screen image and Back restoration exercised. |
| Video | Initial pause, Play, Pause, discrete seek, Mute/Unmute state, Replay and Back exercised. Capture is silent and does not establish acoustic output. |
| Gallery and GIF | Two-item gallery opens image 1, advances to animated GIF 2, clamps at its end, returns to image 1 and restores the exact preview/feed row. |
| Comments | Native list, full comment reading, nested parent context, Hide/Show replies and an unsent reply-to-comment draft exercised. |
| Phone keyboard | Actual Futo soft-key taps in Nexus stream the task text into Reddit. An ADB `input text` trial did not forward because it bypasses the IME InputConnection; this is a harness limitation, not the observed soft-key result. |
| Reply draft | Post draft text was previewed and retained after leaving/reopening. Only this task draft was explicitly discarded. Send was never activated. |
| Text size | The same long post has 7 pages at Large and 9 at Extra large; paging exercised and Standard restored. |
| Latest | Feed initialization fails; Open Latest and Retry do not populate the HUD. |
| Communities | Opens a generic native web projection without community content. |
| Search | Nexus soft typing entered a query; the underlying native tree contains it, but the visible HUD returns to the main menu instead of presenting results. This post-query inspection was outside a usable recording; the video shows the search editor only. |
| Inbox | Opens the main Reddit HUD menu instead of the inbox. |
| Profile | Opens the main HUD menu; a white native account-settings coachmark escapes the green layer. |
| Saved | Opens a generic projection with Back/Retour rather than saved content. |
| Native Reddit settings | Opens an incomplete generic web projection rather than usable preferences. |

The initial Search/Inbox assertions looked at hidden native content and misleading
menu labels. They were explicitly retracted after constraining inspection to the
actual HUD subtree. This report records the corrected failures.

Back from the reply reader returned directly to the post instead of returning to
the preview menu. The draft was retained. Some captured menus/draft screens show a
gray native backdrop, and Profile exposes the white coachmark; the monochrome/black
presentation is therefore not fully enforced on all native overlays.

## Automatic phone keyboard

Read-only source inspection found the requested mechanism already implemented in
this task's existing Nexus changes:

- `phone-hub/.../RedditSetupActivity.kt`: **Auto-open phone keyboard**, accessible
  description **Auto-open Reddit keyboard**, bound to the separate Reddit preference.
- `YoutubeKeyboardSettings.kt`: `reddit_auto_keyboard`, disabled by default;
  exact `com.reddit.frontpage` scope and real Android text-field types.
- `PhoneCoreRemoteBridge.kt`: the same session publication and delayed 400 ms
  prompt as YouTube, with another preference check before opening.
- `RemoteKeyboardPrompt.kt`: uses the existing permitted background activity launch
  when the phone is unlocked and Nexus has overlay permission; notification fallback otherwise.

The installed release-signed Nexus APK was inspected without opening or typing in
the user's currently active phone application. It does not contain the Reddit
settings class, preference key or package string. The old local release APK also
lacks these; the fresh debug APK contains them. Thus auto-opening Reddit's keyboard
is **implemented and compiled, but not deployed to this phone**.

The installed Nexus signer differs from the debug signer. No uninstall or data
removal was performed, no private signing material was accessed or extracted, and
no signing/build configuration was altered. A phone update signed through the
normal Nexus release signing process is needed to install this change in place.
The normal release-signing environment variables are not configured in this session.

Built phone artifact:
`E:/Tools/Rokid/reddit-patch-lab/navigation-preview17-20261008/nexus-phone-reddit-autokeyboard-debug.apk`

Size: 24275134 bytes. SHA-256: `20e5f893d92a4ddecd87ce24b2d9b391cee43869b7c2dfb0c7430810e3069126`.

Existing targeted tests ran successfully:

```powershell
.\gradlew.bat :phone-hub:testDebugUnitTest --tests com.anezium.rokidbus.phone.YoutubeKeyboardSettingsTest
```

Fresh JUnit XML reports 8 tests, 0 failures, 0 errors and 0 skipped. Actual tail:

```text
> Task :phone-hub:testDebugUnitTest
BUILD SUCCESSFUL in 12s
82 actionable tasks: 3 executed, 4 from cache, 75 up-to-date
```

Debug build command:

```powershell
.\gradlew.bat :phone-hub:assembleDebug
```

Actual tail:

```text
BUILD SUCCESSFUL in 10s
105 actionable tasks: 3 executed, 102 up-to-date
```

No `skipCxrGlobal` override, SDK/local.properties change, private cache, commit or
publication was used. The earlier immutable preview17 build/export remains untouched.

## Recording chapters

| Start | Demonstration |
| --- | --- |
| 00:00 | Home - parcours du feed |
| 00:35 | Home - lecture et retour |
| 01:01 | Popular - image et plein ecran |
| 01:33 | Commentaires - liste et lecture |
| 01:54 | Commentaires - masquer les reponses |
| 02:09 | Commentaires - reouvrir et brouillon |
| 02:44 | Video - lecture, pause et seek |
| 03:24 | Video - Mute et Unmute |
| 03:58 | Video - Replay et retour |
| 04:32 | Galerie - image et GIF anime |
| 05:30 | Clavier Nexus - saisie et preview |
| 05:57 | Brouillon - retention et suppression locale |
| 06:24 | Lisibilite - texte Large |
| 06:46 | Lisibilite - texte Extra large |
| 07:03 | Latest - route en echec |
| 07:39 | Communities - route incomplete |
| 07:53 | Recherche - champ de saisie |
| 08:11 | Inbox - mauvais ecran |
| 08:35 | Profile - mauvais ecran et popup blanc |
| 09:00 | Saved - route incomplete |
| 09:30 | Preferences Reddit - route incomplete |

## Limits and remaining work

These checks exercise ADB compatibility keys, not the physical R08/ring or optical
readability. Successful native authentication was already present; no new login,
logout or account switch was attempted. Create-post publication and live comment
Send were excluded by the user. Votes/save, join/leave, moderation/report/block,
comments sort/load-more, infinite feed pagination, offline/locked/expired/sensitive
and deliberate network failure states were not exercised in this recording.
The phone remote's other control modes were not fully revalidated.

Home/Popular reading and media paths work in the observed cases, but this is not a
claim that all Reddit functions work. Repairing the visible secondary-route projection
and native popup/backdrop escape remains necessary. The per-app keyboard toggle also
requires deploying a normally signed Nexus phone update and enabling the Reddit toggle.
