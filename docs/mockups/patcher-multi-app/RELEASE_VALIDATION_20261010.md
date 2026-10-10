# Patcher 1.1.0 and Nexus 1.7.0 release preparation

## Scope and source preservation

The release branch combines the existing Patcher/Reddit work, `origin/main`
at `a306244b`, and the Skills/Workspace branch at `240577df`. Merge commit
`17ee76e7` preserves both the typed Skills contracts and the final native
Patcher tutorial entry points. The Skills worktree was not changed. Assistant
and SDK releases are outside this release's scope.

Four untracked files that overlapped incoming main files were preserved under
`build/tmp/release-preservation/untracked/` with a SHA manifest before merging.
The differing local glasses-home mockup remains there; the incoming tracked
version is used on the release branch. The unrelated launcher PR3A contract
remains untracked and is not part of this release.

## Reddit source bundle and R08

The public source prerelease is
[Reddit preview25](https://github.com/Anezium/morphe-patches/releases/tag/v1.39.1-rokid.3-reddit-preview.25),
at Morphe commit `5e1a8b9d4`. It contains patch code and extensions, not a Reddit
APK or signing material. Its source SHA-256 is
`68f18a16b5ef91e90cd61ec98f164918c59fad887f30b22183cdb83eafc697aa`.
The actual Patcher source resolver downloaded the public release without a
local input and verified this exact digest on 2026-10-10.

Preview24 exposed HUD selection but lacked the actual ring media-key transport
and the foreground focus claim expected by R08 Access Bridge. Preview25 adds
that existing package-bound focus contract and translates R08 slides/taps to
the same controller used by DPAD input. Pausing, destroying or losing window
focus releases the claim. Editor-origin taps cannot become a delayed Send.
Physical R08 hardware has not been tested. These are implemented and compiled
contracts, not a hardware certification.

The pinned YouTube patch bundle and YouTube production patch code are unchanged.
Reddit remains Preview; real sends, votes/save, moderation and optical comfort
are not certified by this release.

## Observed local verification

All commands used the existing SDK, Gradle caches and signing identity. No
`local.properties`, SDK or Gradle installation was modified.

- `python -m unittest discover -s plugins/patcher/scripts`: 11 tests, OK.
- Shared: 414 tests; bus-client: 149; phone hub: 770; glasses hub: 799.
  All 2,132 passed without skips or errors.
- Morphe patches: 120 tests passed, including eight new R08 policy tests.
- Patcher: 158 reported, 147 passed and 11 skipped; zero failures/errors.
  The real Reddit complete-APKM test executed, verified both the native Popular
  factory and R08 controller in output DEX, and checked signing, ARM64 libraries
  and the patch marker through the actual patch engine.
- Both Nexus 1.7.0 hubs and Patcher 1.1.0 release candidates built successfully.
  Manifest versions are 1.7.0/10700 and 1.1.0/2 respectively; all three retain
  certificate SHA-256
  `f5e938e2e79b0526b31e40d36c8c19098450c1636b7e14a306681b4effddf81c`.

Actual command tails:

```text
> Task :phone-hub:assembleRelease
BUILD SUCCESSFUL in 3m 41s
341 actionable tasks: 108 executed, 2 from cache, 231 up-to-date

> Task :patches:test
BUILD SUCCESSFUL in 1m 11s
211 actionable tasks: 9 executed, 202 up-to-date

> Task :plugin-patcher:assembleRelease
BUILD SUCCESSFUL in 2m 35s
185 actionable tasks: 35 executed, 150 up-to-date
```

Local evidence is under `build/outputs/patcher-1.1.0-release-20261010/`:
`hubs-build.log`, `hubs-tests.json`, `morphe-preview25-build.log`,
`patcher-real-engine-build.log`, `patcher-tests.json` and `artifacts.json`.
Local APKs are verification candidates built before the final release commit;
publication workflows must rebuild from the exact release tags. The temporary
real-engine test Reddit APK must not be installed or published.

## Device evidence and remaining limits

The earlier preview24 Popular/device checks are recorded in
`../reddit-glasses/PREVIEW24_VALIDATION_20261010.md`. They do not establish that
preview25 or the newly combined 1.7.0 hubs have been tested on hardware.
During this preparation pass the shared devices were being used by the
Skills/Assistant thread, so no device inputs or APK updates were issued.

Publication requires the normal repository checks and tagged release builds.
Store distribution additionally requires a registry manifest update with the
actual published APK digest and unchanged signing certificate.

## CI follow-up: overlapping bundle loading and key maintenance

The first PR #49 plugin check failed one UI assertion at
`PatcherAppFlowTest.kt:274`. The source was still refused and no service started,
but the asynchronous bundle-loading `busy` guard could mask the more specific
key-maintenance message. The picker now checks key maintenance before generic
screen work. The regression test explicitly holds both conditions and verifies
that the source and key lease stay intact, patching is refused until the lease
ends, and the key-maintenance explanation appears.

The complete `PatcherAppFlowTest` class passed locally after this change:

```text
> Task :plugin-patcher:testDebugUnitTest
BUILD SUCCESSFUL in 21s
66 actionable tasks: 5 executed, 61 up-to-date
```
