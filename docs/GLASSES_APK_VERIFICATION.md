# Glasses APK verification

Before CXR upload, the phone requires a matching release SHA-256 digest, a valid
APK signature, the expected package, and exactly one current signer in its
build-time pin set. `versionCode` must equal `major * 10000 + minor * 100 + patch`
for the selected release (minor/patch `0..99`, code `1..2100000000`). Android on
the glasses enforces downgrade restrictions.
Failures show fixed digest, signature/manifest, package, signer or version
messages in the installation UI.

## Pin provenance

The default certificate SHA-256 is:

```text
f5e938e2e79b0526b31e40d36c8c19098450c1636b7e14a306681b4effddf81c
```

It was extracted with `apksigner verify --verbose --print-certs` from the signed
[`v1.5.0` glasses APK](https://github.com/Anezium/Rokid-Nexus/releases/download/v1.5.0/nexus-glasses-1.5.0.apk)
(single v2 signer, `CN=Anezium, OU=Rokid Nexus`; file SHA-256
`dbed54ffbac6cc5640900f30e262abf408a99bb7e4d5e60367c3fd7a99e492e0`).
It matches the author's [Wireless ADB registry fingerprint](https://github.com/Anezium/RokidBrew-Registry/blob/main/plugins-nexus/wirelessadb.json).
Both sources are author-controlled: the maintainer should confirm the pin
against their signing key before adopting it. Runtime pins come only from the
compiled phone app, never from release or registry metadata.

## Fork builds and key rotation

Obtain your certificate fingerprint with `apksigner verify --print-certs` and
set the phone build's Gradle property:

```sh
./gradlew :phone-hub:testDebugUnitTest :phone-hub:assembleDebug \
  -PglassesApkSignerSha256=YOUR_64_HEX_CERTIFICATE_SHA256
```

A comma-separated list permits a planned transition (`OLD_PIN,NEW_PIN`). Every
element must be exactly 64 hex characters without whitespace; an empty list or
invalid/empty element fails configuration. Omitting the property uses the
upstream pin. It does not change the release feed, package, or signing key.
Distribute the trusted phone build before rotating the glasses key: only the
current signer is authorized, not certificates found in its signing lineage.
Only the current signer reported by apksig is checked; for a v3.1 rotation that is
the signer for the highest SDK range, so the pin set must contain the new key.
Keeping the key the glasses (API 32) actually apply in the set as well is
recommended during the transition. Android must separately accept the installed
app's update signature/lineage.

## Compatibility

`com.android.tools.build:apksig:9.2.0` (Apache-2.0, approximately 495 KiB JAR,
no transitive dependencies) verifies v1/v2/v3 signatures and reads the manifest
without `PackageManager`, so an API 31 glasses APK can be inspected on Android 11.
apksig uses Java 8 bytecode; after D8 lambda desugaring, all `java.*` runtime
references are available at API 30 (checked against `api-versions.xml`); the
release APK grows by ~370 KB. D8 compilation and real signed APK tests under
Robolectric API 30 cover this
integration, but do not replace a physical-device installation check.
