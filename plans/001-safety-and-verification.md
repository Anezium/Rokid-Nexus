# Plan 001: Establish a safe, bounded, green verification baseline

> **Executor instructions**: Follow this plan step by step. Run every
> verification command and confirm the expected result before moving to the
> next step. If anything in the "STOP conditions" section occurs, stop and
> report; do not improvise. When done, update this plan's status row in
> `plans/README.md` unless a reviewer told you they maintain the index.
>
> **Drift check (run first)**:
>
> ```powershell
> git status --short
> git diff --stat da068ad..HEAD -- bus-client phone-hub glasses-hub glasses-client-probe plugin-lyrics lens-glasses BUSSPEC.md TESTPLAN.md
> git diff --stat -- bus-client phone-hub glasses-hub glasses-client-probe plugin-lyrics lens-glasses BUSSPEC.md TESTPLAN.md
> ```
>
> This plan was written while the repository contained uncommitted Lens work.
> Do not execute it in a dirty worktree. Commit that work or move execution to
> an isolated worktree first. After that, compare the excerpts below with live
> code. If they differ materially, stop and refresh the plan.

## Status

- **Status**: DONE
- **Priority**: P1
- **Effort**: L
- **Risk**: MED
- **Depends on**: none
- **Category**: security, correctness, perf, tests, dx
- **Planned at**: commit `da068ad`, 2026-07-09, plus the uncommitted Lens snapshot described above

## Why this matters

The transport and device flows work, but the client can retain unbounded
outgoing data while offline, the HTTP proxy accepts an unbounded response, and
sensitive OCR/credential fallbacks are unsafe for a public product. The root
test task passes, but root lint currently fails across four modules. This plan
creates a trustworthy baseline before the plugin security boundary or public
SDK changes begin, without redesigning CXR-L, CXR-S, SPP, surfaces, or audio.

## Current state

Relevant files:

- `bus-client/src/main/java/com/anezium/rokidbus/client/BusClient.kt` owns the
  client reconnect queue.
- `phone-hub/src/main/java/com/anezium/rokidbus/phone/BusHubService.kt` owns the
  Transitous HTTP proxy and phone transport lifecycle.
- `plugin-lyrics/.../LyricsProviderSettingsStore.kt` stores optional provider
  credentials.
- `lens-glasses/.../LensActivity.kt` owns OCR diagnostics.
- `phone-hub/.../SettingsActivity.kt`, `glasses-hub/.../LauncherOverlayRenderer.kt`,
  and `glasses-hub/.../SurfaceHudView.kt` contain confirmed lint errors.
- `TESTPLAN.md` and `BUSSPEC.md` contain development-machine/device-specific
  details that must not be retained in public documentation.

Current reconnect behavior (`BusClient.kt:132-166`) queues JSON and binary
payloads without count, byte, or age limits:

```kotlin
if (hub == null) {
    queued += Outgoing(path, id, bytes)
    connect()
    return id
}
// ...
if (hub == null) {
    queued += Outgoing(path, id, bytes, data)
    connect()
    return id
}
```

Current proxy behavior (`BusHubService.kt:572-616`) checks an initial host but
does not require HTTPS, bound the total body, or disable redirects:

```kotlin
val url = runCatching { URL(urlText) }.getOrNull()
if (url == null || url.host != "api.transitous.org") { /* error */ }
// ...
while (true) {
    val read = input.read(buffer)
    if (read == -1) break
    total += read
    reply(/* chunk */)
}
```

Current credentials fallback (`LyricsProviderSettingsStore.kt:93-108`) falls
back from encrypted storage to ordinary `SharedPreferences`. The fallback must
be removed rather than made quieter.

Current Lens diagnostics (`LensActivity.kt:1449-1459,1701`) log recognized and
translated user text because the compile-time switch is enabled by default.

The verified commands at plan time were:

- `./gradlew.bat test`: exit 0.
- `./gradlew.bat lintDebug --continue`: non-zero. Confirmed error families are
  `UnspecifiedRegisterReceiverFlag`, two `WrongConstant` sites, CameraX opt-in
  errors, and `ExpiredTargetSdkVersion` on intentionally API-32 glasses builds.

Repository conventions to preserve:

- Pure protocol/policy code gets JVM tests; use
  `shared/src/test/.../FrameProtocolTest.kt` as the assertion/style example.
- Android-specific services keep failures as explicit state/log messages, not
  uncaught exceptions.
- Device logs contain path, result, and byte counts, never device identity or
  user text.
