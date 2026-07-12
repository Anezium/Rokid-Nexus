# Building a Nexus plugin

A Nexus plugin is a **headless phone APK**: it installs and uninstalls like an
app, but it has no launcher icon, no visible identity outside Rokid Nexus, and
its settings screen renders with the same design kit as the host app. The hub
discovers it, the user approves its capabilities once, and from then on it
lives entirely inside Nexus — launched from the glasses launcher, configured
from the phone hub, removable from its own settings screen or the Store.

Feeds, Transit, Lyrics, and Media Deck ship this way. Lens is still compiled
into the hub as a legacy built-in and will migrate to this model.

The wire/SDK contract (artifact coordinates, service base class, payload
limits, approval flow) is specified in [PLUGIN_SDK.md](PLUGIN_SDK.md); this
guide covers how to structure and skin a plugin so it feels native.

## 1. Module

An ordinary application module:

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

        <service android:name=".MyPluginService" android:exported="true">
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

If the plugin needs a foreground service (location sampling, background
WebView), it declares the type and permissions in its own manifest and posts
its own notification — see `TransitPluginService.startLocationForeground`.

### Background policy

A plugin is dormant unless its surface is open. The hub initiates plugin
work: closed plugins must not keep engines, fetching, bindings, or pushes
running, and must never initiate a surface themselves. Android may keep an
enabled notification-listener component alive, but that listener must remain
idle until the hub opens the plugin.

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

End the screen with a "Plugin" section containing an **Uninstall** row that
fires the system dialog — plugins must always be removable from where the
user configures them:

```kotlin
startActivity(Intent(Intent.ACTION_DELETE, Uri.parse("package:$packageName")))
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
