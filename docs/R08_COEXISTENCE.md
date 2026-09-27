# R08 Access Bridge recovery

Watchdog `2026-09-27.1` can maintain the two R08 shell helpers without enabling
legacy TCP ADB. Install the updated Nexus glasses APK and R08 glasses/companion
APKs, then arm R08 once. Nexus must have completed its own self-arm setup.
Existing R08 installations with WRITE_SECURE_SETTINGS also register when the app
opens or its accessibility service connects.

The contract is deliberately limited:

- Global setting `r08_access_bridge_armed=1` opts in. R08 clears it when disarmed.
- Nexus checks that the exact R08 package is enabled and both fixed helper files
  exist in `/data/local/tmp`. It rejects symlinks, non-shell owners, and files
  writable by group/others. No shared-storage code, command text or keys are accepted.
- Each Nexus watchdog cycle starts the helpers idempotently. Recovery runs at
  watchdog startup, explicit repair, and then on the existing recovery/healthy
  schedule (normally 30/180 seconds). There is no additional polling loop.
- Global `rokid_nexus_r08_watchdog` advertises `1:<boot_count>:<uptime_seconds>`.
  R08 accepts the heartbeat for at most 240 seconds in the same boot. A configured
  Nexus accessibility service also prevents R08 from restarting ADB during Nexus's
  startup. R08 reports success only after its own shortcut heartbeat is fresh;
  otherwise it reports waiting for Nexus, with an update/self-arm hint.
- The companion and local R08 setup leave ADB port properties alone when the
  Nexus watchdog is installed and its package is enabled. Without Nexus, R08
  retains its standalone loopback setup.

Both updated apps are required; an older Nexus watchdog cannot restore R08.
No cable is needed for this cooperation once both apps have been set up. Recovery
depends on Nexus successfully restoring its own shell watchdog after reboot.

## Validation

Run `:glasses-hub:testDebugUnitTest :glasses-hub:assembleDebug` and the R08
`:app:testDebugUnitTest :app:assembleDebug :phone:assembleDebug` tasks.

`glasses-hub/src/test/shell/test-r08-coexistence.sh <watchdog-asset>` executes the
actual recovery function with mocked Android services. Run on a POSIX filesystem
with real symbolic links, or on Android with `TMPDIR=/data/local/tmp`. It checks
recovery, idempotency, opt-out, missing/disabled packages, incomplete installation,
file ownership/mode and symlink rejection, and permits only the heartbeat write.

On September 27, 2026, these shell tests passed on the glasses. An additional live
test stopped only R08's two helpers and executed the new recovery function: both
restarted, a second recovery preserved their PIDs, and both TCP port properties
remained `-1`. USB debugging, wireless debugging, Wi-Fi settings and the Nexus app
PID were unchanged. Temporary opt-in/heartbeat settings were restored. This test
did not install either new APK or reboot; a complete updated-APK reboot remains
to be validated before release.
