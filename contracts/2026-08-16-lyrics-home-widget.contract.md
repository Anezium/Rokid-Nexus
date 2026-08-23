---
task: lyrics-home-widget
date: 2026-08-16
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

The Lyrics plugin can display a compact ambient "widget" on the glasses home screen:
two lines of synced lyrics (current line prominent, next line dimmed) drawn over the
stock launcher, which appears automatically when music with available lyrics starts
playing on the phone and disappears when playback stops. A user setting selects the
widget mode: Off / Glance (visible whenever the screen happens to be on; never touches
the display state) / Karaoke (glasses hold the display on while a track is actively
playing). Default: Karaoke. The existing full-screen lyrics surface keeps working
exactly as today; the widget is additive.

The always-on part lives in the HUB, not the plugin (owner decision 2026-08-16 after
escalation): phone-hub gains a **media playback trigger** — it watches media sessions
itself and OPENS the lyrics plugin (hub-initiated background open) when playback
starts, closing it when playback ends. Plugins stay dormant-unless-open; the doctrine
of `plugins/AGENTS.md` is preserved, with the media trigger documented as a new
hub-initiated open reason.

# Non-goals

- No changes to the ROM's own widget/card system (CXR) — we draw our own overlay, we do
  not touch theirs.
- No generic third-party "widget SDK" for other plugins. The wire channel is generic in
  name (`/widget/*`) but only the lyrics use case ships now; do not build extra
  affordances (interaction, images, arbitrary layouts).
- No release work: no version bumps beyond what compilation requires, no tags, no
  registry/dist regeneration, no publishing.
- No phone-side UI redesign; the mode setting is one new row in the existing
  LyricsSettingsActivity, following its current visual style.
- No onboarding wizard changes. The hub's notification access (needed for
  MediaSessionManager) is requested lazily: the widget mode setting shows the grant
  state and deep-links to the system screen (ACTION_NOTIFICATION_LISTENER_SETTINGS)
  when missing. Without the grant the trigger is simply inert.
- The media trigger ships hub-side as a reusable mechanism but only the lyrics plugin
  registers for it now; no other plugin is modified.

# Constraints

- MUST add a new ambient wire channel `/widget/show`, `/widget/update`, `/widget/hide`
  cloned from the pin channel pattern (`BusPaths.PIN_SHOW`/`PIN_HIDE` in
  `shared/src/main/java/com/anezium/rokidbus/shared/BusConstants.kt:51-52`, routing in
  `phone-hub/.../BusHubService.kt` around L1055/L1139/L1174/L1209, capability rules in
  `shared/.../plugin/PathRules.kt:99`). Widget paths require the `surfaces` capability,
  like pins.
- MUST keep the widget OUTSIDE the surface system: it must never count as the active
  foreground surface, never trigger or be blocked by `SURFACE_BUSY`
  (`BusHubService.kt` L1221-1240), and never participate in surface close/`PLUGIN_CLOSE`
  semantics (hiding the widget is NOT a plugin self-close).
- MUST send full timed lines + a playback anchor once, and advance lines LOCALLY on the
  glasses from the anchor (clone the anchor math used by the timed-lines surface;
  anchor-only updates via `/widget/update` for seek/drift/pause). MUST NOT stream one
  message per lyric line.
- Widget window (glasses): `TYPE_ACCESSIBILITY_OVERLAY`, `FLAG_NOT_FOCUSABLE |
  FLAG_NOT_TOUCHABLE`, `PixelFormat.TRANSLUCENT`, pure black background
  (`BusTheme.glassesBg`), text in the phosphor green palette only (`NexusUi.GREEN` /
  BusTheme text tokens — no other hue). Clone the structure of
  `StatusBadgeOverlayRenderer` (small persistent chip) and register in
  `HudOverlayStack` as ambient (before pins) so redraw re-assertion keeps it below
  pins/notices/activities.
