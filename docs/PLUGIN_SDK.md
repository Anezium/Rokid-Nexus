# Nexus Android plugin SDK (external APKs)

> **Plugins that ship with the app are built-in library modules, not external
> APKs — read [PLUGINS.md](PLUGINS.md) first.** This document covers the
> external-APK path only (the Store experiment and `plugin-sample`).

The SDK artifact is `com.github.Anezium.Rokid-Nexus:bus-client`. The current
repository can publish `0.1.0-SNAPSHOT` locally; this is not a public release.
The `shared` artifact is resolved transitively.

## 1. Add the dependency

```kotlin
repositories { mavenLocal() }

dependencies {
    implementation("com.github.Anezium.Rokid-Nexus:bus-client:0.1.0-SNAPSHOT")
}
```

Use `compileSdk = 36` and `minSdk >= 26`. The repository builds with JDK 17.

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
`http_proxy`, and `microphone`; microphone currently returns
`CAPABILITY_NOT_AVAILABLE` until the required HUD indicator exists.

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
`IMAGE_RATE_LIMITED`; plugins should not build animation loops around v1.

## 4. Approve and debug

After installing the APK, open **Rokid Nexus → Settings → Plugin access**. Review
the requested capabilities and approve only those needed. Pending, denied,
disabled, invalid, and missing-capability plugins are not launchable.

For local software validation:

```powershell
.\gradlew.bat :plugin-sample:testDebugUnitTest :plugin-sample:assembleDebug
```

The phone UI exposes package, signer, API, and route details only in developer
mode. Logs and bug reports must redact device identifiers, signing digests,
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
[BUSSPEC.md](../BUSSPEC.md). The complete copyable implementation is in
[`plugin-sample`](../plugin-sample).

A maintainer must choose a repository license before public distribution. No
license or public artifact release is implied by these coordinates.