- The glasses target remains API 32 until a real-device migration is requested;
  do not raise it merely to satisfy Play-oriented lint.

## Commands you will need

| Purpose | Command | Expected on success |
|---|---|---|
| Working tree guard | `git status --short` | empty before source edits |
| Unit tests | `.\gradlew.bat test` | exit 0, all unit tests pass |
| Targeted queue tests | `.\gradlew.bat :bus-client:testDebugUnitTest` | exit 0 |
| Targeted proxy tests | `.\gradlew.bat :phone-hub:testDebugUnitTest` | exit 0 |
| Lint | `.\gradlew.bat lintDebug --continue` | exit 0, no lint errors |
| Debug build | `.\gradlew.bat assembleDebug` | exit 0, all current modules assemble |
| Patch hygiene | `git diff --check` | exit 0, no whitespace errors |

## Suggested executor toolkit

- Use `rokid-glasses-dev` if available. Preserve the validated CXR-L Global,
  CXR-S, Caps, SPP, one-axis input, and Android Go constraints.
- Do not add network libraries or GMS dependencies to the glasses hub.

## Scope

**In scope**:

- `bus-client/src/main/java/com/anezium/rokidbus/client/BusClient.kt`
- `bus-client/src/main/java/com/anezium/rokidbus/client/BoundedOutgoingQueue.kt` (create)
- `bus-client/src/test/java/com/anezium/rokidbus/client/BoundedOutgoingQueueTest.kt` (create)
- `bus-client/build.gradle.kts`
- `phone-hub/src/main/java/com/anezium/rokidbus/phone/BusHubService.kt`
- `phone-hub/src/main/java/com/anezium/rokidbus/phone/HttpProxyPolicy.kt` (create)
- `phone-hub/src/test/java/com/anezium/rokidbus/phone/HttpProxyPolicyTest.kt` (create)
- `phone-hub/src/main/java/com/anezium/rokidbus/phone/SettingsActivity.kt`
- `phone-hub/build.gradle.kts`
- `glasses-hub/src/main/java/com/anezium/rokidbus/glasses/LauncherOverlayRenderer.kt`
- `glasses-hub/src/main/java/com/anezium/rokidbus/glasses/MediaHudView.kt`
- `glasses-hub/src/main/java/com/anezium/rokidbus/glasses/SurfaceHudView.kt`
- `glasses-hub/src/main/AndroidManifest.xml`
- `glasses-hub/src/debug/AndroidManifest.xml` (create if needed)
- `glasses-hub/build.gradle.kts`
- `glasses-client-probe/build.gradle.kts`
- `lens-glasses/src/main/java/com/anezium/rokidbus/lens/LensActivity.kt`
- `lens-glasses/build.gradle.kts`
- `plugin-lyrics/src/main/java/com/anezium/rokidbus/lyrics/settings/LyricsProviderSettingsStore.kt`
- `phone-hub/src/main/java/com/anezium/rokidbus/phone/LyricsSettingsActivity.kt`
- tests for the Lyrics settings store under `plugin-lyrics/src/test/` if its
  Android dependency can be isolated behind a pure policy interface
- `BUSSPEC.md`
- `TESTPLAN.md`

**Out of scope**:

- Plugin identity, capabilities, consent, discovery, or AIDL v3; Plan 002 owns them.
- External plugin lifecycle and SDK publication; Plan 003 owns them.
- Converting Transit, Lyrics, or Lens to separate APKs.
- Changing CXR-L authentication components, CXR session ownership, Caps format,
  SPP framing, the SPP service identifier, audio lease semantics, surface kinds,
  or glasses `targetSdk`.
- Implementing general device selection or no-ADB onboarding.
- Adding a lint baseline that hides existing errors.

## Git workflow

- Branch: `advisor/001-safety-and-verification`
- Use small sentence-style commits matching repository history, for example:
  `Bound BusClient reconnect queues` and `Make Android lint baseline green`.
- Do not push, publish artifacts, install APKs, or open a PR unless instructed.

## Steps

### Step 1: Characterize the bounded queue before integrating it

Create an internal, pure Kotlin `BoundedOutgoingQueue` with an injectable
monotonic clock. Use these initial policy constants, matching the existing hub
wake queues:

- maximum 32 queued JSON messages;
- maximum 512 KiB total serialized JSON bytes;
- TTL 30 seconds;
- FIFO among retained messages;
- oldest JSON messages are dropped first when a limit is exceeded;
- binary messages are never retained while the hub is unavailable because they
  represent realtime/media data.

