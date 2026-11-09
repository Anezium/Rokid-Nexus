# LINE native engine

On this Windows machine, the provisioned Go 1.27.3 binary is outside PATH:

```text
C:/Users/saim2/AppData/Local/RokidNexus/phone-tests/toolchains/go1.27.3/go/bin/go.exe
```

From this directory, use the existing toolchain and normal user caches:

```powershell
$lineGo = 'C:/Users/saim2/AppData/Local/RokidNexus/phone-tests/toolchains/go1.27.3/go/bin/go.exe'
& $lineGo version
& $lineGo test ./...
& $lineGo vet ./...
& ./Build-Android.ps1 -GoPath $lineGo
```

The build requires the existing Android SDK/NDK via `ANDROID_HOME` and Java 17.
Do not install/relocate tools or caches, or change `local.properties`. The ignored
output is `../libs/line-engine.aar`. From the repository root, verify it with:

```powershell
./gradlew.bat :plugin-line:testDebugUnitTest :plugin-line:assembleDebug -PskipCxrGlobal=true
```

See [SEND_IMAGE.md](SEND_IMAGE.md) for the photo wire contract, outcome semantics,
bounded durable deduplication and the compatible preview format's cryptographic limitation.
Photo sends omit `ENC_KM` metadata and upload an encrypted preview;
[MEDIA_PROTOCOL_RESEARCH.md](MEDIA_PROTOCOL_RESEARCH.md) records the evidence and limits.
For opt-in, redacted official-client envelope inspection, see
[DESCRIBE_MEDIA.md](DESCRIBE_MEDIA.md) (`-e describeMedia true` in instrumentation).
The metadata describer is unused by normal plugin pages/settings and downloads
no media; no media/key/locator bytes are output.

## Preview downloads

`Media` resolves and copies the loaded message's locators/keys under the session
locks, then releases them before downloading and authenticating the media. Up to
three calls can download concurrently, with a twelve-second deadline per admitted
download. Callers waiting for a slot are cancelled with the owner. Closing the
engine cancels and waits for admitted media calls before releasing the session.
The caller must invoke `Media` concurrently to use these three slots.

Failures are remembered on the loaded message's native source and returned
without another request on later calls. Reloading that chat's history replaces
the source and permits a retry; an older in-flight failure cannot affect the
replacement. Successful bytes remain the Kotlin thumbnail cache's responsibility.

Received photos with `MEDIA_THUMB_INFO` use `OID + "__ud-preview"` first, as own
photos already do. If the preview fails, original-file fallback is skipped when
`FILE_SIZE` or `MEDIA_CONTENT_INFO.fileSize` declares more than 2 MiB. Small or
unknown-size originals retain fallback, subject to the existing 4 MiB transport
limit. No media locators, keys or failure state enter snapshots.
