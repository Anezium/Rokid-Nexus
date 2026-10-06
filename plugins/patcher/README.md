# Patcher

Phone-only Nexus plugin (`patcher`, API 3, empty capabilities,
`LAUNCHABLE=false`). No launcher icon, bus additions, glasses installation or
background polling. The explicit `PatchActivity` and private `PatchJobService` run in the dedicated `:patcher`
large-heap process and uses the Nexus UI kit. The phone hub may launch it with
`PatcherContract.ACTION_PATCH` for an activity result; no Nexus capability
is exercised by this Android hand-off.

## Targets and migration

`PatchTarget` is data: stock package/accepted versions, trusted stock certificate
digests and verification API range, bundle source and source SHA-256 pin, default
selection, output package, display name/icon and warnings. `PatchTargets` currently
contains only YouTube. Policy, preparation, bundle loading, signing, progress and
the screen operate on the selected target; choices and bundles live in target-specific
private directories. The request and result both require `PatcherContract.EXTRA_TARGET_ID`.
Unknown targets fail closed. The phone hub still exposes only its YouTube setup.

This unpublished plugin was renamed from YouTube Patcher: module `:plugin-patcher`,
package `com.anezium.rokidbus.plugin.patcher`, plugin id `patcher`, release prefix
`patcher-v` and artifact `patcher-phone-release.apk`. Its new Android package has
new private data and generates a new signing key. Existing output cannot update
over another signer: import an exported portable key backup or explicitly reinstall
YouTube on the glasses yourself if you accept losing its app data. Nexus never
uninstalls to bypass this guard. The owner expects that manual reinstall for the
end-to-end acceptance run. Update the hub and approve Patcher's new identity.

## Build and test

Java 17, Android platform 36, the build-tools version AGP uses (36.0.0 with AGP
9.2.0), Python 3 and normal Gradle repositories are required. A missing SDK
component fails `prepareAndroidBundle` with the `sdkmanager` package to install.
The script runs with `python3`, or `python` on Windows; override it with
`-PpythonExecutable=/path/to/python`. Do not change `local.properties`.

```sh
./gradlew :plugin-patcher:testDebugUnitTest :plugin-patcher:assembleDebug \
  -PskipCxrGlobal=true \
  -PpatchBundleInput=/absolute/path/to/patches-1.39.1-rokid.2.mpp
```

Omit `patchBundleInput` to download the pinned genuine release from GitHub during
preparation; that build needs network access (CI has it). A failed download or a
SHA-256 mismatch fails the build with the reason; nothing is skipped. The SHA-256 is
pinned to
`d07e9aae4a5b9fffdd8e2eb81dfcdf8f0305805a9b777ac094a5065d96df6601`.
The raw release is copied outside the APK assets for the JVM fixture test.
`prepareAndroidBundle` runs the installed SDK D8 against the real bundle and its
pinned dependency classpath, retains original resources/classes/notices, adds real
DEX, and records both original and prepared hashes. DEX entries get fixed ZIP
metadata, so the same inputs give the same `bundled.mpp` and hash on every build. No
generated binary is checked into git. The original rokid.2 release contains no DEX:
shipping it unprepared would not work on Android. No runtime conversion or fake loader is used.

Patcher 1.7.0 is pinned and proved against rokid.2 (137 patches, 80 compatible with
stock YouTube 21.04.223, 76 compatible defaults). The signing and split APIs now
live in this patcher, so a second incompatible `morphe-library` is not added.
Bouncy Castle is pinned to 1.79. Hubs do not request `app.morphe` artifacts.

GitHub Packages answers 401 to anonymous reads, even of public packages. Credentials
are attached only to its `app.morphe`-filtered repository, and only when both values
are non-blank: the `GITHUB_ACTOR`/`GITHUB_TOKEN` environment variables, else the
Gradle properties `gpr.user`/`gpr.key` (for example in `~/.gradle/gradle.properties`,
with a personal access token that has `read:packages`). An artifact already in
`mavenLocal()`, which the root settings list first, also satisfies the dependency.

