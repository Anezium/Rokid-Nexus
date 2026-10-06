# Changelog

## 1.0.0

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
- Keep patching across rotation and other configuration changes; back, cancel and
  closing the screen still stop it.
- Bound result retention: 24 hours at most, results older than the newest one after
  10 minutes, never the result being handed to the hub.
- Remember an online bundle update rejected for its content (no Android DEX, wrong
  patcher API, or no compatible patches) so it is not downloaded again until its
  version or URL changes or the plugin is reinstalled or updated; load the bundle
  once per screen opening.
- Switch to a different prepared bundle shipped in a plugin update on the next
  opening, after validation, forgetting earlier update rejections.
- Add tests for the GmsCore package default and bundle updates.
- Release CI: `youtubepatcher-v<semver>` tags (requiring a dedicated
  `read:packages` secret), unit tests in the Tests workflow, and GitHub Packages
  credentials passed only to this plugin's Gradle steps.
- Build on Windows: platform classpath separator and a configurable Python
  interpreter (`-PpythonExecutable`); the D8 and platform paths follow the SDK
  components AGP uses and name any missing one.
- Reproducible `bundled.mpp`: fixed ZIP metadata for the added DEX, so its recorded
  SHA-256 is stable across builds.
- Keep dependency licence and notice files in the APK and list every runtime
  dependency with its licence in `NOTICE`.
