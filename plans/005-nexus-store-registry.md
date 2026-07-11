# Plan 005: Drive the Nexus Store from the RokidBrew registry

> **Executor instructions**: Follow every step and verification gate in order.
> This plan turns the Nexus Store from a local, hard-coded catalogue into a real
> network catalogue backed by the existing `RokidBrew-Registry`, scoped to Nexus
> plugins only, and additionally wires the app's own self-update (Part C) from
> the Nexus repo's public GitHub releases, reusing the same installer. It spans
> the registry (`RokidBrew-Registry`), the Nexus client (`RokidNexus/phone-hub`),
> and the Nexus repo's release CI. Stop on any listed STOP
> condition. Do not invent a second identity, consent, or trust model — reuse
> the Plan 002 grant boundary verbatim. Update `plans/README.md` when complete.

## Status

- **Status**: TODO
- **Priority**: P1
- **Effort**: L
- **Risk**: MED
- **Depends on**: `plans/003-external-plugin-sdk.md` (DONE), `plans/004-externalize-transit.md` (DONE)
- **Category**: architecture, distribution, dx, ui
- **Planned at**: main `2d66884`, 2026-07-12

## Why this matters

Plan 003 shipped the external plugin SDK, discovery, and grant boundary, but
left `StoreActivity` as an explicitly deferred stub: a locally-built catalogue of
already-installed plugins plus two hard-coded "Coming soon" teasers, with a note
to keep remote catalogue/download out of scope until a store API existed. That
store API now exists in a sibling project: **`RokidBrew-Registry`** already
ingests apps from GitHub releases, extracts icons/screenshots/listings via CI,
and publishes an aggregate index (`dist/apps.v1.json`) that the RokidBrew store
consumes. This plan makes the Nexus Store a first-class client of that registry
so a user can discover, install, and update Nexus plugins over the network
without a cable, ADB, or developer setup — realizing the "RokidBrew distribution
metadata" item deferred in `plans/README.md`.

## Product decisions this plan must preserve (locked with the owner)

- **The Nexus Store is plugins-only.** It lists and installs Nexus plugins
  (phone APKs carrying `com.anezium.rokidbus.plugin.*` metadata). Standalone
  glasses apps stay in the RokidBrew store.
- **Plugins are phone-only installs.** Install = the phone `PackageInstaller`
  path with the standard user consent. The Nexus Store never installs on the
  glasses, never uses ADB, and never uses CXR.
- **Glasses install stays RokidBrew's job**, via CXR-L `appUploadAndInstall`
  (`CxrGlobal/.../CXRLink.kt`). It is out of scope here.
- **Trust = owner curation.** The registry owner reviews and validates every
  entry; the registry is authoritative. There is no separate "verified" tier —
  at most a cosmetic "official" flag. Do not add a client-side reputation model.
- **Install never implies capability approval.** After install, the existing
  Plan 002 discovery + grant flow (`PhonePluginDiscovery`, `PluginGrantStore`,
  `PluginPermissionsActivity`) runs unchanged. Downloading an APK grants nothing.
- **The registry keeps plugins separate from apps.** Nexus plugins live in their
  own registry namespace (`plugins-nexus/`) and their own published feed
  (`dist/nexus-plugins.v1.json`), so the plugin schema and the Nexus client feed
  stay clean and evolve with the plugin API independently of the app schema.
- **The app self-update is NOT the registry.** The Nexus host updates from its
  own public GitHub releases (`Anezium/Rokid-Nexus`, public once shipped) via a
  small `nexus-latest.json` manifest — the host is a single first-party artifact,
  not a catalogue. It reuses the shared installer. Android's same-signer
  enforcement on update is the security gate; the manifest `sha256` is
  defense-in-depth. The glasses app updates over CXR-L `appUploadAndInstall`.
- Keep the Phosphor×Mono visual language (`NexusUi`, `BusTheme`). Do not redesign
  the Store; re-skin its data source. The app-update UI already exists
  (`NexusUi.updateBanner`, the Settings row, the amber gear pip); this plan wires
  it, it does not rebuild it.

## Current state

Registry (`E:\Tools\Rokid\RokidBrew-Registry`):

