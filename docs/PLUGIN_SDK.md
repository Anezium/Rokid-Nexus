# Nexus Android plugin SDK

> [PLUGINS.md](PLUGINS.md) is the full guide to building a plugin (module
> structure, the headless-manifest rules, and the NexusUi design kit). This
> document is the SDK reference: artifact coordinates, the service contract,
> and the approval flow.

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

Compatibility details and reserved lifecycle payloads live in
[BUSSPEC.md](../BUSSPEC.md). The complete copyable implementation is in
[`plugins/sample`](../plugins/sample).

A maintainer must choose a repository license before public distribution. No
license or public artifact release is implied by these coordinates.
