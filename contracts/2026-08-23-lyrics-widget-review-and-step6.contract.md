---
task: lyrics-widget-review-and-step6
date: 2026-08-23
status: active
scope_globs:
  - "shared/src/**"
  - "bus-client/src/**"
  - "phone-hub/src/**"
  - "glasses-hub/src/**"
  - "plugins/lyrics/**"
  - "plugins/AGENTS.md"
  - "BUSSPEC.md"
  - "docs/PLUGIN_SDK.md"
  - "contracts/2026-08-23-lyrics-widget-review-and-step6.contract.md"
forbidden_globs:
  - "registry/**"
  - ".github/**"
  - "site/**"
  - "plugins/!(lyrics)/**"
  - "**/settings.gradle.kts"
  - "gradle/**"
test_commands:
  - "gradlew.bat :shared:testDebugUnitTest :bus-client:testDebugUnitTest :phone-hub:testDebugUnitTest :glasses-hub:testDebugUnitTest :plugin-lyrics:testDebugUnitTest"
  - "gradlew.bat :glasses-hub:assembleDebug :phone-hub:assembleDebug :plugin-lyrics:assembleDebug"
max_failures: 2
---

# Goal

HEAD `d9fcbf80` is an unfinished WIP of lyrics-widget step 6 on branch `lyrics-widget`.
A supervisor review found six real bugs in steps 0-5. After this run:

1. All six review findings (A1-A6) are fixed and covered by the tests named below.
2. Step 6 of `contracts/2026-08-16-lyrics-home-widget.contract.md` is finished: the
   plugin compiles, the widget is driven from snapshots, trigger-opened instances
   never raise the full-screen surface, the mode setting exists, the MEDIA_TRIGGER
   manifest token is present, and acceptance test #6 is green.
3. `/widget/*`, the MEDIA_TRIGGER token, and the media trigger as a hub-initiated
   open reason are documented. Lyrics CHANGELOG has an Unreleased entry.

# You are the executor

Work in this checkout. Do not re-derive the product. The governing product
contract is `contracts/2026-08-16-lyrics-home-widget.contract.md`; where THIS
file is more specific, THIS file wins. Implement, test, and commit. Do not push.

# Non-goals

- No device validation, no screenshots, no ADB.
- No plugin-side background media monitoring. The hub trigger is the design.
- No changes to `/widget/*` wire payload shape, existing paths, or surface policy.
- No other plugin, no registry, no CI, no site, no CxrGlobal, no Gradle wrapper.
- No history rewrite. Commit ON TOP of `d9fcbf80`.
- No AI attribution anywhere.

# Constraints

- MUST commit in two coherent steps on top of `d9fcbf80`:
  1. Review findings A1-A6 (phone-hub + glasses-hub + their tests).
  2. Step 6 plugin wiring + acceptance #6 + docs (BUSSPEC, PLUGIN_SDK, plugins/AGENTS.md,
     lyrics CHANGELOG Unreleased).
- Commit author Anezium, English imperative subjects, same style as:
  "Add the hub media trigger that opens the lyrics plugin"
  "Render the ambient lyrics widget on the glasses with a karaoke hold"
- MUST NOT push, tag, or amend `d9fcbf80`.
- MUST NOT touch `registry/**`, `.github/**`, `site/**`, `**/settings.gradle.kts`,
  `gradle/**`, CxrGlobal, or any plugin other than lyrics.
- MUST NOT change `local.properties` or invent a private SDK/Gradle home.
- If a test_command fails for an environment reason, stop and report. Do not work around it.
- Never report a test/build result you did not observe. Paste the real tail.

# Part A — six review findings

## A1. Pass our NLS ComponentName; drop the bogus uses-permission

File: `phone-hub/src/main/java/com/anezium/rokidbus/phone/MediaTriggerNotificationListenerService.kt`

Today:

- `addOnActiveSessionsChangedListener(listener, null, handler)` at L46-50
- `getActiveSessions(null)` at L61