- Geometry (AMENDED by owner 2026-08-23 after seeing the first on-device render —
  supersedes the original above-the-row placement): a bordered CARD at the TOP of
  the HUD, horizontally centered, anchored at the notice band's top offset
  (`HudBandGeometry.topPx`, 12 dp + HudTopInset) so a notice passes in front of it;
  max width 76% of the 480 px screen; dim 1 dp phosphor outline, 7 dp corners,
  black fill (transparent on the optic); current line 16sp phosphor bold and MAY
  WRAP to two lines (ellipsis beyond), next line ~11.5sp dimmed (alpha on pure
  phosphor, never a grey tint), single line. MUST hide (not overlap) when any
  full-screen surface or the launcher overlay is up — same visibility gating
  StatusBadge uses for foreign fullscreen windows.
- HUB MEDIA TRIGGER (the always-on piece — replaces the earlier false premise that the
  plugin monitors in the background): phone-hub declares a NotificationListenerService
  component (the hub has NONE today — verified 2026-08-16) so
  `MediaSessionManager.getActiveSessions` works, and watches sessions passively
  (`OnActiveSessionsChangedListener` + controller callbacks; zero polling while nothing
  plays). On a playback-started edge, if a registered plugin exists, the hub opens it
  through the existing hub-initiated lifecycle in `ExternalPluginController.kt` (the
  `deliver(principal, BusPaths.PLUGIN_OPEN, request.type)` path, ~L261 — add a new open
  request type for the media trigger, following how existing open types are modeled).
  On playback stopped/ended: after a 60 s grace with no playing session, the hub closes
  the plugin (`PLUGIN_CLOSE`) — UNLESS the plugin currently owns a visible foreground
  surface (user is actively using it), in which case leave it alone.
- Trigger registration is declarative so it works while the plugin is dormant: follow
  the existing `PLUGIN.Capabilities` manifest meta-data convention
  (`plugins/lyrics/src/main/AndroidManifest.xml` L65-66) — add a media-trigger
  declaration the hub reads from the installed plugin's manifest. Document the token in
  BUSSPEC.md / PLUGIN_SDK.md.
- Auto show/hide (plugin side, once opened by the trigger): the plugin's existing
  `MediaSessionMonitor` → `LyricsRuntimeEngine` flow starts in `onNexusOpen` as today
  (`LyricsPluginService.kt:43-53` — lifecycle unchanged); NEW logic reacts to snapshot
  transitions: show the widget when `isPlaying && synced lyrics available`; hide it when
  playback pauses/stops for more than a 5 s grace, when the media session disappears, or
  when the track has no lyrics. A trigger-opened (background) plugin instance MUST NOT
  show any surface other than the widget.
- When the full-screen lyrics surface IS open, the widget MUST hide (the surface
  supersedes it) and reappear when the surface closes, music still playing.
- `plugins/AGENTS.md`: add a short paragraph documenting the media trigger as a
  hub-initiated open reason (like scheduled delivery). The dormant-unless-open doctrine
  itself MUST NOT be weakened or reworded.
- Karaoke display-hold: implemented in glasses-hub, driven by the widget's anchor state
  (`playing == true` AND widget visible). Clone the renewable wake-lock pattern of
  `AssistantDisplayEpisode` (`glasses-hub/.../AssistantDisplayEpisode.kt:405` area):
  short renewable holds, released within ≤5 s of pause/hide, hard per-episode ceiling
  (10 min, reset on track change). MUST NOT use bare `FLAG_KEEP_SCREEN_ON` as the
  mechanism (proven insufficient on this firmware — see
  `NoticeOverlayRenderer.kt` L25-32 comment). Glance mode: zero display interaction —
  MUST NOT wake, hold, or call `SurfaceController.wakeScreen()`; the global wake budget
  (`DisplayWakePolicy.kt` L67-70) must never be consumed by widget traffic in either
  mode.
- Mode setting: enum Off/Glance/Karaoke, default Karaoke, persisted in the lyrics
  plugin's existing settings storage (see `LyricsSettingsActivity.kt` and
  `settings/LyricsProviderSettingsStore.kt` for the local conventions), one settings row
  with the three choices. Mode changes apply live (Off hides immediately).
