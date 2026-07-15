# Building a Nexus plugin

A Nexus plugin is a **headless phone APK**: it installs and uninstalls like an
app, but it has no launcher icon, no visible identity outside Rokid Nexus, and
its settings screen renders with the same design kit as the host app. The hub
discovers it, the user approves its capabilities once, and from then on it
lives entirely inside Nexus — launched from the glasses launcher, configured
from the phone hub, removable from its own settings screen or the Store.

Feeds, Transit, Lyrics, Media Deck, and Lens all ship this way as external
headless APKs. The phone hub registry has no built-in plugins.

The wire/SDK contract (artifact coordinates, service base class, payload
limits, approval flow) is specified in [PLUGIN_SDK.md](PLUGIN_SDK.md); this
guide covers how to structure and skin a plugin so it feels native.
For the complete self-contained plugin contract — endpoints, limits,
lifecycle, and publishing — see [`plugins/AGENTS.md`](../plugins/AGENTS.md).

## 1. Module

An ordinary application module. The bus-client AAR supports `minSdk 26`; the
repository plugin-template convention used by Sample and Transit is
`minSdk 31`:

```kotlin
// plugins/<id>/build.gradle.kts — plugin modules live under plugins/,
// each with a README.md and CHANGELOG.md (see plugins/README.md)
plugins { id("com.android.application") }
android {
    namespace = "com.anezium.rokidbus.plugin.<id>"
    compileSdk = 36
    defaultConfig {
        applicationId = "com.anezium.rokidbus.plugin.<id>"
        minSdk = 31
        targetSdk = 36
        versionCode = 1
        versionName = "1.0.0"
    }
}
dependencies { implementation(project(":bus-client")) }   // SDK: bus client + NexusUi kit
```

## 2. Manifest — the headless contract

```xml
<manifest xmlns:android="http://schemas.android.com/apk/res/android">
    <!-- only what the plugin really needs -->
    <uses-permission android:name="android.permission.INTERNET" />
    <uses-permission android:name="android.permission.FOREGROUND_SERVICE" />
    <uses-permission android:name="android.permission.FOREGROUND_SERVICE_SPECIAL_USE" />
    <!-- required for the in-app Uninstall row to fire the system dialog -->
    <uses-permission android:name="android.permission.REQUEST_DELETE_PACKAGES" />

    <queries>
        <intent><action android:name="com.anezium.rokidbus.action.HUB" /></intent>
    </queries>

    <application
        android:allowBackup="false"
        android:icon="@drawable/ic_launcher"    <!-- system app list only -->
        android:label="@string/app_name"
        android:theme="@style/Theme.MyPlugin">  <!-- plain dark NoActionBar; the kit paints everything -->

        <!-- Settings: exported so the hub can open it by explicit component.
             NO intent-filter — that is what keeps the plugin out of launchers. -->
        <activity
            android:name=".MySettingsActivity"
            android:exported="true"
            android:windowSoftInputMode="adjustResize" />

        <service
            android:name=".MyPluginService"
            android:exported="true"
            android:foregroundServiceType="specialUse">
            <property
                android:name="android.app.PROPERTY_SPECIAL_USE_FGS_SUBTYPE"
                android:value="glasses-session" />
            <intent-filter>
                <action android:name="com.anezium.rokidbus.action.PLUGIN" />
            </intent-filter>
            <meta-data android:name="com.anezium.rokidbus.plugin.ID" android:value="myid" />
            <meta-data android:name="com.anezium.rokidbus.plugin.DISPLAY_NAME" android:value="My Plugin" />
            <meta-data android:name="com.anezium.rokidbus.plugin.API_VERSION" android:value="3" />
            <meta-data android:name="com.anezium.rokidbus.plugin.CAPABILITIES" android:value="surfaces" />
            <meta-data android:name="com.anezium.rokidbus.plugin.RECEIVE_PREFIXES" android:value="/plugin/myid,/system/plugin" />
            <meta-data android:name="com.anezium.rokidbus.plugin.SETTINGS_ACTIVITY" android:value=".MySettingsActivity" />
            <meta-data android:name="com.anezium.rokidbus.plugin.LAUNCHABLE" android:value="true" />
        </service>
    </application>
</manifest>
```

**Absolute rule: no `MAIN`/`LAUNCHER` intent-filter anywhere.** A plugin that
puts an icon in the launcher is not a plugin, it is an app. The application
icon/label exist only for the system app list and the uninstall dialog.

## 3. Service and runtime

Extend `NexusPluginService` (bus-client) and keep the domain logic in a
runtime class that talks to a small host interface; the service is only an
adapter. See `FeedsPluginService`/`FeedsRuntime` (simple) and
`TransitPluginService`/`TransitRuntime` (foreground location, bus messages)
for the shape. Lifecycle: `onNexusOpen` (surface opened from the glasses
launcher), `onNexusClose`, `onNexusInput` (touchpad), `onNexusMessage`
(RECEIVE_PREFIXES traffic), `onNexusRegistrationState`.