Both throw `SecurityException` without the signature permission `MEDIA_CONTENT_CONTROL`.
The grant we have is notification access. You MUST pass our own listener component
in BOTH calls:

```
ComponentName(this, MediaTriggerNotificationListenerService::class.java)
```

The existing comment "no component filter: watch every package's sessions" is wrong.
The second argument of `addOnActiveSessionsChangedListener` is the notification-listener
component that authorizes the call, not a package filter. Fix the comment.

Also remove this line from `phone-hub/src/main/AndroidManifest.xml` L28:

```
<uses-permission android:name="android.permission.BIND_NOTIFICATION_LISTENER_SERVICE" />
```

Keep the service's `android:permission="android.permission.BIND_NOTIFICATION_LISTENER_SERVICE"`
attribute at L165. That is the correct use.

## A2. Controller callbacks, not just session-set changes

Same file. Playback edges currently come only from `OnActiveSessionsChangedListener`,
i.e. changes to the SET of active sessions. Play/pause inside an existing session
(the common case: user pauses Spotify) changes no set membership and produces no edge.

MUST register a `MediaController.Callback` (`onPlaybackStateChanged`, `onSessionDestroyed`)
on each active controller. Re-register as the set changes. Unregister stale controllers
on set change, onListenerDisconnected, and onDestroy. Funnel every change through the
existing `pushPlayingState()` aggregation. Zero polling.

Keep `OnActiveSessionsChangedListener` — the contract requires both.

## A3. `playbackState?.isActive` is API 31+; minSdk is 30

Same file L64. On Android 11 this is a swallowed `NoSuchMethodError` and the trigger
is silently dead.

Replace with an explicit state check that compiles for API 30. Treat as playing:

- `STATE_PLAYING`
- `STATE_BUFFERING`
- `STATE_FAST_FORWARDING`
- `STATE_REWINDING`

Do NOT include `STATE_CONNECTING` or `STATE_SKIPPING_*` (transient, not "music is playing").
MUST NOT use a `Build.VERSION` branch that leaves API 30 without a working path.

Extract the predicate if it helps tests; a small package-visible helper is fine.

## A4. Karaoke hold must renew, and the 10-min ceiling must stick

Files:

- `glasses-hub/src/main/java/com/anezium/rokidbus/glasses/LyricsWidgetKaraokeHold.kt`
- `glasses-hub/src/main/java/com/anezium/rokidbus/glasses/LyricsWidgetDisplayHold.kt`
- `glasses-hub/src/test/java/com/anezium/rokidbus/glasses/KaraokeHoldPolicyTest.kt`

`KaraokeHoldPolicy.update` returns `Acquire` only on the not-held → held transition
(L55-59). The renewal tick scheduled at `DEFAULT_HOLD_MS / 2` (DisplayHold L52)
therefore gets `Action.Nothing`, the 8 s wake lock expires, and the 5 s ROM timeout
kills the display ~13 s into every song.

`sawCeilingReset` (L28) is unused. After a ceiling `Release`, `held` is false, so the
next `wantHold` tick re-acquires and the ceiling oscillates.

MUST:

1. A tick while `held && wantHold` and still under the ceiling returns
   `Action.Acquire(holdMs)` (renewal). Ceiling and idle-release still win over renewal.
2. After a ceiling release, further updates for the SAME `contentKey` MUST NOT
   re-acquire. Only a `contentKey` change resets the ceiling. Use the unused flag
   (or equivalent). Do not reset the ceiling on pause/play of the same track.
3. Keep existing tests green. Release-grace semantics may stay as they are.

Extend `KaraokeHoldPolicyTest`:

- (a) a renewal tick while playing re-acquires before the previous hold's timeout
  (e.g. acquire at t=0, tick at t=4000 still playing+karaoke → `Acquire`).
