# Nexus Android plugin SDK

> [PLUGINS.md](PLUGINS.md) is the full guide to building a plugin (module
> structure, the headless-manifest rules, and the NexusUi design kit). This
> document is the SDK reference: artifact coordinates, the service contract,
> and the approval flow.

For the complete self-contained plugin contract — endpoints, limits,
lifecycle, and publishing — see [`plugins/AGENTS.md`](../plugins/AGENTS.md).

The SDK artifact is `com.github.Anezium.Rokid-Nexus:bus-client`, released
through JitPack from `sdk-v*` tags on this repository (see the "Rokid Nexus
SDK" GitHub releases for the current version). The `shared` artifact is
resolved transitively.

## 1. Add the dependency

```kotlin
repositories { maven("https://jitpack.io") }

dependencies {
    implementation("com.github.Anezium.Rokid-Nexus:bus-client:sdk-v0.2.1")
}
```

For local development against a checkout, publish a snapshot instead:
`.\gradlew.bat :shared:publishToMavenLocal :bus-client:publishToMavenLocal
'-PversionName=0.1.0-SNAPSHOT'` and consume it from `mavenLocal()`.

Use `compileSdk = 36`. The bus-client AAR supports `minSdk >= 26`; the
repository's canonical Sample and Transit plugin templates use `minSdk = 31`.
The repository builds with JDK 17.

## 2. Declare the plugin service

Declare exactly one exported service for the Nexus plugin action. Installation
does not approve it.

```xml
<service android:name=".HelloPluginService" android:exported="true">
    <intent-filter>
        <action android:name="com.anezium.rokidbus.action.PLUGIN" />
    </intent-filter>
    <meta-data android:name="com.anezium.rokidbus.plugin.ID" android:value="hello" />
    <meta-data android:name="com.anezium.rokidbus.plugin.DISPLAY_NAME" android:value="Hello Nexus" />
    <meta-data android:name="com.anezium.rokidbus.plugin.API_VERSION" android:value="3" />
    <meta-data android:name="com.anezium.rokidbus.plugin.CAPABILITIES" android:value="surfaces" />
    <meta-data android:name="com.anezium.rokidbus.plugin.RECEIVE_PREFIXES" android:value="/plugin/hello,/system/plugin" />
    <meta-data android:name="com.anezium.rokidbus.plugin.SETTINGS_ACTIVITY" android:value=".HelloActivity" />
    <meta-data android:name="com.anezium.rokidbus.plugin.LAUNCHABLE" android:value="true" />
</service>
```

Plugin IDs use `[a-z][a-z0-9._-]{2,63}`. Requested capabilities are `surfaces`,
`http_proxy`, `microphone`, and `camera`. Camera paths are protected by the
approved signer-bound grant. `microphone` is grantable from the phone UI for any
plugin that requests it (see §3.1); the plugin needs no Android `RECORD_AUDIO`
permission — glasses-microphone PCM reaches the plugin over the hub, not through
the phone's own recorder.

## 3. Implement the service

```kotlin
class HelloPluginService : NexusPluginService() {
    private var surface: NexusSurfaceSession? = null

    override fun onNexusOpen() {
        surface = nexusSurfaceSession("main")
        surface?.showCard(
            NexusCard(
                title = "Hello Nexus",
                lines = listOf("> First", "  Second"),
                footer = "swipe · tap · back",
            ),
        )
    }

    override fun onNexusInput(event: NexusInputEvent) {
        if (event.action == KeyEvent.ACTION_DOWN && event.keyCode == KeyEvent.KEYCODE_BACK) {
            surface?.hide()
        }
    }

    override fun onNexusClose() {
        surface?.hide()
        surface = null
    }
}
```

The hub can cold-start this service after the app process was stopped. Do not use
an Activity initializer or static factory. `onNexusOpen`, `onNexusClose`, input,
link-state, and registration callbacks are serialized on the application main
thread. Duplicate lifecycle IDs are ignored. The glasses path already deduplicates
paired directional aliases; plugins should act once on each delivered input.

Approved, registered plugins automatically receive informational glasses signals;
no descriptor capability or extra grant is required. Test
`LinkStateBits.GLASSES_WORN` in `onNexusLinkState`, override
`onNexusGlassesAiButton(active)` for the AI-assist button (`true` on start,
`false` on stop), and handle `BusPaths.GLASSES_DEVICE_INFO` in `onNexusMessage`.
The version-1 device payload contains `deviceName`, `batteryLevel`, `sound`,
`brightness`, `systemVersion`, `isCharging`, and `wearingStatus`, in addition to
`type`, `id`, and `pluginId` envelope fields — the hardware serial number is
never included. These callbacks are observational and do not alter Hi Rokid's
assistant behavior.

Beyond the typed surface API, the service exposes `hubTarget` to select which
hub the plugin binds (phone by default), and two raw hooks for traffic on the
declared receive prefixes: `onNexusMessage` (JSON envelopes) and
`onNexusBinaryMessage` (binary frames with their metadata). Hub state rides the
additive capabilities contracts in `shared`: the phone announces `features`
plus the camera consumer display name (`PhoneHubCapabilitiesContract`), the
glasses announce renderer features, image-surface limits, their app version,
and onboarding completion (`GlassesHubCapabilitiesContract`); unknown fields
stay ignorable in both directions.

Surface IDs are local to the plugin. The SDK validates fields and payload size;
the hub injects verified ownership and global sequencing. High-level code cannot
set a trusted owner, global sequence, or arbitrary system path.

### Real image surfaces

Image surfaces use the existing `surfaces` grant; do not add a descriptor
capability and do not change API version 3. They are available only while the
glasses renderer has announced image v1 and the SPP binary link is live. Check
`nexusClient?.supportsImageSurface` immediately before sending and keep a card
fallback: it is a live value and can become false when SPP drops. `showImage`
and `updateImage` return `CAPABILITY_NOT_AVAILABLE` without sending when either
condition is absent.

Preprocess on the phone. Correct orientation, downscale so both decoded edges
are at most 512 px (and total pixels at most `512 * 512`), then encode as JPEG or
PNG. For photographs, start around JPEG quality 70--80 and adjust to a 20--40 KiB
target. The hard compressed cap is 65,536 bytes. PNG is most useful for simple
graphics; neither format may exceed the decoded bounds. The SDK verifies the
format signature, actual encoded dimensions, SHA-256, metadata, and size before
calling the binary transport. Do not base64 the image.

```kotlin
val bytes = resources.openRawResource(R.raw.image_surface_sample).use { it.readBytes() }
val image = NexusImage(
    contentKey = "tweet-123-photo-1", // stable identity, max 128 chars
    mimeType = ImageSurfaceContract.MIME_JPEG,
    pixelWidth = 480,
    pixelHeight = 480,
    title = "Photo",
    caption = "Optional caption",
    footer = "back",
    handlesBack = true,
)

val result = if (nexusClient?.supportsImageSurface == true) {
    surface?.showImage(image, bytes)
} else {
    surface?.showCard(NexusCard("Photo", listOf("Image preview unavailable")))
}
```

Use `updateImage(image, bytes)` to replace the current image. Every image update
is a complete binary frame and the phone hub enforces 150 ms between image
frames for the same surface. A faster frame is rejected with `/error` code
`IMAGE_RATE_LIMITED`; the SDK preflight returns
`NexusSdkResult.IMAGE_RATE_LIMITED` immediately. Plugins should not build
animation loops around v1.

### 3.1 Microphone (audio lease)

Request the `microphone` capability and add `/audio` to the plugin's receive
prefixes:

```xml
<meta-data android:name="com.anezium.rokidbus.plugin.CAPABILITIES"
    android:value="surfaces,microphone" />
<meta-data android:name="com.anezium.rokidbus.plugin.RECEIVE_PREFIXES"
    android:value="/plugin/yourid,/system/plugin,/audio" />
```

Once the owner grants `microphone`, acquire a lease through
`nexusAudioSession(callbacks)` and drive it with `start()` / `stop()`. The hub
holds a single glasses-microphone lease at a time and streams the raw PCM to the
current holder; the SDK routes the reply, frames, and revocation to your
callbacks — you never handle the raw `/audio/*` envelopes yourself.

```kotlin
class DictationService : NexusPluginService() {
    private var audio: NexusAudioSession? = null

    fun beginListening() {
        val session = nexusAudioSession(object : NexusAudioCallbacks {
            override fun onAudioStarted(format: NexusAudioFormat) {
                // format is 16000 Hz, 1 channel, "pcm16le"
            }

            override fun onAudioFrame(pcm: ByteArray, seq: Long, elapsedRealtimeMs: Long) {
                // ~50 frames/s of little-endian 16-bit mono PCM. Feed your STT,
                // recorder, VAD, etc. `pcm` is owned by the caller — copy it if
                // you keep it past this call.
            }

            override fun onAudioStopped(reason: NexusAudioStopReason) {
                // RELEASED, REVOKED (link lost), or a DENIED_* / ERROR terminal.
                audio = null
            }
        }) ?: return
        audio = session
        when (session.start()) {
            NexusSdkResult.SENT -> Unit                       // lease requested
            NexusSdkResult.CAPABILITY_NOT_GRANTED -> Unit     // owner hasn't granted mic
            NexusSdkResult.NOT_REGISTERED -> Unit             // hub not connected yet
            else -> Unit
        }
    }

    fun stopListening() {
        audio?.stop()   // fires onAudioStopped(RELEASED); safe if already stopped
    }
}
```

Format is fixed at **16 kHz, mono, signed 16-bit little-endian PCM**
(`NexusAudioFormat`). `onAudioStopped` fires exactly once per active session —
on your own `stop()`, on a hub revoke (e.g. the glasses link drops), or on a
denied acquire (`DENIED_BUSY` when another plugin holds the lease,
`DENIED_NO_LINK`, `DENIED_START_FAILED`). The session also tears down (with
`onAudioStopped`) if the plugin loses approval or the service is destroyed, so
you do not need to release on `onNexusClose` yourself.

Two hardware facts to design around:

- **The glasses must be worn.** The on-glasses microphone DSP beamforms toward
  the wearer's mouth and gates otherwise, so a lease acquired while the glasses
  sit unworn yields near-silence. Gate your UX on
  `LinkStateBits.GLASSES_WORN` from `onNexusLinkState` if silence would confuse
  the user.
- **The level is conservative.** Captured speech peaks well below full scale;
  if you play the audio back or show a meter, apply gain (roughly 5×) or
  normalize.

## 4. Approve and debug

After installing the APK, open **Rokid Nexus → Settings → Plugin access**. Review
the requested capabilities and approve only those needed. Pending, denied,
disabled, invalid, and missing-capability plugins are not launchable.

For local software validation:

```powershell
.\gradlew.bat :plugin-sample:testDebugUnitTest :plugin-sample:assembleDebug
```

**Settings → Advanced → Developer mode** is a global toggle. It unlocks the
Bus inspector, a live journal of plugin traffic and rejections, and shows DEV
badges; package, signer, API, and route details are available with developer
details. Logs and bug reports must redact device identifiers, signing digests,
credentials, locations, user text, and full payloads.

Normal use should not require ADB. The present repository still needs owner-run
device validation for APK install/update, glasses accessibility onboarding,
force-stop wake, input, revoke, and CXR-L/SPP continuity. Those are deployment
and hardware gates, not SDK initialization requirements.

Debug builds include a phone-hub-owned end-to-end image probe. With both hubs
installed, the glasses accessibility service armed, and SPP connected, run:

```powershell
adb -s $phone shell am broadcast -n com.anezium.rokidbus.phone/.PhoneProbeBroadcastReceiver -a com.anezium.rokidbus.phone.PROBE --es probe image-surface
```

This loads the bundled 480x480 JPEG in the phone hub and sends it through the
normal SPP frame, glasses validation/decode, and HUD renderer. The receiver is
present only in debug builds.

Compatibility details and reserved lifecycle payloads live in
[BUSSPEC.md](../BUSSPEC.md). [`plugins/sample`](../plugins/sample) is the
canonical headless template: package `com.anezium.rokidbus.plugin.sample`,
`minSdk 31`, a headless manifest, and a NexusUi/BusTheme settings screen with
the required uninstall row.

This project is licensed under the [Apache License 2.0](../LICENSE).
