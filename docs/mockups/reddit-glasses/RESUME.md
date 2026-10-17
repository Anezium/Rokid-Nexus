## Active continuation: navigation fixes and signed Nexus updates (2026-10-08)

This section supersedes the older preview13/deployment state below.

- User authorized root Back exit, native discovery/account-route repairs, and the same
  automatic Nexus keyboard behavior as YouTube. All HTML directions remain unchanged.
- Installed signed Nexus phone and glasses hubs in place using the existing release
  certificate. Local process-only signing/GitHub auth followed the normal build guidance;
  no machine configuration or signing material was changed.
- Reddit auto-open keyboard preference was enabled through its normal phone switch.
  Automatic Nexus editor opening was observed from phone Home.
- Root Back and explicit Exit were observed returning outside Reddit; nested Back
  remains contextual. Actual launcher screenshots and root-exit recording are in
  the exit-preview18-20261008 lab directory.
- Native adapters now implement post/community search, joined communities and feeds,
  cold Latest, Profile/Saved posts/comments, notification Inbox, and read-only preferences.
  Exact session/account graph validation replaces generic web routes for these screens.
- Preview23 installed and Patcher release updated in place with the existing signer.
  Feed refresh is accessible by a previous swipe from the first post. Default Android
  focus highlights are disabled on HUD controls; true black after editor return was
  verified on-device. Frozen older bundles and APKs are preserved.
- No real Reddit Send, vote, save, join, moderation or create-post write was performed.

﻿# Reddit glasses current checkpoint - 2026-10-08

Work resumed at the user's request. Native login now succeeds. No
commit, release, or real Reddit content/vote/save was created. Keep credentials
out of commands, files and reports.

## Root Back correction pending build

The user reported the MENU -> cached feed Back loop. It was reproduced on preview17.
`RedditController` now has a shared exit for root Back and Exit Reddit, with
idempotent cleanup and `finishAndRemoveTask()`. This is a source-only preview18
candidate: Morphe settings/plugin initialization currently fails before compilation
while configuring GitHub Packages (`gpr.user`/`gpr.key` and corresponding session
GitHub variables are absent). No build-environment repair or credential workaround
was performed. Installed preview17 remains unchanged and still has the bug.
See `ROOT-BACK-FIX-PENDING-BUILD.md` and the diff/evidence in
`E:/Tools/Rokid/reddit-patch-lab/exit-preview18-20261008`.
Do not update the Reddit bundle pin or report preview18 built/installed until a
normal authenticated build and actual device exit checks pass.

## Native preview17 recording and keyboard deployment check

The user requested actual-device navigation video without live publication.
The edited 9m56s recording and corrected functional results are in
`NAVIGATION-PREVIEW17-20261008.md`. Home/Popular reading, image, video, gallery/GIF,
nested comment collapse/expand, actual Nexus soft typing and retained unsent drafts
were exercised. Standard text was restored. No post/comment was sent.
Latest, Communities, Search results, Inbox, Profile, Saved and native settings remain
broken/incomplete in the HUD. Profile also exposes a white native coachmark; some
menus retain a gray native backdrop. Do not report all Reddit routes as working.

The requested separate Reddit auto-keyboard toggle already exists in current Nexus
source and shares YouTube's delayed session prompt. Eight existing targeted tests
pass, and a new phone debug APK is built. The release Nexus installed on the phone
lacks this code; the debug certificate differs, so no replacement/uninstall occurred.
Normal release signing is needed for an in-place phone update. Do not extract keys
or silently install debug over release. The phone APK and video are separate from
the immutable preview17 export, under the navigation recording directory.

## A with inline media - approved, Android preview17 built and installed

The user approved the final Cloud design and authorized building its Android patch
on 2026-10-08. The recommended swipe route is implemented: compact Ledger feed with a
static thumbnail on the selected row; tap opens the post; text pages precede the
final inline media preview; one additional swipe opens full-screen media; tap keeps
the action menu; Back restores the preview page and feed selection/scroll.
Preview17 builds through the actual Nexus engine and is installed in place using
the existing debug certificate. Frozen preview13 and YouTube inputs, native
authentication and exact reply targets were preserved. Final device checks cover
feed thumbnails, inline/full-screen image, video Play/Pause/seek and live clock,
native comments, both empty reply drafts with readable parent context, paired
keys and exact Back restoration. System emoji are also filtered to green.
Build results, hashes, evidence and limitations are in `BUILD-PREVIEW17.md`.
Artifacts are under `E:/Tools/Rokid/reddit-patch-lab/final-preview17`.
The Patcher phone debug APK is built with the final bundle but is not installed;
the existing phone release remains unchanged. No real Reddit write occurred.

