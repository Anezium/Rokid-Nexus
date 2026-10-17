# Patcher design review — Claude Fable 5.1 API

Read-only review requested on 2026-10-09. No implementation changes.

```json
{
  "requested_model": "claude-fable-5-1",
  "lookup_model": "claude-fable-5-1",
  "source_sha256": {
    "bus-client/src/main/java/com/anezium/rokidbus/client/ui/NexusUi.kt": "e0c18c0827aa28ac00acc3b27248c3b7bed2cbb4b30ed7ebfa9147a3ab16ee22",
    "plugins/patcher/src/main/java/com/anezium/rokidbus/plugin/patcher/PatcherHomeActivity.kt": "01ca519fe67605dfa1f74a47230a827fc22813a64c377e952f96780c5afe7e24",
    "phone-hub/src/main/java/com/anezium/rokidbus/phone/YoutubeSetupActivity.kt": "5185eed8cf3b554ba7d5c06bfe63b8a7b9a71c79ba1fa94cdf284e0711142fd0",
    "plugins/patcher/src/test/java/com/anezium/rokidbus/plugin/patcher/PatcherAppFlowTest.kt": "e0b1332231111b45a3c62c23b9a61e8dbf06b8045f8f72bb0732434764eb92d1",
    "phone-hub/src/test/java/com/anezium/rokidbus/phone/YoutubeSetupActivityTest.kt": "7ffb43f73e52f35f9bc847f9dc4e5d65cf2af6e745368c016dd4d57818cb7456"
  },
  "request_id": "req_011CfrevsFnnN6Xt51R9Xh5L",
  "response_model": "claude-fable-5-1",
  "usage": {
    "input_tokens": 98331,
    "cache_creation_input_tokens": 0,
    "cache_read_input_tokens": 0,
    "cache_creation": {
      "ephemeral_5m_input_tokens": 0,
      "ephemeral_1h_input_tokens": 0
    },
    "output_tokens": 7976,
    "service_tier": "standard",
    "inference_geo": "global",
    "output_tokens_details": {
      "thinking_tokens": 4787
    }
  },
  "stop_reason": "end_turn"
}
```

# Patcher phone UI — validation of the revised native screens (Fable 5.1, read-only)

## Verdict: **CHANGES REQUIRED** (two one-line fixes), then approve for build/device QA

The five adjustments are implemented as asked, inside the kit, without touching routes, capabilities, job engine, signing or validation. The measured geometry disproves my earlier "~150 dp rows" and "repeat path unverified" claims; both are withdrawn. What remains are two unmeasured locked/ready states where hub text still ellipsizes — exactly the class of defect this round was meant to close. Both are trivial. Nothing else blocks.

## Blocking defects (grounded in source + geometry)

**B1 — `YoutubeSetupActivity.advanced()`: the Advanced lock line clips at font scale 1.0.**
`"Locked while YouTube is being patched in Patcher."` (49 chars) is a plain `rowSub` (kit default `maxLines = 1`, `ellipsize = END`). The Patcher home's shorter lock line (37 chars, same 11 sp mono, same 286 dp) measures `w=286 lines=1` at 1.0 and needs `lines=2` at 1.3 only because Opus set `maxLines = 2; ellipsize = null` there. Forty-nine characters at the same metrics (~7 dp/char ≈ 345 dp) do not fit 286 dp, so the Advanced lock state — not in the render set; `hub-advanced` shows the unlocked state — truncates to "Locked while YouTube is being patched in Pat…" on a real phone. The test passes because it compares `text`, not layout.
*Fix:* apply the home pattern: `.apply { maxLines = Int.MAX_VALUE; ellipsize = null; setTextColor(AMBER) }`.

**B2 — `stepCard()` status title can ellipsize the instruction.**
The step-card status uses `rowTitle` (kit `maxLines = 2`, `ellipsize = END`). `hub-source-locked-360-font130` already shows a 32-char status at `lines=2`. The prepared-and-disconnected status asserted by the test — `"Ready to install — <label> is patched and waiting. Connect the glasses, then install."` (~90 chars) — needs ≈5–6 lines at 1.3 and ≥3 at 1.0; the trailing "Connect the glasses, then install." is the actionable part and is cut. Same risk for `"Patched APK ready in Patcher — install it on the glasses."` at 1.3.
*Fix:* `rowTitle(...).apply { maxLines = Int.MAX_VALUE; ellipsize = null }` in `stepCard`, mirroring what `stepRow` already does for its `rowSub`.

Neither fix changes `NexusUi.kt`, behaviour or tests. Re-render `hub-source-locked`, `hub-patch` (prepared + disconnected) and `hub-advanced` (jobRunning) at 1.3 after the change.

## Review of the five revisions