- `apps/*.json` — one descriptor per app: `id`, `name`, `category`, `type`
  (`combo`/`phone`/`glasses`), `version`, `summary`, `description`,
  `artifacts[]` (`target`, `url`, `sha256`, `sizeBytes`, `packageName`,
  `versionCode`, `versionName`), `author`, `sourceUrl`, `screenshotAssets[]`,
  `publishedAt`, `listing.descriptionMarkdown`, `releases[]`.
- `dist/apps.v1.json` — the aggregate index clients fetch.
- `scripts/*.mjs` — `build-registry`, `add-app-from-url`, `import-github-releases`,
  `extract-missing-icons`, `generate-ai-listing`, `check-updates`, etc.
- `.github/workflows/*.yml` — CI that ingests releases and builds the index.
- `assets/{apks,icons,screenshots}/`.

Nexus (`E:\Tools\Rokid\RokidNexus\phone-hub`):

- `StoreActivity.kt` — a static catalogue: one hard-coded "Relay" featured card
  and one "Scribe" card, both `comingSoon()`, plus the locally installed plugins
  from `BusHubService.pluginCatalog(this)` shown as "Installed · <state>".
- `PluginCatalog.kt` / `PhonePluginDiscovery` — local discovery of installed
  plugin APKs by `action.PLUGIN` metadata, merged with built-ins.
- `PluginGrantStore`, `PluginPermissionsActivity` — the Plan 002 grant flow.
- No network client, no remote catalogue, no download/install path.

## The plugin descriptor (registry side)

New namespace `plugins-nexus/<id>.json`. Plugins are phone-only, so a single
artifact. Plugin-specific data lives under a `nexus` block:

```json
{
  "id": "feeds",
  "kind": "nexus-plugin",
  "name": "Feeds",
  "category": "Social",
  "summary": "Read Bluesky and X feeds on your glasses.",
  "description": "…",
  "author": "Anezium",
  "sourceUrl": "https://github.com/Anezium/…",
  "publishedAt": "2026-…",
  "iconAsset": "feeds-icon.png",
  "screenshotAssets": ["feeds-1.jpg"],
  "listing": { "descriptionMarkdown": "…" },
  "releases": [ { "version": "0.1.0", "date": "…", "notes": "…" } ],
  "nexus": {
    "pluginId": "feeds",
    "apiVersion": 3,
    "capabilities": ["surfaces"],
    "launchable": true,
    "settingsActivity": ".FeedsSettingsActivity",
    "minHostVersionCode": 6
  },
  "artifact": {
    "target": "phone",
    "url": "https://github.com/Anezium/…/releases/download/v0.1.0/feeds-phone-release.apk",
    "sha256": "…",
    "sizeBytes": 0,
    "packageName": "com.anezium.rokidbus.plugin.feeds",
    "versionCode": 3,
    "versionName": "0.1.0"
  }
}
```

`nexus.pluginId` MUST equal the plugin's `com.anezium.rokidbus.plugin.ID`
metadata, and `artifact.packageName` MUST equal the plugin's real package —
these are the join keys against the local catalogue. `build-registry.mjs` emits
the aggregate `dist/nexus-plugins.v1.json` (a versioned array of these entries)
next to `apps.v1.json`.

## Scope

**In scope — `RokidBrew-Registry`**:

- `plugins-nexus/*.json` descriptor schema and the first two real entries
  (`feeds`, `transit`).
- `scripts/build-registry.mjs` to also aggregate `plugins-nexus/` into
  `dist/nexus-plugins.v1.json`.
- Ingestion scripts / workflows parameterized by a `kind` so a plugin release can
  be imported into `plugins-nexus/` reusing the existing icon/listing extraction;
  do NOT fork the pipeline.
- A short registry `README`/schema note documenting the plugin descriptor.

**In scope — `RokidNexus/phone-hub`**:

- `RegistryClient.kt` (create) — fetch + cache + parse `nexus-plugins.v1.json`.
- `StoreCatalog.kt` (create) — merge the registry feed with the local
  `PluginCatalog` into a per-entry Store state.
- `PluginInstaller.kt` (create) — download, sha256-verify, `PackageInstaller`.
- `StoreActivity.kt` — render the merged Store catalogue and drive
  install/update/open; remove the hard-coded teasers (or make them registry-fed).
- `phone-hub/src/main/AndroidManifest.xml` — INTERNET (already present), a
  `PackageInstaller` result receiver, and a cache `FileProvider` if needed.
