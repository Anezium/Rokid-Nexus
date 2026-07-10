# Plan 004: Ship Transit as the first independent Nexus plugin APK

> **Executor instructions**: Execute this plan only after Plans 001-003 are
> `DONE`. Transit is the proof that installing an ordinary phone APK, approving
> it, and opening it from the glasses works without linking plugin code into the
> hub. Run every verification gate and stop on the listed conditions rather
> than weakening Android location, plugin identity, or process isolation.
> Update `plans/README.md` when complete unless a reviewer owns the index.
>
> **Drift check (run first)**:
>
> ```powershell
> git status --short
> git diff --stat da068ad..HEAD -- plugin-transit phone-hub settings.gradle.kts TESTPLAN.md BUSSPEC.md
> git diff --stat -- plugin-transit phone-hub settings.gradle.kts TESTPLAN.md BUSSPEC.md
> ```
>
> This plan was authored at commit `da068ad` plus an uncommitted Lens snapshot.
> Its excerpts describe the pre-SDK built-in Transit implementation. After
> Plans 002-003 land, compare behavior and symbols, then refresh paths/excerpts
> if needed. Never execute from a dirty worktree.

## Status

- **Priority**: P1
- **Effort**: L
- **Risk**: HIGH
- **Depends on**: `plans/003-external-plugin-sdk.md`
- **Category**: migration, architecture, product, tests
- **Planned at**: commit `da068ad`, 2026-07-09, plus the uncommitted Lens snapshot described above

## Why this matters

Transit currently proves the HUD and data flow, but it is an Android library
compiled into the hub. The phone hub owns Transit location permissions, settings
Activity, foreground-service location type, lifecycle, and launcher row. That
is the opposite of the intended empty core. This plan moves Transit into its own
phone APK/process using the public SDK from Plan 003, preserves the validated
glasses UX, and proves dynamic discovery, approval, wake, input, and uninstall.

## Product decisions that this plan must preserve

- The hub contains no mandatory Transit code and does not auto-install Transit.
- Transit is optional and can be installed/uninstalled independently.
- The same APK works for a normal RokidBrew user and a side-loading developer.
- Transit requests only Nexus `surfaces`; it uses its own phone INTERNET and
  Android location permissions, not the Nexus HTTP proxy.
- The glasses receive declarative `card` surfaces only. No Transit glasses APK.
- Existing chooser, Near Me, Favorites, pagination, one-axis swipe, tap, BACK,
  and refresh-stop behavior remain unchanged.
- Do not silently request background location. If plugin-owned foreground
  location cannot work from the hub-open flow on the target phone, stop and
  present the explicit architecture decision described below.

## Current state

Relevant files:

- `plugin-transit/build.gradle.kts` applies `com.android.library`; it cannot be
  installed independently.
- `plugin-transit/src/main/AndroidManifest.xml` has an empty application.
- `plugin-transit/.../TransitPlugin.kt` implements the in-process
  `NexusPlugin` interface and receives `NexusPluginHost` from the phone hub.
- `plugin-transit/.../TransitRepository.kt` accesses Transitous directly over
  phone networking.
- `plugin-transit/.../TransitLocationProvider.kt` checks Android location
  permission on the context supplied by the hub.
- `phone-hub/.../TransitSettingsActivity.kt` owns Transit search/favorites UI
  and imports plugin implementation classes.
- `phone-hub/.../BusHubService.kt` constructs `TransitPlugin()` directly.
- `phone-hub/build.gradle.kts` links `implementation(project(":plugin-transit"))`.
- `phone-hub/src/main/AndroidManifest.xml` declares location permissions and a
  location foreground-service type for Transit.
- `phone-hub/.../ServiceInfoCompat.kt` conditionally adds the location FGS bit.

Current module shape (`plugin-transit/build.gradle.kts:1-25`):

```kotlin
plugins { id("com.android.library") }
// ...
dependencies {
    implementation(project(":shared"))
    implementation("androidx.core:core:1.13.1")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.2")
}
```

Current runtime entry (`TransitPlugin.kt:22-50`):