- Compatibility: an older glasses-hub receiving unknown `/widget/*` paths must not
  crash — verify the hub's unknown-path behavior and, if needed, gate sending on the
  hub capabilities handshake the same way newer surface features are gated (find the
  existing capability-gating precedent in `PhoneHubCapabilitiesContract.kt` /
  `BUSSPEC.md` and follow it). Document the new channel in `BUSSPEC.md` and
  `docs/PLUGIN_SDK.md` following the `readerAnchor` documentation style
  (`BUSSPEC.md` L267-279 as template).
- MUST NOT change the behavior, wire format, or policy of any existing path (surfaces,
  pins, notices, timed-lines full-screen rendering, foreground exclusivity for
  surfaces).
- MUST NOT touch CxrGlobal, any other plugin, the registry, CI, or the site.
- Work on a new git branch `lyrics-widget` off current main; commit in coherent steps,
  author Anezium, no AI attribution anywhere, do NOT push, do NOT touch tags. English
  code/comments/commits.
- Fix `plugins/lyrics/CHANGELOG.md`: the 1.0.0 claim "Auto-open on playback changes,
  toggleable in settings" becomes true with this change — add an Unreleased entry
  describing the widget instead of retroactively editing 1.0.0.

# Acceptance tests

| # | Check | Command | Expected |
|---|-------|---------|----------|
| 1 | All unit tests pass | test_commands[0] | BUILD SUCCESSFUL |
| 2 | APKs build | test_commands[1] | BUILD SUCCESSFUL |
| 3 | Widget channel constants + routing exist | `grep -rn "WIDGET_SHOW\|/widget/show" shared/src phone-hub/src bus-client/src` | hits in BusConstants, PathRules, BusHubService, SDK client |
| 4 | Widget never enters foreground-surface policy | new unit test in phone-hub asserting `/widget/*` bypasses the SURFACE_BUSY gate | test present and green |
| 5 | Local line advance | new glasses-hub unit test: given lines+anchor, renderer selects correct current/next line at t, t+n without new messages | test present and green |
| 6 | Auto show/hide transitions | new plugin-lyrics unit test: snapshot playing+lyrics → show sent; pause >5 s → hide sent; no lyrics → no show | test present and green |
| 7 | Karaoke hold bounded | new glasses-hub unit test: hold acquired only when playing+visible, released ≤5 s after pause/hide, ceiling enforced | test present and green |
| 8 | Docs updated | `grep -n "widget" BUSSPEC.md docs/PLUGIN_SDK.md plugins/AGENTS.md` | new channel + media trigger documented |
| 9 | Trigger opens/closes plugin | new phone-hub unit test: playback-start edge with registered plugin → PLUGIN_OPEN with the media-trigger type; 60 s no-playing grace → PLUGIN_CLOSE; no close while plugin owns a visible surface | test present and green |
| 10 | Trigger inert without grant | new phone-hub unit test: notification access absent → no session watching, no open attempts, no crash | test present and green |

Device validation (screencaps, real Spotify playback, karaoke hold measurement) is done
by the supervisor after review — not part of this run.

# Plan sketch

