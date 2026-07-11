# Plan 003: Deliver an external plugin SDK, lifecycle, sample, and reproducible publication

> **Executor instructions**: Follow every step and verification gate in order.
> This plan turns the low-level bus client into a public plugin contract while
> preserving the current built-in plugins until their individual migration
> plans. Stop on any listed STOP condition; do not create a second identity or
> consent model. Update `plans/README.md` when complete unless a reviewer owns it.
>
> **Drift check (run first)**:
>
> ```powershell
> git status --short
> git diff --stat da068ad..HEAD -- settings.gradle.kts build.gradle.kts shared bus-client phone-hub phone-client-probe glasses-client-probe README.md jitpack.yml .github plugin-sample
> git diff --stat -- settings.gradle.kts build.gradle.kts shared bus-client phone-hub phone-client-probe glasses-client-probe README.md jitpack.yml .github plugin-sample
> ```
>
> Execute only after Plans 001 and 002 are `DONE` in a clean branch/worktree.
> This plan was authored against commit `da068ad` plus uncommitted Lens work;
> compare the current-state excerpts after those prerequisite plans land.

## Status

- **Priority**: P1
- **Effort**: L
- **Risk**: MED
- **Depends on**: `plans/002-plugin-identity-and-capabilities.md`
- **Category**: architecture, dx, docs, migration, tests
- **Planned at**: commit `da068ad`, 2026-07-09, plus the uncommitted Lens snapshot described above

## Why this matters

The current `:bus-client` proves cross-process communication, but an external
developer still has to understand raw paths, AIDL registration, JSON shapes,
service wake rules, and hub discovery. The phone launcher only knows in-process
objects linked into the hub. This plan creates one supported SDK path: declare a
plugin service, receive lifecycle/input callbacks, send typed surfaces, request
approved resources, and appear dynamically in Nexus after user consent. It also
makes the SDK build and publish independently of the developer's sibling
`CxrGlobal` checkout.

## Product decisions that this plan must preserve

- Nexus core remains neutral; this plan does not bundle a default plugin.
- Both curated and side-loaded APKs use the same SDK and security boundary.
- Normal mode hides protocol complexity; developer mode exposes diagnostics.
- Plugin installation never implies capability approval.
- Phone plugins are the default zero-glasses-install model.
- A plugin with an optional glasses companion (Lens later) is an extension of
  this model, not a reason to force glasses code on every plugin.
- Package/application IDs and current wire actions remain under
  `com.anezium.rokidbus` for compatibility; product naming may say Rokid Nexus.

## Current state

Relevant files:

- `bus-client/src/main/java/.../BusClient.kt` exposes raw path/payload send and
  request operations.
- `bus-client/src/main/java/.../BusClientService.kt` depends on an in-memory
  static factory unless an app subclasses it.
- `shared/src/main/java/.../plugin/NexusPlugin.kt` is an in-process interface,
  not an inter-APK contract.
- `phone-hub/.../PhonePluginRegistry.kt` receives a constructor list of
  `NexusPlugin` objects and builds the launcher from that list.
- `phone-hub/.../MainActivity.kt` hardcodes `2 Active`, Lyrics, and Transit.
- `phone-hub/.../StoreActivity.kt` is a static visual catalogue.
- `settings.gradle.kts` unconditionally includes a sibling `../CxrGlobal` build.
- `bus-client/build.gradle.kts` has no `maven-publish` configuration and uses an
  internal `implementation(project(":shared"))` dependency.
- There is no root README, SDK quickstart, CI workflow, JitPack config, or Maven
  publication task.

Current in-process registration (`PhonePluginRegistry.kt:18-40`):

```kotlin
class PhonePluginRegistry(
    override val context: Context,
    plugins: List<NexusPlugin>,
    // ...
) : NexusPluginHost {
    private val pluginsById = linkedMapOf<String, NexusPlugin>()
    init { plugins.forEach(::register) }
}
```

Current launcher sync (`PhonePluginRegistry.kt:108-123`) serializes only those
in-process objects. Current home UI (`MainActivity.kt:121-145`) separately
hardcodes the same two products, so registry and UI can already drift.

Current service base (`BusClientService.kt:11-50`) stores a process-local static
factory. A process cold-started by the hub cannot rely on another Activity having
registered that factory first.