HUD cards go through `nexusSurfaceSession(id).showCard/updateCard` with the
limits from `SurfaceModels.kt` — in particular **contentKey ≤ 128 chars: hash
it, never concatenate content into it**.

If the plugin needs an additional foreground-service type (location sampling,
background WebView), it declares the type and permissions in its own manifest
and re-promotes the SDK-managed session foreground service with that type —
see `TransitPluginService.startLocationForeground`. The SDK constructs the
required session notification object; the plugin does not post a separate one.

### Background policy

A plugin is dormant unless its surface is open. Closed plugins must not keep
engines, fetching, bindings, or pushes running. The hub normally opens the
plugin from the glasses launcher, but arbitration also contains a proactive
surface: a `show` while the HUD is idle adopts its sender as foreground and
delivers a real `PLUGIN_OPEN`; a `show` or `update` while another plugin owns
the HUD returns `SURFACE_BUSY`. Give up quietly rather than retry-looping.
Android may keep an enabled notification-listener component alive, but that
listener must remain idle until the plugin is open. While open, the SDK
promotes the plugin service to a special-use foreground service so OEM app
freezers leave it alone; when closed, it returns the plugin to dormant state.

The SDK always constructs the notification object required for the session
foreground service. Do **not** declare or request `POST_NOTIFICATIONS`: on
Android 13+ the SDK notification stays suppressed, and the Rokid Nexus hub
notification — which names the plugin live on the glasses — remains the only
user-visible one. Plugins must not post any additional notification.

## 4. Settings screen — the design kit

The screen is a plain code-built `Activity` using **only** `NexusUi` +
`BusTheme` from bus-client (`com.anezium.rokidbus.client.ui`). No XML layouts,
no hand-rolled `GradientDrawable`/colors/dp math. If a component is missing,
add one generic helper to `NexusUi` following its conventions.

The palette and type are fixed ("Phosphor × Mono"): `NexusUi.BG/PANEL/CARD`
layers, `LINE` hairlines, the `INK`→`INK4` text ladder, one `GREEN` accent
(`AMBER`/`DANGER` sparingly), system sans for names/body, monospace for
caps/meta. Component vocabulary: `sectionRow`, `card`/`pressableCard`,
`rowTitle`/`rowSub`/`rowLabel`/`rowValue`, `pillButton`/`outlinePillButton`/
`textButton` (`danger = true`), `field`, `dot`, `divider`, `metaLabel`,
`cardBody`, `iconTileImage` (plugin marks live in bus-client:
`com.anezium.rokidbus.client.R.drawable.ic_plugin_*`).

Every plugin screen uses the same shell:

```kotlin
override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    window.statusBarColor = NexusUi.BG
    window.navigationBarColor = NexusUi.BG
    val content = NexusUi.contentColumn(this).apply {
        addView(NexusUi.cardBody(this@MySettingsActivity, "One-line intro."), NexusUi.block())
        addView(BusTheme.gap(this@MySettingsActivity, 18))
        // sections, cards, rows, fields, pill buttons...
    }
    val root = NexusUi.fixedRoot(this).apply {            // fixedRoot handles system-bar insets
        addView(
            NexusUi.pluginHeader(this@MySettingsActivity,
                BusClientR.drawable.ic_plugin_send, "My Plugin", "One-liner · v1.0"),
            NexusUi.block(),
        )
        addView(
            NexusUi.screen(this@MySettingsActivity, content),
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f),
        )
    }
    setContentView(root)
}
```

End the screen with a "Plugin" section containing the canonical
`NexusUi.uninstallCard` — plugins must always be removable from where the
user configures them, and every plugin uses the exact same card:

```kotlin
NexusUi.uninstallCard(this, "My Plugin") {
    startActivity(Intent(Intent.ACTION_DELETE, Uri.parse("package:$packageName")))
}
```

## 5. Install, approval, state

- Installing the APK grants nothing: the hub discovers it and the user
  approves the requested capabilities in **Rokid Nexus → Settings → Plugin
  access** (or the Store flow). Pending/denied/disabled plugins are not
  launchable.
- SharedPreferences live in the plugin's own package; name the main file
  `nexus_plugin_<id>`.
- Uninstalling removes the plugin and all its state; the hub's grant becomes
  stale and harmless.

## 6. Checklist

1. Application module, headless manifest (no LAUNCHER), `:bus-client` dep.
2. `NexusPluginService` shell + runtime class, unit-tested.
3. Settings activity on the kit: `pluginHeader` + `fixedRoot` shell, an
   Uninstall row, declared exported without intent-filter, wired via the
   `SETTINGS_ACTIVITY` meta-data.
4. `./gradlew :plugin-<id>:assembleDebug :plugin-<id>:testDebugUnitTest` green.
5. On-device: install, approve in Plugin access, launch from the glasses,
   confirm no launcher icon appeared, confirm the uninstall dialog works.