**1. Home app cards — accepted.** Order is now `cardTitle` → one short mono `rowSub` → sans `cardBody`, mark top-aligned (`gravity = Gravity.TOP`) now that the column is three lines deep. Measured: status `lines=1` at both scales (`Glasses setup · 4 steps` w=228), summary 3 lines, lock line wraps (`h=36 lines=2` at 1.3) instead of clipping. Copy is honest: `Official ${target.versionLabel}` for Reddit, no invented "not set up" for YouTube (the plugin cannot know hub progress — correct refusal of my suggestion). `PREVIEW` meta sits beside the title with 10 dp gap and fits at 1.3 (ends x=231 of 323). Running state reads "Patching" GREEN_DIM + "Adding the glasses controls. Leaving this screen does not stop it." — the explanation moved to body copy, as requested. Matches the base Nexus card grammar (tile, sans name, mono meta) better than the previous render.

**2. Step headers — accepted.** `Route(title, step)` with `Step N of 4 · YouTube` subtitle; every header measures `lines=1` at 1.0 and 1.3 (`w=218`). Back behaviour unchanged: `onBackPressed` and the header chevron both return to OVERVIEW from any sub-route, `finish()` only at the root; the test walks SIGNIN → overview → ADVANCED → overview → finish.

**3. Controller report — accepted.** `report()` is a 6 dp `dot` (GREEN while `busy`, else INK4) + 13 sp sans `statusLine`, placed under the `SETUP` section row on the overview and under the status title inside each step card and the Advanced card. The disconnection message measures `lines=2 h=33/42`, whole. The message-only fast path in `render()` reuses the same `message` view, so progress ticks don't rebuild cards (test "message updates keep the screen…"). Not packed into the section-row value — correct, that value stays "N OF 4 DONE".

**4. Overview + Advanced — accepted.** Rows are `pressableCard` + 34 dp `iconTile` (ordinal or ✓) + `rowTitle` + wrapping `rowSub` + `chevron`: 67 dp (1.0) / 78 dp (1.3), four steps above the fold, `PHONE KEYBOARD` at y=444 within the 584 dp viewport. Keyboard and Advanced both above the fold is not reachable without shrinking kit rows; a normal scroll is the right call. Advanced is a `navCard` with chevron opening its own route with header, Back and the same locks (`CHOOSE PATCHED YOUTUBE APK` disabled + amber line while `jobRunning`; `INSTALL PREPARED APK` gated on `canInstall`). Footer repeat path is real source, not a mockup idea: `prepared → Install patched APK`, `jobRunning → Open running job`, `!patcherApproved → Get or approve Patcher`, `JOB_READY → Install patched APK`, `youtubeDone → Patch an update`, else `Patch now`; each branch has a Robolectric case, and `hub-overview-running` renders `Open running job`. "Get or approve Patcher" is the legitimate absent/unapproved state, not a stub. Footer disabled on `busy`, hidden off the overview.

**5. Secondary controls — accepted.** `more()` renders an end-aligned `More/Less` and reveals end-aligned `textButton`s (48 dp standalone target, kit left at 42 dp); `expanded` survives recreation via `onSaveInstanceState`. Primary stays a full-width `pillButton`, utilities are `outlinePillButton`s, and the sign-in mark is an explicit `Mark sign-in done` / `Mark sign-in to do` toggle stored only in `YoutubeSetupChecklist`. Sign-in copy is a numbered manual checklist and states that Nexus/Patcher never see account, password, tokens or outcome. Draft/key/job/remote security paths are untouched (`patch()` re-authenticates, `acceptsResult` boundary, per-launch request codes, picker lock, `FLAG_SECURE` on password prompt, `EXTRA_SECURE_SESSION` on remote input).

## Against the base Nexus screenshot / current kit

Hierarchy, hairlines, PANEL cards at 15 dp radius, mono caps section rows with GREEN_DIM values, sans names, mono meta, single green accent with AMBER only for attention/preview/lock and DANGER only for `REMOVE ›` — all consistent. The home ends with the canonical `uninstallCard`; its subtitle ellipsis at 1.3 is unchanged kit behaviour, not a regression. 46 dp pills and 42 dp key-card text buttons are inherited kit dimensions, not defects; no kit change is needed for this release.

## Optional polish (not blocking)

- `checkLine` keeps `rowSub`'s `ellipsize = END` with `maxLines = 3`; the longest line is exactly 3 lines at 1.3, so OEM scales >1.3 would clip "the only confirmation that counts". Set `ellipsize = null`.
- If `YoutubeSetupState` exposes an error flag, tint the report dot DANGER/AMBER; today the disconnect message is INK2 and relies on the amber "Needs attention" rows for alarm.
- Guard `report()` against a blank `message` (a lone 6 dp dot would render).
- Home "Stopped · open to retry" reuses the generic summary sentence; a one-liner such as "The last run stopped. Open it to see why." would help. This state was not rendered (harness could not force FAILURE) — code review only.
- Overview "Patching in Patcher" row status could use GREEN_DIM like the home card.

## Risks and limitations

Renders are Robolectric test states, not device captures; real fonts and OEM scaling differ. The stopped home state and the two blocking states above were never rendered — B1/B2 are derived from the measured metrics of sibling lines, not from a failing image. No release build, install or physical input occurred, and this session exercised no tools. Approval after B1/B2 is design/code approval to proceed to release build and device QA, not proof that all native APIs or devices behave.