Current Gradle settings (`settings.gradle.kts:29-33`) require a local sibling:

```kotlin
includeBuild("../CxrGlobal") {
    dependencySubstitution {
        substitute(module("com.example.cxrglobal:lib")).using(project(":lib"))
    }
}
```

The security and consent source of truth produced by Plan 002 is mandatory:
`PluginDescriptor`, `PluginCapability`, `PluginGrantStore`, registration result
codes, and package/UID/certificate verification must be reused verbatim.

## Public SDK shape

Keep `:bus-client` as the public artifact to match `VISION.md`; do not create a
competing plugin-SDK module. Add these supported public concepts:

- `NexusPluginService`: abstract wakeable Android service with no static factory;
- `NexusPluginClient`: lifecycle-aware client owned by the service;
- `NexusPluginCallbacks`: `onOpen`, `onClose`, `onInput`, link state, and denied state;
- immutable surface models/builders for `card` and `timed-lines`;
- `NexusSurfaceSession`: show/update/hide using a plugin-local surface ID;
- capability-aware request helpers for HTTP/audio that fail locally when not granted;
- explicit `HubTarget`/hub package configuration; never bind the first service
  returned for a generic action.

The hub remains authoritative for plugin identity, owner injection, surface
sequence/session metadata, and grants. The SDK must not ask developers to set a
global sequence or trusted owner ID.

Use versioned reserved lifecycle messages with stable paths documented in
`BUSSPEC.md`, for example:

- `/system/plugin/open`
- `/system/plugin/close`
- `/system/plugin/input`
- `/system/plugin/registration`

These paths are hub-to-plugin only under Plan 002 route policy. Every payload has
`version`, `type`, `id`, and the minimum required fields; parsing ignores unknown
fields but rejects unsupported required versions cleanly.

## Commands you will need

| Purpose | Command | Expected on success |
|---|---|---|
| Preconditions | `.\gradlew.bat test lintDebug assembleDebug` | exit 0 after Plans 001-002 |
| SDK tests | `.\gradlew.bat :shared:testDebugUnitTest :bus-client:testDebugUnitTest` | exit 0 |
| Local publication | `.\gradlew.bat :shared:publishToMavenLocal :bus-client:publishToMavenLocal -PversionName=0.1.0-SNAPSHOT` | exit 0 |
| Published consumer smoke | `.\gradlew.bat :plugin-sample:assembleDebug -PusePublishedSdk=true -PversionName=0.1.0-SNAPSHOT` | exit 0 |
| Full regression | `.\gradlew.bat test lintDebug assembleDebug` | exit 0 |
| Patch hygiene | `git diff --check` | exit 0 |

## Suggested executor toolkit

- Use `rokid-glasses-dev` for bus/Caps/wake/device constraints.
- Use official Gradle/Android publishing behavior already available in the
  installed toolchain; do not add a third-party publishing plugin unnecessarily.
- No network publication, GitHub release, Maven Central upload, or JitPack build
  trigger is authorized by this plan.

## Scope

**In scope**:

- `settings.gradle.kts`
- `build.gradle.kts`
- `gradle.properties` if a default snapshot version property is required
- `shared/build.gradle.kts`
- `bus-client/build.gradle.kts`
- existing bus-client AIDL and Kotlin sources as required by the Plan 002 API
- new SDK sources under `bus-client/src/main/java/com/anezium/rokidbus/client/plugin/`
- SDK unit tests under `bus-client/src/test/`
- `phone-hub/src/main/java/com/anezium/rokidbus/phone/PhonePluginRegistry.kt`
- `phone-hub/src/main/java/com/anezium/rokidbus/phone/ExternalPluginController.kt` (create)
- `phone-hub/src/main/java/com/anezium/rokidbus/phone/PluginCatalog.kt` (create)
- `phone-hub/src/main/java/com/anezium/rokidbus/phone/BusHubService.kt`
- `phone-hub/src/main/java/com/anezium/rokidbus/phone/MainActivity.kt`
- `phone-hub/src/main/java/com/anezium/rokidbus/phone/StoreActivity.kt` only for
  installed-plugin state/settings intents; do not implement remote store download
- `phone-hub/src/main/AndroidManifest.xml`
- `phone-client-probe` and `glasses-client-probe` only to pin their expected hub
  package/target and preserve debug verification
