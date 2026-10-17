# Reddit preview24: installed Popular cold-start fix

Date: 2026-10-10. This report supersedes the unresolved runtime result in the earlier preview23 recheck. It records a local preview, not a release.

## Outcome and source preservation

`reddit-glasses-preview24.apk` was installed in place on the glasses with `adb install -r`. Its installed SHA-256 matched the signed artifact exactly. The signer matched the previously installed Reddit signer; no data reset, new login or account change was performed.

The app still opens its main menu with Home selected. Home opens the signed-in user's native feed; Popular is a separate public feed. No direct-feed startup change was made.

Morphe sources are committed on `dev/reddit-popular-preview24` in `E:/Tools/Rokid/Morphe-Patches-restartfix`, commit `4e4f045be`. This includes the previously local Reddit implementation and the reviewed five-file Popular fix. Nexus pins, the real-engine DEX assertion and this validation are preserved on `dev/reddit-patcher-ui`. Neither branch was pushed or merged. Skills/Workspace and unrelated Launcher files were not staged or changed.

Popular now constructs its paging owner from the active native graph using the inspected stock dependency order. It validates graph/session/account ownership through paging and hydration, including empty completion, and caches only a validated owner for the current graph. Refresh uses the stock first-page reset. No stock UI bootstrap, additional Reddit HTTP/authentication path or new user identifier is required.

## Build and test evidence

The original plugin configuration failure is preserved in [PREVIEW24_POPULAR_FIX_STATUS.md](PREVIEW24_POPULAR_FIX_STATUS.md). Following the renewed direct-install instruction, automatic approval accepted an offline retry with process-only, nonsecret GitHub repository placeholders. No credentials were supplied to a network request. SDK, Gradle home, caches, `local.properties`, machine configuration and signing material were not replaced.

Morphe command, in the existing checkout:

```text
.\gradlew.bat :patches:test :patches:jar -Pversion=1.39.1-rokid.3-reddit-preview.24 --offline --console=plain
```

Observed: **112 tests, zero failures/errors/skips**. Actual output tail:

```text
> Task :patches:compileTestJava
> Task :patches:testClasses
> Task :patches:test
BUILD SUCCESSFUL in 1m 3s
211 actionable tasks: 9 executed, 202 up-to-date
```

Nexus command, with paths relative to this checkout for readability:

```text
.\gradlew.bat :plugin-patcher:testDebugUnitTest :plugin-patcher:assembleRelease -PskipCxrGlobal=true -PpatchBundleInput=build/outputs/reddit-popular-preview24-20261010/youtube-source-pinned.mpp -PredditPatchBundleInput=build/outputs/reddit-popular-preview24-20261010/reddit-source-preview24.mpp -PredditStockApkm=E:/Tools/Rokid/reddit-patch-lab/reddit-2026.14.0-2614001.apkm -PredditTestOutput=build/outputs/reddit-popular-preview24-20261010/reddit-preview24-native-test.apk --offline --console=plain
```

Observed: **158 tests reported, 147 executed successfully, 11 skipped, zero failures/errors**. The complete-APKM `RealRedditPatchTest` executed successfully using the real Nexus/Morphe engine. It verifies the new `NativePopular` class definition in the output DEX and the output APK. A first test compilation failed because the engine's dexlib dependency was runtime-only; the exact existing dexlib version was added to the test classpath and the command then passed. Both logs are retained. This adds no new release dependency.

Actual successful tail:

```text
> Task :plugin-patcher:testDebugUnitTest
> Task :plugin-patcher:packageRelease
> Task :plugin-patcher:createReleaseApkListingFileRedirect UP-TO-DATE
> Task :plugin-patcher:assembleRelease
BUILD SUCCESSFUL in 3m 1s
185 actionable tasks: 11 executed, 174 up-to-date
```

## Artifact identity

All artifacts and raw logs are under `build/outputs/reddit-popular-preview24-20261010/` (ignored local output).