```kotlin
class TransitPlugin : NexusPlugin {
    override val id: String = SURFACE_ID
    override val displayName: String = "Transit"
    override val handlesBack: Boolean = true

    override fun onRegister(host: NexusPluginHost) {
        this.host = host
        repository = TransitRepository()
        locationProvider = TransitLocationProvider(host.context)
        favoritesStore = TransitFavoritesStore(host.context)
    }
}
```

Current surface behavior (`TransitPlugin.kt:433-443`) sends an existing v1
`card`; preserve its payload semantics through the typed SDK:

```kotlin
val payload = JSONObject()
    .put("surfaceId", SURFACE_ID)
    .put("kind", "card")
    .put("contentKey", contentKey)
    .put("title", card.title)
    .put("lines", lines)
    .put("footer", card.footer)
host.send(if (forceShow || lastSentKey == null) SURFACE_SHOW else SURFACE_UPDATE, payload)
```

Current refresh loop (`TransitPlugin.kt:321-349`) runs while the board is open
and cancels on close. That lifecycle is an acceptance requirement.

Current favorite stops are stored in the phone hub app's private
`transit_favorites` preferences because the in-process plugin receives the hub
context. An independent APK cannot read them. A verified one-release migration
is required; do not export those preferences generally because they contain
user-selected location data.

## Target APK shape

Keep the existing `:plugin-transit` module name and Kotlin package namespace to
minimize code churn, but change it to an Android application with its own
application ID, version, manifest, resources, permissions, settings Activity,
and `TransitPluginService : NexusPluginService`.

Manifest requirements:

- `INTERNET`
- coarse/fine location (requested at runtime by Transit UI)
- foreground service and foreground-service location
- notification permission where applicable to show the active Transit FGS
- plugin action/metadata from Plan 002
- requested Nexus capabilities: `surfaces` only
- wakeable exported plugin service owned by the SDK security model
- launcher/settings Activity for permissions and favorite management

Use the real existing Nexus launcher bitmap unchanged as the temporary family
icon if no approved Transit-specific source asset exists. Do not draw or
generate an approximate logo in this migration.

## Commands you will need

| Purpose | Command | Expected on success |
|---|---|---|
| Preconditions | `.\gradlew.bat test lintDebug assembleDebug` | exit 0 after Plan 003 |
| Transit tests | `.\gradlew.bat :plugin-transit:testDebugUnitTest` | exit 0 |
| Transit APK | `.\gradlew.bat :plugin-transit:assembleDebug` | exit 0 and APK exists |
| Hub build without Transit dependency | `.\gradlew.bat :phone-hub:assembleDebug` | exit 0 |
| Dependency check | `.\gradlew.bat :phone-hub:dependencies --configuration debugRuntimeClasspath` | output contains no `project :plugin-transit` |
| Full regression | `.\gradlew.bat test lintDebug assembleDebug` | exit 0 |
| Patch hygiene | `git diff --check` | exit 0 |

## Suggested executor toolkit

- Use `rokid-glasses-dev` for HUD input and device validation.
- Preserve the no-GMS glasses rule; all Transit dependencies stay on the phone.
- Validate foreground-location behavior on the real target phone rather than
  assuming generic Android behavior is sufficient.

## Scope

**In scope**:

- `plugin-transit/build.gradle.kts`
- all `plugin-transit/src/main/` Kotlin/manifest/resources needed for an APK
- existing Transit JVM tests and new lifecycle/migration tests
- `phone-hub/build.gradle.kts`
- `phone-hub/src/main/AndroidManifest.xml`
- `phone-hub/src/main/java/com/anezium/rokidbus/phone/BusHubService.kt`
- `phone-hub/src/main/java/com/anezium/rokidbus/phone/PhonePluginRegistry.kt`
- `phone-hub/src/main/java/com/anezium/rokidbus/phone/MainActivity.kt`
- `phone-hub/src/main/java/com/anezium/rokidbus/phone/StoreActivity.kt`
- `phone-hub/src/main/java/com/anezium/rokidbus/phone/TransitSettingsActivity.kt` (remove after migration)
- `phone-hub/src/main/java/com/anezium/rokidbus/phone/ServiceInfoCompat.kt`
- `phone-hub/src/main/java/com/anezium/rokidbus/phone/TransitLegacyStateExporter.kt` (temporary, create)
- tests for the temporary migration exporter
- `settings.gradle.kts` only if application/plugin configuration requires it
- `TESTPLAN.md`
- `BUSSPEC.md` only for external Transit lifecycle/migration documentation

