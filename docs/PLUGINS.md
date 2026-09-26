# Building a Nexus plugin

A Nexus plugin is a **headless phone APK**: it installs and uninstalls like an
app, but it has no launcher icon, no visible identity outside Rokid Nexus, and
its settings screen renders with the same design kit as the host app. The hub
discovers it, the user approves its capabilities once, and from then on it
lives entirely inside Nexus — launched from the glasses launcher, configured
from the phone hub, removable from its own settings screen or the Store.

Assistant, Relay, Navigation, Feeds, Transit, Lyrics, Media Deck, Lens,
Photos Sync, Wireless ADB, and Tasker all ship this way as external headless APKs, and so do
the plugins written outside this repository. The phone hub registry has no
built-in plugins.

The wire/SDK contract (artifact coordinates, service base class, payload
limits, approval flow) is specified in [PLUGIN_SDK.md](PLUGIN_SDK.md); this
guide covers how to structure and skin a plugin so it feels native.
For the complete self-contained plugin contract — endpoints, limits,
lifecycle, and publishing — see [`plugins/AGENTS.md`](../plugins/AGENTS.md).

## 1. Module

An ordinary application module. The bus-client AAR supports `minSdk 26`; the
repository plugin-template convention used by Sample and Transit is
`minSdk 30` (Android 11), matching the phone hub:

```kotlin
// plugins/<id>/build.gradle.kts — plugin modules live under plugins/,
// each with a README.md and CHANGELOG.md (see plugins/README.md)
plugins { id("com.android.application") }
android {
    namespace = "com.anezium.rokidbus.plugin.<id>"
    compileSdk = 36
    defaultConfig {
        applicationId = "com.anezium.rokidbus.plugin.<id>"
        minSdk = 30
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
            <meta-data android:name="com.anezium.rokidbus.plugin.ICON" android:value="star" />
            <!-- Optional string-array of name|pathData custom HUD glyphs. -->
            <meta-data android:name="com.anezium.rokidbus.plugin.GLYPHS" android:resource="@array/nexus_glyphs" />
            <meta-data android:name="com.anezium.rokidbus.plugin.API_VERSION" android:value="3" />
            <meta-data android:name="com.anezium.rokidbus.plugin.CAPABILITIES" android:value="surfaces,stt" />
            <meta-data android:name="com.anezium.rokidbus.plugin.RECEIVE_PREFIXES" android:value="/plugin/myid,/system/plugin,/stt" />
            <meta-data android:name="com.anezium.rokidbus.plugin.SETTINGS_ACTIVITY" android:value=".MySettingsActivity" />
            <meta-data android:name="com.anezium.rokidbus.plugin.LAUNCHABLE" android:value="true" />
        </service>
    </application>
</manifest>
```

**Absolute rule: no `MAIN`/`LAUNCHER` intent-filter anywhere.** A plugin that
puts an icon in the launcher is not a plugin, it is an app. The application
icon/label exist only for the system app list and the uninstall dialog.

### Plugin icon

Prefer the built-in `ICON` metadata shown above. Supported keys are `music`,
`disc`, `bus`, `lens`, `mic`, `send`, `feed`, `weather`, `chat`, `calendar`,
`clock`, `star`, `heart`, `game`, `globe`, `bell`, `terminal`, `grid`, `map`,
`bolt`, and `bookmark`.

For an identity that does not fit that set, use a custom name for `ICON`,
declare the same name in `META_PLUGIN_GLYPHS`, and keep a drawable in the
plugin package for phone-side rendering. Photos Sync is the complete pattern:

```xml
<!-- res/values/nexus_glyphs.xml -->
<string-array name="nexus_glyphs">
    <item>photosync|M2,9 A2,2 0 0 1 4,7 L7.2,7 L8.5,4.8 L13.5,4.8 L14.8,7 L18,7 A2,2 0 0 1 20,9 L20,17 A2,2 0 0 1 18,19 L4,19 A2,2 0 0 1 2,17 Z M7.8,13 A3.2,3.2 0 1 0 14.2,13 A3.2,3.2 0 1 0 7.8,13 M18.6,5.6 L18.6,1.6 M18.6,1.6 L16.4,3.8 M18.6,1.6 L20.8,3.8</item>
</string-array>

<!-- AndroidManifest.xml, on the plugin service -->
<meta-data
    android:name="com.anezium.rokidbus.plugin.ICON"
    android:value="photosync" />
<meta-data
    android:name="com.anezium.rokidbus.plugin.ICON_DRAWABLE"
    android:resource="@drawable/nexus_glyph_photosync" />
<meta-data
    android:name="com.anezium.rokidbus.plugin.GLYPHS"
    android:resource="@array/nexus_glyphs" />
```

Follow the design system in [GLYPHS.md](GLYPHS.md): a 24×24 `VectorDrawable`,
stroked at 1.7, in `#FF4DFF8C` and no other colour. Every render path tints
what it draws, so the source colour is moot — authoring it in the phosphor
anyway keeps one rule instead of two, and `NexusGlyphArtTest` checks it. A
full-colour logo will render as a green blob.

The phone resolves an unknown `ICON` key through `ICON_DRAWABLE`, so adding the
custom name does not change its appearance. The glasses resolve a recognized
built-in first, then a custom glyph with the same name under that plugin ID,
then the legacy plugin table/grid fallback. Custom arrays are capped at 8
glyphs and 1024 path characters per glyph; malformed or unavailable resources
are ignored without removing the plugin from the launcher.

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