- new `plugin-sample/` application module
- `README.md` (create)
- `docs/PLUGIN_SDK.md` (create)
- `docs/PROTOCOL.md` (create or link clearly to `BUSSPEC.md` without duplication)
- `BUSSPEC.md`
- `jitpack.yml` (create)
- `.github/workflows/sdk.yml` (create)

**Out of scope**:

- Converting Transit, Lyrics, or Lens; Plan 004 starts with Transit.
- Removing built-in plugin support before both current plugins have external APKs.
- Display arbitration, toast/actionable layers, microphone indicator, camera
  companion provisioning, no-ADB setup, or RokidBrew network integration.
- Publishing to the internet, creating a release/tag, or pushing a branch.
- Adding a repository license without maintainer confirmation. Apache-2.0 is the
  current recommendation, but legal licensing is a maintainer decision.
- Renaming Android package IDs from `rokidbus` to `rokidnexus`.
- Loading third-party classes into the hub process. External code stays in its
  own APK/process and communicates through Binder.

## Git workflow

- Branch: `advisor/003-external-plugin-sdk`
- Suggested commits: `Add external Nexus plugin lifecycle`, `Drive launcher from
  plugin catalog`, `Publish bus client to Maven local`, `Add plugin SDK sample`.
- Do not publish, tag, push, or open a PR unless instructed.

## Steps

### Step 1: Make hub selection explicit and injectable

Replace `BusClient.resolveHubIntent()` first-match behavior with an explicit
target package/component. Provide constants for the official phone and glasses
hub packages and an injectable target for tests/private forks. Verify the
resolved service belongs to the expected package and exposes the expected hub
action before binding.

Keep an explicit low-level constructor for internal clients. The public phone
plugin path defaults to the official phone hub package. Existing phone/glasses
probes must pass their target explicitly.

Tests must cover a malicious earlier query result, missing expected package,
wrong service action, and successful explicit resolution.

**Verify**: `.\gradlew.bat :bus-client:testDebugUnitTest :phone-client-probe:assembleDebug :glasses-client-probe:assembleDebug` -> exit 0.

### Step 2: Implement a cold-start-safe `NexusPluginService`

Create an abstract Android service that reads its descriptor using Plan 002
constants, creates its client in `onCreate`/`onBind`, registers with
`registerPlugin(packageName, pluginId, callback)`, and closes it in `onDestroy`.
Do not use a static factory or require an Activity to run first.

Expose protected callbacks:

- `onNexusOpen()`
- `onNexusClose()`
- `onNexusInput(event)`
- `onNexusLinkState(state)`
- `onNexusRegistrationState(result)`

Lifecycle callbacks are serialized on one executor/main dispatcher documented
by the SDK. Duplicate open/close messages with the same event ID are idempotent.
`BACK` is delivered exactly once through the existing paired-key/dedupe path;
the SDK does not reinterpret swipes.

**Verify**: unit tests with an injected fake transport cover process cold start,
approved/pending/denied registration, duplicate lifecycle events, close cleanup,
and callback ordering.

### Step 3: Add typed surface and capability helpers

Add immutable SDK models/builders for the existing `card` and `timed-lines`
payloads. Validate required fields and reasonable local payload limits before
sending. Provide a `NexusSurfaceSession` with a plugin-local ID and methods:

- `showCard`, `updateCard`
- `showTimedLines`, `updateTimedLinesAnchor`
- `hide`

The hub injects the verified owner and global ordering/session metadata. The SDK
must not expose a trusted `pluginId`, global sequence, or arbitrary system path
field in the high-level surface API.

Expose HTTP/audio helpers only when the corresponding capability was approved.
Because Plan 002 keeps third-party microphone disabled until a HUD indicator
exists, the SDK must return a stable `CAPABILITY_NOT_AVAILABLE` result instead of
falling back to the phone microphone.

**Verify**: tests cover valid/invalid card and timed-lines payloads, anchor-only
update, local surface IDs, capability denial, and unknown response fields.

### Step 4: Add the external plugin controller and unified catalog

Create a `PluginCatalog` whose entries come from:

1. current built-in plugins (temporary compatibility);
2. valid, approved external descriptors from Plan 002.

One canonical catalog drives phone UI, glasses launcher sync, settings intent,
enabled state, and open routing. Duplicate IDs are invalid; do not let insertion
order pick a winner.

