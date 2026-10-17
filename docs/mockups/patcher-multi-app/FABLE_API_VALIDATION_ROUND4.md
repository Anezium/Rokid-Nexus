# Patcher text-wrap validation — Claude Fable 5.1 API

Read-only review. Native renders are simulated test states.

```json
{
  "requested_model": "claude-fable-5-1",
  "lookup_model": "claude-fable-5-1",
  "source_sha256": "cdc16bfa2de670a3c0bc6a61eefe1c0c2a7be6db9528f197418d21bb6ff2ecf9",
  "request_id": "req_011CfrggEBC6TMc3iHZEDLhs",
  "response_model": "claude-fable-5-1",
  "usage": {
    "input_tokens": 52880,
    "cache_creation_input_tokens": 0,
    "cache_read_input_tokens": 0,
    "cache_creation": {
      "ephemeral_5m_input_tokens": 0,
      "ephemeral_1h_input_tokens": 0
    },
    "output_tokens": 2073,
    "service_tier": "standard",
    "inference_geo": "global",
    "output_tokens_details": {
      "thinking_tokens": 489
    }
  },
  "stop_reason": "end_turn"
}
```

# Fable verdict — round 4 (read-only)

## Verdict: **APPROVE** — B1 and B2 are resolved; the native UI may proceed to release build and actual phone QA.

## Evidence that B1 is resolved

Current source, `advanced()`:

```kotlin
if (setup.jobRunning) box.addView(NexusUi.rowSub(this, "Locked while YouTube is being patched in Patcher.").apply {
    maxLines = Int.MAX_VALUE; ellipsize = null
    setTextColor(NexusUi.AMBER)
}, …topMargin = 6 dp)
```

This matches the Opus edit byte-for-byte and the home-card pattern I asked for. Gating (`setup.jobRunning`), colour and margin are unchanged. Geometry `hub-advanced-running-360`: `w=286 h=27 lines=2`, full string; at 1.3: `w=286 h=36 lines=2`, full string. The render shows "…patched in / Patcher." on two amber mono lines with no ellipsis, and `CHOOSE PATCHED YOUTUBE APK` is visibly disabled above it, `INSTALL PREPARED APK` disabled below (no `canInstall`). This is the state that was never rendered last round; it is now rendered and whole.

## Evidence that B2 is resolved

Current source, `stepCard()`:

```kotlin
addView(NexusUi.rowTitle(this@YoutubeSetupActivity, status).apply {
    maxLines = Int.MAX_VALUE; ellipsize = null
    if (status.startsWith("Done")) setTextColor(NexusUi.GREEN) …
```

Placed before the colour logic, mirroring `stepRow`. Geometry `hub-patch-offline-360`: the ~90-char prepared-and-disconnected status is `lines=3` at 1.0 and `lines=4` at 1.3, and both content renders end with "Connect the glasses, then install." — the actionable clause that was previously at risk. `hub-source-locked`: `lines=1` / `lines=2`, complete. The parent's six-line check reports no ellipsis, vertical clipping or width overflow for every targeted text.

## Scope check

Diff against the previously reviewed SHA is exactly two property pairs on two `TextView`s in `YoutubeSetupActivity.kt`. `NexusUi.kt`, routes, Back handling (`onBackPressed` → OVERVIEW, `finish()` at root), job lock, `patch()` re-authentication, `acceptsResult`, picker lock, request-code rotation, `FLAG_SECURE`/`EXTRA_SECURE_SESSION` paths and the footer state table are untouched. Normal hub suite: 736/736, debug `assembleDebug` succeeded. The 27 init failures in the first combined invocation were caused by the render-only resource init script leaking into the whole suite; the corrected run without that override is the one that counts, and no build files were modified.

## Design consistency

The three corrected screens remain inside the Phosphor × Mono grammar: PANEL card at 15 dp radius, sans 15 sp status title (GREEN when done), 6 dp report dot + 13 sp sans report line, sans body, mono outline pills, one filled green pill as the single primary, amber reserved for the lock, end-aligned `More` at 48 dp. Nothing ported from the mockup's gradients, glow or sub-floor type sizes. The mixed-case filled pill versus uppercase outline pill is inherited kit behaviour, not a regression.

## Optional polish (non-blocking, unchanged from last round)

- `checkLine` keeps `maxLines = 3` with `ellipsize = END`; the long line is exactly 3 lines at 1.3 (`h=53`), so OEM scales above 1.3 could still clip it. One-line fix when convenient.
- `report()` with a blank `message` renders a lone dot; guard it.
- Report dot could take DANGER/AMBER on error if the state exposes a flag.

## Honest limits

These are Robolectric `GraphicsMode.NATIVE` renders at 360×740 dp, density 2, with Robolectric's fonts — not device captures; real OEM fonts and scales above 1.3 may differ. The six scenarios cover the three previously unrendered states at two scales; the stopped home state (`FAILURE`) still exists only in code review. I executed no tools, builds, tests or device actions in this session; all measurements are the parent's artifacts, cross-checked against the source and images supplied. This is design/code approval to proceed to release build and phone QA, not validation of real device, installer or Patcher API behaviour.