- (b) after the ceiling fires, subsequent playing ticks on the SAME track stay
  released (`isHeld == false`, no further `Acquire`). A new `contentKey` still
  re-acquires (existing test already covers the new-track reset).

## A5. Grace close must not close a different plugin

File: `phone-hub/src/main/java/com/anezium/rokidbus/phone/MediaTriggerCoordinator.kt` L60-65

`closeTriggeredPlugin` calls `externalPluginController.closeActive(...)`, which closes
WHATEVER plugin is active. If the user opened another plugin during the 60 s grace
(the controller already closed lyrics via its switch path at
`ExternalPluginController.kt:54`), the trigger then closes the user's plugin.

Before closing, verify the active plugin is still the media-trigger plugin. Compare
against `resolveRegisteredPlugin()`. `ExternalPluginController` already exposes
`activeId()` (L154). Prefer comparing `grantKey()` if you expose a small accessor;
comparing `activeId()` to `resolveRegisteredPlugin()?.descriptor?.id` is also fine.
If it is not the trigger plugin (or nothing is active), drop the hold only:
`isHoldingOpen = false`, no `closeActive`.

Add a test in `phone-hub/src/test/java/com/anezium/rokidbus/phone/MediaTriggerCoordinatorTest.kt`:

- Open lyrics via the trigger.
- Open a different principal through the same `ExternalPluginController` (this
  closes lyrics via the switch path).
- Fire the 60 s grace.
- Assert: no `PLUGIN_CLOSE` for the other plugin (or at most the switch-close of
  lyrics, not a second close of the new plugin), `isHoldingOpen == false`.

You will need a second `PhonePluginPrincipal` (different id / grantKey). Copy the
existing principal construction in that test file.

## A6. 60 % max width is currently `min(-2, x) = -2`

File: `glasses-hub/src/main/java/com/anezium/rokidbus/glasses/LyricsWidgetOverlayRenderer.kt` L186-199

```
width = min(WindowManager.LayoutParams.WRAP_CONTENT, maxWidthPx)
```

`WRAP_CONTENT` is -2, so the clamp is a no-op and a long line renders full-width.

Fix: keep the window width as `WRAP_CONTENT`. Enforce `maxWidthPx` on the TextViews
and/or the container (`TextView.maxWidth = maxWidthPx`), same pattern as
`PinOverlayRenderer.kt` L186-207. Keep single-line + END ellipsis (already at L240-242).
Children should wrap content (not MATCH_PARENT, which fights WRAP_CONTENT parent
measurement). Window stays horizontally centered.

# Part B — finish step 6

HEAD `d9fcbf80` does not compile. Fix every compile break and complete the wiring.

## B1. Compile breaks (must fix)

`LyricsRuntimeHost` (`plugins/lyrics/src/main/java/com/anezium/rokidbus/lyrics/LyricsRuntime.kt` L19-27)
gained `showWidget` / `updateWidgetAnchor` / `hideWidget`.
`LyricsPluginService` (`plugins/lyrics/src/main/java/com/anezium/rokidbus/plugin/lyrics/LyricsPluginService.kt`
L18-36) does not implement them. Add the three methods, delegating to the SDK client
exactly like the surface methods delegate to `nexusSurfaceSession`:

```
override fun showWidget(widget: NexusLyricsWidget) { nexusClient?.showWidget(widget) }
override fun updateWidgetAnchor(...) { nexusClient?.updateWidgetAnchor(...) }
override fun hideWidget() { nexusClient?.hideWidget() }
```

`LyricsRuntime.unregister`/`close` (L87) calls `widgetDriver.hideForTest()`. The driver
has `reset()` (`LyricsWidgetDriver.kt` L80-84). Replace with `widgetDriver.reset()`.

`handleWidgetState` (L144) does `if (decision.show) showWidget(snapshot)` where
`snapshot` is `LyricsSnapshot?` and `showWidget` takes a non-null. Only call
`showWidget(snapshot)` when `decision.show && snapshot != null`. The driver already
returns hide for a null snapshot.

