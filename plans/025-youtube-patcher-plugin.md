# Plan 025 — YouTube Patcher plugin and a one-screen YouTube onboarding

Status: TODO — plan written 2026-09-28, nothing implemented yet.
Branch: continue on `dev/youtube-glasses-setup` (or a child branch of it). It is not
merged into `main`; its base is behind `main`, so rebase or merge `main` first.

## Product outcome

A wearer with a Rokid phone and glasses sets up YouTube for the glasses without leaving
Rokid Nexus, apart from downloading the stock YouTube APK in the browser:

1. **Glasses apps → Set up YouTube** shows four steps, each with one primary button
   and a state that Nexus detects by itself (done, to do, needs attention).
2. Nexus patches YouTube on the phone with the **YouTube Patcher** plugin, from the
   Rokid patch bundle, with the patch selection shown and editable.
3. Nexus installs the patched YouTube on the glasses and confirms it.

Morphe Manager is no longer required. It stays as a folded **Advanced** fallback.

## Where things stand (2026-09-28)

| What | Where | State |
|---|---|---|
| Rokid controls patch and Rokid patch bundle | `E:\Tools\Rokid\Morphe-Patches`, branch `rokid-glasses-controls`, pushed to **github.com/Anezium/morphe-patches** branch `rokid` (default branch) | Released `v1.39.1-rokid.1` and `v1.39.1-rokid.2`. `rokid.2` selects Rokid controls by default. README section "Rokid glasses fork" explains patches, modifying, building and releasing |
| Bundle metadata | `patches-bundle.json` on branch `rokid` of the fork | Points at the latest `.mpp` release asset. This is what a patcher reads to find the newest bundle |
| YouTube setup screen in the hub | this branch: `phone-hub/.../YoutubeSetupActivity.kt`, `YoutubeSetupController.kt`, `YoutubeApkPolicy.kt`, `YoutubeApkSource.kt`, `shared/.../YoutubeSetupContract.kt`, `glasses-hub/.../YoutubePackageInventory.kt` | Works on device: MicroG download and install, stock YouTube link, patched APK import, install on glasses through CXR, inventory checks. Last commit `ff59e75d` adds **Add Rokid patches to Morphe**, **Patch with Morphe** (opens Morphe's patch dialog for YouTube) and a README link |
| Docs | `docs/YOUTUBE_GLASSES.md`, `BUSSPEC.md` (§ native apps, "YouTube setup also uses…") | Describe the Morphe Manager flow |

The user flow today, for reference: MicroG button, then install; APKMirror download;
add the Rokid source to Morphe Manager; patch in Morphe Manager and save the APK; back
in Nexus, choose that APK and install it on the glasses; sign in from MicroG on the
glasses with Keyboard & remote; open YouTube.

## Decision: split between the plugin and the hub

The plugin patches. The hub keeps everything that touches the glasses.

- **Why the install stays in the hub.** Installing on the glasses goes through the CXR
  upload API, which only the hubs link against. `BUSSPEC.md` states that YouTube
  installation "adds no bus route or plugin capability". Giving a plugin a way to
  install arbitrary APKs on the glasses would be a new high-risk capability for no
  gain: the hub already verifies and installs the APK.
- **Why the patcher is a plugin.** The patcher and its dependencies (morphe-patcher,
  smali, ARSC and signing libraries) add megabytes and a large-heap process that most
  hub users never need. A plugin installs from the Store only for those who set up
  YouTube, updates on its own schedule when Morphe changes, and keeps the GitHub
  Packages dependency out of the hub build and the hub CI.
- **How they talk.** Android activity results with a content URI. No bus route, no
  new capability, no AIDL change:
  1. The hub's setup screen starts the plugin's patch activity by explicit component
     with `startActivityForResult`.
  2. The plugin returns `RESULT_OK` with a `content://` URI of the patched APK from
     its own `FileProvider`, and `FLAG_GRANT_READ_URI_PERMISSION`.
  3. The hub feeds that URI to the existing `IMPORT_YOUTUBE` path
     (`YoutubeSetupController.prepare(uri)`), which copies, hashes and validates the
     APK exactly as it does for a hand-picked file, then runs `install()` on its own.

  The hub trusts nothing from the plugin beyond the bytes: package name, version,
  signer continuity with the installed app and `minSdk` are already checked by
  `YoutubeApkPolicy`.

## Part A — the YouTube Patcher plugin

### Identity

| Item | Value |
|---|---|
| Module | `plugins/youtube-patcher` → Gradle `:plugin-youtube-patcher` |
| applicationId | `com.anezium.rokidbus.plugin.youtubepatcher` |
| Plugin id | `youtube_patcher` |
| Display name | YouTube Patcher (decided, see Decisions) |
| Capabilities | none needed. Checkpoint: confirm the descriptor validator accepts an empty set, and how a plugin without a glasses surface appears in the glasses launcher |

Follow `plugins/AGENTS.md` and copy `plugins/sample`: one exported
`NexusPluginService`, no launcher activity, the settings activity exported without an
intent filter and declared through `com.anezium.rokidbus.plugin.SETTINGS_ACTIVITY`.
The patch activity is that settings activity, or a second exported activity without
an intent filter that the settings activity also opens.

### Dependencies

- `app.morphe:morphe-patcher` and `app.morphe:morphe-library` from
  `https://maven.pkg.github.com/MorpheApp/registry`. GitHub Packages needs
  credentials even for public packages: read `GITHUB_ACTOR` / `GITHUB_TOKEN` from
  the environment, only in this module's repository block. The plugin's release
  workflow needs a token with `read:packages`.
- **Pin the patcher version to one that loads our bundle.** The bundle is built
  against morphe-patcher 1.7.0 (`Morphe-Patches/gradle/libs.versions.toml`). Morphe
  Manager ships 1.14.1 with `morphe-library` 1.4.0 and loads bundles of that
  generation. First task: load `patches-1.39.1-rokid.2.mpp` with the chosen version
  in a JVM unit test and list its patches. Do not pick a version without that proof.
- Reference implementation to read, not copy wholesale (GPLv3; keep notices if code
  is taken): `MorpheApp/morphe-manager`, `app/src/main/java/app/morphe/manager/patcher/`
  (`Session.kt`, `runtime/CoroutineRuntime.kt`, `runtime/ProcessRuntime.kt`,
  `patch/PatchBundle.kt`, `split/SplitApkPreparer.kt`). Licence check: Nexus plugins
  must be license-compatible with GPLv3 if Morphe code is copied.

### Bundle source

- Fetch `https://raw.githubusercontent.com/Anezium/morphe-patches/rokid/patches-bundle.json`.
  Require `download_url` to start with
  `https://github.com/Anezium/morphe-patches/releases/download/`, the same way
  `YoutubeApkPolicy.microGRelease` pins the MicroG URL.
- Download the `.mpp` to the plugin's private files, keep the last good one, and show
  its version. Check for a newer bundle only when the patch screen opens; no
  background polling (plugin dormancy rule).
- Record the SHA-256 of the downloaded bundle and show it under Details. The fork has
  no signature (`signature_download_url` is empty); adding one is out of scope.

### Patch screen (phone, Nexus UI kit from `docs/PLUGINS.md`)

1. **Stock YouTube APK.** A file picker (`ACTION_OPEN_DOCUMENT`). Validate before
   anything else: package `com.google.android.youtube`, versionName `21.04.223`, a
   Google's signer. Accept both a single APK and an APKMirror bundle (`.apkm`, `.apks`,
   `.xapk`): port Morphe Manager's `SplitApkPreparer` to merge the base and the splits
   for this phone's ABI and density into one APK before patching (decided, see
   Decisions). The wearer downloads whatever APKMirror offers and never has to pick a
   variant.
   The hub may pass nothing: the plugin owns this picker.