Create `ExternalPluginController` to:

- bind/wake the descriptor's exact service component;
- wait for approved `registerPlugin` registration using the existing finite
  supervisor queue rules;
- deliver versioned open/close/input lifecycle events to that principal only;
- route input using the hub-injected owner ID, never only `surfaceId` text;
- hide/close the active external surface when the plugin is revoked, disabled,
  uninstalled, dies, or fails to register within the timeout;
- keep plugin code outside the hub process.

Update `PhonePluginRegistry` so built-in and external plugins share catalog
presentation but retain separate execution adapters. Do not delete built-in
support in this plan.

**Verify**: phone-hub tests cover catalog merge, duplicate ID, external open,
cold wake, registration timeout, input ownership, revocation, binder death, and
fallback to a built-in plugin.

### Step 5: Render installed plugins dynamically on the phone and glasses

Replace hardcoded `2 Active`, Lyrics, and Transit rows in `MainActivity` with
catalog entries. Preserve the existing visual language via `NexusUi` and
`BusTheme`. States must include pending approval, enabled, disabled, invalid,
and missing capability. An empty hub displays an intentional empty state and a
Store entry.

Update `StoreActivity` only enough to derive `installed/open/settings` state
from the catalog and launch a descriptor's verified settings component. Keep
remote catalogue/download actions as `Coming soon`; do not invent a store API.

Launcher sync sends only enabled, approved, launchable catalog entries. Preserve
text-first ordering and the current HUD scroll/focus behavior.

**Verify**: UI/catalog unit tests where possible, phone build, and a manual test
with zero external entries, one pending entry, and one approved sample.

### Step 6: Create a standalone Hello plugin sample

Add `:plugin-sample` as an ordinary phone application. It must:

- declare the plugin action and all required descriptor metadata;
- request only the `surfaces` capability;
- subclass `NexusPluginService` without static initialization;
- show one small `card` surface on open;
- handle forward/backward/tap/back using the SDK callback without touch UI;
- include a minimal phone Activity explaining approval and linking to Nexus
  plugin access settings if available;
- contain no CXR, Bluetooth, location, notification, camera, microphone, or
  network dependency.

Use it as the canonical third-party example. Its manifest and service must be
copyable without depending on phone-hub implementation classes.

**Verify**: `.\gradlew.bat :plugin-sample:testDebugUnitTest :plugin-sample:assembleDebug` -> exit 0.

### Step 7: Configure local Maven/JitPack-compatible publication

Apply `maven-publish` to `:shared` and `:bus-client`. Publish release AARs and
source artifacts. Use:

- group: `com.github.Anezium.Rokid-Nexus`
- artifacts: `shared` and `bus-client`
- version from `-PversionName`, defaulting to an explicit snapshot value only
  for local builds.

Make the public bus-client dependency on shared transitively resolvable (use the
appropriate Gradle `api`/POM relationship). Do not bundle phone hub, CxrGlobal,
Rokid SDK, or plugin implementations into the SDK artifact.

Make the sibling `CxrGlobal` composite include conditional on its directory
existing. Tasks that build the phone hub may still fail cleanly without that
private/local dependency, but SDK publication and sample consumption must not.

Allow the sample build to switch between `project(":bus-client")` and the Maven
local coordinate via `-PusePublishedSdk=true`. Add `jitpack.yml` only to define
the correct JDK/build publication command; do not trigger JitPack.

**Verify**:

```powershell
.\gradlew.bat :shared:publishToMavenLocal :bus-client:publishToMavenLocal -PversionName=0.1.0-SNAPSHOT
.\gradlew.bat :plugin-sample:assembleDebug -PusePublishedSdk=true -PversionName=0.1.0-SNAPSHOT
```

Expected: both commands exit 0 from a checkout where `../CxrGlobal` is renamed
or unavailable. The consumer resolves both AARs from Maven local.

### Step 8: Write public developer documentation and CI

Create a root README with product/module map, prerequisites, build/test commands,
normal-user vs developer-mode distinction, and links to vision/spec/SDK docs.
Create `docs/PLUGIN_SDK.md` with one complete Hello-plugin setup: Gradle
dependency, manifest metadata, service subclass, surface call, approval flow,
debugging, compatibility, redaction rules, and no-ADB limitations.

