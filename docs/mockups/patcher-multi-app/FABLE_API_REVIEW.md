# Patcher design review — Claude Fable 5.1 API

Read-only review requested on 2026-10-09. No implementation changes.

```json
{
  "requested_model": "claude-fable-5-1",
  "lookup_model": "claude-fable-5-1",
  "request_id": "req_011CfrcWyYRM7NcFy5xYHgc5",
  "response_model": "claude-fable-5-1",
  "usage": {
    "input_tokens": 106759,
    "cache_creation_input_tokens": 0,
    "cache_read_input_tokens": 0,
    "cache_creation": {
      "ephemeral_5m_input_tokens": 0,
      "ephemeral_1h_input_tokens": 0
    },
    "output_tokens": 7337,
    "service_tier": "standard",
    "inference_geo": "global",
    "output_tokens_details": {
      "thinking_tokens": 3711
    }
  },
  "stop_reason": "end_turn"
}
```

# Patcher phone UI — design review (round 2 mockup vs native)

## Verdict

**Minor adjustments.** The native `PatcherHomeActivity` and the hub-owned YouTube tutorial already have the right structure, the right ownership split and the right design-kit vocabulary. The mockup should be treated as a **behavioural spec** (states, copy intent, lock rules, Back rules), not a pixel spec: several of its visual choices drift off the Phosphor × Mono kit, and the native implementation is more faithful to Nexus than the mockup is. Nothing observed justifies a focused rework or a redesign; five targeted fixes to the native screens close the real gaps (type misuse on app cards, header wrapping, an orphaned status sentence, overview density/repeat path, secondary-action placement).

Applying the frontend-design skill here means executing *refined minimalism within an existing system*: precision in spacing, type roles and hierarchy. Its calls for new display fonts, gradients, glow or asymmetric layouts are explicitly out of scope and are not recommended.

## Evidence-backed comparison

**Against base Nexus (home screenshot + current `NexusUi.kt`).** Nexus cards are: tinted 34–48 dp mark, sans `cardTitle`, one short mono `rowSub` ("Installed · enabled"), chevron, 15 dp radius, 15/14 dp padding; section rows are mono caps with an optional green-dim value on the right; primary actions are `pillButton` (filled) or `outlinePillButton` (48 dp, uppercase mono). The native Patcher home (render 2) matches all of this: `pluginHeader` with bolt tile, `sectionRow` "APPS", `card`, 44 dp mark, `outlinePillButton` full-width, Signing key card with `textButton`s, canonical `uninstallCard` last. Consistent.

**Mockup discrepancies (do not port):**
- Custom 48 px top bar with a gear for maintenance, 15 px title — Nexus plugin screens use `pluginHeader` (48 dp tile, 20 sp title, `rowSub` subtitle, hub back chevron).
- Uninstall moved behind "Signing key & plugin" row — violates the kit contract that every plugin screen ends with `uninstallCard`. Native is correct.
- Gradients (`.band`, `.top.nexus`), box-shadow glow on live steps/ok dots, 13 px panel radius, `--warn #ffd166` / `--bad #ff6b6b` instead of `AMBER`/`DANGER`. Native uses the kit tokens; keep it that way.
- Compact 10.5 px mono buttons right-aligned inside cards, and 9.5–10.5 px mono status lines: below Nexus's 12 sp pill/11 sp `rowSub` floor and fragile at 360 px with enlarged text. Native sizes are correct.
- Hub-owned stand-ins with a tinted header: unnecessary natively — hub screens are hub Activities with their own `pluginHeader`.

**Where the mockup is better than the current native renders:**
- App cards separate a sans summary from a short mono meta line; native stuffs a three-line sentence into mono `rowSub` (render 2, "Four steps: MicroG, official YouTube…"), which reads as console output, not body copy, and is `maxLines = 3` + ellipsized at large font scale.
- Overview: four step rows fit above the fold with keyboard and Advanced visible at 360 px; native step cards are ~150 dp each, so the keyboard switch and Advanced start below the fold.
- Footer quick action ("Patch now / Patch an update / Install the patched APK") for repeat users. The native render only shows "Get or approve Patcher" (a hub-side test state), so the repeat path is unverified.
- Step screens use "More" as a labelled list (Check updates / Source / Refresh). Native centres a lone "More" `textButton` under a hint (render 4).

