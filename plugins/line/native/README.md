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
bounded durable deduplication and the security changes requiring device revalidation.
