# APK verification test inputs

`unsigned.apk` contains only the compiled `AndroidManifest.xml` (minSdk 31)
and a stored `assets/payload.txt` containing `nexus-apk-verification-test-payload`.
It has no executable code. The payload allows tests to corrupt signed content
without damaging ZIP/manifest structure.

`test-signers.p12` contains two generated RSA test keys (`old`, `current`), both
with password `test-only`. These public test fixtures must never sign a real app.
Tests use ApkSigner to create real v2 signatures and a v3 rotation lineage at
runtime; no local release/debug signing keys are used.

To recreate the unsigned fixture using an existing Android SDK:

```sh
aapt package -f -M AndroidManifest.xml -I "$ANDROID_SDK_ROOT/platforms/android-36/android.jar" -F unsigned.apk
python3 - <<'PY'
import zipfile
with zipfile.ZipFile('unsigned.apk', 'a') as apk:
    apk.writestr('assets/payload.txt', 'nexus-apk-verification-test-payload', compress_type=zipfile.ZIP_STORED)
PY
```

To replace each test key (the tests derive pins from the fixture):

```sh
keytool -genkeypair -alias old -keyalg RSA -keysize 2048 -validity 36500 \
  -dname 'CN=Nexus test old key - never use in production' -storetype PKCS12 \
  -keystore test-signers.p12 -storepass test-only -keypass test-only
# Repeat with alias current and a distinct test subject.
```
