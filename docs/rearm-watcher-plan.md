# Glasses accessibility re-arm watcher — implementation plan

Branch: `glasses-rearm-watcher` (worktree `E:/Tools/Rokid/RokidNexus-rearm`).
Goal: RokidNexus glasses keep their accessibility service enabled with **zero user
input** and **survive being killed** — self-healing — without draining the battery.

## Why this exists
`RokidBusAccessibilityService` (glasses-hub) drives the HUD/input. Android (and Rokid's
own security behaviour) can disable or kill it. Re-enabling it means writing the secure
settings `enabled_accessibility_services` + `accessibility_enabled`, which the app cannot
do itself (no `WRITE_SECURE_SETTINGS`). The proven mechanism is a privileged loopback-ADB
path that installs a native shell watchdog. This branch ports that mechanism from the
sibling **Rokid Relay** app into Nexus and retargets it, then optimizes battery.

## Proven facts — DO NOT re-derive
- Glasses live Wi-Fi OFF; loopback ADB on `127.0.0.1:5555` works Wi-Fi-free once
  `persist.adb.tcp.port=5555` is set and adbd restarts; it survives reboot.
- Relay is CURRENTLY armed on the device and its watchdog design works. Source of truth:
  `E:/Tools/Rokid/Rokid Relay/glasses/src/main/java/com/anezium/rokidrelay/glasses/`.
- Nexus glasses app runs (pid alive) but its a11y service is NOT in
  `enabled_accessibility_services` today — nothing keeps it there yet.

## Source → target port map (read from Relay, write into glasses-hub)
Retarget EVERY identifier: package `com.anezium.rokidrelay.glasses` → `com.anezium.rokidbus.glasses`;
a11y component → `com.anezium.rokidbus.glasses/com.anezium.rokidbus.glasses.RokidBusAccessibilityService`;
watchdog name `rokid-relay-a11y-watchdog` → `rokid-nexus-a11y-watchdog`.

Port these (adapt, don't blindly copy):
- `AdbLoopbackClient.kt` (+ the `AdbKeyMaterial` type it uses) — raw-protocol loopback ADB
  client `runShell(command, keyMaterial)`. Command-agnostic; keep it that way.
- The KADB private-key store (Relay persists a keypair under app filesDir). Port the store;
  Nexus gets its OWN keypair.
- `SelfArmController.kt` — port the RE-ARM core only:
  `accessibilityRepairNeeded`, `repairAccessibilityDirect`, watchdog
  install/status/stop (`buildInstallCommand`, `ensureInternalWatchdog`), `runSelfArm`
  degradation logic. LEAVE OUT Relay-specific prefs/phone-provisioning coupling.
- `assets/rokid-relay-a11y-watchdog.sh` → new `assets/rokid-nexus-a11y-watchdog.sh`,
  retargeted PKG/SVC/NAME, plus the battery changes below.
- New `SelfArmConstants` (or fold into existing glasses config) for the Nexus values.

## Battery strategy (hard requirement) — improve on Relay's pure poll
Relay's watchdog is `while true; sleep 60` doing 2×`settings get` + `pidof` every 60s
forever. Replace with a **two-tier** design:

1. **Event-driven fast path (app-side, ~zero battery).** In the glasses app process,
   register `AccessibilityManager.addAccessibilityStateChangeListener` (and re-check on
   `BusHubService` start / a11y `onServiceConnected`). When a11y flips OFF, trigger an
   immediate `repair_once` via the loopback path. This handles the common case (service
   disabled while the app is still alive) instantly, with no polling.
2. **Coarse shell safety-net (survives app death).** The native watchdog still runs as
   shell uid so it works when the whole app process is dead — but:
   - Adaptive interval: `INTERVAL=30` for the first few cycles after a repair, then
     back off to a healthy baseline of **180s** (make it a script var, default 180).
   - Only run the expensive rebind block (services_without_relay dance) when the app pid
     is actually gone AND the service is missing — never on the healthy path.
   - Keep the log rotation; keep heartbeat.
Net effect: instant recovery when the app is alive (event-driven), and a low-duty-cycle
(≈180s) shell net only for the app-fully-dead case.

## Triggers to wire (all UI-free)
- `BootReceiver` (already declared) → ensure watchdog installed + running on boot.
- `BusHubService` start / a11y `onServiceConnected` → ensure watchdog + register the
  AccessibilityManager listener.
- The event-driven listener → immediate repair.
Do NOT add a foreground service that polls in a tight loop.

## Out of scope for this branch (do not build)
- First-time arming of Nexus's own ADB key (the locale-aware Wireless-Debugging
  automator port). The code must DEGRADE GRACEFULLY when the key isn't enrolled or the
  loopback port isn't open (log and no-op, exactly like Relay's `runSelfArm`). Nexus key
  enrollment for testing is handled manually on-device by the maintainer.
- Any phone→glasses provisioning handshake. The glasses app self-provisions its bundled
  watchdog asset. No phone dependency.
- Plugin/OOP architecture (that lives on the `plugin-rewrite` branch).

## MUST-NOT
- Do NOT touch `E:/Tools/Rokid/RokidNexus` (main worktree, holds uncommitted Lens work)
  or `E:/Tools/Rokid/RokidNexus-plugins`. Work ONLY in this worktree on branch
  `glasses-rearm-watcher`. Verify with `git rev-parse --abbrev-ref HEAD` before editing.
- No leftover `rokidrelay` / `RelayAccessibilityService` reference in ported code.
- No arbitrary shell exposed beyond the self-contained watchdog install.
- Do not break existing glasses-hub behavior; keep the build green.

## Acceptance criteria
1. `./gradlew :glasses-hub:assembleDebug` (and existing unit tests) green.
2. On a device where Nexus's ADB key is enrolled + loopback 5555 open: disabling
   `RokidBusAccessibilityService` (via `settings put secure enabled_accessibility_services`)
   is auto-repaired — instantly if the app is alive (event path), within ≤ one watchdog
   interval if the app is dead.
3. Watchdog survives `am force-stop com.anezium.rokidbus.glasses` and re-enables the
   service.
4. Healthy-state watchdog interval is ≥180s (verify via the script + its log).
5. When the key/loopback is unavailable, the app logs and no-ops (no crash, no user
   prompt).