`LyricsWidgetMode.DEFAULT` is declared `const val DEFAULT = KARAOKE` in
`LyricsWidgetSettingsStore.kt` L15. `const val` cannot be an enum. Change to
`val DEFAULT = KARAOKE` if it does not compile.

## B2. Background open vs full-screen surface

Contract line: a trigger-opened (background) plugin instance MUST NOT show any
surface other than the widget.

Today `handleWidgetState` L138 is wrong:

```
val visible = !backgroundOpen && fullScreenVisible
```

That inverts the meaning. Correct split:

- `backgroundOpen == true` → `pushState` MUST NOT send the full-screen timed-lines
  / card surface (the `host.sendTimedLines` / `host.sendCard` / `host.updateTimedLinesAnchor`
  show path). Widget channel only. When a later normal open arrives, full-screen
  resumes unchanged.
- `fullScreenVisible` is passed to `widgetDriver.decide` as-is (true → hide widget).
- `setBackgroundOpen` should also re-evaluate widget + suppress/resume surface as needed.

Wire `setBackgroundOpen` from the open type in `LyricsPluginService.onNexusOpen`:

```
runtime.setBackgroundOpen(currentOpenType == BusPaths.PLUGIN_OPEN_TYPE_MEDIA_TRIGGER)
```

`currentOpenType` is already on `NexusPluginService` (L39-40). Clear on close
(`setBackgroundOpen(false)` in `onNexusClose`). Open is re-entrant: a later
user/launcher `PLUGIN_OPEN` re-invokes `onNexusOpen` with a different type, which
clears background mode and lets full-screen resume.

## B3. `setFullScreenVisible` from the existing surface show/hide flow

When the host actually shows the full-screen surface (`sendCard`/`sendTimedLines`
with `show == true`), call `runtime.setFullScreenVisible(true)`.
When `hideSurface` runs, call `setFullScreenVisible(false)`.
`setFullScreenVisible` already re-runs `handleWidgetState` (LyricsRuntime L63-67).

Do not invent a second visibility source. The widget hides while the full-screen
lyrics surface is up and reappears after it closes (driver already implements that
when `fullScreenVisible` is true).

## B4. Mode setting row

One new row in `LyricsSettingsActivity`, following its existing `settingRow` visual
conventions exactly (see L80-118). Three choices Off / Glance / Karaoke, backed by
the already-written `LyricsWidgetSettingsStore`, default Karaoke, applied live
(`LyricsRuntime.setWidgetMode`; Off hides immediately).

The plugin service and the settings activity do not share an object. Use
`LyricsRuntimeGraph` (or a SharedPreferences listener) as the live bridge: settings
writes the store then notifies the running runtime if the plugin is open. Load the
persisted mode in `onNexusOpen` / `runtime.open` so a fresh open starts on the saved mode.

The row also shows a hint about the hub's notification-access grant and deep-links
to `Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS`. The grant state readable from
the plugin is limited — a hint that always deep-links is acceptable. Do NOT build
IPC just for this. Do NOT confuse this with the existing "Music access" row, which
is the plugin's own NLS (`MediaNotificationListenerService`). Keep that row. The
new row is the widget mode; its subtitle/hint can say the glasses widget needs
Nexus notification access.

A three-choice picker can be a small dialog or cycling the value on tap — match
the file's existing dialog style (`showSpotifyDialog` etc.), not a new UI kit.

## B5. Manifest

Add next to the existing plugin meta-data in
`plugins/lyrics/src/main/AndroidManifest.xml` (after L75 is fine):

```
<meta-data
    android:name="com.anezium.rokidbus.plugin.MEDIA_TRIGGER"
    android:value="true" />
```

The token is already `BusConstants.META_PLUGIN_MEDIA_TRIGGER`. The hub already
parses it (`PluginDescriptor.mediaTrigger`). Without this, the trigger never
resolves a plugin.

