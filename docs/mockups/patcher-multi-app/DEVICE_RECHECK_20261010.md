# Current-version phone and glasses recheck

Date: 2026-10-10. User authorized using the reconnected phone. This tests the **installed preview23**, not the unbuilt Popular fix.

## Device ownership and preservation

Skills/Workspace's parent was terminal and its live implementation child explicitly prohibited device mutations. Launcher was editing/reviewing a mockup. Neither was performing a device test in the observed thread state. No thread message attempted to cross a broader permission mode.

ADB exposed one physical pair of glasses through direct and mDNS endpoints. The test harness verified matching physical identity locally, without printing or retaining it, and selected the direct endpoint. The phone was the verified USB handset. No installation, signing, account login, credential access, preference change or Reddit send/post/comment/vote/save occurred.

Before/after SHA-256 checks passed for the phone hub, Patcher, glasses hub, Reddit and YouTube. Both devices returned to their respective launchers. Skills/Workspace code and existing app data remain untouched.

## Actual observed results

Sixteen functional assertions passed, plus five installed-APK preservation assertions:

- Phone: Patcher exposes both setup entries; each opens its native YouTube/Reddit guide; Android Back returns to Patcher; Reddit names the complete APKM and has no MicroG instructions. Seven assertions. No patch/install/download was started.
- Glasses: cold launch opens the main menu with Home selected; Home loads real posts; paired right/down advances once and one previous restores selection; a post and its native comments open; Back returns to Home; Latest loads; root Back exits Reddit. Nine assertions.
- **Popular still fails when selected as the first feed after a cold launch. Retry fails too.** Screens show `Feed unavailable. Check your connection.` Home and Latest load in the same session, so this is not evidence of a general network outage.

One initial harness assertion failed because it did not strip the leading selection marker from the accessible Home label. That predicate was corrected and the cold-launch check passed. It was not an app defect; the original failed entry remains in the evidence ledger.

No new full-screen GIF/video/gallery test, YouTube playback test, editor test or physical R08/touchpad test occurred. The direction/Back tests used ADB compatibility keys. Native Reddit server writes remain untested. This is not full release approval.

## Evidence

Local delivery: `build/outputs/reddit-current-version-recheck-20261010/`.

- `baseline.json`: initial APK hashes, versions and foregrounds, without device identity.
- `checks.jsonl`: actual assertions and redacted route events; final APK hashes equal the baseline.
- Nine original framebuffer PNGs were captured and visually inspected. These are not optical photos.
- `reddit-patcher-device-screenshots-20261010.zip`: eight selected, unaltered PNGs plus README and hash manifest. No account/login screen, secure editor, credentials or device identifier is included. Public post/comment author names belong to visible public content.
- `gallery.html`: interactive review of those eight screenshots. T3 native previews at 728px and 360px reported eight loaded images, all choices working, no horizontal overflow and no console errors.

## Outstanding Popular delivery

The five-file source fix is preserved in `docs/mockups/reddit-glasses/PREVIEW24_POPULAR_FIX.patch`. It has been reviewed against the pinned stock native ABI but **has not been compiled or installed**. Root Patcher still embeds preview23.

Normal Morphe builds fail in the settings plugin's repository credential configuration before compilation. A proposed offline process using non-secret local placeholders was rejected by automatic approval review because AGENTS.md prohibits working around build-environment failures. It was not executed. Device-use authorization does not resolve that build blocker.

The installed Popular defect remains open until a new bundle/APK is built with existing signing identity and cold-start/pagination/refresh/Home/Latest checks pass on the actual glasses.
