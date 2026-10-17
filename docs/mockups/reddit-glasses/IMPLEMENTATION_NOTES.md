# Reddit glasses implementation and verification

The user approved Claude Fable 5.1's interactive HTML design on 2026-10-07.
Android implementation follows the existing YouTube pipeline: official stock
APKM, selected Morphe patches and the custom Rokid patch in one Patcher pass.
The output retains `com.reddit.frontpage`. No MicroG or external OAuth client is
introduced. This checkpoint is an unpublished local preview.

## Exact inputs

The official complete Reddit 2026.14.0 bundle was downloaded through Helium.
It remains outside git at
`E:/Tools/Rokid/reddit-patch-lab/reddit-2026.14.0-2614001.apkm`.

| Property | Verified value |
| --- | --- |
| Package | `com.reddit.frontpage` |
| Version | `2026.14.0`, versionCode `2614001` |
| Size | 77,140,493 bytes |
| SHA-256 | `1f6a8939589fb88205a857fb1efd00b7fa9304347ee7ced50d4add10f342da3b` |
| Contents | Base APK and 32 configuration APKs |
| Stock certificate SHA-256 | `970b91143813b4c9d5f3634f672c9fcaa5621b4efaaedafd6c235cbbb869736f` |
| Base API levels | Minimum 29, target 36 |

SDK apksigner verified all 33 APKs against the expected certificate over API
30-32. SDK aapt2 verified their package/versionCode; the base declares versionName
and configuration splits omit it. Evidence is in the lab's
`stock-verification.json`.

## Morphe implementation

Source is in `E:/Tools/Rokid/Morphe-Patches-restartfix`, based on `c06102d4b`
(`1.39.1-rokid.3`), preserving that baseline's YouTube fixes. The older
`Morphe-Patches` checkout is not the implementation source.

`RokidRedditControlsPatch.kt` supports this exact stock build and depends on the
shared Reddit extension, Spoof signature and a private HUD marker patch.
Application lifecycle registration covers the main activity and native Reddit
activities, including `AuthActivityKt`. The input hook modifies the existing
ancestor's final `dispatchKeyEvent`; adding a MainActivity override caused a real
Android verifier error and was removed.

The production Java extension under `extensions/reddit/.../rokid/` implements:

- A black portrait HUD, measured text pagination, one canonical selected row for
  directions and R08 selection, repeat suppression and shared direction debounce.
  Native content beneath the HUD is excluded from external accessibility
  navigation while direct native projection remains available. Text sizes are
  Standard, Large and Extra large.
- Native Home, Popular and Latest feed adapters, stock post/comment models,
  nested comments, collapse/expand, comment sorting and pagination.
- Exact post/comment reply targets and per-account/per-parent in-memory drafts.
  The existing Android phone keyboard edits a draft; finishing editing does not
  publish it. Preview and explicit Send are separate actions.
- Fresh destination/account/lock validation before the inspected native create
  operation. Returned comment, parent and post ids must match. Uncertain outcomes
  retain the draft and require reconciliation; no automatic retry.
- Native vote/save operations and post text/media viewing. Sensitive media needs
  explicit reveal and video playback uses explicit controls.
- Projection of the official app's semantic UI into HUD rows for communities,
  search results, create post, inbox, profile, saved, account and native settings.
  Native editable fields retain input types; password editing uses `FLAG_SECURE`.
  Native forms and destructive actions receive review/confirmation.

Secondary flows use actual native accessibility nodes, not mock data. They are
not yet equivalent to every bespoke screen in the HTML design.

Native bindings come from the verified APK's original bytecode: Link, Comment,
session owner, feed paging owners, link repository `l`, comment repository `b`
and the obfuscated coroutine ABI. The patch validates the coroutine contract
before rebinding extension continuation classes. Reply creation calls native `f`
with LINK or COMMENT and the immutable exact parent fullname. Account tokens are
never exported.

