# Kadb Android compatibility build

Both hubs use this source-built Android library instead of the published
`com.flyfishxu:kadb:2.1.1` variant. The published Android classes have JVM major
version 65 (Java 21); Nexus builds and runs host unit tests with JDK 17 and targets
Java 11 bytecode. Upstream's 2.1.1 build configures `jvmToolchain(21)` globally
inside its JVM target. Merely changing the hubs' source/target compatibility does
not make JDK 17 able to load those classes.

## Provenance and scope

- Upstream: <https://github.com/flyfishxu/Kadb>
- Tag: `2.1.1`
- Pinned commit: `95c4ac56cb1f4998003192e44323ed84843eaba0`
- License: Apache-2.0; the upstream `LICENSE` and source copyright notices are retained.
- `src/commonMain` and `src/androidMain` are complete, **unmodified** copies from
  that commit. `upstream-sha256.json` records each source file and the license.
- The local build script replaces upstream's publishing, signing and JVM target
  configuration with an Android-only Kotlin Multiplatform target. It explicitly
  emits JVM 11 bytecode (major 55). The Kotlin and Android Multiplatform plugins
  come from the existing root-pinned AGP 9.2.0 classpath; no second Kotlin plugin
  version or JDK download is introduced.
- All upstream Android runtime dependency versions and the Okio `api` exposure
  are retained. SPAKE2, AES-GCM, TLS, certificate/key storage, shell and sync APIs
  remain upstream implementations. There is no protocol or security-policy patch.
- Local host regression tests are separate in `src/androidHostTest` and are not
  included in the upstream checksum manifest.

This deliberately does not downgrade Kadb: older releases may change pairing,
identity and shell behavior. Published 2.1.2–2.1.4 also contain Java 21 classes,
so a version bump alone is not a compatibility fix. Moving the entire project
and host tests to Java 21 would change Nexus's documented Java 17 toolchain and
require provisioning another JDK. Binary class-header rewriting is not a valid
rebuild and is not used. No private Maven artifact, cache injection or machine
configuration change is required.

## Verification

From the repository root, using JDK 17 and the existing SDK configuration:

```sh
python3 kadb-compat/verify_upstream.py
./gradlew :kadb-compat:testAndroidHostTest \
  :phone-hub:testDebugUnitTest :phone-hub:assembleDebug \
  :glasses-hub:testDebugUnitTest :glasses-hub:assembleDebug
./gradlew :shared:testDebugUnitTest \
  :plugin-wireless-adb:testDebugUnitTest :plugin-wireless-adb:assembleDebug \
  :plugin-youtube-patcher:testDebugUnitTest :plugin-youtube-patcher:assembleDebug
```

The hubs require the existing sibling `../CxrGlobal`; do not use
`-PskipCxrGlobal=true` for their builds. Do not regenerate `local.properties`.

The compatibility tests verify the rebuilt public class's Java 11 bytecode,
identity reuse after reload, defensive key copies, fail-closed handling of an
invalid stored key with auto-healing disabled, successful SPAKE2 pairing, rejection
of tampered ciphertext and wrong passwords, and destroyed pairing-context behavior.
These are host regressions, not a substitute for wireless pairing/TLS checks on
real Android devices.

To independently compare the vendor tree with upstream, clone into a scratch
location, check out the **commit** above, then run:

```sh
python3 kadb-compat/verify_upstream.py --upstream /path/to/Kadb-checkout
```

The verifier checks the commit, complete source file set, hashes and byte-for-byte
source/license equality. For a future update, review upstream security/API changes,
replace both source sets and the license from a newly pinned commit, regenerate the
manifest, update provenance, and rerun these suites. Do not silently edit vendor
sources; any future source patch needs a documented delta and its own regression.