2. **Patches.** List every patch of the bundle compatible with that YouTube version,
   with the bundle's defaults ticked (Rokid controls, GmsCore support, Hide ads,
   SponsorBlock and the other defaults). Show the four key ones first with one line of
   explanation; the rest under "All patches". Patch options stay at their defaults in
   the first release. Persist the selection per bundle version; a new bundle keeps the
   user's choices and ticks new default patches.
3. **Patch.** Progress with steps (reading APK, applying patches, writing, signing)
   and a cancel button. On success, return the result to the hub, or, if the screen
   was opened from the plugin settings rather than by the hub, offer to share/save the
   APK.

Guards: refuse to start with GmsCore support unticked unless the user confirms that
sign-in will not work; warn if Rokid controls is unticked, since the glasses cannot
drive stock YouTube.

### Patching runtime

- Run the patcher in a dedicated process (`android:process=":patcher"`,
  `android:largeHeap="true"`). YouTube needs on the order of 1 GB. Start with the
  in-process coroutine runtime; if phones below 8 GB RAM fail with
  `OutOfMemoryError`, port Morphe Manager's `ProcessRuntime`, which starts a separate
  `app_process` with a larger `-Xmx`.
- Patching takes minutes. Keep the activity on screen with `FLAG_KEEP_SCREEN_ON` and
  show "Keep this screen open". No foreground service: that would need a new
  exception to the dormancy rule in `plugins/AGENTS.md` §1 (decided, see Decisions).
  If the activity is destroyed mid-patch, discard the partial output and let the user
  start again; never return a partial APK.
