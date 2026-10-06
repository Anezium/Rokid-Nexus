# Changelog

## 1.0.0

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