CI passes them only to the Gradle steps that build this plugin, with `GITHUB_ACTOR`
set to `github.actor`. The release workflow **requires** the `MORPHE_PACKAGES_TOKEN`
repository secret, a personal access token with only `read:packages`: a
`patcher-v*` tag fails early without it. The Gradle steps, whose tests run
code from the third-party bundle, receive only that secret, and checkout does not
persist the workflow token; the write-capable `github.token` is passed only to the
later release-creation step, in the same job. The Tests workflow uses the secret when set, else its read-only
`github.token`, which may be unable to read packages owned by another organisation
(`MorpheApp`); if it fails with 401 while resolving `app.morphe`, add the secret.

## Releases

Follows [plugins/README.md § Releases](../README.md#releases): push the tag
`patcher-v<versionName>` (for example `patcher-v1.0.0`) after setting
`versionName` and adding the matching `CHANGELOG.md` section. The release asset is
`patcher-phone-release.apk`. The release build downloads the pinned bundle and
needs the GitHub Packages credentials above.

## Usage and safeguards

- Pick a stock APK, APKM/APKS/XAPK complete split bundle. Every source APK is
  cryptographically verified against Google's pinned YouTube signer; package,
  version and split version-code continuity are checked before merge. One loose
  split or split-required base is refused, including a base-only archive. Manifest
  `uses-split`, `requiredSplitTypes`/`splitTypes`, configuration owners and legacy
  `com.android.vending.splits.required` are checked before and after selection.
  Missing dependencies/types, duplicate modules and unresolved requirements fail
  closed. A legacy split-required flag without type names requires configuration
  modules; manifests cannot prove the presence of every optional/store-only module.
  ZIP extraction is bounded and does not use untrusted entry paths. The output runs
  on the glasses, not the phone: the target ABI is the fixed glasses ABI
  `arm64-v8a`, and every density configuration split is kept so the merged APK is
  density-universal (no glasses density is assumed). Language/feature splits are
  retained. A standalone APK or merge result with native code but no
  `lib/arm64-v8a/` is refused, so 32-bit-only builds fail. After merging, the
  manifest is re-read and must declare no split, `isSplitRequired=false`, empty
  `requiredSplitTypes`/`splitTypes`/`uses-split` and no
  `com.android.vending.splits*` meta-data; anything else fails closed.
  Unsupported/malformed bundles show an error.
- Unsupported split archives:
  - several base/universal APKs, or no base APK;
  - a single split APK on its own;
  - no `arm64-v8a` native split, or native splits that differ between features;
  - splits not signed by Google, with mixed versionCodes, or for another package;
  - configuration splits whose owner is missing, or features missing a required
    configuration type;
  - a legacy "splits required" base without configuration modules;
  - more than 128 APK entries or more than 1 GiB in total;
  - encrypted or nested archives.

  `.xapk` OBB files and `manifest.json` are ignored, not installed. Optional
  on-demand modules that the manifest does not declare cannot be detected. Merging
  feature modules and language splits has not been exercised against the real
  merger; only density and ABI configuration splits have.
- Compatible patches and descriptions come from the actual bundle. Key patches
  appear first; options keep bundle defaults. Selection is editable and persisted
  by bundle version, carrying choices forward and enabling new defaults.
- Bundle metadata is fetched only on screen opening from the Rokid fork. Downloads
  must be pinned fork release URLs. Android-incompatible bundles or wrong patcher
  APIs are refused without replacing the last working one. Runtime updates support
  only publisher-prepared `.mpp` archives containing Android `classes.dex` and the
  exact Patcher 1.7.0 API. The pinned rokid.2 raw release is JVM-only: upstream JVM-only
  updates do **not** work on the phone. Obtain a plugin rebuilt with a supported
  build-time-prepared bundle, or a publisher-prepared compatible release. There is
  no on-phone D8 conversion and no promise that "latest" is compatible.
  When a plugin update ships a different prepared bundle, the plugin switches to it
  on the next opening, after the same validation, and forgets earlier update
  rejections. An online update rejected for its content (no Android DEX, wrong
  patcher API, or no compatible patches) is not downloaded again until its version
  or URL changes or the plugin is reinstalled or updated. If a plugin update bundles
  an older bundle than one previously downloaded, the bundled one replaces it and
  the newer one is downloaded again at the next check.
  Executable files are created with an open descriptor, made read-only **before the
  first byte is written**, synced, validated and atomically renamed. Existing writable
  saved bundles migrate by copying to a new protected inode, not by chmod after writing.
  Cancellation/failure deletes uncommitted files; crash partials are cleared on opening.
  The active pointer changes atomically only after validation. The fork publishes no
  bundle signature; hashes are shown for inspection, not claimed as signatures.
- File preparation and patching run in a non-exported foreground `dataSync`
  service in `:patcher`, with an ongoing notification and a timed partial wake lock.
  Switching apps, Back, screen-off and activity destruction do not cancel the job.
  Only Cancel (screen or notification) stops it. No boot restart or automatic retry.
  Android's service timeout and the one-hour job limit stop unfinished work safely.
  Notification permission is asked once, at the first patch, with its reason shown on
  the screen beforehand; denial never blocks patching. A durable state reports
  interrupted work on the next open with a retry.
  Ready results survive activity recreation; the activity returns the result to the
  hub on reopening or offers share/save when opened from settings. Only a completely
  patched, signed and verified APK becomes a result.
- The signing key is generated once in private PKCS12 storage (Android backup is
  disabled). Backup/export is AES-256-GCM authenticated encryption with a salted
  PBKDF2-HMAC-SHA256 password key (210,000 iterations); import validates the
  key/certificate pair before atomically replacing the old key. Wrong passwords
  and tampering preserve the previous key. Backups need at least eight characters.
  This portable backup format does **not** import Morphe Manager keystores.
- Success returns a FileProvider `content://` URI with read grant and ClipData,
  plus informational package/version/hash extras. The hub copies the APK and
  re-validates its bytes itself; the extras are not trusted. From settings, share/save is offered instead. Unselecting
  GmsCore is explicitly confirmed; the stock-package result cannot satisfy the
  hub's patched-package policy. Unselecting Rokid controls is also warned.
- Uninstalling the plugin loses its signing key. Keep a password-protected backup.
  Existing Manager-patched YouTube has another signer: uninstall it manually on
  the glasses or continue using its original patcher/key. No silent uninstall.

## Timing and repeated runs

The service exposes `PatchJobStore.state`, a `StateFlow<PatchJobState>`. It includes
the phase and its index/total, an optional phase fraction, the most recent completed
selected patch with index/total, monotonic elapsed milliseconds, and durable terminal
success/failure/cancelled/interrupted states. Fractions measure bytes or reported
patch completions, not a time estimate. Unknown fractions use an indeterminate bar;
remaining time is deliberately null. Phase boundaries and terminal states are synced
to disk; callbacks and a one-second heartbeat update observers without disk writes.
Activity recreation reconnects to this same store, and process restart recovers the
last terminal result or marks unfinished work interrupted.

`PatchPresentation` turns that state into words: the ten patch phases read as five
stages (Load, Patch, Build, Sign, Save) with done/current/upcoming marks, a live line
names the sub-phase with a number only when one was measured, and the last applied
patch shows with its count. The screen adds a ticking elapsed clock and a
`PhosphorBar` that fills for a known fraction, sweeps for an unknown one and draws a
static dashed fill when the user has turned animations off. Notifications use the
same words, name the target and carry the elapsed time; the result notification
reopens the screen, and when the live screen already exists in the hub's task it is
brought forward (`REORDER_TASKS`) instead of a second copy. Failure, cancelled and
interrupted states are shown on the screen and as notifications, each with a retry.

Filter Android logs by tag `Patcher`. Each completed boundary emits
`step=<step> duration_ms=<integer> outcome=ok|failed|skipped`, with
`patch_index=<integer>` on patch-result intervals. No paths, exception text,
accounts or keys are included. Steps are `read_copy_input`, `split_extract`,
`signature_check`, `split_merge`, `bundle_load`, `patch_read`, `patch_apply`,
`patch_apply_total`, `patch_compile`, `write`, `align`, `sign`, `verify`,
`publish_result` and `hand_off`. A standalone input records split merge as skipped.
`patch_apply` measures between upstream result callbacks (including dependencies),
not a profiler inside each patch. `align` is nested inside `write` at the real
Patcher 1.7.0 alignment boundary; do not add nested durations to their parent.
`hand_off` ends at the activity result; hub validation and CXR install follow it.

Verified output moves atomically within private storage instead of copying another
170–214 MB APK. The activity no longer rehashes or reparses output for informational
extras; the runtime verification and independent hub byte validation stay intact.
The required writer copy uses the existing bounded 64 KiB buffer. Prepared stock
input survives retries, avoiding another document copy, signature check and split
merge until a different file is chosen. D8 is already a Gradle input-cached build
step, so no runtime D8 cache or mutable patch-instance cache is added. The job's
dedicated thread uses Android's default CPU priority even with the screen off.

## Trust model

- The phone hub installs this plugin's output automatically only while the plugin
  has an enabled Nexus approval (Plugin access) bound to its current signing
  certificate. That approval is the user's decision about the APK installed on the
  phone; it is not independent verification of the publisher.
- A Store install is pinned to the registry entry's `sha256` and `signerSha256`.
  A sideloaded build is trusted only through the user's approval.
- The patch bundle fetched from the Rokid fork has no signature: the plugin checks
  the pinned release URL and Android compatibility and shows its SHA-256 for
  inspection only.
- The hub re-validates the returned APK bytes (package, signer continuity, version,
  Android compatibility) and cannot prove which patches were applied.

## Verification limits

JVM tests cover metadata URL policy, stock identity policy, split selection and
archive safety, manifest-declared split completeness, persisted selection migrations,
genuine bundle loading, and key persistence/authenticated backup recovery. Read-only
lifecycle tests observe actual POSIX permissions before writing, before validation and
after atomic rename; cancellation at each lifecycle boundary and invalid-artifact
rejection preserve the working bundle. This is host-side lifecycle proof, not an
Android 14 ART/DexClassLoader execution test. `assembleDebug` verifies packaging,
including a prepared DEX bundle. These are not phone execution tests. This engine
phase used no phone or glasses: full stock 21.04.223 patching, memory/time measurements,
Android DEX loading, URI hand-off, glasses installation and Rokid rail operation
still require a device run. In particular the upstream patcher's Android runtime
is not certified by a desktop JVM test.

Robolectric also checks activity background/destruction/reopening without cancelling
the job, explicit Cancel routing, foreground `dataSync` notification/wake-lock lifetime,
task removal, failure cleanup and the ready notification. POSIX-only tests are skipped
on Windows: read-only bundle lifecycle/update checks and the recreated activity's real
FileProvider URI hand-off (Android path matching assumes `/`). Those are exclusions,
not passes. Run the same suite on a POSIX host for their full coverage.

## Licensing and attribution

This plugin module is GPL-3.0-only to comply with its linked GPLv3 patcher. The
Apache-2.0 bus client is GPLv3-compatible. No Manager source was copied; the split
preparer is independently implemented on the patcher's public merge API.
Morphe Patcher: https://github.com/MorpheApp/morphe-patcher (original upstream
Revanced code notices remain in that dependency). Rokid patches:
https://github.com/Anezium/morphe-patches/tree/rokid. The bundled release retains
its GPLv3 license, attribution and additional section 7 branding terms. The
plugin's product name is **Patcher**, not Morphe. Distribution must include
corresponding source for the plugin and exact dependencies/bundle, and comply with
those notices; no release is produced by this implementation task.

`NOTICE` lists every third-party artifact on the runtime classpath with its licence.
The APK concatenates the dependencies' `LICENSE*`, `NOTICE*` and
`META-INF/DEPENDENCIES` files instead of dropping them, which keeps the patcher's
GPLv3 section 7 notice. The rest of the Rokid Nexus repository stays Apache-2.0.