## B6. Acceptance test #6

New plugin-lyrics unit test on `LyricsWidgetDriver` and/or `LyricsRuntime`:

- playing + synced lyrics → show sent
- pause > 5 s → hide
- no lyrics → no show

`LyricsWidgetDriver` is the right primary target (pure, clock-injected). Also add
a `LyricsRuntime` host-fake test that:

- `setBackgroundOpen(true)` then a playing+synced snapshot does NOT call
  `sendTimedLines`/`sendCard`
- a later `setBackgroundOpen(false)` + force/open resumes the surface path

`LyricsSnapshot` is in `plugins/lyrics/src/main/java/com/anezium/rokidbus/lyrics/contracts/LyricsContracts.kt`.
There is no `LyricsWidgetDriver` test file yet; create one next to
`plugins/lyrics/src/test/java/com/anezium/rokidbus/lyrics/LyricsRuntimeTest.kt`.

# Part C — docs

Document, do not invent a new payload. The wire is already
`shared/.../WidgetSurfaceContract.kt` (`kind=widget`, `contentKey`, `lines[{timeMs,text}]`,
`anchor{positionMs,playing,sentAtElapsedRealtime}`, optional `holdDisplay`).
SDK model is `NexusLyricsWidget` + `NexusPluginClient.showWidget` /
`updateWidgetAnchor` / `hideWidget`. Paths: `/widget/show`, `/widget/update`,
`/widget/hide`. Requires `surfaces`. Outside SURFACE_BUSY / PLUGIN_CLOSE.

- `BUSSPEC.md`: a "Widget protocol v1" section after the pin protocol
  (`BUSSPEC.md` ~L363). Same factual density as the pin section. Mention
  `com.anezium.rokidbus.plugin.MEDIA_TRIGGER` (`true`) as the declarative
  registration token the hub reads from the installed plugin manifest.
- `docs/PLUGIN_SDK.md`: a short section near Persistent pins (~L800) covering
  `showWidget` / `updateWidgetAnchor` / `hideWidget` on `NexusPluginClient`,
  capability gating (`supportsWidgetSurface` if that helper exists — check
  `NexusPluginClient.kt` ~L87), and that a widget is not a foreground surface.
  Also document the MEDIA_TRIGGER meta-data token.
- `plugins/AGENTS.md`: add a short paragraph documenting the media trigger as a
  hub-initiated open reason (like scheduled delivery). The dormant-unless-open
  doctrine itself MUST NOT be weakened or reworded. Add it as a third sanctioned
  exception or a sibling sentence under §1, without touching the existing two
  exceptions' wording.
- `plugins/lyrics/CHANGELOG.md`: add an `## Unreleased` entry above 1.0.3
  describing the ambient home widget. Do NOT edit the 1.0.0 entry.

# Plan sketch

1. A1-A3 + A5 in phone-hub, plus the A5 coordinator test.
2. A4 policy + tests; A6 overlay maxWidth.
3. Commit 1: review findings.
4. B1-B6 plugin compile + wiring + setting + manifest + tests.
5. Part C docs.
6. Run both test_commands. Fix failures (max 2 attempts).
7. Commit 2: step 6 + docs.

# Context the executor cannot re-derive

- Branch is `lyrics-widget`. HEAD `d9fcbf80` is dirty-in-intent but the tree is
  committed. Do not reset it.
- phone-hub `minSdk = 30`. glasses-hub is API 32. Do not use API 31-only members
  without a compile-time API 30 path.
- `PlaybackState.isActive` covers PLAYING, BUFFERING, FF, REWIND, CONNECTING,
  SKIPPING_TO_*. We want the first four only.
- `ExternalPluginController.open` of a different principal already closes the
  previous via `closePrincipal(..., "switch")` (L54). A5 is about the *later*
  grace `closeActive`.
- Widget window: `TYPE_ACCESSIBILITY_OVERLAY`, not-focusable, not-touchable,
  translucent, phosphor text, black bg. Do not restyle.