The frozen JVM source is
`E:/Tools/Rokid/reddit-patch-lab/reddit-source-preview3.mpp`, SHA-256
`3f0f28caf76b85fe4bb7c7f848f9e52df413b8c24beea3af5b11db1bea5bc5bd`.
Prepared preview metadata is `1.39.1-rokid.3-reddit-preview.3`; the JVM manifest
records baseline `1.39.1-rokid.3` and Patcher `1.7.0`. This is not a published
release. Nexus prepares Android DEX from `:patches:jar`; a source already
processed by `buildAndroid` is rejected.

## Nexus and Patcher integration

Patcher now registers YouTube and Reddit separately. Reddit defaults to Rokid
Reddit controls, Spoof signature and Hide ads in one pass. Version, versionCode
and stock certificate are pinned; package renaming is disabled. The preview has
its own source SHA-256 and Android assets (`reddit.mpp/json`). Its remote updater
is disabled until a real asset is published. YouTube retains its published source
and original `d2b7de48fe7d58b04027754ad7bbd79f0cdff5b2b61364d652b2f4684185adf7`
pin. Local build commands and required source inputs are in Patcher's README.

The phone adds a separate Reddit setup screen and command/state channel through
the existing inventory, transfer, install and signing checks. Glasses inventory
accepts only the fixed Reddit package. Preparation rechecks exact version,
manifest, hash, HUD marker and signer. Installation checks fresh device inventory,
API and signer and confirms the installed result. Both controllers share the
existing single-operation CXR transfer boundary.

Reddit keyboard auto-open is a separate preference, off by default, checked
against the exact package and rechecked after the opening delay. Manual Keyboard
& remote remains available. No plugin capability or public trusted core route was
added.

## Observed verification

Logs and binaries remain outside git in the lab. Morphe builds use the existing
YouTube invocation with already configured GitHub CLI credentials scoped to the
process. No credentials or machine configuration were changed.

Final Morphe source build (`patch-build-preview3.log`):

```text
:patches:test :patches:jar
BUILD SUCCESSFUL in 11s
211 actionable tasks: 7 executed, 204 up-to-date
```

XML reports contain 100 tests in 11 suites, zero failures/errors/skips, including
nine production reply-draft tests and three single-axis tests.

Shared and both hubs (`nexus-hubs-round1.log`), without `skipCxrGlobal`:

```text
:shared:test :phone-hub:testDebugUnitTest :phone-hub:assembleDebug
:glasses-hub:testDebugUnitTest :glasses-hub:assembleDebug
BUILD SUCCESSFUL in 7m 57s
165 actionable tasks: 54 executed, 8 from cache, 103 up-to-date
```

That run verified 361 shared, 714 phone and 683 glasses tests with zero failures
or errors. A later targeted run checked the new Reddit setup controller and eight
keyboard preference tests, plus 131 bus-client tests (`nexus-reddit-regression.log`):

```text
:phone-hub:testDebugUnitTest --tests ...RedditSetupControllerTest
--tests ...YoutubeKeyboardSettingsTest :bus-client:testDebugUnitTest
BUILD SUCCESSFUL in 1m 11s
87 actionable tasks: 4 executed, 1 from cache, 82 up-to-date
```

The host bundle preparation tests passed seven cases. Desktop Morphe CLI patched
the genuine stock APKM with all three selected patches and no failed patches.
The opt-in `RealRedditPatchTest` also exercised Nexus' actual Patcher 1.7.0,
preparation, merging, signing and result verification successfully for preview 1
(`nexus-reddit-real-engine.log`):

```text
:plugin-patcher:testDebugUnitTest --tests ...RealRedditPatchTest
BUILD SUCCESSFUL in 1m 23s
66 actionable tasks: 2 executed, 64 up-to-date
```

Final preview 3 plugin build and tests (`nexus-patcher-preview3.log`):

