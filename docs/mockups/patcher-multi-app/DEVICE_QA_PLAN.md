# Patcher device QA after Skills/Workspace

Requested on 2026-10-09. Do not use the phone until thread
`847cd5a6-e515-40c8-8fed-ecc634ad020c` has finished its active work.

## Delivery prerequisite

Read that thread's final result and check the currently installed phone hub before
deploying. Its Skills/Workspace work uses a separate QA checkout. The Patcher
root-checkout hub APK must not replace a newer hub with fewer features. Prepare a
compatible combined hub if required, preserving existing account state, grants,
settings, signing certificates and the other work. Do not commit or publish.

The new hub activity entry and Patcher settings activity must be present together.
Use existing signing configuration only. Do not uninstall apps to bypass a signer
or change SDK, Gradle, local.properties or device accessibility settings.

## Five checks

1. **Entry and navigation.** Open Patcher through Nexus; inspect equal YouTube and
   Reddit cards and real green app marks. Open each route, return through Back,
   and confirm the original Nexus screen is restored. Check that Glasses apps has
   no YouTube setup action, while ordinary app rows/Open remain available.
2. **YouTube setup.** Visit overview, MicroG, official source, patch/install,
   sign-in instructions and Advanced. Check actual inventory wording and scrolling
   without initiating MicroG replacement or login. Open/cancel the document picker
   without replacing a held source. Confirm source/result target locks and return
   ownership. A real long patch is not needed solely to repeat engine coverage.
3. **Keyboard and lifecycle.** Record the original YouTube auto-keyboard setting,
   toggle and reopen to confirm persistence, then restore it. Check the secure
   remote route without entering credentials or capturing protected content.
   Reopen the app and confirm screen/job state stays coherent. Preserve the Reddit
   keyboard preference and the other thread's app configuration.
4. **Reddit and maintenance.** Visit Reddit's preparation route and verify the
   target/version/Preview label and absence of MicroG. Inspect maintenance without
   importing a production key or replacing it. Validate key/job exclusion through
   the already-observed unit tests; do not expose key backup passwords or material.
5. **Evidence.** Capture actual phone screens for Patcher home, YouTube overview,
   one or more step screens, Advanced, Reddit and Glasses apps. Inspect each image
   before showing it; exclude account names, device identifiers, private file
   listings and secure keyboard screens. Record observed assertions, APK hashes,
   install status, limitations and any failing navigation.

## Reporting

Distinguish actual device screenshots from earlier Robolectric renders. Show the
new screenshots in this thread after inspection. Do not claim native installs,
accounts, server writes or physical glasses controls were tested unless exercised.
No real Reddit posting, votes/save, account login or signing-key replacement is
needed for this UI validation.
