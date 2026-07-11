# Plan 002: Authorize plugin principals and enforce capabilities at the bus boundary

> **Executor instructions**: Follow this plan step by step. Run every
> verification command and confirm the expected result before moving on. Stop
> on any condition listed under "STOP conditions"; do not invent a weaker
> security model. Update `plans/README.md` when complete unless a reviewer owns
> the index.
>
> **Drift check (run first)**:
>
> ```powershell
> git status --short
> git diff --stat da068ad..HEAD -- shared bus-client phone-hub glasses-hub phone-client-probe glasses-client-probe BUSSPEC.md VISION.md
> git diff --stat -- shared bus-client phone-hub glasses-hub phone-client-probe glasses-client-probe BUSSPEC.md VISION.md
> ```
>
> This plan was authored against commit `da068ad` plus uncommitted Lens changes.
> Execute only after Plan 001 is done in a clean branch/worktree. Compare every
> current-state excerpt with live code; stop if the security boundary has drifted.

## Status

- **Status**: DONE (software gates complete 2026-07-10; owner on-device checks pending)
- **Priority**: P1
- **Effort**: L
- **Risk**: HIGH
- **Depends on**: `plans/001-safety-and-verification.md`
- **Category**: security, architecture, protocol, tests
- **Planned at**: commit `da068ad`, 2026-07-09, plus the uncommitted Lens snapshot described above

## Why this matters

Nexus is intended to accept arbitrary phone APKs after explicit user approval,
not only apps signed by the hub author. Today the exported Binder accepts a
client-supplied ID and arbitrary path prefixes, then trusts all subsequent
messages from that UID. A local app can subscribe broadly, consume traffic
before it reaches the glasses, request hub-owned resources, or impersonate
another surface. This plan introduces an Android package/UID/signing-certificate
principal, a fail-closed consent store, and capability/path enforcement while
preserving debug compatibility for the hardware probes.

## Product decisions that this plan must preserve

These are settled requirements, not implementation suggestions:

- The hub is a neutral core. Lyrics, Transit, Lens, and future apps are optional.
- Normal users get a simple approval screen; power users can enable developer
  details and approve side-loaded plugins.
- Do **not** use a custom signature permission. That would prevent third-party
  plugins and contradict the open-platform model in `VISION.md`.
- User grants are granular: `surfaces`, `microphone`, and `http_proxy`.
- A plugin identity comes from Android (package, UID, signing certificate) and
  verified manifest metadata, never from an untrusted JSON payload.
- One Android package hosts one Nexus plugin principal in protocol v3. Supporting
  multiple principals in a shared UID/package is deferred until there is a real
  use case.

## Current state

Relevant files:

- `bus-client/src/main/aidl/.../IBusService.aidl` is the append-only Binder ABI.
- `phone-hub/.../BusHubService.kt` registers callers and routes local/remote data.
- `glasses-hub/.../GlassesHub.kt` implements the symmetric glasses Binder.
- `phone-hub/.../PhoneClientSupervisor.kt` discovers wakeable services from
  untrusted manifest metadata.
- `phone-hub/src/main/AndroidManifest.xml` and
  `glasses-hub/src/main/AndroidManifest.xml` export their hub services.
- `shared/.../BusConstants.kt` and `BUSSPEC.md` define the stable protocol names.

Current Binder registration (`IBusService.aidl:5-11`) lets the caller choose all
identity/routing fields:

```aidl
interface IBusService {
    int apiVersion();
    void register(String clientId, in String[] pathPrefixes, IBusCallback cb);
    void unregister(in IBusCallback cb);
    oneway void send(String path, String id, in byte[] payload);
    int linkState();
    oneway void sendBinary(String path, String id, in byte[] meta, in byte[] data);
}
```

The phone hub records `Binder.getCallingUid()` but does not resolve or authorize
the package (`BusHubService.kt:104-120`):

```kotlin
val registration = Registration(
    clientId = clientId,
    prefixes = pathPrefixes.filter { it.isNotBlank() },
    uid = Binder.getCallingUid(),
    callbackBinder = callbackBinder,
    callback = cb,
    deathRecipient = deathRecipient,
)
registrations += registration
```

Delivery uses lexical prefix matching (`BusHubService.kt:318-337`), so `/foo`
also matches `/foobar`, and the first local delivery prevents remote routing:

```kotlin
if (registration.prefixes.any { envelope.path.startsWith(it) }) {
    // callback delivery
    delivered = true
}
```

Hub-owned paths are handled before any authorization check
(`BusHubService.kt:268-305`), including HTTP and audio lease requests.