**Out of scope**:

- Changing Transitous provider, parsing, departure grouping, card layout,
  refresh cadence, chooser behavior, or favorites product semantics except where
  process isolation requires lifecycle-safe cancellation.
- Externalizing Lyrics or Lens.
- Adding a Transit glasses APK, navigation surface kind, icons/glyph protocol,
  display arbitration, toast/actionable classes, or RokidBrew downloads.
- Moving Android location permission back into the hub without an explicit
  maintainer decision after the hardware STOP condition.
- Requesting `ACCESS_BACKGROUND_LOCATION` silently or keeping a permanent
  Transit foreground service when no Transit surface is active.
- Special-casing the Transit package in Plan 002 security policy.
- Changing CXR-L, CXR-S, SPP, Caps, or surface wire framing.

## Git workflow

- Branch: `advisor/004-external-transit`
- Suggested commits: `Package Transit as a Nexus plugin`, `Migrate Transit
  settings and favorites`, `Remove Transit from the phone hub`, `Validate
  external Transit on hardware`.
- Do not publish the APK, update RokidBrew, push, or open a PR unless instructed.

## Steps

### Step 1: Capture pre-migration behavior with tests

Keep all existing Transit tests green. Add lifecycle characterization around the
current runtime via an injectable/fake plugin host:

- open sends the chooser as `card`;
- forward/backward changes the chooser selection exactly once;
- tap enters the selected mode;
- BACK from a board returns to chooser;
- BACK from chooser hides;
- close cancels refresh and no further surface send occurs;
- refresh failure renders a bounded failure card without crashing;
- `handlesBack` behavior is retained through the SDK.

Extract only the minimal interface needed to test lifecycle without Android
Binder. Do not refactor repository/parsing code unrelated to migration.

**Verify**: `.\gradlew.bat :plugin-transit:testDebugUnitTest` -> exit 0 with the
new lifecycle characterization tests.

### Step 2: Convert `:plugin-transit` from library to application

Apply `com.android.application`, set the existing namespace/application ID,
target the current phone SDK, and add `:bus-client` as the SDK dependency from
Plan 003. Keep `:shared` only if public Transit types still require it directly;
do not depend on phone-hub.

Add the application manifest and resources described above. Copy an existing
real Nexus launcher bitmap unchanged if no approved Transit icon exists. Add a
small launcher/settings Activity; the plugin must also cold-start headlessly
through its exported `TransitPluginService`.

Declare Nexus metadata using the exact Plan 002 constants: stable ID `transit`,
display name, API version, `surfaces`, receive/lifecycle paths, settings Activity,
and launchable state.

**Verify**:

```powershell
.\gradlew.bat :plugin-transit:processDebugMainManifest :plugin-transit:assembleDebug
```

Expected: exit 0; merged manifest declares only intended permissions/capability;
the debug APK is produced.

### Step 3: Adapt the runtime to `NexusPluginService`

Create `TransitPluginService : NexusPluginService`. Adapt the current
`TransitPlugin` into a runtime/controller that receives the SDK surface session
and lifecycle callbacks instead of an in-process `NexusPluginHost`.

Requirements:

- `onNexusOpen` initializes chooser state and shows the chooser card;
- `onNexusInput` preserves current one-axis/tap/BACK behavior;
- `onNexusClose`, Binder death, revocation, and service destruction all cancel
  refresh, stop location foreground mode, and hide/close the surface idempotently;
- surface updates use typed SDK card helpers and plugin-local `transit` ID;
- no direct AIDL, CXR, Bluetooth, or raw global sequence code enters Transit;
- cold start works after `am force-stop` through hub bind/wake;
- pending/denied registration does no network/location work.

**Verify**: lifecycle tests use the SDK fake transport and pass; the APK builds.

### Step 4: Give Transit ownership of settings, network, and permissions

Move/reimplement `TransitSettingsActivity` inside the plugin package. Do not move
the hub's `NexusUi` implementation into the plugin. Use SDK `BusTheme` or a small
Transit-owned phone UI that preserves the existing visual language without
creating a hub dependency.