- `AppUpdateChecker.kt` (create) — fetch `nexus-latest.json`, compare versionCode,
  set `NexusPhoneState.updateAvailable`; wire the existing banner / Settings
  "Check"/"Install" action and the amber pip to the shared installer.
- Glasses app update via `CxrGlobal` `CXRLink.appUploadAndInstall` (phone side).
- Replace the hard-coded `UPDATE_VERSION_LABEL` with the real installed/available
  version.
- Unit tests for parse, merge/state, installer orchestration, and the update
  checker (seam-tested).

**In scope — `RokidNexus` repo (release CI)**:

- A release workflow that builds the phone + glasses release APKs, computes each
  `sha256`, and publishes them plus a `nexus-latest.json` manifest as GitHub
  release assets. (Plan 003 deliberately did not set up releases; this adds them.)

**Out of scope**:

- Glasses-app install (CXR-L / RokidBrew), the RokidBrew web store, and any
  RokidBrew plugins page (a later, separate task consuming the same feed).
- Extracting/slimming the glasses self-arm module (tracked separately).
- Payments, ratings, remote telemetry, or a client-side trust/reputation model.
- Renaming package IDs; loading plugin code into the hub process.

## Steps

### Part A — Registry

#### A1: Define the plugin descriptor and add two real entries

Add `plugins-nexus/feeds.json` and `plugins-nexus/transit.json` following the
schema above, pointing `artifact.url` at real phone-plugin release APKs with
correct `sha256`, `packageName`, and `versionCode`. Add their icons under
`assets/icons/`.

**Verify**: `node scripts/build-registry.mjs` runs clean and both descriptors
validate (add a minimal JSON-schema/shape check if the build has one).

#### A2: Emit the plugin feed from `build-registry.mjs`

Extend the builder to aggregate every `plugins-nexus/*.json` into
`dist/nexus-plugins.v1.json` with a top-level `{ "version": 1, "plugins": [...] }`
shape. Leave `apps.v1.json` untouched. Publish `dist/` the same way the app index
is published (raw GitHub / Pages — match the existing mechanism).

**Verify**: `dist/nexus-plugins.v1.json` contains `feeds` and `transit`, each
with a resolvable `artifact.url` and matching `sha256`.

#### A3: Parameterize ingestion by `kind`

Add a `--kind nexus-plugin` (default `app`) to the ingestion scripts
(`add-app-from-url` / `import-github-releases`) and the matching workflow input so
a plugin release imports into `plugins-nexus/` with the plugin schema while
reusing the shared icon/screenshot/listing extraction. Do not duplicate the
pipeline; branch only on target directory + schema.

**Verify**: importing a plugin release with `--kind nexus-plugin` writes a valid
`plugins-nexus/<id>.json` and leaves `apps/` untouched.

### Part B — Nexus client (`phone-hub`)

#### B1: Registry client

Create `RegistryClient` that fetches `nexus-plugins.v1.json` over HTTPS on an IO
thread, caches the body + ETag/last-fetch in app storage, serves the cache
offline, and parses into an immutable `RegistryPlugin` model. Reject unknown
top-level `version`; ignore unknown fields. Never trust the feed for identity —
it is a catalogue, not an authority.

**Verify**: pure-JVM tests for parse (valid, unknown-field, wrong-version),
cache-hit offline, and malformed-body rejection.

#### B2: Store catalogue merge + state

Create `StoreCatalog` that joins `RegistryPlugin` (by `nexus.pluginId` /
`artifact.packageName`) with the local `PluginCatalog` and the installed package
`versionCode`, producing one `StoreEntry` per plugin with a state:

- `AVAILABLE` — in feed, not installed;
- `UPDATE_AVAILABLE` — installed, feed `versionCode` > installed;
- `INSTALLED` — installed and current (carry the local grant state:
  enabled/pending/disabled/denied for the button/label);
- `SIDELOADED` — installed but absent from the feed (local only);
- `REQUIRES_HOST` — `nexus.minHostVersionCode` > installed host `versionCode`
  (offer disabled with a "Requires Nexus x.y" note).

Duplicate `pluginId` across the feed is invalid and dropped with a log.

**Verify**: unit tests for each state transition, duplicate feed id, and the
host-version gate.

#### B3: Plugin installer