Wake discovery (`PhoneClientSupervisor.kt:153-177`) trusts the first service
whose metadata prefix matches and does not check approval, certificate, or
namespace ownership.

The glasses hub repeats the same broad registration/prefix pattern. Full public
glasses-companion approval is not yet designed; release builds must therefore
reject unknown glasses-side legacy clients, while debug builds retain the
existing Lens/probe workflow until the optional companion model is planned.

## Target protocol and identity model

Add these stable constants under the existing `com.anezium.rokidbus` namespace:

- action: `com.anezium.rokidbus.action.PLUGIN`
- metadata keys for plugin ID, display name, plugin API version, requested
  capabilities, receive prefixes, and optional settings activity
- protocol/API version: 3

Plugin IDs must match `[a-z][a-z0-9._-]{2,63}`. Capability wire values are
exactly `surfaces`, `microphone`, and `http_proxy`. Unknown capabilities make
the descriptor invalid; they are not silently ignored during registration.

Append this method at the **end** of `IBusService.aidl` so existing transaction
codes stay stable:

```aidl
int registerPlugin(String packageName, String pluginId, IBusCallback cb);
```

Use stable result constants exposed by `:bus-client`, for example
`APPROVED`, `PENDING_USER_APPROVAL`, `DENIED`, `INVALID_DESCRIPTOR`,
`IDENTITY_MISMATCH`, and `UNSUPPORTED_API`. Do not send exception strings or
certificate data over AIDL as the result.

`registerPlugin` must:

1. capture `Binder.getCallingUid()` before clearing any Binder identity;
2. verify `packageName` belongs to that UID;
3. query exactly one exported service in that package for `ACTION_PLUGIN`;
4. parse the descriptor from that service's manifest metadata;
5. verify the supplied plugin ID equals the descriptor ID;
6. compute the current signing-certificate SHA-256 digest using modern and
   legacy PackageManager paths as appropriate;
7. look up a grant keyed by package + plugin ID + certificate digest;
8. register only manifest-declared receive prefixes and only when the required
   grants are approved.

Never log the full certificate digest in normal logs. Developer UI may display
it deliberately, but normal logs use package/plugin ID and a redacted status.

## Commands you will need

| Purpose | Command | Expected on success |
|---|---|---|
| Precondition | `.\gradlew.bat test lintDebug` | exit 0 from Plan 001 baseline |
| Shared policy tests | `.\gradlew.bat :shared:testDebugUnitTest` | exit 0 |
| Phone hub tests | `.\gradlew.bat :phone-hub:testDebugUnitTest` | exit 0 |
| AIDL/client compile | `.\gradlew.bat :bus-client:assembleDebug` | exit 0 |
| Hub builds | `.\gradlew.bat :phone-hub:assembleDebug :glasses-hub:assembleDebug` | exit 0 |
| Full regression | `.\gradlew.bat test lintDebug assembleDebug` | exit 0 |
| Patch hygiene | `git diff --check` | exit 0 |

## Suggested executor toolkit

- Use `rokid-glasses-dev` if available for CXR and public-plugin constraints.
- Treat all manifest metadata and plugin payloads as untrusted input.
- Do not add signature-only permissions, GMS, Firebase, or a second CXR session.

## Scope

**In scope**:

- `shared/src/main/java/com/anezium/rokidbus/shared/BusConstants.kt`
- `shared/src/main/java/com/anezium/rokidbus/shared/plugin/PluginCapability.kt` (create)
- `shared/src/main/java/com/anezium/rokidbus/shared/plugin/PluginDescriptor.kt` (create)
- `shared/src/main/java/com/anezium/rokidbus/shared/plugin/PathRules.kt` (create)
- corresponding pure JVM tests under `shared/src/test/`
- `bus-client/src/main/aidl/com/anezium/rokidbus/client/IBusService.aidl`
- `bus-client/src/main/java/com/anezium/rokidbus/client/PluginRegistrationResult.kt` (create)
- `phone-hub/src/main/java/com/anezium/rokidbus/phone/PhonePluginDiscovery.kt` (create)
- `phone-hub/src/main/java/com/anezium/rokidbus/phone/PluginGrantStore.kt` (create)
- `phone-hub/src/main/java/com/anezium/rokidbus/phone/PluginRoutePolicy.kt` (create)
- `phone-hub/src/main/java/com/anezium/rokidbus/phone/PluginPermissionsActivity.kt` (create)
- `phone-hub/src/main/java/com/anezium/rokidbus/phone/BusHubService.kt`
- `phone-hub/src/main/java/com/anezium/rokidbus/phone/PhoneClientSupervisor.kt`
- `phone-hub/src/main/java/com/anezium/rokidbus/phone/SettingsActivity.kt`
- `phone-hub/src/main/AndroidManifest.xml`
- phone hub tests under `phone-hub/src/test/`
- `glasses-hub/src/main/java/com/anezium/rokidbus/glasses/GlassesHub.kt`
- `glasses-hub/src/main/java/com/anezium/rokidbus/glasses/GlassesClientSupervisor.kt`
- glasses hub tests under `glasses-hub/src/test/` when pure policy can be shared
- probe manifests/services only as required to keep **debug** hardware tests working
- `BUSSPEC.md`
- `VISION.md` only if wording must be clarified; do not rewrite product direction