Document artifact coordinates without claiming a public release exists. State
clearly that a repository license must be chosen before public distribution;
do not add Apache-2.0 or MIT without maintainer confirmation.

Add CI that runs SDK/shared tests, lint for those modules, publication to Maven
local, and the published-coordinate sample build. It must work without sibling
`CxrGlobal` and must not upload APKs or secrets.

**Verify**: run the exact CI commands locally; all exit 0. Confirm no workflow
step publishes externally or prints environment secrets.

### Step 9: Validate an external APK end to end

On a phone/glasses pair using environment-provided ADB targets:

1. Install/update hubs and install the sample APK.
2. Confirm it appears pending, not automatically approved.
3. Approve `surfaces` only.
4. Force-stop the sample process.
5. Open it from the glasses launcher; confirm bind-wake, registration, and card.
6. Confirm one paired swipe advances exactly once, tap works, and BACK hides the
   card without reaching the app beneath.
7. Revoke the plugin and confirm it disappears and cannot wake/send.
8. Confirm CXR-L/SPP remain connected and built-in Lyrics/Transit still work.

Record redacted PASS/FAIL evidence in `TESTPLAN.md`; never record device identity,
certificate digest, or payload/user text.

**Verify**:

```powershell
.\gradlew.bat test lintDebug assembleDebug
git diff --check
git status --short
```

Expected: exit 0 and only scoped files plus plan status changed.

## Test plan

- Explicit hub resolution tests, including a malicious first query result.
- Plugin service cold-start, registration state, lifecycle idempotency, and
  callback serialization tests.
- Surface builder validation and unknown-field compatibility tests.
- Catalog/controller tests: built-in + external, duplicate ID, pending, disabled,
  timeout, binder death, uninstall, revoke, input ownership.
- Maven-local published consumer compile gate.
- Hardware sample test from Step 9.
- Existing bus frame, hub, touchpad, Lyrics, and Transit tests remain green.

## Done criteria

- [ ] External apps use `NexusPluginService` without a static factory.
- [ ] BusClient never binds an arbitrary first matching hub service.
- [ ] One canonical catalog drives phone UI and glasses launcher.
- [ ] Approved external plugins cold-start, open, receive input, close, and revoke correctly.
- [ ] SDK owns typed `card`/`timed-lines` helpers; hub owns trusted identity/sequence.
- [ ] Sample requests only `surfaces` and has no Rokid/CXR/BT/GMS dependency.
- [ ] `shared` and `bus-client` publish to Maven local with resolvable transitive dependencies.
- [ ] Published-coordinate sample builds without `../CxrGlobal`.
- [ ] README, SDK quickstart, protocol link, JitPack config, and non-publishing CI exist.
- [ ] No license was added without explicit maintainer approval.
- [ ] `.\gradlew.bat test lintDebug assembleDebug` exits 0.
- [ ] External sample hardware gate passes with redacted evidence.
- [ ] No out-of-scope files changed except `plans/README.md` status.

## STOP conditions

Stop and report if:

- Plans 001 or 002 are incomplete, or their models/constants have drifted.
- Execution starts from a dirty worktree.
- The SDK would need to trust client-supplied UID, certificate, owner ID, global
  sequence, or arbitrary receive prefixes.
- A lifecycle design requires loading external classes into the hub process.
- Publishing `:bus-client` cannot resolve `:shared` transitively without changing
  consumer coordinates; report the exact generated POM instead of hiding it.
- The sample cannot cold-start without a static factory.
- JitPack/CI requires checking in credentials, a private repository URL, a local
  APK, or a sibling source checkout.
- A public release or license must be chosen. The maintainer must authorize the
  license and release separately.
- Hardware input behavior differs from the current one-axis/tap/back model.
- Any verification fails twice after a scoped correction.

## Maintenance notes

- Plan 004 must be the first production proof of this SDK; avoid special-casing
  Transit in the hub.
- Built-in adapters remain temporary until Transit and Lyrics migration plans
  finish. Do not let new plugins use the in-process interface.
- A future Maven Central move should preserve package names and public API while
  changing coordinates deliberately.
- Public API review should focus on lifecycle ownership, main/background thread
  guarantees, cancellation, surface ID namespace, and forward compatibility.
- Apache-2.0 is recommended for the SDK, but the repository owner must explicitly
  choose it before a `LICENSE` file or public release is created.