The queue API must return a result describing accepted/dropped/expired items so
`BusClient` can emit one bounded, non-sensitive `BusEvent.Error`; do not log or
include payload content. Write tests for count overflow, byte overflow, TTL,
FIFO, requeue-after-`RemoteException`, and binary rejection.

**Verify**: `.\gradlew.bat :bus-client:testDebugUnitTest` -> exit 0 and at least
six new queue-policy tests pass.

### Step 2: Replace the unbounded `BusClient` queue

Replace `ConcurrentLinkedQueue<Outgoing>` and every direct `queued +=` site with
the tested queue. JSON sends may be queued under the policy above. `sendBinary`
must connect/reconnect and emit an error when offline, but must not retain the
binary byte array. `flushQueued()` must preserve FIFO and put a failed JSON item
back at the head exactly once without violating limits.

Keep the public `send` and `sendBinary` signatures unchanged. Do not introduce
surface-specific coalescing; the display arbiter will own that later.

**Verify**: `.\gradlew.bat :bus-client:testDebugUnitTest :phone-client-probe:assembleDebug :glasses-client-probe:assembleDebug` -> exit 0.

### Step 3: Extract and enforce a finite HTTP proxy policy

Create a pure `HttpProxyPolicy` used before `HttpURLConnection` is opened. The
policy must enforce:

- HTTPS only;
- the current exact Transitous API host, case-insensitively, with no user-info
  and no non-default port;
- methods `GET` and `POST` only;
- request body at most 64 KiB;
- only `Accept`, `Content-Type`, `If-None-Match`, `If-Modified-Since`, and
  `User-Agent` forwarded from caller headers;
- redirects disabled (`instanceFollowRedirects = false`);
- response stream capped at 4 MiB total;
- exactly one terminal `done=true` response on success or failure.

When the response cap is reached, stop reading, disconnect, and return a stable
machine-readable error code such as `RESPONSE_TOO_LARGE`; do not include remote
response text or a full exception message in user-visible/log output. Preserve
the binary-chunk ordering guarantee.

Write unit tests for scheme, host boundary, user-info, port, method, request
body, header filtering, and response-limit accounting. Do not perform real
network calls in unit tests.

**Verify**: `.\gradlew.bat :phone-hub:testDebugUnitTest` -> exit 0 and all proxy
policy tests pass.

### Step 4: Fail closed for secrets and user-observed text

In Lens, make raw OCR/translation text logging unavailable in normal builds.
If a debug-only diagnostic toggle is retained, it must require both
`BuildConfig.DEBUG` and an explicit preference that defaults to false. Normal
logs may contain block counts, latency, language code, and payload size only.

In `LyricsProviderSettingsStore`, remove the ordinary `SharedPreferences`
fallback. Refactor secure preference creation so reads return no credentials
and writes return a failure when encrypted storage is unavailable. Update
`LyricsSettingsActivity` to display a stable message such as `Secure storage
unavailable; credentials were not saved.` Session-token cache writes may be
skipped after a redacted warning; credentials must never be persisted in clear
text. Never log email, password, session token, or exception data that embeds
them.

Redact device serials/addresses from `TESTPLAN.md` and `BUSSPEC.md`; replace
commands with `$phone` and `$glasses` variables populated by the operator's
environment. Change the SPP connection log so it does not print the bonded
device name. Do not change the currently validated device-selection logic in
this plan.

**Verify**:

```powershell
rg -n 'LOG_OCR_STRINGS\s*=\s*true|fallback.*SharedPreferences|device\.name' lens-glasses plugin-lyrics phone-hub -S
rg -n 'Serials used during validation|target by MAC' TESTPLAN.md BUSSPEC.md -S
```

Expected: both commands return no matches. Then run
`.\gradlew.bat :plugin-lyrics:testDebugUnitTest :lens-glasses:assembleDebug` -> exit 0.

### Step 5: Make debug probes release-safe

The component-targeted glasses probe receiver is useful for ADB development but
must not be exported in release. Move the exported receiver declaration to a
debug-only manifest overlay, or otherwise ensure the merged release manifest
does not export it. Preserve all existing debug probe commands.

**Verify**:

```powershell
.\gradlew.bat :glasses-hub:processDebugMainManifest :glasses-hub:processReleaseMainManifest
Select-String -Path glasses-hub\build\intermediates\merged_manifests\release\processReleaseMainManifest\AndroidManifest.xml -Pattern 'ProbeBroadcastReceiver'
```