**Out of scope**:

- Public plugin base classes, lifecycle callbacks, surface helpers, sample plugin,
  Maven/JitPack publication, or README tutorial; Plan 003 owns them.
- Migrating Transit, Lyrics, or Lens to separate APKs.
- Display stack, toast/actionable arbitration, rate limiting, or launcher ordering.
- Granting microphone to third-party release plugins before the mandatory HUD
  microphone indicator exists. Plan 002 may model/request the capability, but
  release approval must remain disabled with a clear `not available yet` state.
- General public approval of glasses-side companion APKs. Release glasses hub
  should reject unknown legacy clients; debug compatibility may remain until a
  later companion-install/grant plan.
- Device pairing, Hi Rokid auth changes, no-ADB installation, CXR/SPP changes,
  or transport encryption.
- A signature permission or hardcoded allowlist of third-party package names.

## Git workflow

- Branch: `advisor/002-plugin-identity-capabilities`
- Use logical commits such as `Define Nexus plugin principals`, `Enforce plugin
  routes and capabilities`, and `Add plugin consent settings`.
- Preserve append-only AIDL transaction order.
- Do not push or publish without operator instruction.

## Steps

### Step 1: Add pure descriptor, capability, and path rules

Implement immutable shared models and parsers for:

- plugin ID validation;
- capability parsing and canonical serialization;
- normalized absolute bus paths;
- segment-aware prefix matching: a prefix matches when `path == prefix` or
  `path.startsWith("$prefix/")`, never by raw lexical prefix;
- reserved paths owned by the hub (`/launcher`, `/surface/input`, `/system`,
  `/security`, `/error`);
- capability gates: `/surface/show|update|hide` -> `surfaces`,
  `/audio/lease/acquire|release` -> `microphone`, and `/http/request` ->
  `http_proxy`;
- plugin-private namespaces under `/plugin/<pluginId>`.

The parser must reject blank/relative paths, root `/`, duplicate conflicting
metadata, malformed IDs, unknown capabilities, and prefixes outside the
plugin's namespace except SDK-declared lifecycle/reply paths.

**Verify**: `.\gradlew.bat :shared:testDebugUnitTest` -> exit 0 with tests for
segment boundaries, reserved routes, ID validation, and every capability.

### Step 2: Discover descriptors and bind them to Android identity

Create `PhonePluginDiscovery` using `PackageManager` with metadata and signing
certificate flags. Query `ACTION_PLUGIN`, parse each service descriptor, and
produce one of: valid candidate, invalid candidate with stable reason, or
duplicate-ID conflict. Sort deterministically by display name then package.

Bind a valid descriptor to package name, service component, UID, signing digest,
plugin ID, API version, requested capabilities, and receive prefixes. Never
trust a package or service name read from plugin JSON.

Reject shared-UID/multiple-plugin ambiguity for protocol v3. If more than one
valid descriptor maps to one UID, mark all involved candidates unsupported and
surface that reason in developer details.

**Verify**: unit-test descriptor validation with injected fake package records;
`.\gradlew.bat :phone-hub:testDebugUnitTest` -> exit 0.

### Step 3: Persist fail-closed grants

Create `PluginGrantStore`. A grant key contains package, plugin ID, and signing
digest. Store a separately approved subset of requested capabilities plus
enabled/disabled state. Certificate change, package reinstall with different
identity, descriptor ID change, or newly requested capability invalidates only
the affected approval and returns the plugin to pending.

This store contains authorization state, not secrets, so app-private
`SharedPreferences` is sufficient. Never persist an approval keyed only by
display name or plugin ID.

Expose operations to approve a capability subset, deny, revoke, and reconcile
installed candidates. Revocation must synchronously remove live registrations,
release any resource held by that principal, and prevent future wake binds.

