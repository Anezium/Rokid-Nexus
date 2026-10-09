# Final Patcher device smoke, 2026-10-09

## Result

The installed native phone UI passed all 72 fresh checks. The exercised YouTube
browsing, playback, pause, seek dispatch, fullscreen and automatic Nexus editor
paths worked. Reddit Home/Latest, posts, nested comments, images, automatic
Nexus editor and root Back worked. **Reddit Popular remains a confirmed defect
in the installed preview23:** it fails after a fresh process launch and Retry
repeats the same uninitialized-service error. This pass does not approve every
Reddit function or a public release.

## Device coordination and preservation

Device work began after the Skills/Workspace thread had no active or queued run
and the Launcher thread had released device work. The Launcher thread received
coordination and release notes. No APK installation, signing-key operation,
login, app-data reset, public Reddit write, source change, or persistent device
configuration change was performed. Phone keyboard preferences were restored.
The installed SHA-256 hashes of Patcher, both Nexus hubs, YouTube and Reddit
were identical before and after the pass. Skills/Workspace sources were untouched.

Devices are released. The final observed foreground was phone Nexus MainActivity
and glasses YouTube. A generic MAIN-intent attempt to restore the glasses Nexus
foreground failed activity resolution; the release note was corrected. No device
input was sent after release.

## Fresh phone checks

The 72 checks comprise Reddit tutorial migration (32), YouTube tutorial (21),
header/inventory navigation (9), and keyboard preferences (10). Both tutorials
are in Patcher; Glasses apps retains its inventory and passive setup hint.
Authenticated tutorial entry, native headers/system Back, advanced document
picker cancellation, unchanged Patcher home state, secure remote entry and
FLAG_SECURE were observed. No secure phone field was captured or persisted.

The current Patcher home has no prepared job/result. The older harness expected
a cached YouTube result; it was corrected to compare the actual initial state
across tutorial navigation. The reason for the absent result was not established.
The already-installed YouTube page offers `Reinstall / update`, which the harness
now accepts. A wait/scroll correction was needed to reach Patcher after inventory.
These were harness corrections, not Android changes.

## Fresh glasses observations

- Home browsing advances directly between posts. A long text post pages only
  after opening it. Back restores its originating feed selection.
- Native nested comments load; selecting one opens full reading, then Tap opens
  its action menu. Reply-to-comment remains available. Back restores comments
  and then the post. No reply editor or publication was invoked in this pass.
- Latest loads native results. Paired right/down input advances exactly one row,
  verified by a single previous step restoring the original selection.
- Inline image preview opens fullscreen on the next swipe. Back returns to the
  identical preview screenshot. The final media sample was **one image**, despite
  the initial `gallery-*` evidence filenames; another swipe stays on image 1/1.
  Multi-image galleries, GIF and Reddit video were not freshly verified.
- Reddit and YouTube Search each automatically opened Nexus RemoteInputActivity
  from the phone's normal Nexus screen. The secure phone screen was excluded
  from screenshots. Query typing/result retrieval was not exercised in this pass.
- YouTube navigation and play/pause commands reached the native controller;
  changing video frames and native time labels demonstrated playback. Seek and
  fullscreen dispatch were accepted; fullscreen was visually inspected. Seek
  time delta was not independently sampled.
- Root Back leaves the Reddit task. No install was needed to reproduce this.

Initial ADB directions were injected before the glasses regained application
focus, or while a repeated Wake command temporarily disturbed it. The system's
`com.rokid.sysconfig` MockWindow held focus. The local harness now wakes only an
asleep display and waits for application focus. No accessibility service or
system setting was changed. Additional harness corrections account for comment
Tap opening reading before actions, subtitle-bearing accessibility labels, and
the first input revealing a faded YouTube player rail.

There are 19 explicit passing glasses assertions in checks.jsonl, plus visually
inspected screenshot sequences. Earlier failed harness assertions remain in the
ledger rather than being erased.

## Open defect: Popular after fresh initialization

Popular renders `Feed unavailable. Check your connection.` Retry reproduces it.
The glasses have a validated Wi-Fi network. The privacy-safe native failure log
contains only exception classes and method locations:

```text
java.lang.IllegalStateException
  at NativeBindings.service(NativeBindings.java:94)
  at NativeFeed.load(NativeFeed.java:38)
  at RedditController.loadFeed(RedditController.java:360)
  at RedditController.chooseFeed(RedditController.java:288)
```

Source inspection shows Popular requests
`com.reddit.feeds.popular.impl.data.paging.a`. Unlike Latest and several other
services, it currently falls back to a constructor-captured instance. That
instance is absent in this launch. Fixing service acquisition at cold start and
retesting Popular, refresh, paging and Back is required before claiming complete
feed support. The installed Reddit APK is byte-identical to the earlier validated
preview23; this pass did not change the APK or establish a Patcher UI regression.

## Evidence and limits

Fresh evidence is under
`build/outputs/patcher-last-device-smoke-20261009/`: before/after baselines, the
four phone check lists, checks.jsonl and original on-device PNG files. The share
ZIP contains only 12 visually inspected, unaltered PNGs, an English README and
their hash manifest. No account screen, credential, phone editor, device identity
or private local build path is in that ZIP. Public post/comment author names are
ordinary content, not the user's logged-in account.

The image gallery was checked using T3 native HTML preview at 728px and 360px.
These are Android framebuffer captures, not optical photos through the lenses;
YouTube's framebuffer retains color even though the hardware HUD is monochrome.

No fresh Gradle build or full patch/install job was run. Earlier build/test
results remain in DEVICE_QA_RESULTS.md and the implementation review. A clean
first-time onboarding, physical R08/temple controls, optics, account routes,
moderation and live Reddit sends/votes/save are outside this final smoke pass.