Expected: Gradle exits 0 and the release merged manifest either has no probe
receiver or declares it non-exported. Inspect the debug merged manifest and
confirm the receiver remains component-addressable for the documented probes.

### Step 6: Fix lint errors without hiding platform constraints

- Register the phone log receiver through `ContextCompat.registerReceiver`
  with `RECEIVER_NOT_EXPORTED` on every supported API.
- Replace the two break-strategy constants with constants accepted by the
  target API/lint combination; because glasses `minSdk` is 31, use the modern
  `LineBreaker` constants if that is what the compiled API expects.
- Add the narrow CameraX experimental opt-in annotations at the methods that
  use preview transforms, Camera2 interop, and `ImageProxy.image`; do not blanket
  suppress `UnsafeOptInUsageError` for the module.
- For glasses-only modules that intentionally target API 32, disable only
  `ExpiredTargetSdkVersion` in Gradle with a comment explaining YodaOS/side-load
  compatibility. Do not create a baseline and do not disable this check for the
  phone hub.

Run lint after each module fix to keep attribution clear.

**Verify**: `.\gradlew.bat lintDebug --continue` -> exit 0 with zero errors.

### Step 7: Run the complete software regression gate

Run all unit tests, lint, and debug assembly. Confirm that generated outputs are
ignored and no APK or report is staged.

**Verify**:

```powershell
.\gradlew.bat test lintDebug assembleDebug
git diff --check
git status --short
```

Expected: Gradle exits 0; `git diff --check` exits 0; status contains only the
files explicitly listed in this plan plus the plan-index status update.

## Test plan

- `BoundedOutgoingQueueTest`: FIFO, count cap, byte cap, TTL, oldest-drop,
  failed-send head requeue, binary rejection, and no payload in error text.
- `HttpProxyPolicyTest`: permitted request, non-HTTPS rejection, lookalike host
  rejection, user-info rejection, port rejection, method rejection, body cap,
  header allowlist, redirect policy, and response cap.
- Lyrics secure-store policy: encrypted-store failure returns no credentials and
  no successful save; no clear-text fallback is opened.
- Existing `FrameProtocolTest`, touchpad tests, Lyrics tests, and Transit tests
  remain unchanged and green.
- Manual smoke after software verification: authorize Hi Rokid if necessary,
  start the hub, open Transit, and confirm a board still renders. Use operator
  device variables; do not paste device identifiers into logs or the plan.

## Done criteria

- [x] `.\gradlew.bat test lintDebug assembleDebug` exits 0.
- [x] New queue and HTTP policy tests exist and pass.
- [x] Binary payloads are not retained by `BusClient` while disconnected.
- [x] JSON reconnect data is capped by count, bytes, and TTL.
- [x] HTTP proxy requires HTTPS, disables redirects, and caps request/response sizes.
- [x] Lens does not log OCR or translation text by default.
- [x] Lyrics has no ordinary-preferences credential fallback.
- [x] Release glasses manifest does not export the debug probe receiver.
- [x] Device identifiers are absent from public test/spec prose.
- [x] No lint baseline was added and glasses `targetSdk` was not raised.
- [x] No file outside Scope is modified, except `plans/README.md` status.
- [x] `plans/README.md` marks Plan 001 `DONE` only after all gates pass.

## STOP conditions

Stop and report instead of improvising if:

- The repository is dirty when execution begins.
- The current Lens excerpts differ because the in-flight Lens work was changed
  or partially committed after this plan was written.
- A real client depends on replaying binary data after reconnect.
- Transitous requires redirects or a request method/header outside the explicit
  policy for a currently tested flow.
- Removing the clear-text credential fallback makes existing encrypted
  credentials unreadable; do not delete or migrate them without a separate,
  tested migration decision.
- A lint fix appears to require raising the glasses `targetSdk` or changing HUD
  behavior on the real device.
- Any verification fails twice after a reasonable, scoped correction.

## Maintenance notes

- Plan 002 must build authorization on the bounded/rate-limited primitives from
  this plan rather than adding a second queue implementation.
- The Hi Rokid authorization token still uses app-private preferences in the
  current architecture. Moving it to a shared fail-closed secret store is a
  separate follow-up because it touches the CXR-L reauthorization lifecycle.
- General device selection and replacement of prototype device constants are
  deliberately deferred to the public-onboarding plan; this plan only redacts
  logs and documentation.
- Reviewers should scrutinize all failure logs for leaked URL query strings,
  OCR text, credentials, device identity, and payload previews.