**Verify**: tests cover fresh pending, partial grant, certificate change,
capability expansion, deny, revoke, uninstall reconciliation, and deterministic
serialization.

### Step 4: Append AIDL v3 registration without moving existing methods

Append `registerPlugin` to `IBusService.aidl`; do not reorder or modify the six
existing methods. Bump `BusConstants.API_VERSION` to 3 and document compatibility
in `BUSSPEC.md`.

Implement `registerPlugin` in the phone hub using the target model above. The
legacy `register(clientId, prefixes, callback)` path must be:

- allowed for same-UID hub internals;
- allowed in debug builds for existing probes with a conspicuous redacted log;
- rejected for unknown external callers in release builds.

For the glasses hub, apply the same release/debug legacy rule. Do not pretend
that phone approval already provisions glasses companion grants.

If `linkToDeath` fails during registration, do not add the registration. Return
or log a stable registration failure without Binder/exception internals.

**Verify**:

```powershell
.\gradlew.bat :bus-client:assembleDebug :phone-hub:assembleDebug :glasses-hub:assembleDebug
```

Expected: exit 0; generated AIDL compiles on both hubs.

### Step 5: Enforce principals on every local send and subscription

Extend registration records with the verified principal and granted
capabilities. For every Binder `send`/`sendBinary`, resolve the calling UID to
one live principal before handling any hub-owned path. Reject unregistered,
ambiguous, denied, and pending callers.

Apply `PluginRoutePolicy` before `handleHubPath`, local delivery, supervisor wake,
or remote forwarding. Requirements:

- plugin-private routes stay inside `/plugin/<pluginId>`;
- surface sends require `surfaces`, and the hub overwrites/injects the verified
  owner plugin ID instead of trusting payload identity;
- audio acquisition/release requires `microphone`; third-party release grants
  remain unavailable until the indicator plan;
- HTTP requests require `http_proxy`;
- plugins cannot send launcher lists/open commands, surface input, security, or
  other system-control paths;
- receive prefixes come only from the verified descriptor and use segment-aware
  matching;
- one plugin cannot consume a message owned by another plugin merely by
  registering a broad prefix.

Keep internal hub-to-hub traffic and validated CXR/SPP routing behavior intact.
Return stable `/error` codes for rejected operations; never include certificate,
UID, full path payload, or user data.

**Verify**: policy tests cover allowed and denied sends for every capability,
cross-plugin namespace access, lexical-prefix confusion, pending/revoked callers,
and internal hub traffic.

### Step 6: Make wake-on-message authorization-aware

Update both supervisors to use the shared segment-aware path matcher. The phone
supervisor must only bind an approved, enabled descriptor whose component,
package/UID, certificate, and prefixes still match discovery. Never use
`firstOrNull()` over unvalidated candidates; resolve a single owner or return a
stable conflict/error.

Keep the existing queue limits and idle reaper from Plan 001. Revoked/denied
plugins must not be woken, and their queued JSON must be removed.

**Verify**: tests cover approved wake, denied wake, duplicate owners, revoked
queue purge, boundary-safe prefixes, and uninstall between enqueue and bind.

### Step 7: Add normal and developer consent UI

Add a `Plugin access` entry to Settings and implement
`PluginPermissionsActivity` using existing `NexusUi`/`BusTheme` components.

Normal view shows:

- plugin display name;
- installed/pending/approved/denied/invalid state;
- plain-language descriptions for requested capabilities;
- individual toggles for capabilities that are currently available;
- approve, deny, and revoke actions.

Developer details are behind an explicit preference toggle and additionally
show package, service component, plugin ID, API version, signing digest, and
declared receive prefixes. Side-loaded plugins are still approvable; the UI must
label them unverified rather than silently blocking them.

The microphone toggle is visible but disabled with `Requires Nexus microphone
indicator support` until the display-arbitration plan lands. Do not silently
grant it.

**Verify**: build the phone hub and use a fake/debug descriptor to confirm
pending -> partial approval -> active -> revoked state transitions. No external
messages or GitHub issues are created.

### Step 8: Run software and device regression gates

Run the complete Gradle gate. Then validate on hardware in a debug build:

1. Existing phone and glasses probes still register through the explicit debug
   compatibility path.
2. An unapproved test plugin cannot send a surface or request HTTP/audio.
3. Approving `surfaces` permits a test surface but still denies HTTP/audio.
4. Revocation closes/unregisters the plugin and prevents wake-on-message.
5. CXR-L and SPP remain connected and existing Lyrics/Transit built-ins still
   work; they are not externalized until later plans.