**Against OLD Patcher (git HEAD).** The old screen was single-target: stock file → patches → action cards in one scroll, with the per-target icon in the header and the key/uninstall below. The new home adds one layer (app picker) for the settings path; the Nexus-request path still goes direct (`open()` → hub setup or `PatchActivity`), so beginners gain discoverability and the locked flow loses nothing. The old in-card numbered `stepHeader`, live hero timer, stage rows and the Cancel/Close `quiet` buttons are a good fit and should remain the model for the Reddit prepare/running screens. Old copy "Your APK never leaves this phone" (and render 2's "Your APKs never leave this phone") is wrong given glasses install; source already says "Patching runs locally, with no cloud upload" — confirmed correct.

**Native issues observed in renders (not mockup-derived):**
- Render 4: `pluginHeader` title "1 · MicroG on the glasses" wraps to two lines at 20 sp on 360 px; worse with enlarged text.
- Renders 3 and 4: a free-floating `cardBody` "Glasses apps refreshed." sits between intro and sections / below the card like orphaned prose.
- Render 3: Advanced is a centred green `textButton` with a two-line label, not a kit row/card.

## Keep

- `PatcherHomeActivity` skeleton: header, intro, APPS / MAINTENANCE / PLUGIN sections, `uninstallCard` last, full-width `outlinePillButton` per app card, amber "PREVIEW" `metaLabel` beside the Reddit title, 44 dp green-adapted brand marks (geometry preserved, tile-tinted — consistent with `iconTileImage`).
- Running-job status on the card ("Patching · leaving this screen does not stop it", GREEN_DIM) and the amber "Locked while … is being patched" line with disabled CTA.
- Signing key card copy and More/Import/Export layout; Morphe Manager caveat under More.
- The tutorial's SETUP / "0 OF 4 DONE" section row with numbered tiles, hub back chevron, keyboard `switchRow` card with consequence copy, fixed footer pill.
- All mockup behaviour rules: job lock, inventory-only Done, no automatic uninstall, signer-mismatch recoveries, sign-in as user-marked checklist, offline states.

## Prioritised adjustments (max five)

**1. App card type roles (home).** Split summary from status; `cardBody` sans for the sentence, `rowSub` mono for one short line.
Before: `rowSub` "Four steps: MicroG, official YouTube, patch and install, sign in." (3 mono lines).
After: `cardBody` "MicroG, official YouTube, patch and install, sign in — all guided here." + `rowSub` "NOT SET UP · 4 STEPS" (or "STEP 2 OF 4 NEXT", "PATCHING · 02:14", "APK READY · INSTALL ON GLASSES"). Reddit: `cardBody` "Glasses controls, ad filtering. No MicroG needed." + `rowSub` "OFFICIAL 2026.14.0 · PREVIEW". Drop `maxLines = 3` on status. Effort: trivial. Risk: none.

**2. Step-screen headers.** Keep `pluginHeader(onBack)`, shorten title, put the ordinal in the subtitle.
Before: title "1 · MicroG on the glasses", sub "Patcher · YouTube".
After: title "MicroG", sub "STEP 1 OF 4 · YOUTUBE". Same for "Official YouTube" / "Patch and install" / "Sign in and open". Effort: trivial. Risk: none; fixes wrapping at 360 px and large text.

**3. Status sentence placement.** "Glasses apps refreshed." must not be free `cardBody`. Fold it into the section row value or a card footer meta.
After (overview): `sectionRow("Setup", "0 of 4 done · refreshed just now")`; (MicroG card) `rowSub` "GLASSES REPORT · JUST NOW" under the card title; errors stay `statusLine` in DANGER/AMBER inside the relevant card. Effort: small. Risk: none.

**4. Overview density and repeat path.** Replace four full `card`s with `pressableCard` rows (34 dp numbered tile, `rowTitle`, `rowSub` status, chevron, 12 dp gaps → ~70 dp each) so steps, keyboard and Advanced fit one 360 px screen; make Advanced a `navCard("Advanced", "Morphe Manager or an already patched APK")` with chevron instead of a centred text link. Keep the fixed footer `pillButton` and confirm it switches to "Patch now" / "Patch an update" / "Install the patched APK" / "Open running job" per state (mockup's strongest idea; not visible in renders). Effort: small–medium. Risk: low; footer state logic must be checked against the hub's real state source.

**5. Secondary actions on step cards.** Match the Signing key pattern: primary `pillButton` full-width, then an end-aligned `textButton` row.
Before (MicroG): primary, hint, centred "More".
After: primary "Update MicroG"; hint; row [spacer] "Check updates" "Source" "Refresh glasses" (or a single "More" that expands rows, end-aligned). Apply the same to Install (Retry / Check glasses) and Sign-in. Effort: small. Risk: none.

## Effort / risk summary

All five are presentation changes inside existing `NexusUi` helpers; no new widgets, routes or capabilities. Only #4's footer logic touches state and needs a Robolectric case per state. No on-device verification has occurred; a 360 dp + 1.3× font-scale render pass should follow.

## Unresolved assumptions

- I have not seen the native `PatchActivity` (prepare/running/result) rework or the YouTube sub-steps beyond MicroG; their fidelity to the old in-card step pattern is assumed.
- "Get or approve Patcher" in render 3 is presumed a hub test state where the plugin is absent/unapproved, not the normal footer.
- Whether the footer quick action, "Prepared files · Clear" and the signing-key "backups: none yet" meta from the mockup exist natively is unknown; the first is recommended, the latter two optional.
- The Nexus home screenshot is older; current hub cards may differ slightly, but `NexusUi.kt` confirms the tokens used here.
- Reddit's install hand-off remains hub-side per the mockup's open question; no symmetry change is recommended by this review.
