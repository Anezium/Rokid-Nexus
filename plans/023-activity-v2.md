# Plan 023 — Activity v2 (a panel that fits what it carries)

Status: IMPLEMENTED on branch `activity-v2` (owner go 2026-09-26); device
validation and release pending.

Visual reference: `E:\Tools\Rokid\design\nav-hud-proposals.html`, section
"Activity v2" (before/after frames drawn at 1 CSS px ≈ 1 dp).

## Goal

The activity panel from plan 012 is correct but too rigid for the processes it
was built for. Measured against the renderer (`ActivityPanelView`: 250 dp wide,
48 dp glyph, 24 sp monospace primary, inline ETA), only about eight primary
characters fit next to an ETA, although the contract allows twelve, so
"Départ 3 min" renders as "Dép…". There is no way to say which line a bus is,
how many stops remain, or that a transition is time-critical.

Activity v2 keeps the plan 012 model — the plugin describes state, the platform
draws it — and adds a smarter layout plus four optional, additive fields. It is
built for the navigation plugin (Wave D) but is deliberately generic: rides,
deliveries, and parcels use the same fields.

## Decisions (owner, 2026-09-26)

- Semantic fields drawn by the platform (option A). Plugin-authored panel
  layouts (an Ink template in the corner) were considered and not chosen; they
  would reverse plan 012's "constrained container, semantic content" rule.
- **No local countdown.** The glasses never tick a value between updates. Every
  number the wearer sees is the one the source app last reported.
- The chip is unchanged.

## Changes

### 1. Primary autosize (renderer only, no wire change)

The panel fits `primary` before it ever ellipsizes:

1. Inline with the ETA, try 24 sp down to 20 sp.
2. If nothing fits, move the ETA to the right end of the secondary row and use
   the largest size from 24 sp down to 16 sp that fits alone. A full
   12-character primary keeps about 22 sp this way instead of dropping to 16.
3. Ellipsize only if 16 sp alone still overflows (not reachable within the
   12-character cap at the current panel width, kept as a guard).

This is a pure function of measured widths and gets a unit test without a
device. Every published plugin benefits without an SDK update.

### 2. `badge`

Optional string, at most 5 characters after trim, nonblank when present
("38", "M4", "RER B"). Drawn as an outlined plate in the panel's glyph slot and
in the flare band's leading slot. The chip keeps `glyph`, which stays required.

### 2b. `measure`

Optional string, at most 8 characters after trim ("250 m", "1,2 km"): a second
quantity that belongs with `primary`. Added on device review: a transit walk
timed in minutes also has a distance, and the wearer wanted both at a glance
without the panel's largest text turning into one long value. The panel and
the flare draw `primary - measure` through the same fit as the primary; the
chip stacks `measure` under the primary beside the glyph and keeps `secondary`
on its line below.

### 3. `track`

Optional object: `{"count": 2..12, "at": 0..count-1, "target": at..count-1,
"label": ≤ 20 chars, optional}`. Drawn as a row of dots under the secondary
line: passed, current, target, beyond. In the v2 panel and flare band it
replaces the progress bar. A plugin should also send `progress`, which old
glasses keep drawing as the bar.

### 4. `tone: "urgent"`

Update-only and transient, exactly like `significant`, and valid only together
with `significant: true` (otherwise `INVALID_ACTIVITY`). The flare gets a
bright phosphor outline that beats once the band has arrived. The glasses allow one urgent
flare per activity per 60 s, on a budget separate from the 10-second flare
interval so the maneuver flare just before it cannot swallow it; a throttled
one becomes an ordinary significant update under the existing flare budget.
Urgent never changes wake rules: it wakes only when the activity started with
`wakeDisplay`, under the global five-second wake cap.

### 5. Transit glyphs

`bus`, `metro`, `train`, `tram` join the platform set (`docs/GLYPHS.md`,
`NexusGlyphs`, glasses drawables). Additive; an older hub renders `dot`.

## Wire and compatibility

- `activitySurfaceVersion` stays 1: both hubs match it exactly, so raising it
  would switch activities off between a new and an old hub. Glasses announce
  extras separately, with feature bit 4096 (`ACTIVITY_EXTRAS`) and
  `activityExtrasVersion: 1`; the phone hub passes the bit to plugins only when
  both are present. Paths, ownership, caps of the v1 fields, and rate limits
  are unchanged.
- Out-of-cap values are rejected, never truncated, as in v1.
- `/activity/update` still carries the complete mutable state: `badge` and
  `track` are explicitly `null` when cleared; `tone` is sent only when urgent.
- v1 glasses read only the fields they know, so v2 fields are ignored and the
  panel degrades to glyph + progress bar + ordinary flare.
- An old phone hub re-serialises validated v1 content and therefore drops v2
  fields; same degradation.
- The SDK never refuses a call because of v2 fields. It exposes
  `supportsActivityExtras` so a plugin can decide whether to add a notice
  fallback for an urgent moment on old glasses.

## Touch points

- `:shared` — `ActivitySurfaceContract` (`EXTRAS_VERSION`, validation,
  serialisation, content model), `GlassesHubCapabilitiesContract`
  (`activityExtrasVersion`), `BusCapabilityBits.ACTIVITY_EXTRAS`, tests.
- `:bus-client` — `NexusActivity.badge`, `NexusActivityTrack`,
  `updateActivity(activity, significant, urgent)`, `NexusGlyphs`,
  `supportsActivityExtras`; SDK minor bump.
- `:phone-hub` — extras capability bit and urgent-tone forwarding; tests.
- `:glasses-hub` — `ActivityOverlayRenderer` (autosize, badge, track, urgent
  band), `ActivityPresentationPolicy` (urgent quota), glyph drawables,
  capability announcement.
- `plugins/sample` — a v2 demo route for on-device validation.
- Docs — `BUSSPEC.md` (Activity protocol v2), `docs/PLUGIN_SDK.md`,
  `docs/GLYPHS.md`, `CHANGELOG.md`.

## MUST NOT

- MUST NOT let plugins supply colors, layouts, images, animation, or timing;
  `urgent` is a meaning, the platform owns its drawing and its quota.
- MUST NOT tick, interpolate, or estimate any value on the glasses.
- MUST NOT change the chip, pins, notices, or surfaces.
- MUST NOT keep the screen on or add a wake path beyond plan 016.
- MUST NOT make any v1 payload invalid.

## Acceptance

1. `:shared:test`, `:bus-client:testDebugUnitTest`,
   `:phone-hub:testDebugUnitTest`, `:glasses-hub:testDebugUnitTest` green, with
   new pure tests for the autosize decision and the urgent quota.
2. Both hubs and the sample `assembleDebug`.
3. On device: v2 panel over the Rokid home (autosized primary, badge, track);
   urgent flare and its throttle; v1 plugin unchanged; v2 payload on v1 glasses
   degrades without error.

Estimate: about three days including tests and docs, plus the device pass.