Use environment-provided phone/glasses ADB serials. Logs must be redacted.

**Verify**:

```powershell
.\gradlew.bat test lintDebug assembleDebug
git diff --check
git status --short
```

Expected: all commands exit 0 and only scoped files plus the plan index changed.

## Test plan

- `PathRulesTest`: exact match, child match, lexical near-miss, root rejection,
  normalization, reserved route behavior.
- `PluginCapabilityTest`: canonical values, unknown rejection, each route gate.
- `PhonePluginDiscoveryTest`: UID/package verification, signing digest binding,
  duplicate ID, multiple plugin/UID ambiguity, malformed metadata.
- `PluginGrantStoreTest`: initial pending, partial grant, certificate rotation,
  capability expansion, deny, revoke, uninstall.
- `PluginRoutePolicyTest`: unauthorized send, cross-plugin receive, surface owner
  injection, audio/HTTP gates, system-route denial, internal route pass.
- Supervisor tests: only the approved exact owner is selected and revoked queues
  are purged.
- Hardware smoke follows Step 8 and records only PASS/FAIL and redacted counts in
  `TESTPLAN.md`.

## Done criteria

- [x] AIDL existing methods remain in their original order and `registerPlugin` is appended.
- [x] API version is 3 and BUSSPEC documents v2 compatibility.
- [x] Plugin identity is verified from UID, package, certificate, and manifest metadata.
- [x] Grants are keyed by package + plugin ID + signing digest and are granular.
- [x] Unknown/revoked callers cannot send, subscribe, wake, use surfaces, HTTP, or audio.
- [x] Raw lexical prefix matching is absent from hub/supervisor authorization paths.
- [x] External release callers cannot use legacy `register`.
- [x] Debug probe compatibility is isolated to debuggable builds without weakening release policy.
- [x] Normal and developer consent views are implemented and build successfully.
- [x] Third-party microphone approval remains disabled pending HUD indication.
- [x] `.\gradlew.bat test lintDebug assembleDebug` exits 0.
- [ ] Hardware regression steps: pending owner on-device verification; device interaction was prohibited for this execution.
- [x] Only in-scope files, `TESTPLAN.md`, and plan status files are modified.

## Execution record (2026-07-10)

- Drift check was clean. The scoped `da068ad..HEAD` diff contains the documented
  Lens/Media baseline snapshot and Plan 001 hardening; the uncommitted scoped diff
  was empty.
- The registration, lexical-prefix routing, and first-match supervisor excerpts
  were stale only in line placement because of the baseline snapshot and Plan 001.
  Their described v2 end-state still existed and was verified directly before the
  v3 AIDL append, segment-aware policy, and authorized wake selection were applied.
- Verified successfully: Plan 001 precondition `test lintDebug`; shared policy
  tests; phone discovery/grant/route/wake tests; AIDL/client compile; phone and
  glasses hub builds; full `test lintDebug assembleDebug`; and `git diff --check`.
- Pending owner on-device verification: debug phone/glasses probe registration,
  unapproved/partially approved/revoked plugin behavior, consent UI transitions,
  and CXR-L/SPP continuity. No device commands were run.

## STOP conditions

Stop and report if:

- Plan 001 is not complete or the lint/test baseline is not green.
- Execution starts from a dirty worktree or current Binder/routing excerpts no
  longer match this plan.
- Android reports multiple packages for one calling UID in a way that cannot be
  resolved against the declared service without weakening verification.
- A currently required release client depends on legacy unrestricted prefixes.
- A proposed fix requires a signature permission, a hardcoded third-party
  allowlist, trusting client-supplied certificate/UID data, or silently granting
  a new capability.
- Correct release enforcement would break the in-progress Lens companion before
  a debug-only compatibility path can isolate it. Do not make release permissive;
  report the migration dependency instead.
- CXR/SPP hub-to-hub messages become subject to plugin consent accidentally.
- Any verification fails twice after a scoped correction.

## Maintenance notes

- Plan 003 must consume these descriptor/result constants; it must not create a
  second identity or consent format.
- Plan 004 should request only `surfaces`; Transit uses its own phone network and
  Android location permissions, not the Nexus HTTP proxy.
- The display-arbitration plan must enable third-party microphone grants only
  after a hub-owned HUD indicator is impossible for plugins to suppress.
- Public glasses companion approval remains a separate design: phone approval
  must eventually provision package/certificate grants to the glasses hub over
  a reserved hub-to-hub path.
- Review AIDL changes, Binder identity capture, `clearCallingIdentity` placement,
  certificate invalidation, and revocation cleanup especially carefully.
