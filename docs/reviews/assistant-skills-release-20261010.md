# Assistant 1.5.0 and Skills provider release checks

Date: 2026-10-10. Source base: `8a9de621` (merged PR #50).

This release changes versions and release notes only. It preserves the tested
Skills/Workspace implementation and the final named-page viewing correction.
Both Nexus hubs 1.7.0 already publish the Skills routing framework.

| Plugin | Version | versionCode | Unit tests |
| --- | --- | --- | --- |
| Assistant | 1.5.0 | 19 | 552 |
| Transit | 1.0.5 | 6 | 130 |
| Media Deck | 1.0.3 | 4 | 20 |
| Hello Nexus (sample) | 1.0.5 | 6 | 17 |

All 719 observed tests passed: zero failures and zero errors. Debug Assistant
and release APKs for all four modules assembled locally. Local release builds
are compilation evidence; published artifacts must come from the tagged CI
release workflow and retain the existing release certificate.

Actual command:

```powershell
.\gradlew.bat :plugin-assistant:testDebugUnitTest :plugin-assistant:assembleDebug :plugin-assistant:assembleRelease :plugin-transit:testDebugUnitTest :plugin-transit:assembleRelease :plugin-media:testDebugUnitTest :plugin-media:assembleRelease :plugin-sample:testDebugUnitTest :plugin-sample:assembleRelease -PskipCxrGlobal=true --console=plain
```

Actual output tail:

```text
BUILD SUCCESSFUL in 1m 27s
409 actionable tasks: 192 executed, 53 from cache, 164 up-to-date
```

Device evidence and remaining limits are recorded in
[the final device pass](skills-workspace-final-device-pass.md) and
[the persistent-index recheck](persistent-workspace-index-device-recheck-20261010.md).
Those passes used local Assistant 1.4.9 before this version-only bump.
Written input, approved read operations, PDF citations and visual pages were
exercised. Voice, Transit during a real journey, and real music players remain
unverified; Media used a test player. Workspace retrieval is lexical, with
large-folder and semantic/hybrid qualification still pending.

Skill operations require explicit wearer approval. Relevant document excerpts
and requested page images may reach the configured AI provider. No Reddit
content is posted by this release preparation.
