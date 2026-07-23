# Plan 008 — Plugin developer experience: Dev Mode, third-party distribution, SDK publishing

Status: APPROVED — all owner decisions locked 2026-07-15.
Scope decisions: developer toggle + debug tooling in the phone app; registry stays an
index with APKs hosted on each developer's own GitHub releases (F-Droid-like PR
submission); the plugin-authoring contract ships as `plugins/AGENTS.md` in this repo;
`bus-client` gets published as a versioned Maven artifact.
Policy decisions: license = **Apache-2.0 for the whole repo**; Store-installed
signer-pinned plugins are labeled `Verified · <author>` (sideloaded stay
Unverified/DEV); the dev-mode-only hub notification on sideload detection is
approved; registry review = mechanical CI + human merge, no editorial curation in v1.

Branch: `plan-008-devex`, based on `plan-007-pipeline` (NOT main) — plan 007 heavily
reworked `BusHubService` and merges imminently; basing there avoids rebase pain.
Plan 008 therefore merges to main only after plan 007 does.

Factual baseline: read-only investigation of `RokidNexus` (branch `plan-007-pipeline`)
and `RokidBrew-Registry` (main), 2026-07-15. Anchors below reference that snapshot.

## Goals

1. A third-party developer can build a plugin against a published SDK artifact,
   sideload it, approve it, and debug it — without cloning this repo and without
   asking us anything the docs don't answer.
2. A third-party developer can distribute the plugin by hosting the APK on their own
   GitHub releases and submitting one manifest PR to RokidBrew-Registry; CI validates
   the submission mechanically, we review only intent.
3. The Store installs third-party plugins with the same integrity guarantees as
   first-party ones (sha256 + signer pinning), from any public HTTPS host.

## Non-goals

- No HUD simulator on the phone (explicitly deferred; developers need the glasses).
- No automated publishing without human PR review (validation is automated, merge is not).
- No microphone capability work (that shipped separately on the mic-capability branch).
- No app self-update work (that is plan 005 Part C, unchanged).

## What already exists (do not rebuild)

- Discovery is registry-independent: any installed APK exposing the
  `com.anezium.rokidbus.action.PLUGIN` service is discovered, signer-hashed, and
  gated by the grant flow (`PhonePluginDiscovery.kt:49`, grant key =
  package + pluginId + signer sha256, `PluginGrantStore.kt:8`).
- The Store already models sideloaded plugins (`StoreEntryState.SIDELOADED`,
  `StoreCatalog.kt:103`) and renders them as `Local · …`.
- The installer already accepts **any** public HTTPS URL with redirects
  (`PluginInstaller.kt:242`); host flexibility is NOT the gap — signer pinning is.
- A per-screen `Developer details` checkbox already exists inside Plugin access
  (`PluginPermissionsActivity.kt:215`) showing invalid reasons and signer info.
- `bus-client` + `shared` already have `maven-publish` blocks, a `jitpack.yml`, and a
  CI workflow that compiles the sample against the locally-published coordinate
  (`sdk.yml:20`).
- Registry ingestion tooling already handles third-party GitHub URLs
  (`add-app-from-url.mjs:251`) and the builder validates schema shape
  (`build-registry.mjs`).

## Part A — Developer mode in the phone app

A1. **Global "Developer mode" toggle** in Settings → Advanced (the natural slot;
    Settings is `NexusUi`-programmatic, `SettingsActivity.kt:93`). Persisted in hub
    prefs. The existing per-screen `Developer details` checkbox becomes driven by it
    (keep the local checkbox as an override or fold it in — implementer's call, but
    one source of truth).

A2. **Provenance surfaced everywhere.** `PluginCatalog` currently cannot distinguish
    Store-installed from sideloaded (`MainActivity.kt:334` rows have no badge slot).
    Add provenance to the catalog entry (registry-matched vs local-only, via the same
    matching logic `StoreCatalog` uses), then:
    - Home plugin rows: `DEV` badge on sideloaded plugins when dev mode is ON
      (subtle `Local` subtitle when OFF — the user should never mistake a sideloaded
      plugin for a Store one).
    - Store cards: proper badge field on `storeCard` instead of overloading the
      metadata string.
    - Fix the labeling lie: Store-installed plugins should not read
      `Unverified installed plugin` (`PluginPermissionsActivity.kt:127`); reserve
      "unverified" wording for local-only installs.

A3. **Sideload ergonomics.** On `PACKAGE_ADDED` of a valid plugin candidate with no
    grant, when dev mode is ON: post a hub notification "New plugin detected —
    tap to review access" deep-linking to Plugin access. (Today the developer must
    find the pending row by themselves; `BusHubService.kt:738` only reconciles.)

