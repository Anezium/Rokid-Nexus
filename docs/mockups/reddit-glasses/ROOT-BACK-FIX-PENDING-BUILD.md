# Root Back exit correction - source candidate, not deployed

Date: 2026-10-08. The user reported that Back from a feed opened the main menu,
then another Back returned to the feed instead of leaving Reddit.

## Reproduction and source change

The installed preview17 reproduced this exact sequence using verified glasses ADB
compatibility keys. Evidence is under
`E:/Tools/Rokid/reddit-patch-lab/exit-preview18-20261008`:
`before-root-menu.png`, `before-unwanted-feed-return.png`, and `events.jsonl`.

`RedditController.back()` previously returned to the cached feed at MENU whenever
posts were available. The corrected MENU case calls a shared exit method. The
explicit Exit Reddit row uses the same method. It closes the controller/media and
removes the activity's task with `finishAndRemoveTask()`. Controller close is
idempotent so the subsequent lifecycle destroy callback can safely close it again.
Nested post/comment/media/editor Back handling is unchanged.

The exact small change is `root-back-exit.diff`; before/after source snapshots are
beside it. The controller remains in the existing Morphe checkout, not a new fork.

## Build blocker and actual state

Command attempted twice, second time with a stack trace:

```powershell
.\gradlew.bat :patches:test :patches:jar --console=plain --stacktrace
```

Actual output excerpt:

```text
Settings file 'E:\Tools\Rokid\Morphe-Patches-restartfix\settings.gradle.kts' line: 22
An exception occurred applying plugin request [id: 'app.morphe.patches', version: '1.3.3']
> Failed to apply plugin 'app.morphe.patches'.
   > java.lang.IllegalArgumentException (no error message)
BUILD FAILED in 1s
```

The stack identifies `SettingsPlugin.configureDependencies` at lines 46/48 through
`OrElseFixedValueProvider`. Both `GITHUB_ACTOR`/`GITHUB_TOKEN` are absent from this
session and `gpr.user`/`gpr.key` are absent from the user's Gradle properties. The
project's GitHub Packages credentials depend on these. The failure occurs during
settings/plugin application before compilation or tests.

Per repository build-environment instructions, no credentials, SDK/cache paths,
local.properties, Gradle properties or plugin versions were changed to bypass it.
No secrets were requested/read/extracted and no substitute credentials were used.

The preview18 source candidate has **not been compiled or tested on device**.
No preview18 bundle, APK, source pin or asset was produced. Installed Reddit remains
preview17 and still has the root Back bug. The prior immutable preview17 export and
its bundle/signing state remain unchanged. No commit/release/phone change occurred.

## Recommended order after restoring normal build authentication

1. Build this root-exit correction and verify feed -> menu -> launcher, explicit
   Exit, relaunch and nested Back restoration on the glasses.
2. Repair Search results and Communities using the pinned native services/screens
   rather than the generic web/main-menu projections that failed QA.
3. Repair Inbox/Profile/Saved, native settings and popup/backdrop containment.
4. Deploy the normally signed Nexus phone update for the existing Reddit automatic
   keyboard toggle, then rerun navigation and physical-input checks.