- Output: `aapt`-free path as in Morphe Manager (the patcher rewrites resources in
  Java); verify on the phone that the resulting APK installs on the glasses.

### Signing

- Generate one keystore per phone on first patch, in the plugin's private storage
  (Morphe Manager does the same). Every later patch uses it, so updates install over
  the previous patched YouTube.
- The hub never uninstalls to solve a signer conflict (existing rule). If the plugin
  is uninstalled, the key is lost and the next update fails the hub's signer check:
  offer **Export key** / **Import key** in the plugin settings, protected by a
  password, and say in the UI that uninstalling the plugin loses it.
- A YouTube patched earlier with Morphe Manager has Manager's key. The hub's error
  already explains the conflict; add one sentence to it pointing at the key import or
  at uninstalling YouTube from the glasses by hand.

### Tests

- JVM: bundle metadata parsing and URL pinning; stock APK validation (package,
  version, signer, split rejection); selection persistence and new-default merge;
  loading the real `.mpp` and listing patches (fixture outside git if too large,
  downloaded in the test setup).
- Device (phone): patch the real 21.04.223 APK end to end, time it and record peak
  memory; install the output on the glasses through the hub; open YouTube on the
  glasses and check the Rokid rail.

## Part B — the setup screen in the hub

Rewrite `YoutubeSetupActivity` as four step cards. Each card shows its state from the
inventory and one primary button; secondary actions go under a small "More" row.

| Step | Done when | Primary button | Notes |
|---|---|---|---|
| 1. MicroG on the glasses | inventory shows `app.revanced.android.gms` installed | **Install MicroG** | Chain the existing `PREPARE_MICROG` and `INSTALL` into one action |
| 2. YouTube APK | the plugin reports a valid stock APK, or YouTube is already installed | **Download YouTube 21.04.223** | Opens APKMirror as today. APK or bundle are both fine; drop the "APK, not bundle" hint |
| 3. Patch and install | inventory shows `app.morphe.android.youtube` with a signer | **Patch and install** | Plugin missing → **Get YouTube Patcher** opens the Store entry; plugin present → start the patch activity for result, then import and install without another tap |
| 4. Sign in and open | the user taps Done (account state is never read) | **Open MicroG on glasses** | Then **Keyboard & remote**, then **Open YouTube**. Unchanged privacy rule: Nexus never reads accounts or tokens |