0. `phone-hub`: media trigger — NLS component declaration, passive session watcher,
   playback edge detection, declarative registration read from plugin manifests,
   open/close through `ExternalPluginController` with a new request type (+ tests #9,
   #10).
1. `shared`: add `/widget/*` path constants + PathRules capability entries (+ tests).
2. `bus-client`: SDK model (`NexusLyricsWidget`: timed lines, anchor, mode-independent)
   + session methods `showWidget`/`updateWidgetAnchor`/`hideWidget` + payload tests,
   following the timed-lines/pin models in `SurfaceModels.kt`.
3. `phone-hub`: route the three paths pass-through with seq metadata like pins
   (clone the pin branches in `BusHubService.kt`), explicitly outside the foreground
   surface policy (+ test #4).
4. `glasses-hub`: payload parsing + `LyricsWidgetOverlayRenderer` (clone
   StatusBadgeOverlayRenderer structure; HudTopInset subscription; HudOverlayStack
   registration; fullscreen-hiding gate) + local anchor clock (+ tests #5).
5. `glasses-hub`: karaoke display-hold episode driven by widget state (+ test #7).
6. `plugins/lyrics`: auto show/hide from snapshot transitions + mode setting row +
   background behavior + widget/full-screen mutual exclusion (+ test #6).
7. Docs: BUSSPEC.md + PLUGIN_SDK.md + lyrics CHANGELOG Unreleased entry.
8. Full test + build pass; commit sequence review.

# Context the executor cannot re-derive

- Glasses hardware: Android 12 API 32, 480x640 framebuffer, additive green monochrome
  optic — pure black renders as transparent, any non-green hue lands as green mush.
  The ROM forces `screen_off_timeout=5000` at every boot; that is why the karaoke mode
  needs an active renewable wake-lock, not window flags.
- ROM home geometry measured on device 2026-08-16: hint/cards zone `no_plan_tips`
  y 288-310, icons row ~y 320-340, ROM status band y 353-375 (time x1-41, weather
  x69-92, battery x452-479). The calibrated row center used by StatusBadge is 364 px.
- The `readerAnchor` change (issue #18) is the canonical "thread a field through all
  three layers" template: SDK `bus-client/.../SurfaceModels.kt` L135-141/L159/L193 →
  hub passes payload opaquely (`withExternalSurfaceMetadata`, `BusHubService.kt`
  L3354-3373) → glasses parse `glasses-hub/.../SurfaceModels.kt` L266/L393-398 →
  renderer consumes. Imitate that discipline: hub never decodes widget content.
- Anchor-only update precedent: `SurfaceController.kt` L583-591 (`isAnchorOnlyUpdate`),
  SDK `updateTimedLinesAnchor` in `bus-client/.../SurfaceModels.kt` (~L515-528).
- The lyrics plugin's surface visibility today is ONLY open/close/BACK-driven
  (`LyricsRuntime.pushState` `show = previous == null || force`,
  `LyricsPluginService.kt` L98-135); `isPlaying` currently feeds only state + anchor.
- StatusBadge constants worth reusing as reference: `StatusBadgeGeometry.kt` (row
  height 20dp, label 11sp), fullscreen coverage detection `FULLSCREEN_COVERAGE_PERCENT
  = 50` in `StatusBadgeOverlayRenderer.kt` (~L95).
- Display doctrine numbers: `DisplayWakePolicy.kt` L67-70 — `BUDGET_WINDOW_MS = 5000`,
  `WAKE_LOCK_MS = 3000`. `AssistantDisplayEpisode` ceiling: `DISPLAY_HOLD_CEILING_MS =
  90_000` (~L373) — the widget episode may renew beyond 90 s while playing, but must
  enforce its own 10 min per-track ceiling.
- Escalation history (why the hub trigger exists): a first execution attempt stopped
  correctly on a real conflict — the contract assumed the plugin monitors media in the
  background, but `plugins/AGENTS.md` mandates plugins be dormant unless open and
  `LyricsPluginService.kt:43-53` starts/stops the monitor strictly on open/close. The
  owner chose the hub-side trigger (doctrine preserved) over an ambient-plugin
  exception. Do not resurrect the plugin-side background monitoring idea.
- phone-hub has NO NotificationListenerService today (grep-verified 2026-08-16); the
  NLS component and its grant handling are new work. `ExternalPluginController.kt`
  L261/L280-314 is the hub-initiated PLUGIN_OPEN machinery; open requests carry a type.
- Line-number caution: all L-numbers above were read on 2026-08-16 from main; treat
  them as strong hints, re-locate by symbol if a file has drifted.

# Escalation triggers (mechanical — never self-assessed)

- Any test_command fails after 2 fix attempts.
- Diff touches files outside scope_globs or matching forbidden_globs.
- The hub capabilities handshake has no usable gating precedent for new paths (report
  the finding instead of inventing a scheme).
- Any change to existing wire paths or policy files becomes "necessary" — stop and
  report; that is a design conflict, not an implementation detail.

# Autonomy

- Executor decides alone: internal naming, file placement within the listed modules,
  test structure, exact dp paddings within the given ranges, grace-period timer
  implementation.
- Executor stops and reports: anything in Escalation triggers; any need to widen scope;
  any conflict between this contract and existing code comments/specs (quote both).