The user selected A Ledger and requested feed media previews plus text-first
post reading, followed by a preview and swipe into full-screen media.
Fable round `reddit-glasses-fable-a-media-20261008-round2` wrote
`ui-a-media/index.html` but failed with
`Claude API rate limit reached. Try again later.` Its final notes were not written.
The parent fixed a null-action media-menu error and recorded observed T3 native
browser checks. The subsequent Fable Cloud round completed the design: the user
returned its two-file ZIP and unified HTML diff. The parent validated and imported
the actual final `ui-a-media/index.html` and `DESIGN.md`, retaining a temporary
backup of the pre-import files. Only those design files and this checkpoint changed.
Fable recommends text pages > final media preview > one more swipe to full screen.
The comparison route now opens media on a genuine single tap and moves the page's
post actions into the full-screen menu. Both routes preserve Back and reply drafts.
The recommended swipe route is approved; the direct-tap comparison is not selected.

Failed child task:
`node:delegated-task:command%3Amcp%3A16d057aa-5254-4823-9afe-6e0ce2793562%3Adelegate-task%3Areddit-glasses-fable-a-media-20261008-round2`.
The earlier local child is terminal; no new timer was created.

The user showed $250 in Claude Cloud session credits. Official promotion terms
confirm that eligible Cloud sessions consume those credits before normal plan
usage. Local CLI 2.1.293 supports `--cloud` and is signed in via claude.ai on Max.
The existing T3 child provider is `claudeAgent`; Cloud credits are not a switch
in the current delegation tool.

At the user's follow-up request, a real Claude Cloud session was created:
`session_01Bjk9eUnn7rs3rdw7y4xmWM`.
URL: https://claude.ai/code/session_01Bjk9eUnn7rs3rdw7y4xmWM
The creation command explicitly requested `claude-fable-5-1`; a follow-up
`/model claude-fable-5-1` message to this same session returned `ok: true`.
Neither acknowledgment verifies the actual running model, finished files or
credit consumption. Do not claim those were observed.

Preparation lives in the separate sparse checkout:
`C:/Users/saim2/AppData/Local/Temp/reddit-glasses-fable-cloud-20261008-round3`.
It was cloned from the published GitHub repository. Only six safe design inputs
were staged there, including the actual current HTML, parent notes, complete
historical brief with updated round-3 constraints, and skill guidance. The Cloud
creation used `CCR_FORCE_BUNDLE=1`; no dirty main workspace, APKs, secrets or
signing material was copied. No commit, push, PR or publication was performed.

Automatic retrieval is currently unavailable: the account rejects interactive
attachment to an existing Cloud session, and T3 native preview encounters the
Cloudflare security-verification page. Automatic approval review rejected a
local Claude client launch for status inspection because it could consume normal
quota or modify the local review workspace. That action was not retried.
Use this same Cloud session; do not duplicate it or substitute a model. The user
can open the URL to verify its actual model/state. Cloud completion does not
currently wake this T3 thread through an app-owned delegated task.

The user subsequently confirmed the Cloud session was running and supplied a
screenshot of its browser QA harness executing against the copied HTML. The
visible output included passing browsing/gallery/paired-input checks and failing
harness assertions being corrected; do not claim the final suite passed yet.
The title repeats the requested model name but is not independent verification
of the selected model or the credit balance. A delivery-only message was sent
to this same session requesting the final HTML and DESIGN.md as downloadable
files or a two-file ZIP, with a complete diff as fallback if Cloud cannot attach
artifacts. No additional design round/session was created.

Cloud delivery is now received and inspected. The imported final HTML SHA-256 is
`4793d2f7404a08c1b684c3368732114419efb4ce85f22027ae26620057e2aa7c`.
Fable reports 100 headless assertions passing with no external requests/errors;
this is attributed to Fable, not claimed as the parent's full rerun. After closing
an unresponsive collaborative review tab, the parent obtained clean T3 native
HTML previews at 728/360 px and independently observed 17 synchronous DOM/geometry
checks passing at each width. Details and limits are appended to DESIGN.md.
Presentation uses the actual final HTML with only its initial gallery selection
set in the render copy. All previous prototypes and Android/APK state remain intact.

## Previous UI design variants

Claude Fable 5.1 delivered three interactive green monochrome AR directions:
A Ledger (dense index), B Deck (focused summary plus next post), C Headline
(editorial reading). Main Home/Popular and post/comment action menus are retained.
Deliverables: `ui-variants/index.html` and `ui-variants/DESIGN.md`.
The revised brief is `UI_VARIANTS_BRIEF.md`; the approved older prototype remains.