| Artifact | SHA-256 | State |
| --- | --- | --- |
| `reddit-source-preview24.mpp` | `bddd6341f7069897902a072db8ba434af47d2c30cc8d56d6db58e70563a80a01` | Both Nexus Reddit source pins |
| `reddit-android-preview24.mpp` | `bd00e94139abf51bf00afcedebdb582f269f784f38587d487290dd02b22c7ca7` | Prepared Android bundle |
| `reddit-glasses-preview24.apk` | `69e93a62ca71a9d20a82aa19e64aae4d3e4ea6988ceb9a95dfdf1238c55ba4bc` | Installed and hash verified |
| `nexus-patcher-preview24-release.apk` | `73f2b9d2a90eb1f451f50ae6116b73c89935583afa0c7f8489e078b2463de412` | Installed on the phone after Skills tests ended; hash verified |
| `youtube-source-pinned.mpp` | `d2b7de48fe7d58b04027754ad7bbd79f0cdff5b2b61364d652b2f4684185adf7` | Unchanged input |

Reddit signer SHA-256: `b6e8f6fa0421f79bcfb20aaae8ccb56e13086f55ddcb8fb5c89b0c4a93867ac5`.
Patcher signer SHA-256: `f5e938e2e79b0526b31e40d36c8c19098450c1636b7e14a306681b4effddf81c`.

The temporary real-engine test APK is not the installed artifact; the delivered Reddit APK was re-signed with the existing installed certificate.

## Actual device observations

The local `device/checks.jsonl` contains the pass ledger and screenshot hashes. All preview24 assertions passed.

- Popular loaded as the **first feed** after force-stop/relaunch, twice, without opening the stock Popular screen or visiting Home first.
- The paired ADB direction aliases advanced one selection; one previous step restored it.
- A Popular post and its native comments opened. Back returned to the originating feed selection. The returned screenshot SHA-256 exactly matched the original feed screenshot.
- Refresh loaded Popular successfully. Paging appended entries from 8 to 33, including the Feed options row in both counts.
- Home and Latest loaded using the retained account. Green monochrome media previews were visible in the feeds.
- Back from a feed returned to the main menu; Back again exited Reddit to the launcher.
- SHA-256 checks confirmed unchanged phone hub, glasses hub, YouTube and phone Patcher APKs. This preserves the installed Skills/Workspace hub rather than replacing it with a root build.
- Both devices were released at their stock launchers. No Reddit posts, comments, votes, saves or account login were performed.

Nine actual 480x640 captures are retained: `popular-cold-first.png`, `popular-post.png`, `popular-comments.png`, `popular-returned.png`, `popular-refreshed.png`, `popular-paginated.png`, `popular-cold-repeat.png`, `home-regression.png`, and `latest-regression.png`. All were visually inspected. They contain public post/comment content; no credentials, private account/settings screens or device identifiers are included. The screenshots ZIP preserves originals with a SHA-256 manifest.

## Phone Patcher update after explicit authorization

Automatic approval initially rejected installing the newly built Patcher APK on the phone because that additional APK replacement lacked explicit authorization. That attempt did not execute. The user then explicitly authorized the update, conditional on thread `847cd5a6-e515-40c8-8fed-ecc634ad020c` finishing its device tests. A proposed temporary scheduler was rejected and never created; the parent waited directly without accessing either device during the active tests.

At 2026-10-10 11:23 UTC, the Skills thread reached `completed`, with no active run or pending requests. Its final report recorded successful device tests, cleanup, preserved hubs/Reddit and local commit `240577df`. Only then was the phone accessed, initially at its neutral stock launcher with no active Patcher job.

The installed and new Patcher APKs were both cryptographically verified against the existing certificate before a single `adb install -r` update. The installed APK then matched the preview24 artifact SHA-256 exactly. The native Patcher screen displayed both `SET UP YOUTUBE` and `SET UP REDDIT`. Its actual screenshot was captured and visually inspected: `build/outputs/reddit-popular-preview24-20261010/phone-update/patcher-preview24-installed.png`, SHA-256 `2336ae2fcf728e2c60bed590dac016fee632e272fac93c00dc2c5c61acd7522c`.

All nine assertions in `phone-update/checks.jsonl` passed. Hashes captured immediately before this update confirmed the installed phone hub, glasses hub, Reddit and YouTube APKs remained unchanged. No hub or Assistant APK was replaced, no data reset or key replacement occurred, and no patch job or Reddit write was triggered. The phone returned to its original stock launcher. The installed phone Patcher now includes the verified preview24 Reddit bundle.

## Remaining limits

This pass does not revalidate full-screen image/GIF/gallery/video playback, editors, sending, create-post, moderation or physical R08 input. The successful feed/comment checks use ADB compatibility input and native services; they do not establish physical-control or optical performance. No build environment repair, hub update, source APKM replacement, public release, push or merge occurred.