A4. **Grant-reconciliation hardening** (real bug found by the investigation): a hub
    that never observes `PACKAGE_REMOVED` keeps stale grants that re-match a later
    same-signer reinstall (`BusHubService.kt:337`). Add unconditional grant
    reconciliation at `BusHubService` startup. Small, independent, ship first.

A5. **Known paper cuts** (fix in passing, all confirmed by the investigation):
    - Hard-coded `3 new plugins` Store teaser on home (`MainActivity.kt:418`) while
      the live registry is empty — derive from the actual feed.
    - Exported `PluginPermissionsActivity` (`AndroidManifest.xml:47`) — decide
      exported-or-not deliberately and document why (it is the approval surface).

## Part B — Bus inspector

B1. **Hub-side event journal.** A bounded in-memory ring buffer (~500 events, dev
    mode only) fed from the narrow choke points the investigation identified:
    `registerPlugin` + `addRegistration`/`removeRegistration` (registration attempts
    AND rejection codes — today rejections are mostly silent, `BusHubService.kt:192`),
    `routeLocal` (shows/updates/hides + every rejection: capability, namespace,
    SURFACE_BUSY, image validation), `routeRemote` + `PhonePluginRegistry.handleRemote`
    (launcher opens, input), `deliverExternalLifecycle`/`deliverExternalBinary`
    (including the currently-silent >512 KiB binary drop, `BusHubService.kt:799`),
    `sendRemote`/`deliverLocal` (transport choice, terminal drops), and
    `ExternalPluginController` (timeouts, rebinds, self-close).
    Each event: timestamp, pluginId, direction, path, size, verdict (ok/rejected+reason).

B2. **Inspector screen** under Settings → Advanced (dev mode only): live event list,
    filter by plugin, tap for detail. Replaces nothing — the existing broadcast
    console stays. NexusUi styling; this is developer UI but it still ships in the
    consumer app, so it must not look foreign.

B3. **SDK-side visibility** — the hub cannot see SDK preflight failures (typed-model
    `require()`s, the 64 KiB surface ceiling, image preflight, queue evictions;
    `SurfaceModels.kt:396`), and `NexusPluginClient.onError` currently DISCARDS
    transport/queue errors (`NexusPluginClient.kt:127`). Minimum viable fix, no new
    IPC channel: (a) route those errors to an overridable callback + logcat with a
    stable tag (`NexusPlugin`), (b) document the tag in AGENTS.md as "your plugin's
    error stream". A dev-mode bus channel reporting SDK errors to the inspector is a
    possible follow-up, explicitly out of scope for v1.

## Part C — Third-party distribution (registry + installer)

C1. **Signer pinning end-to-end** (the one real security gap):
    - Registry schema: add `artifact.signerSha256` (lowercase hex, same encoding as
      `PhonePluginDiscovery.kt:169`). Builder requires it for `nexus-plugin` kind.
    - Phone: `RegistryClient` parses it; `PluginInstaller` verifies the downloaded
      APK's signer against it BEFORE the PackageInstaller session (it currently reads
      only `packageName` pre-install, `PluginInstaller.kt:226`).
    - Feed version stays 1 with an additive field; the hub treats a missing signer
      field as "install allowed, shown as unpinned" only for a transition window —
      or we just require it from day one since the feed is still empty. Prefer:
      require from day one.

C2. **Registry CI actually verifies submissions.** On PR: download the artifact URL,
    recompute sha256/size, `aapt`-extract package/versionCode/versionName and compare
    to the manifest, extract the signer cert digest and compare to `signerSha256`,
    parse the plugin service metadata (pluginId, apiVersion == 3, capabilities against
    the Nexus allowlist, headless check: no MAIN/LAUNCHER activity, exactly one
    exported plugin service). The `update-artifact-metadata.mjs` machinery already
    downloads + aapt-parses; extend it rather than writing new tooling.

C3. **Align builder validation with what the phone accepts** (every mismatch is a
    submission that validates green and then silently never appears in the Store):
    require HTTPS URLs (`build-registry.mjs:74` vs `RegistryClient.kt:319`), require
    `id == nexus.pluginId` (`StoreCatalog.kt:40` drops mismatches), validate
    capabilities against the allowlist, pin `apiVersion == 3`, make
    `settingsActivity` optional to match the phone model (`add-app-from-url.mjs`
    currently requires it).

C4. **Submission UX**: PR template + `plugins-nexus/README.md` rewrite as the
    canonical "publish your plugin" doc (host your APK on your releases, fill the
    manifest — the excluded `EXAMPLE.template.json` becomes the copyable starting
    point, open a PR, CI validates, we review). Cross-linked from AGENTS.md.

## Part D — AGENTS.md + sample + docs cleanup

