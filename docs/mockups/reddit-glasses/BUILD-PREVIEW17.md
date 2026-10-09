# Approved Ledger + inline media: Android preview17

Date: 2026-10-08. The user approved the final Cloud HTML and authorized the native
Android patch build. This is a local, unpublished debug preview of the official
Reddit 2026.14.0 / 2614001 APK. It is installed on the connected glasses through
an in-place update with the existing debug certificate; app data was retained.

## Resulting interaction

- The compact Ledger feed selects posts directly. The selected row shows a static
  media thumbnail; other rows show short titles, community, media type and comment
  count. Tap opens the post directly.
- Inside a post, swipes page the text first. The last page includes the inline
  media preview if it fits, or uses a dedicated preview page. One additional
  swipe opens full-screen media. Tap retains the post/comment action menus.
- Back restores the exact preview page and the originating feed selection and
  scroll. Full-screen swipes advance gallery items or seek video by 10 seconds;
  they never exit the media screen. Regular video opens paused.
- Images, video, thumbnails and system emoji use green monochrome rendering on
  black. Video has explicit paused/playing, time and sound labels. An attached
  playback clock refreshes once per second and stops on pause/detach/close.
- Post and comment replies retain immutable native target IDs internally, while
  the HUD displays the author's name and a readable excerpt. Existing Nexus phone
  keyboard, draft, preview and explicit Send behavior is preserved.

Implementation is in `E:/Tools/Rokid/Morphe-Patches-restartfix`. Nexus embeds the
prepared Reddit bundle with a new exact source pin. No WebView was introduced.
Per-scope feed state and pending-request invalidation protect navigation from
stale asynchronous results; switching accounts clears account-dependent feed
state while retaining drafts under their original account and parent.

## Build and artifact identity

| Item | SHA-256 |
| --- | --- |
| Official complete input APKM | `1f6a8939589fb88205a857fb1efd00b7fa9304347ee7ced50d4add10f342da3b` |
| Frozen YouTube source, unchanged | `d2b7de48fe7d58b04027754ad7bbd79f0cdff5b2b61364d652b2f4684185adf7` |
| `reddit-source-preview17.mpp` | `d6db4cbb8a62b333ef9366aa15f63a3ede46d1766ef9ccadc70d8bc480f89cd9` |
| Prepared `reddit.mpp` | `adb7ab30cd50a2b74fbcd849e4f1006279b11f7c1835f81a37e688395a6b5e2c` |
| `reddit-preview17-debug.apk` | `b940cbc049486077fc5325042ba3a1d5a75d495b73b3eb6eed6a0761f000fc47` |

Bundle version: `1.39.1-rokid.3-reddit-preview.17`; unpublished, baseline
`c06102d4b`. APK package remains `com.reddit.frontpage`. The HUD marker verifies
schema 1, version 2026.14.0, versionCode 2614001 and `rokid-reddit-1`. Four native
libraries are present, all arm64-v8a. Signing verification for API 30-32 and
`zipalign -c -P 4 4` passed. The installed APK's SHA-256 matches the final file.

The existing debug certificate digest is
`b6e8f6fa0421f79bcfb20aaae8ccb56e13086f55ddcb8fb5c89b0c4a93867ac5`.
No signing key is included in the deliverables. The companion Patcher debug APK
contains the verified preview17 assets, but was **not installed on the phone**;
the phone's existing release installation and signing state were preserved.

Final observed command results:

```text
Morphe: :patches:test :patches:jar --console=plain
BUILD SUCCESSFUL in 11s
211 actionable tasks: 7 executed, 204 up-to-date

Nexus: :plugin-patcher:testDebugUnitTest :plugin-patcher:assembleDebug
       -PskipCxrGlobal=true, frozen YouTube input, preview17 Reddit source,
       official complete APKM, preserved real-engine APK output
BUILD SUCCESSFUL in 2m 28s
87 actionable tasks: 12 executed, 75 up-to-date
```

Morphe XML: 104 tests, 0 failures/errors/skips. Nexus XML: 141 tests, 0
failures/errors, 11 opt-in skips, **130 executed**. `RealRedditPatchTest` ran
against the actual APKM with 1 test and 0 skips; it patched the stock app using
Rokid Reddit controls, Spoof signature and Hide ads. The bundle preparation
Python suite also passed 7 tests during this work. The YouTube pin and prepared
metadata remained unchanged. Neither SDK/Gradle setup nor `local.properties`
was modified.

## Observed device checks

The final preview17 was tested on the connected 480x640/API 32 glasses using
ADB compatibility keys, not physical R08 input.

- Actual Popular titles, comment counts and selected image/video thumbnails load.
- A paired right/down swipe moves one post, and opening full-screen media does
  not immediately apply the paired alias as a second media gesture.
- The image preview, full-screen image and Back to the original preview work.
- Actual video opens paused at 0:00. Play produces different frames and the
  visible clock advances from 0:00 to 0:03. Pause and a paired seek produce a
  paused 0:15 position; Back restores the post and exact feed selection/bounds.
- Native comments load. Both post and comment drafts open under the retained
  account with the correct readable parent context. No text was entered and Send
  was never selected during these final checks.
- Back from the comment draft restores its comment, comments list, post and exact
  originating feed selection and bounds.
- Home loads a real text post spanning five measured pages. A paired swipe moves
  Page 1 / 5 to Page 2 / 5; Back restores the originating Home row, and returning
  to Popular restores its previous selected row and bounds.
- Final feed and selected emoji-row screenshots contain zero pixels outside the
  green hue check (active pixels above 24; red/blue no greater than 65% of green).
  This is a screenshot check, not an optical or perceptual readability measurement.

Final evidence is in `E:/Tools/Rokid/reddit-patch-lab` and copied into the delivery:
`device-preview17-feed-popular.png`, `device-preview17-feed-video-thumbnail.png`,
`device-preview17-feed-emoji.png`, `device-preview17-post-inline-preview.png`,
`device-preview17-image-fullscreen.png`, `device-preview17-comments.png`,
`device-preview17-video-playing-clock-1.png`,
`device-preview17-video-playing-clock-2.png`,
`device-preview17-video-paused-seek.png`,
`device-preview17-text-reader-first-page.png`, and
`device-preview17-text-reader-second-page.png`.

The preceding preview14-16 checks also exercised actual video playback, pause,
paused seeking, media/menu return, selected-row following and main-menu return.
The earlier preview13 validation documents real native login, Home/Popular,
image/GIF/gallery/video, nested comments and unsent drafts entered with the actual
Nexus phone keyboard. Those historical checks are not a full rerun on preview17.

## Boundaries and remaining verification

Live Send/server success/failure, votes/save, moderation, create-post and secondary
account integrations are unverified; no real Reddit write was attempted. Physical
R08 and full phone-control behavior remain unverified. GIF/gallery playback was
verified in the preceding previews, but not fully rerun after this media redesign.
Default HUD size was exercised; larger-size optics need a physical review.

Cold Popular can still require the existing **Open Popular** native fallback once
to initialize its feed service, then return to the HUD and select Popular again.
Live feed order changes, so device checks selected media by its type instead of
assuming a fixed post index. Transient wake commands were used without changing
device sleep settings. ADB UI dumps can fail while an actively changing video
screen does not become idle; captures are used for playback observation instead.

Previous preview13-16 sources, APKs and evidence were preserved. No commit,
publication, release, build-environment repair or phone APK replacement occurred.