Create `PluginInstaller` that, for a `StoreEntry`, downloads the artifact to app
cache, verifies the `sha256` (abort on mismatch, delete the file), and installs
via a `PackageInstaller` session (standard user-confirmation UI — no silent
install). Surface progress/failure/cancel states. On `STATUS_SUCCESS`, trigger a
catalogue refresh so `PhonePluginDiscovery` picks the new package up. Never
install an unverified or host-incompatible artifact.

**Verify**: seam-tested orchestration (download ok / sha256 mismatch / install
failure / cancel), with the real `PackageInstaller` exercised on-device in B6.

#### B4: Rewrite `StoreActivity` over the merged catalogue

Drive the list from `StoreCatalog`. Category chips come from the feed's
`category` values. Per-entry button: `Install` (AVAILABLE), `Update`
(UPDATE_AVAILABLE), `Open` (INSTALLED+launchable), `Review`
(INSTALLED+pending/denied), disabled (REQUIRES_HOST). Remove the hard-coded
Relay/Scribe teasers, or render them only if the feed carries a
`comingSoon`-flagged entry. Preserve `NexusUi`/`BusTheme`. Show an intentional
empty state when the feed is empty/unreachable and the cache is cold.

**Verify**: phone build; a manual pass with zero, one available, one installed,
and one update-available entry.

#### B5: Wire install → discovery → grant end to end

After a successful install, route the user into the existing
`PluginPermissionsActivity` grant flow for that principal (install ≠ grant).
Confirm an updated plugin keeps its prior grants and does not silently re-enable
a revoked one.

**Verify**: phone-hub tests for the post-install refresh and grant hand-off.

#### B6: On-device validation

On a clean phone (plugin not installed): open the Store, install `Feeds` from the
registry, confirm sha256-verified download, standard install consent, then
pending → grant `surfaces` → open on the glasses. Then publish a higher
`versionCode` and confirm the Store shows `Update available` and updates in place
without dropping grants. Record redacted PASS/FAIL in `TESTPLAN.md`.

**Verify**: `.\gradlew.bat :phone-hub:testDebugUnitTest :phone-hub:assembleDebug`
exits 0; the device scenario passes.

### Part C — Nexus app self-update (host, not plugins)

The host updates from its own **public GitHub releases** (`Anezium/Rokid-Nexus`),
deliberately not through the registry. It reuses Part B's installer core
(download → sha256 → `PackageInstaller`) for the phone APK; the glasses APK
installs over CXR-L.

#### C1: Publish a release manifest from the Nexus repo

Add a release workflow (`RokidNexus/.github/workflows/`) that, on tag, builds the
phone and glasses release APKs, computes each `sha256`, and publishes them as
release assets alongside a small `nexus-latest.json`:

```json
{
  "versionName": "1.5",
  "phone":   { "url": "…/nexus-phone-release.apk",   "versionCode": 12, "sha256": "…", "packageName": "com.anezium.rokidbus.phone" },
  "glasses": { "url": "…/nexus-glasses-release.apk", "versionCode": 12, "sha256": "…", "packageName": "com.anezium.rokidbus.glasses" }
}
```

The updater fetches the stable
`https://github.com/Anezium/Rokid-Nexus/releases/latest/download/nexus-latest.json`
(one GET, no token). **Verify**: a tagged run publishes both APKs + a valid
manifest; the manifest `sha256` matches the assets.

#### C2: App update checker

Create `AppUpdateChecker` that fetches the manifest, compares `phone.versionCode`
to `BuildConfig.VERSION_CODE`, and sets `NexusPhoneState.updateAvailable`. Cache
the last fetch; check on launch and on the Settings "Check" tap. Replace the
hard-coded `UPDATE_VERSION_LABEL`/"1.4" with the real installed and available
versions. **Verify**: unit tests for newer/equal/older and offline (no false
positive from a stale/absent manifest).

#### C3: Install the phone update

Wire the existing `NexusUi.updateBanner` and the Settings "Install" action to the
shared installer: download `phone.url`, verify `sha256`, `PackageInstaller`
(Android enforces same-signer). Remove the "Coming soon" toast. **Verify**:
seam-tested; on-device in C5.

#### C4: Install the glasses update via CXR-L

