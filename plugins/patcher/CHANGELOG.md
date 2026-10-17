# Changelog

## 1.1.1

- Show the installed version in the Patcher header, which still said v1.0.0 in 1.1.0.

## 1.1.0

- Add Reddit preview alongside YouTube, using the official complete Reddit 2026.14.0 APKM and the existing local validation, patching and signing engine.
- Keep both guided setups in Patcher: YouTube includes MicroG and its full tutorial; Reddit uses its own tutorial without MicroG. Update both Nexus hubs for the integrated guides.
- Show the real app icons in Nexus green and retain the existing Nexus settings design, key backup/import and job state.
- Add the green monochrome Reddit HUD with compact media previews, text paging, nested comments, exact reply context and the Nexus phone keyboard. Popular now loads directly after a cold launch, refreshes and paginates through native Reddit services.
- Include app-side R08 input integration using the same ring-focus contract as YouTube. Physical ring behavior and real Reddit writes remain unverified; Reddit stays labeled Preview.
- Fetch the published Reddit source bundle during builds and verify its SHA-256 before D8 preparation. Keep the YouTube source pin and updater unchanged.
- Preserve stored files, job state and signing keys during the Patcher update. No app is uninstalled to bypass a certificate mismatch.

## 1.0.0

- Deliver ready-notification job identity to the existing hub-attached window and
  preserve it across recreation. Nexus setup offers Open Patcher while waiting.
- Ship the Rokid patches 1.39.1-rokid.3 bundle: the first-launch "Restart required"
  dialog and other Morphe dialogs now work with the glasses touchpad and the R08 ring.
- Enter picture-in-picture while a patch runs and design that window: a label with a
  breathing dot, a large elapsed clock, the live line and a thin phosphor bar, closing
  in the outcome's colour at the end, with a Cancel action.
- Say honestly that a patch takes about 6–7 minutes with the screen or the floating
  window visible and much longer hidden or locked; drop the "a few minutes" and
  "turn the display off" claims everywhere.
- Name substeps in plain words with grouped class counts and decimal megabytes, show a
  notification bar only for measured movement, and put the substep and elapsed time in
  the notification text. The ready notification asks to keep the glasses connected.
- Show how old a saved result is above a plain "Use this result" action when Nexus asks
  again, and phrase every fallback message as what happened, then what to do.
- Rename the unpublished plugin to Patcher (`:plugin-patcher`, package
  `com.anezium.rokidbus.plugin.patcher`, id `patcher`). The new package generates a
  new private signing key; import a portable backup or manually reinstall YouTube
  on the glasses. Nexus still refuses signer changes and never uninstalls for you.
- Run explicit file preparation and patch jobs in a `dataSync` foreground service
  in `:patcher`, with notifications, explicit cancellation and a timed wake lock.
  App switches, screen-off and activity recreation preserve the job; interrupted
  work is reported with retry and completed results remain available on return.
- Add typed observable phases, byte/patch fractions, completed patch counts and
  monotonic elapsed time. Keep remaining time unknown until it can be estimated.
- Show the patch as a live screen: a ticking elapsed clock, a phosphor bar that fills
  when the amount is known and sweeps when it is not (static when animations are
  off), the current sub-phase, the last applied patch with its count, and five stages
  marked done, current or upcoming. Success, failure, cancelled and interrupted each
  explain themselves and offer the next step; nothing resets silently.
- Name the target and the phase in notifications, with elapsed time and Cancel while
  running and a result channel for ready, failed, cancelled and interrupted. Tapping
  one returns to the live screen even when it sits in the hub's task.
- Ask for notification permission once, at the first patch, with the reason shown
  beforehand; patching runs the same when it is denied.
- Log each measured phase under `Patcher`; atomically move the verified result,
  avoid the activity's redundant result hash/manifest read and retain stock for retries.
- Make stock signer/version/API-range checks, bundle pin/source, defaults and output
  identity target data. YouTube is the first target. Require a target id in the
  activity hand-off and keep signer-bound hub approval and byte validation intact.

- Add phone-only YouTube patching with real Morphe Patcher 1.7.0 and build-time
  Android preparation of the pinned genuine Rokid patch bundle.
- Validate stock identity and every split signature, merge splits for the glasses,
  expose compatible editable patch defaults and persist choices by version.
- Add visible progress, dedicated-process cancellation, persistent signing,
  authenticated password-protected key backups and content-URI/share/save output.
- Add JVM tests; phone/glasses end-to-end verification is pending.
- Target the glasses: fixed `arm64-v8a` ABI, every density split kept for a
  density-universal merge, 32-bit-only input refused, and the merged manifest
  verified to carry no split requirement.
- Keep patching across rotation and other configuration changes; only explicit Cancel
  stops the patch job. Back and closing the screen leave it running.
- Bound result retention: 24 hours at most, results older than the newest one after
  10 minutes, never the result being handed to the hub.
- Remember an online bundle update rejected for its content (no Android DEX, wrong
  patcher API, or no compatible patches) so it is not downloaded again until its
  version or URL changes or the plugin is reinstalled or updated; load the bundle
  once per screen opening.
- Switch to a different prepared bundle shipped in a plugin update on the next
  opening, after validation, forgetting earlier update rejections.
- Add tests for the GmsCore package default and bundle updates.
- Release CI: `patcher-v<semver>` tags (requiring a dedicated
  `read:packages` secret), unit tests in the Tests workflow, and GitHub Packages
  credentials passed only to this plugin's Gradle steps.
- Build on Windows: platform classpath separator and a configurable Python
  interpreter (`-PpythonExecutable`); the D8 and platform paths follow the SDK
  components AGP uses and name any missing one.
- Reproducible `bundled.mpp`: fixed ZIP metadata for the added DEX, so its recorded
  SHA-256 is stable across builds.
- Keep dependency licence and notice files in the APK and list every runtime
  dependency with its licence in `NOTICE`.