Parent checks passed browsing/Back, long reading, paired input aliases, exact-parent
post/comment drafts, simulated failure/retry and narrow layout in all three styles.
T3 HTML previews at 728/360 px reported no console messages. The parent used the
existing headless browser for interactions after T3 collaborative preview reported
no available host. See the design notes and lab receipt for exact observations.
The user selected A; its requested media refinement is covered above.

The 15:00 Europe/Paris one-time wakeup was deleted before delegation.
Completed child task:
`node:delegated-task:command%3Amcp%3A0ef314d6-0bb7-4703-8711-ba7455445e5a%3Adelegate-task%3Areddit-glasses-fable-ui-variants-20261008-round1`.
Provider/model: `claudeAgent` / `claude-fable-5-1`.

## Current state

- Glasses: reddit-preview13-debug.apk, installed with the same existing Android
  debug certificate. Native login stayed valid across replacement installs.
- Phone: existing release Nexus hub and Patcher preserved. The original Futo IME
  is restored. Local debug APKs cannot update those release certificates.
- Official stock input: complete Reddit 2026.14.0/2614001 APKM in
  E:/Tools/Rokid/reddit-patch-lab. Do not redownload or replace it unnecessarily.
- Actual Home/Popular feeds, images, animated GIF play/pause, three-item gallery
  next/previous, native video playback/seek/pause, nested comments and unsent
  post/comment draft previews were exercised. See VALIDATION-20261008.md for
  exact preview versions, readings and limitations.
- Preview12 regression retains a paused 15,000 ms video position through menu removal
  and reattachment. Playback resumes; the final paused 14,639 ms position remained
  stable across two inspections. Preview13 also retained 15,000 ms through the menu and updates GIF/gallery headers live. Native keyframe seeking can land before the
  requested timestamp, but the zero-position reset is fixed.
- A cold feed may offer Open Home/Open Popular to initialize its native repository.
  Use that visible action and reopen the HUD feed. Drafts are in memory only.
- No live Send was activated. Successful reply writes, rate limits/reconciliation,
  votes/saves, moderation, native create-post and other account routes have not
  been established by these device checks. Physical R08 was not tested.

## Source and builds

Morphe checkout: E:/Tools/Rokid/Morphe-Patches-restartfix, baseline c06102d4b
(1.39.1-rokid.3). Original frozen YouTube bundle unchanged.

Frozen final Reddit source: reddit-source-preview13.mpp, SHA-256
ba69e714e61bf2f6b0eddb5fd4d1bb3f786025ba07176e56fc6111137ca302ef.
Both Nexus pins match. Metadata version: 1.39.1-rokid.3-reddit-preview.13.
Never overwrite older frozen bundles, APKs, or final-preview3/.

Observed log tails (lab):

```text
morphe-resume-preview13.log
BUILD SUCCESSFUL in 26s
211 actionable tasks: 7 executed, 204 up-to-date

nexus-resume-preview13.log
BUILD SUCCESSFUL in 3m 21s
87 actionable tasks: 12 executed, 75 up-to-date
```

Patcher: 130 executed tests, zero failures/errors, 11 existing opt-in skips,
including a real complete-APKM patch through the actual Nexus engine. Full phone
suite: 721 passed; its debug build succeeded. The prior unchanged glasses suite
has 683 passing tests and was not rerun during this continuation. Real tails,
artifact hashes and detailed device evidence are in VALIDATION-20261008.md.

The phone source contains printable/digit IME sendKeyEvent/composition/deletion
fixes and idempotent core-bridge startup after hub re-enable. Those fixes were
built/tested but are not installed over the phone release. Login temporarily used
Samsung soft keys for Futo's lost password digits on the old release; Futo was
restored immediately. Normal reply text was then exercised with Futo.

## Delivery and cleanup

Final-preview13 artifacts are in E:/Tools/Rokid/reddit-patch-lab/final-preview13/. The complete archive is reddit-glasses-preview13-complete-20261008.zip in the lab. Task-helper cleanup is complete.
The archive contains debug previews, source/prepared bundles,
public HUD evidence, non-sensitive inspection receipts, and build logs. Clearly
label Nexus phone/Patcher debug APKs as not deployed over the installed release.
Do not extract the phone Patcher key or uninstall a user app to bypass signatures.

Task-only cleanup completed: uninstalled com.anezium.qa.redditnative, restored glasses
stay_on_while_plugged_in to original 0, ended reddit-resume-qa-20261008, and checked
scrcpy processes with task window titles. Removed the task's temporary
phone keyboard screenshot. Do not alter R08 watchdog/accessibility settings.

No SDK path, local.properties, Gradle home/cache configuration, production signing
material, account token or permanent accessibility configuration was changed.