Rich authored pages go through `nexusInkSurfaceSession(id).show/update/hide`
and require the separate `ink_surface` descriptor grant. Ink is a strict,
inert AIUI-compatible subset compiled by the phone and rendered as native Views
on the glasses; it shares foreground ownership with cards. Check the live
`nexusClient?.supportsInkSurface` flag and keep a structured-card fallback.
`plugins/sample` is the canonical page/action/data-patch example, and the exact
support matrix and budgets live in [PLUGIN_SDK.md](PLUGIN_SDK.md#ink-surfaces).

If the plugin needs an additional foreground-service type, there are two
shipped shapes. For a type the session service itself can carry (location
sampling), declare the type and permissions in your manifest and re-promote
the SDK-managed session foreground service with it — see
`TransitPluginService.startLocationForeground`; the SDK constructs the session
notification, the plugin does not post one. For a capability Android forces
into its own service (Feeds' overlay WebView host `XWebViewHostService`), run
a dedicated foreground service with its own minimal notification — it must
start with your surface and tear down with it (see the notification exception
in [plugins/AGENTS.md](../plugins/AGENTS.md) §1).

### Background policy

A plugin is normally dormant unless its surface is open. Final-closed plugins must not keep
engines, fetching, bindings, or pushes running. The hub normally opens the
plugin from the glasses launcher, but arbitration also contains a proactive
surface: a `show` while the HUD is idle adopts its sender as foreground and
delivers a real `PLUGIN_OPEN`; a `show` or `update` while another plugin owns
the HUD returns `SURFACE_BUSY`. Give up quietly rather than retry-looping.
Android may keep an enabled notification-listener component alive, but that
listener must remain idle until the plugin is open. While open, the SDK
promotes the plugin service to a special-use foreground service so OEM app
freezers leave it alone; when closed, it returns the plugin to dormant state.

**Pins are the first exception**, and they are deliberately narrow. A dormant
plugin may wake on a phone-side event, connect, send a single `/pin/show` or
`/pin/hide`, and go straight back to dormant — that is the shape the pin was
designed for (a ride-hailing plugin putting the arriving car in the corner).
The exception buys you one push, not a foothold: no surface is opened, nothing
is adopted as foreground, and you must not keep the connection, an engine, or a
polling loop alive between pushes. The pin outlives your process on its own,
and `ttlMs` bounds it if you never come back. Anything that needs to keep
running still needs an open surface or the active audio lease described below.

**Scheduled delivery is the second**, and it is narrower still: a plugin may
wake at a moment the wearer explicitly scheduled through it — an alarm,
reminder or timer they asked for by name — to deliver exactly that item. The
Assistant's reminders are the precedent. The wake is an `AlarmManager`
broadcast into a short-lived foreground service that posts the matching
notification, optionally raises one notice or pin through a one-shot
registration, and stops itself within seconds; a boot receiver may do nothing
beyond rescheduling those same user-created alarms. "The user would probably
want it" does not qualify — only an item they created with a delivery time.

**Lease-bounded audio is the third** (SDK 0.18.0, hubs 1.4.10 or newer). After
`onAudioStarted`, an approved microphone plugin can call `surface.detach()` to
close its last ordinary surface while retaining its active audio session and
SDK foreground service. `onNexusBackground()` releases the UI; reopening sends
`onNexusOpen("resume")`. The phone row provides Stop, and lease release,
revocation, link loss, or process death ends the background session. This is not
a general background-work permission. A pin can indicate listening without
keeping the display awake; use a short TTL renewed by audio frames so it expires
after a crash. See [the SDK example](PLUGIN_SDK.md#let-the-display-sleep-while-listening).

The SDK always constructs the notification object required for the session
foreground service. Do **not** declare or request `POST_NOTIFICATIONS`: on
Android 13+ the SDK notification stays suppressed, and the Rokid Nexus hub
notification — which names the plugin live on the glasses — remains the
primary user-visible one. The exception is scheduled delivery above: a plugin
that rings user-created reminders may declare `POST_NOTIFICATIONS`,
`SCHEDULE_EXACT_ALARM` and `RECEIVE_BOOT_COMPLETED`, strictly for those
deliveries. Otherwise plugins must not post notifications beyond the
open-scoped dedicated-foreground-service exception described above.

Android runtime permissions used by the plugin itself are a separate consent
layer from Nexus Plugin access. Declare only the platform permissions the
feature needs, request them contextually from the settings screen, and keep the
feature disabled with an actionable explanation when consent is missing. Do
not add a Nexus capability or bus route for phone-local provider access:
Assistant's `READ_CALENDAR`/`WRITE_CALENDAR` Calendar Provider integration is
the precedent. Destructive actions must fail closed when the target is absent
or ambiguous.

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
`com.anezium.rokidbus.client.R.drawable.ic_plugin_*`), and `updateBanner`
(amber update strip with configurable `title`, `actionLabel`, and
`actionEnabled`).

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
- Updating a descriptor's requested capability set — adding `stt` or `tts` to a
  plugin that already had `surfaces`, say — returns the existing grant to
  Pending by design. The user must review and approve the new set before the
  plugin is launchable again.
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