If `glasses.versionCode` exceeds the installed glasses version (query via CXR-L
`appIsInstalled`), download `glasses.url`, verify `sha256`, and install through
`CXRLink.appUploadAndInstall`, surfacing `onInstallAppResult`. Gate on an active
CXR link; never ADB. **Verify**: on-device in C5.

#### C5: On-device update cycle

From an older build: launch → banner + amber pip appear → Settings shows
"Install" → phone updates via `PackageInstaller` with plugins/grants preserved →
glasses updates via CXR-L. Record redacted PASS/FAIL in `TESTPLAN.md`.

**Verify**: `.\gradlew.bat :phone-hub:testDebugUnitTest :phone-hub:assembleDebug`
exits 0; the update cycle passes on device.

## Test plan

- Registry: `build-registry.mjs` emits a valid `nexus-plugins.v1.json`; `--kind`
  import writes a valid plugin descriptor without touching `apps/`.
- Client: feed parse (valid/unknown/version), cache offline, merge/state matrix,
  duplicate id, host-version gate, installer orchestration (ok/mismatch/fail/
  cancel), post-install refresh, grant hand-off.
- App self-update: manifest parse, versionCode compare (newer/equal/older),
  offline safety, phone-install seam, glasses CXR-L install seam.
- Existing phone-hub catalogue, discovery, grant, Lyrics/Media/Lens, and Feeds
  tests remain green.
- Hardware: B6 plugin install + update cycle; C5 app self-update cycle
  (phone + glasses).

## Done criteria

- [ ] `plugins-nexus/` schema documented; `feeds` and `transit` published in
      `dist/nexus-plugins.v1.json` with verifiable `sha256`.
- [ ] Ingestion imports plugins via `--kind` without forking the pipeline.
- [ ] `RegistryClient` fetches + caches + parses the feed and serves it offline.
- [ ] `StoreCatalog` computes AVAILABLE/UPDATE/INSTALLED/SIDELOADED/REQUIRES_HOST
      correctly from feed × local catalogue.
- [ ] `PluginInstaller` downloads, verifies sha256, and installs via
      `PackageInstaller`; a mismatch aborts.
- [ ] `StoreActivity` renders the real catalogue with correct per-state actions
      and no hard-coded teasers.
- [ ] Install routes into the existing grant flow; install never implies a grant.
- [ ] On-device plugin install + update cycle passes with grants preserved.
- [ ] The Nexus repo publishes phone + glasses release APKs and a
      `nexus-latest.json` manifest on tag.
- [ ] `AppUpdateChecker` sets `updateAvailable` from the manifest; the existing
      banner / Settings action / amber pip drive a real update (no "Coming soon").
- [ ] Phone self-update installs via `PackageInstaller` (same-signer); glasses
      update installs via CXR-L `appUploadAndInstall`; both verify `sha256`.
- [ ] `.\gradlew.bat test lintDebug assembleDebug` exits 0.

## STOP conditions

Stop and report if:

- The published feed URL is not owner-controlled or not served over HTTPS.
- Installing a **plugin** would require silent install, device-owner, ADB, or
  CXR (plugins install via the phone `PackageInstaller` only). CXR is permitted
  solely for the **host glasses-app self-update** in Part C, never for plugins.
- An **app update** would install without same-signer enforcement (phone) or
  without verifying the manifest `sha256`.
- The design would let the feed assert plugin identity, capabilities, or grants
  (the feed is a catalogue; the hub + Android identity remain authoritative).
- sha256 cannot be verified before install, or install would proceed on a
  host-incompatible plugin.
- The plugin schema would need to merge into the app schema (`apps/*.json`)
  rather than its own `plugins-nexus/` namespace.
- Any verification fails twice after a scoped correction.

## Maintenance notes

- A later task can add a RokidBrew store "Plugins" page consuming the same
  `nexus-plugins.v1.json` feed; keep the feed shape stable and versioned.
- A plugin with an optional glasses companion (Lens-style) is a future extension:
  the phone artifact still installs via `PackageInstaller`; a glasses companion
  would install through RokidBrew/CXR-L, not the Nexus Store.
- Revisit self-arm module extraction separately; it is not a Store dependency
  because Nexus plugins are phone-only.
- Keep the plugin API version (`nexus.apiVersion`) and `minHostVersionCode` in
  the descriptor so the Store can gate incompatible plugins as the host evolves.