```text
:plugin-patcher:testDebugUnitTest :plugin-patcher:assembleDebug
-PskipCxrGlobal=true -PpatchBundleInput=.../patches-1.39.1-rokid.3.mpp
-PredditPatchBundleInput=.../reddit-source-preview3.mpp
-PredditStockApkm=.../reddit-2026.14.0-2614001.apkm
-PredditTestOutput=.../reddit-preview3-nexus.apk
BUILD SUCCESSFUL in 2m 7s
87 actionable tasks: 12 executed, 75 up-to-date
```

The XML reports contain 141 tests in 24 suites, zero failures/errors and 11
existing opt-in skips: 130 executed, including the real complete APKM test.
The preserved result came from Nexus' actual engine. A copy was signed with the
Android debug key for repeatable local device QA. The original temporary test
key was removed by the test; production Patcher retains its own signing key.

## Device evidence and remaining verification

A task-owned debug test APK remains installed on the connected Rokid glasses,
where Reddit was initially absent. Final preview 3 displayed the real native
account screen as a black 480x640 HUD. ADB direction/activation selected the
exact mobile sign-in row, opened the actual phone-number form and selected its
exact editable field. The HUD editor was empty and focused; the configured IME
was `com.anezium.rokidbus.glasses/.NexusRemoteInputMethodService`. Back ended
editing and returned from authentication with no submission. Final native
notifications state was Off. No credentials, number or login request were
submitted and no Reddit content was posted.

The normal accessibility tree (`uiautomator dump --compressed`) contained HUD
rows while excluding native controls underneath. Direct native projection and
editing still worked. The uncompressed diagnostic dump intentionally includes
non-important native nodes. Physical R08 hardware and a phone-to-IME character
round trip were not exercised. The final AndroidRuntime error log was empty.
Authentication windows blocked screenshots, so their evidence is the empty-field
XML rather than a screenshot. The final account screenshot is
`device-preview3-final.png`; key snapshots are `qa3-*.xml` in the lab.

An unlabeled onboarding notifications checkbox initially received an incorrect
email sign-in label. Native XML showed only a checkbox toggle. Its original
unchecked value was restored and verified. Preview 2 calls the exact native
checkbox Email notifications and shows its On/Off state.

Authenticated feeds, votes, saves, media, native create/edit/delete/report flows
and a live post/comment send remain unverified on device. Inspected bindings and
local state tests do not establish successful Reddit server operations. Drafts
are in memory and do not survive process death. Reconciliation confirms one exact
matching reply; otherwise UNKNOWN continues to block duplicate sends.

The phone's installed hub and Patcher have release certificates different from
these debug APKs. Their release signing environment is unavailable locally. They
were left installed; no app was uninstalled to bypass a signer mismatch. The
updated setup screen was built but has not been deployed on that phone.

Final binaries, the source and Android-prepared bundles, hashes, test receipts
and the actual device screenshot are packaged outside git in
`E:/Tools/Rokid/reddit-patch-lab/final-preview3/`. These are debug previews. The
device test Reddit APK uses the Android debug certificate, not the phone's
production Patcher output key. Switching that installed APK to another signer
requires an explicit user choice; Nexus continues to reject a mismatched update.

No commits, tags, release uploads, real Reddit writes or SDK path,
`local.properties`, Gradle home or dependency cache changes were made.

## Follow-up checkpoint

The user resumed the authentication/media follow-up on 2026-10-08. Final preview13
is installed on the glasses with a valid authenticated native session. Images,
animated GIF pause/resume, gallery navigation, video playback/seek/pause and
retained position through the media action menu were exercised on the device.
Post/comment drafts were entered through the actual Nexus phone keyboard and
reviewed with their exact parent destinations; no live Send was activated.

See [VALIDATION-20261008.md](VALIDATION-20261008.md) for actual readings, final
hashes, build tails, preview-specific evidence and unverified account operations.
[RESUME.md](RESUME.md) holds the current checkpoint. The preview3 evidence above
is historical. Phone IME/core-bridge corrections were built and tested but not
installed over the phone's existing release certificate. No commit or release
was created.