- Detect the plugin with `PackageManager` by package name and check the Nexus
  approval state the way other plugin entry points do. A plugin installed but not
  approved still returns patched APKs: no capability is used, so approval is not
  needed for the activity result. Checkpoint: confirm that is acceptable, or require
  approval for consistency.
- Keep the Morphe Manager buttons from `ff59e75d` under **Advanced → Patch with Morphe
  Manager instead**, together with **Choose patched YouTube APK**.
- Keep the existing guards unchanged: fresh inventory before upload and after
  success, signer continuity, 180 s install confirmation timeout, Wi-Fi requirement.
- Strings: short sentences, one action per card, no jargon ("patch" is explained once:
  "adds the glasses controls to YouTube").

## Part C — docs and release

- `docs/YOUTUBE_GLASSES.md`: rewrite around the four steps; move Morphe Manager to
  "Advanced".
- `BUSSPEC.md`: add one sentence under YouTube setup: the phone hub may receive the
  patched APK from the YouTube Patcher plugin as an activity result URI; still no bus
  route or capability.
- `plugins/youtube-patcher/README.md` and `CHANGELOG.md` like other plugins.
- `plugins/AGENTS.md`: the foreground-service exception, only if the patch job needs it.
- Release: the plugin follows `plugins/README.md` § Releases (namespaced tag such as
  `youtubepatcher-v1.0.0`, `CHANGELOG.md` section as notes, registry entry updated after
  the release assets exist). The hubs ship with a `v*` tag, which bumps both hubs.
  Build or release only on the owner's explicit go.

## Delivery slices

1. **Proof of patching** (half a day): `:plugin-youtube-patcher` skeleton, dependency
   wiring, JVM test that loads the real `.mpp`, then a debug button that patches the
   APK on the phone and saves the output. Measure time and memory on the owner's
   phone. Stop and report if memory fails before going further.
2. **Plugin patch screen** (about a day): APK and APKMirror bundle picker, split merge and validation, patch list with
   defaults, progress, signing with a persisted key, activity result with a URI.
3. **Hub hand-off** (half a day): four-step setup screen, plugin detection and Store
   link, result URI into `prepare(uri)` and automatic `install()`.
4. **Hardening and docs** (half a day): key export/import, error texts, docs, device
   run from a fresh phone state: no MicroG, no YouTube, no plugin.

## Acceptance

- From a phone with Nexus only, the owner completes setup with one browser detour
  (APKMirror) and no other app.
- The patch list shows the four key patches ticked; unticking one is kept for the
  next patch.
- A second patch (for example after a new bundle) installs over the first on the
  glasses without a signer error.
- No new bus route, capability or AIDL change. `:phone-hub`, `:glasses-hub`,
  `:shared` and `:plugin-youtube-patcher` test suites pass; the plugin passes the
  registry CI rules (no launcher activity, single signer).
- Evidence (screenshots, timings) stays under `E:\Tools\Rokid\tmp`, never committed.

## Decisions (owner, 2026-09-28)

1. **Name: YouTube Patcher.** It keeps "Morphe" out of the plugin's branding, which the
   GPLv3 §7 terms of Morphe Patches forbid for derivative works. Credit Morphe and
   link the fork in the plugin's settings and README.
2. **No foreground service.** Keep the screen on during the patch; see Patching runtime.
3. **Whatever is simplest for the user: accept both the APK and the APKMirror
   bundle.** Port `SplitApkPreparer`; see Patch screen step 1.

## Checkpoints for the implementer

Verify these in the code before building on them, and record the answer in this plan:

1. **Empty capability set.** Confirm the descriptor validator accepts a plugin with no
   capabilities, and how a phone-only plugin appears in the glasses launcher. If it
   shows up there, give it a one-line glasses surface ("Open YouTube Patcher on your
   phone") or hide phone-only plugins from the launcher, whichever is smaller.
2. **Approval for the hand-off.** No capability is used, so the activity result works
   without Nexus approval. Prefer not requiring it; if the hub's plugin model makes
   an unapproved plugin confusing, send the user to approval from step 3.