The Activity must:

- explain and request location permission in foreground;
- manage favorite-stop search/add/remove;
- show plugin approval/connection state;
- avoid logging query text, selected stop coordinates, or location;
- handle no location/network/model state explicitly.

Network calls continue directly from the phone plugin via its own `INTERNET`
permission. Do not request Nexus `http_proxy`.

For Near Me while the phone UI is backgrounded, start a Transit-owned foreground
service with location type and a low-importance notification only while the
surface/refresh loop is active. If permission is absent, show a card instructing
the user to open Transit on the phone; never launch a permission dialog from a
background service.

**Verify**: build/lint and a phone test where permission denied -> explanatory
state, permission granted -> current location, close -> FGS notification removed.

### Step 5: Migrate existing favorite state to the verified plugin once

Add a temporary `TransitLegacyStateExporter` in phone-hub for one compatibility
release. It reads only the known Transit favorites/mode preferences and can send
them only to the approved principal whose verified plugin ID is `transit`. The
exporter is invoked internally by `ExternalPluginController`; do not expose an
exported Activity/provider/broadcast or a general bus path.

Flow:

1. External Transit registers approved and reports no completed migration.
2. Hub serializes favorites and last mode into a versioned payload.
3. Controller delivers it directly to the verified Transit callback.
4. Transit validates/deduplicates, writes to its own preferences, and returns a
   count/checksum acknowledgement over its owned namespace.
5. Hub marks migration complete and removes legacy data only after valid ack.
6. Empty/no legacy data is also marked complete without sending location data.

Do not include current coordinates or recent boards. Do not log stop names,
coordinates, serialized payload, or digest. Test wrong principal, replay,
partial failure, corrupt entry, empty state, and successful acknowledgement.

**Verify**: phone-hub and Transit migration tests pass; repeating migration does
not duplicate favorites or resend acknowledged state.

### Step 6: Remove Transit implementation and permissions from the hub

After the external APK passes software tests:

- remove `implementation(project(":plugin-transit"))` from phone-hub;
- remove `TransitPlugin()` construction/import from `BusHubService`;
- remove the hub `TransitSettingsActivity` and manifest entry;
- remove hardcoded Transit home/store rows now supplied by Plan 003 catalog;
- remove hub coarse/fine location and location-FGS permissions;
- change hub service type to connected-device only;
- simplify `ServiceInfoCompat` to connected-device only or remove it if no longer
  useful;
- retain only the narrow, temporary legacy-state exporter with no compile-time
  dependency on Transit implementation classes.

Do not remove Lyrics or Lens dependencies in this plan.

**Verify**:

```powershell
.\gradlew.bat :phone-hub:dependencies --configuration debugRuntimeClasspath | Select-String 'project :plugin-transit'
rg -n 'TransitPlugin|TransitSettingsActivity|ACCESS_(COARSE|FINE)_LOCATION|FOREGROUND_SERVICE_LOCATION' phone-hub -S
.\gradlew.bat :phone-hub:assembleDebug
```

Expected: first two searches return no unintended matches (the temporary
migration exporter may contain only documented preference-schema names); hub
build exits 0.

### Step 7: Validate plugin-owned background location on the target phone

This is a hard architecture gate, not an optional smoke test:

1. Install the external Transit APK and hub debug APK.
2. Open Transit on phone once, grant location/notification, add a favorite.
3. Return HOME and force-stop Transit.
4. Open Transit from glasses while the phone Activity is not foreground.
5. Confirm hub bind-wakes it, the service enters location FGS legally, a live
   Near Me board arrives, and no `ForegroundServiceStartNotAllowedException` or
   location security error occurs.
6. BACK to chooser then close; confirm refresh traffic stops and the plugin FGS
   notification disappears.

If Step 5 fails because Android forbids this background FGS start, STOP. Report
device/OS state with identifiers redacted and present exactly these choices to
the maintainer; do not choose automatically:

- require the user to start Transit from its phone notification/Activity before
  Near Me sessions;
- add a narrowly scoped hub-owned location broker capability with lazy runtime
  permission;
- request background location in the Transit APK with explicit user education.

The recommended fallback is the first option for initial beta; never silently
choose background location or move broad permission into the hub.

