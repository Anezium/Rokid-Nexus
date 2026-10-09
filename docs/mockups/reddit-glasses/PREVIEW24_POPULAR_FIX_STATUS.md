# Popular cold-start source fix: build blocked

Date: 2026-10-09. Intended next preview: 24. No preview24 bundle or APK has been produced or installed.

The last actual preview23 smoke reproduced Popular failing after a cold launch and Retry. Home and Latest loaded. Popular depended on a constructor-captured paging owner that the stock Popular screen had not initialized.

## Source changes

The five-file fix is applied in `E:/Tools/Rokid/Morphe-Patches-restartfix`. [PREVIEW24_POPULAR_FIX.patch](PREVIEW24_POPULAR_FIX.patch) preserves the exact changes against the previous local source, including the new factory and regression tests.

- Construct Popular from the active native account/app graphs using the inspected `hc1.a1.L2` dependency order and `eo.d("popular")` analytics scope. Never construct a stock screen or add a separate HTTP/authentication path.
- Validate native types and session ownership before publishing a paging owner, and retain that owner per graph for native pagination/correlation state. The inspected first-page path clears consumed IDs and rotates correlation state on refresh.
- Keep graph, session and account ownership through feed loading and link hydration, including empty-page completion. Anonymous public feeds remain allowed; stale owner callbacks fail closed.
- Add two regression cases for anonymous ownership and graph/session replacement. They have **not run** because the build fails before test compilation.

A second read-only review by GPT-6.1 Sol checked the pinned stock DEX constructor signatures, argument order, provider field types and stable session identity. Its empty-page guard finding was applied. This is source/ABI evidence, not runtime validation.

The launch route is unchanged: `RedditController` opens the main menu with Home selected. Selecting Home opens the currently signed-in user's native personalized feed. Popular is the public feed. No direct-feed startup was requested or implemented.

## Actual build failure

Command, from the existing Morphe checkout:

```text
.\gradlew.bat :patches:test :patches:jar -Pversion=1.39.1-rokid.3-reddit-preview.24 --console=plain
```

Actual output tail:

```text
FAILURE: Build failed with an exception.

* Where:
Settings file 'E:\Tools\Rokid\Morphe-Patches-restartfix\settings.gradle.kts' line: 22

* What went wrong:
An exception occurred applying plugin request [id: 'app.morphe.patches', version: '1.3.3']
> Failed to apply plugin 'app.morphe.patches'.
   > java.lang.IllegalArgumentException (no error message)

BUILD FAILED in 20s
```

One diagnostic retry with `--stacktrace` also failed before compilation. Its cause is `OrElseFixedValueProvider.calculateOwnValue` while `app.morphe.patches.gradle.SettingsPlugin.configureDependencies` configures repository credentials (SettingsPlugin.kt:48). The existing settings read GitHub Packages credentials from Gradle properties/environment. This is a build-environment configuration blocker, not a reported Java/test failure.

Per repository instructions, no environment repair or further build workaround was attempted. `local.properties`, SDK, Gradle setup, credentials and signing identities were not changed. Root Patcher still pins preview23; the YouTube input is unchanged.

## Preservation and remaining checks

Exact previous source backup (33 files) and fixed source snapshot (five files plus SHA manifest) are under `build/outputs/reddit-popular-fix-preview24-20261009/`. The root branch keeps the reviewable source patch and this report. Unrelated Launcher and Skills/Workspace files are untouched.

No device was used in this fix turn. No account login, Reddit send/post/comment/vote/save, APK installation, hub replacement or public release occurred. Skills/Workspace remains active; device QA must wait for it to release the devices.

After normal build credential configuration is available: run Morphe tests/bundle build, freeze the new bundle and update both Reddit pins, exercise the real complete APKM through Nexus' engine, build/sign with existing identities, then verify Popular **as the first feed after a cold launch**, Retry/refresh/pagination and Home/Latest regressions on free glasses. Only actual successful results can close the prior Popular defect.