D1. **`plugins/AGENTS.md`** — the single self-contained plugin-authoring contract,
    written for a developer OR their coding agent. Content plan:
    - What a plugin is (headless APK model) and the exact manifest contract.
    - SDK dependency coordinates (Maven, from Part E) and minimal build.gradle.
    - Full lifecycle: discovery → grant → launcher open → PLUGIN_OPEN/CLOSE/INPUT,
      dormant policy, FGS session behavior, open-ack timeout + rebind, SURFACE_BUSY
      arbitration, self-close semantics.
    - **Complete endpoint reference**: every bus path a plugin can send/receive with
      capability requirements, plus every enforced limit — the investigation's
      inventory (surface/card/timed/media/image/artwork limits, HTTP proxy budgets
      and host allowlist, binary ceilings, descriptor regex, timeouts) becomes the
      normative table. Undocumented-but-enforced items from the gap table all land
      here.
    - Debugging: dev mode, the inspector, the SDK logcat tag, common rejection
      reasons and what they mean.
    - Testing loop: build → adb install → approve → launch from glasses launcher.
    - Publishing: link to the registry submission flow (C4).
    Keep `PLUGIN_SDK.md` as the wire/AIDL spec and `PLUGINS.md` as the styling/UX
    guide; AGENTS.md links rather than duplicates where they are already correct.

D2. **Fix the documented lies** (from the investigation's gap table): the
    minSdk 26/31 contradiction, the notification-policy contradiction, "Lens remains
    built-in" (stale since plan 007), "plugins never initiate surfaces" (idle-HUD
    adoption exists), BUSSPEC's coexisting API 1/2/3 and base64/raw sections get a
    clear "historical" partition or deletion, `camera` capability documented.

D3. **Rehabilitate `plugins/sample`** into the canonical headless template: remove
    the MAIN/LAUNCHER filter (the disqualifying bug), NexusUi/BusTheme settings
    screen with uninstall row, `com.anezium.rokidbus.plugin.sample` id + minSdk 31 +
    version 1.0.0 per convention, fix the missing `raw/image_surface_sample`
    resource, drop the hard-coded hub component launch. Acceptance: a byte-for-byte
    copy of sample with a renamed id passes the C2 validation checks.

## Part E — SDK publishing

E1. **License — DECIDED: Apache-2.0 for the whole repo** (owner, 2026-07-15). Add
    LICENSE, per-module license headers policy (none required beyond the root file),
    and remove the "not yet licensed" wording from README + PLUGIN_SDK.md
    (`README.md:44`). Takes effect when the repo goes public.

E2. **Publish `bus-client` + `shared` together** (bus-client's `api(project(":shared"))`
    makes shared a hard transitive requirement, `bus-client/build.gradle.kts:33`),
    same version, via JitPack (jitpack.yml already exists; zero infra) triggered by an
    `sdk-v<semver>` tag. Add POM metadata (name/description/url/license/scm). GitHub
    Packages is NOT the choice — it requires auth even for public reads, which kills
    the "clone the template and build" flow.

E3. **Version discipline**: `sdk-v*` tag workflow validates the tag matches
    `-PversionName`, runs the existing sample-against-published-artifact check
    (`sdk.yml`), and cuts a GitHub release whose notes state the plugin API version
    (3) it targets. AGENTS.md references the coordinate with a real version.

## Sequencing & delegation

Order: **A4 (grant fix) → B (inspector) → A (dev mode UX) → C (registry) → D (docs/
sample) → E2/E3 (publish, gated on E1 + repo public)**. C and D can run in parallel
with A/B; D1 should land after B so the debugging chapter describes the real tooling.

Delegation (per standing model policy):
- A4, B1, C1–C3, D3, E2/E3: Codex (xhigh; A4 and C3 high). Each as one self-contained
  run with acceptance criteria; B1/B2 split (journal plumbing vs screen).
- A1–A3, B2 screen design, D1, D2, C4 prose: Fable/Opus (user-facing UI + docs = taste
  work; AGENTS.md is the product's public face to developers).
- Review gate: every Codex diff reviewed before build, as always.

## Acceptance (end-to-end)

A stranger with glasses + phone + the public repo docs can: build the sample from the
published SDK coordinate, sideload it, get the detection notification, approve it,
open it from the glasses launcher, watch its traffic in the inspector, deliberately
trigger a SURFACE_BUSY and see it explained — then publish it via a registry PR that
CI green-lights, and install their own plugin from the Store on a second phone.

## Decisions log

All four open questions were resolved by the owner on 2026-07-15 and are folded into
the Status block above: Apache-2.0 everywhere (E1), `Verified · <author>` labeling
for signer-pinned Store installs (A2), the dev-mode sideload notification is approved
(A3), and registry review is mechanical CI + human merge (C4).