- Karaoke hold must never touch `DisplayWakePolicy` / `SurfaceController.wakeScreen()`.
  Glance mode: zero display interaction. Already true; do not regress.
- `LyricsPluginService` surface methods go through `nexusSurfaceSession("lyrics")`.
  Widget methods go through `nexusClient`, like pins — not through the surface session.
- Settings visual language: `NexusUi` + `BusTheme`, phosphor, existing `settingRow`.
- Build: `gradlew.bat` from the repo root. Hubs must NOT be built with
  `-PskipCxrGlobal=true`. Plugins may use that flag but the listed commands do not
  need it. Do not modify `local.properties`.
- Sibling `../CxrGlobal` must exist for hub builds; if it does not, report and stop.

# Acceptance tests

| # | Check | Command | Expected |
|---|-------|---------|----------|
| 1 | Unit tests | `gradlew.bat :shared:testDebugUnitTest :bus-client:testDebugUnitTest :phone-hub:testDebugUnitTest :glasses-hub:testDebugUnitTest :plugin-lyrics:testDebugUnitTest` | BUILD SUCCESSFUL |
| 2 | APKs | `gradlew.bat :glasses-hub:assembleDebug :phone-hub:assembleDebug :plugin-lyrics:assembleDebug` | BUILD SUCCESSFUL |
| 3 | A1 component name | grep MediaTriggerNotificationListenerService for `getActiveSessions` / `addOnActiveSessionsChangedListener` | both pass a ComponentName of this service; no `null` component |
| 4 | A1 manifest | grep phone-hub AndroidManifest for BIND_NOTIFICATION_LISTENER | only the service `android:permission`, no uses-permission |
| 5 | A2 callbacks | MediaTriggerNotificationListenerService registers MediaController.Callback | onPlaybackStateChanged + onSessionDestroyed present; stale unregister present |
| 6 | A3 API 30 | no `playbackState?.isActive` in phone-hub media trigger | explicit STATE_* check |
| 7 | A4 renewal + ceiling | KaraokeHoldPolicyTest new cases | both green |
| 8 | A5 foreign plugin | MediaTriggerCoordinatorTest new case | green; closeActive not used on a foreign active plugin |
| 9 | A6 width | LyricsWidgetOverlayRenderer | no `min(WRAP_CONTENT, ...)`; TextView/container maxWidth used |
| 10 | Step 6 compile | plugin-lyrics assemble | LyricsPluginService implements the three host methods |
| 11 | Acceptance #6 | plugin-lyrics unit test | playing+synced → show; pause >5s → hide; no lyrics → no show |
| 12 | Background open | plugin-lyrics unit test | backgroundOpen suppresses sendTimedLines/sendCard |
| 13 | Manifest token | plugins/lyrics AndroidManifest | MEDIA_TRIGGER true |
| 14 | Docs | grep widget / MEDIA_TRIGGER in BUSSPEC.md docs/PLUGIN_SDK.md plugins/AGENTS.md | documented; dormant-unless-open wording in AGENTS.md unchanged |

# Escalation triggers (mechanical — never self-assessed)

- Any test_command fails after 2 fix attempts.
- Diff touches files outside scope_globs or matching forbidden_globs.
- Any change to existing wire paths or `/widget/*` payload shape becomes "necessary".
- CxrGlobal / SDK / local.properties is missing or a build fails for environment reasons.

# Autonomy

Decide alone: internal naming, helper extraction, settings picker (dialog vs cycle),
whether A5 compares id or grantKey, exact test fixtures.

Stop and report: escalation triggers; any need to widen scope; any conflict between
this file and existing code comments/specs (quote both).

# Report back

End your last.md / final message with:

- what changed per finding (A1-A6)
- what step 6 required beyond the unfinished WIP
- full test/build output summary (pass/fail per module) with the real command tails
- anything you had to decide alone