### Step 8: Run the full external-plugin hardware acceptance cycle

With phone/glasses targets supplied via environment variables:

1. Hub installed with Transit absent -> launcher/home shows no Transit.
2. Install Transit -> it appears pending, not enabled.
3. Approve `surfaces` -> it appears on phone and glasses launcher.
4. Force-stop Transit -> open from glasses -> bind-wake succeeds.
5. Validate chooser, Near Me, Favorites, pagination, tap, paired swipe dedupe,
   board BACK, chooser BACK, and refresh stop.
6. Restart phone hub -> Transit surfaces are not rejected as stale.
7. Revoke Transit -> it closes/disappears and cannot wake/send.
8. Uninstall Transit -> hub and glasses remain stable and empty/other plugins work.
9. CXR-L and SPP remain connected throughout.

Record redacted results in `TESTPLAN.md`. Never include device serial, address,
certificate digest, selected stop/location, or full payload.

**Verify**:

```powershell
.\gradlew.bat test lintDebug assembleDebug
git diff --check
git status --short
```

Expected: exit 0 and only scoped files plus plan status changed.

## Test plan

- Preserve all existing Transit parsing/formatting/pager/selection tests.
- Add runtime lifecycle tests for open/input/BACK/close/refresh cancellation.
- Add registration-state tests: pending/denied performs no work; approved opens.
- Add plugin FGS controller tests: start only for active Near Me, stop on every
  terminal lifecycle, no background permission prompt.
- Add legacy favorites migration tests including wrong principal and replay.
- Add phone-hub catalog tests proving Transit is absent without APK and dynamic
  when installed/approved.
- Complete both hardware gates in Steps 7 and 8.

## Done criteria

- [ ] `:plugin-transit` produces an independent installable APK.
- [ ] Transit depends on `:bus-client` SDK and never on phone-hub implementation.
- [ ] Transit requests Nexus `surfaces` only.
- [ ] Transit owns its phone network/location permissions and settings UI.
- [ ] No Transit implementation dependency, Activity, location permission, or
  location FGS type remains in phone-hub.
- [ ] Existing favorites migrate only to the verified Transit principal and only once.
- [ ] Installing/approving the APK dynamically adds Transit to phone/glasses.
- [ ] Revoking/uninstalling dynamically removes it without destabilizing hubs.
- [ ] Force-stopped Transit bind-wakes from the glasses launcher.
- [ ] Chooser/boards/Favorites/input/BACK/refresh behavior remains validated.
- [ ] Plugin-owned background location hardware gate passes, or the plan is
  explicitly `BLOCKED` with the three choices reported.
- [ ] `.\gradlew.bat test lintDebug assembleDebug` exits 0.
- [ ] CXR-L/SPP hardware regression passes with redacted logs.
- [ ] No out-of-scope files changed except `plans/README.md` status.

## STOP conditions

Stop and report if:

- Plans 001-003 are not done or the SDK/identity contract differs materially.
- Execution starts from a dirty worktree.
- Existing favorite state cannot be migrated without exposing a general exported
  provider/path or trusting only a plugin ID string.
- Android blocks plugin-owned foreground location from the glasses-open flow.
  Use the explicit decision gate in Step 7.
- Migration appears to require silent background-location permission.
- Transit can only work by reintroducing a compile-time hub dependency or a
  hardcoded package exception in the security layer.
- Cold wake requires an Activity/static factory to run first.
- External Transit changes the glasses protocol, card content, input count, or
  refresh cadence outside migration necessity.
- No faithful existing Nexus icon can be identified and a new icon would need
  to be invented. Report the branding gap instead of drawing one.
- Any verification fails twice after a scoped correction.

## Maintenance notes

- Keep the legacy favorites exporter for exactly one documented compatibility
  window, then remove it in a dedicated cleanup after migration telemetry/manual
  evidence is sufficient.
- Transit is the reference architecture. Future Teleprompter, Home Assistant,
  and Scribe plugins should copy its service/manifest/approval pattern, not its
  domain code.
- Review background work carefully: no refresh coroutine or location FGS may
  survive `onNexusClose`, revoke, binder death, or service destruction.
- Externalizing Lyrics next will move notification-listener permission and UI
  out of the hub; do not solve that inside this plan.
