# Building a Nexus plugin

Nexus plugins are **built-in library modules compiled into the phone hub** —
not standalone apps. A plugin adds no launcher icon, no theme, and no APK of
its own: its logic lives in a `plugin-*` Gradle module, and its settings screen
lives in `phone-hub` on the shared design kit. Lyrics, Media Deck, Lens, Feeds,
and Transit all follow this model.

(A second, external-APK path exists behind `com.anezium.rokidbus.action.PLUGIN`
for the Store experiment — see [PLUGIN_SDK.md](PLUGIN_SDK.md) and
`plugin-sample`. Do not use it for anything that ships with the app.)

## 1. Module

Create `plugin-<id>/` as an Android **library** and register it:

```kotlin
// plugin-<id>/build.gradle.kts
plugins { id("com.android.library") }
android {
    namespace = "com.anezium.rokidbus.plugin.<id>"
    compileSdk = 36
    defaultConfig { minSdk = 31 }
}
dependencies { implementation(project(":shared")) }
```

```kotlin
// settings.gradle.kts          // phone-hub/build.gradle.kts
include(":plugin-<id>")         implementation(project(":plugin-<id>"))
```

The manifest stays near-empty (see `plugin-lyrics` — `<application/>` only).
Components the merged APK genuinely needs (a foreground service, for example)
may be declared here and will be merged into phone-hub's manifest, as
`plugin-feeds` does for its WebView host service. `uses-permission` entries
merge too. Never declare: a LAUNCHER activity, an application icon/label/theme,
styles, or XML layouts.

## 2. Plugin class

Implement `NexusPlugin` (`shared/.../plugin/NexusPlugin.kt`). Keep the domain
logic in a runtime class that talks to a small host interface of its own, and
make the plugin an adapter from that interface onto `NexusPluginHost` — see
`FeedsPlugin` (simple) and `TransitPlugin` (host callback for foreground
location) for the shape.

```kotlin
class HelloPlugin : NexusPlugin {
    override val id = "hello"                  // [a-z][a-z0-9._-]{2,63}
    override val displayName = "Hello"
    override val handlesBack = true            // plugin consumes BACK itself

    private lateinit var host: NexusPluginHost

    override fun onRegister(host: NexusPluginHost) {
        this.host = host                       // host.context, send, subscribe, post, log
        host.subscribe("/plugin/hello") { path, id, payload -> /* bus messages */ }
    }
    override fun onOpen() { /* user opened the surface from the glasses launcher */ }
    override fun onClose() { /* surface dismissed — stop work */ }
    override fun onInput(event: NexusInputEvent) { /* touchpad keys while focused */ }
}
```

HUD output goes through the bus surface paths with `surfaceId = id`:
`BusPaths.SURFACE_SHOW` / `SURFACE_UPDATE` / `SURFACE_HIDE`, payload
`{surfaceId, kind: "card", title, lines, footer, contentKey, handlesBack}`.
Card limits are enforced (`bus-client/.../SurfaceModels.kt`): ≤64 lines,
line ≤240 chars, title ≤120, footer ≤240, **contentKey ≤128 — hash it, never
concatenate content into it**.

## 3. Register it in the hub

Add the plugin to `phone-hub/.../PhoneBuiltInPlugins.kt`, in **both** places:

- `create()` — the runtime instance list;
- `specs()` — the catalog entry: id, display name, `launchable` (true if the
  glasses launcher should list it), and its settings activity class name.

Built-ins do not go through the external grant flow; `PluginCatalog` shows
them as `BUILT_IN` and they win any id collision with a stale external APK.

## 4. Settings screen

The screen is a plain `Activity` in **phone-hub** (package
`com.anezium.rokidbus.phone`), declared non-exported in phone-hub's manifest:

```xml
<activity android:name=".HelloSettingsActivity"
    android:exported="false"
    android:parentActivityName=".MainActivity"
    android:windowSoftInputMode="adjustResize" />  <!-- if it has inputs -->
```

**Kit rules — this is what keeps the app coherent:**

- Build every view in code from `NexusUi` (+ `BusTheme.gap`). No XML layouts,
  no themes, no hand-rolled `GradientDrawable`, colors, or dp math in the
  activity. If a component is genuinely missing, add one generic helper to
  `NexusUi` following its conventions — never style inline.
- The palette and type are fixed ("Phosphor × Mono"): `NexusUi.BG/PANEL/CARD`
  layers, `LINE` hairlines, `INK`→`INK4` text ladder, one `GREEN` accent
  (`AMBER`/`DANGER` sparingly), system sans for names/body, monospace for
  caps/meta labels.
- Every plugin screen uses the same shell:

```kotlin
override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    window.statusBarColor = NexusUi.BG
    window.navigationBarColor = NexusUi.BG
    val content = NexusUi.contentColumn(this).apply {
        addView(NexusUi.cardBody(this@HelloSettingsActivity, "One-line intro."), NexusUi.block())
        addView(BusTheme.gap(this@HelloSettingsActivity, 18))
        addView(NexusUi.sectionRow(this@HelloSettingsActivity, "Section"), NexusUi.block())
        // cards, rows, fields, pill buttons...
    }
    val root = NexusUi.fixedRoot(this).apply {          // fixedRoot handles system-bar insets
        addView(
            NexusUi.pluginHeader(this@HelloSettingsActivity,
                R.drawable.ic_plugin_hello, "Hello", "One-liner · v1.0"),
            NexusUi.block(),
        )
        addView(
            NexusUi.screen(this@HelloSettingsActivity, content),
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f),
        )
    }
    setContentView(root)
}
```

Component vocabulary: `sectionRow`/`sectionLabel` (mono caps headings),
`card`/`pressableCard`, `rowTitle`/`rowSub`/`rowLabel`/`rowValue`,
`pillButton`/`outlinePillButton`/`textButton` (`danger = true` for red),
`field` (text input), `dot`/`setDotColor` (status), `divider`, `metaLabel`,
`cardBody` (body copy/notes), `iconTileImage` (plugin mark, vector drawable in
`phone-hub/res/drawable/ic_plugin_*.xml`).

## 5. State, permissions, hardware

- SharedPreferences live in the hub process; name them `nexus_plugin_<id>`.
- Runtime permissions are requested from the plugin's settings screen (see
  `TransitSettingsActivity` for the location flow). If the plugin needs a
  foreground-service type (location, etc.), it must be declared on
  `BusHubService` in phone-hub's manifest and enabled dynamically — see
  `setTransitLocationForeground` in `BusHubService`.
- The WebView cookie jar is shared across the whole hub — scope any cookie
  clearing to your own domains (see `FeedsSettingsActivity.expireXCookies`).

## 6. Checklist

1. Library module, near-empty manifest, `:shared` dependency, in
   `settings.gradle.kts` + phone-hub deps.
2. `NexusPlugin` implementation + runtime, unit-tested.
3. Registered in `PhoneBuiltInPlugins.create()` and `specs()`.
4. Settings activity in phone-hub: kit-only, `pluginHeader` + `fixedRoot`
   shell, declared non-exported in the manifest.
5. `./gradlew :phone-hub:assembleDebug :phone-hub:testDebugUnitTest
   :plugin-<id>:testDebugUnitTest` green; screens verified on a phone.
