<p align="center">
  <img src="docs/assets/logo.png" width="180" alt="Rokid Nexus logo" />
</p>

<h1 align="center">Rokid Nexus</h1>

<p align="center"><b>Install a phone app, get a glasses app.</b></p>

<p align="center"><a href="https://rokid-nexus.anezium.me">rokid-nexus.anezium.me</a></p>

<p align="center">
  <a href="https://github.com/Anezium/Rokid-Nexus/releases"><img src="https://img.shields.io/github/v/release/Anezium/Rokid-Nexus?filter=v*&label=app&color=00c853" alt="App release" /></a>
  <a href="https://github.com/Anezium/Rokid-Nexus/releases?q=sdk"><img src="https://img.shields.io/github/v/release/Anezium/Rokid-Nexus?filter=sdk-v*&label=SDK&color=00c853" alt="SDK release" /></a>
  <img src="https://img.shields.io/badge/Kotlin-7F52FF?logo=kotlin&logoColor=white" alt="Kotlin" />
  <img src="https://img.shields.io/badge/Android%2011%2B-3DDC84?logo=android&logoColor=white" alt="Android 11+" />
  <img src="https://img.shields.io/badge/Rokid%20Glasses-0a0a0a" alt="Rokid Glasses" />
  <a href="LICENSE"><img src="https://img.shields.io/github/license/Anezium/Rokid-Nexus?color=blue" alt="License" /></a>
</p>

<p align="center">
  <a href="https://ko-fi.com/anezium" target="_blank">
    <img height="36" style="border:0px;height:36px;" src="https://storage.ko-fi.com/cdn/kofi4.png?v=6" border="0" alt="Buy Me a Coffee at ko-fi.com" />
  </a>
</p>

Rokid Nexus is a plugin platform for Rokid AR glasses: one permanent hub lives
on the glasses and renders everything; all features ship as ordinary Android
APKs on the phone. Nothing is ever installed on the glasses again.

Plugins stay isolated in their own processes, appear only after explicit user
approval, and draw on the HUD through declarative surfaces — cards, synced
timed lines, media decks, real images, and compiled interactive Ink pages. They
can also speak, listen, and see:
speech in and out, and the glasses camera, are platform capabilities the wearer
grants one at a time and can take back.

## Plugins

| Plugin | What it puts on the HUD |
|---|---|
| **[Assistant](plugins/assistant/)** | Hold the touchpad and ask out loud: your words appear as you speak them, then the answer arrives on the band, in your ear, or as a native Ink page with charts and interactive controls — and it can look through the glasses camera to tell you what you are seeing. Ask it to remind you, set a timer, or take a note, and it does: the phone rings at the hour and the glasses raise it. It can also add, read, and safely delete phone-calendar events. Runs on your own ChatGPT plan, or any AI provider you bring a key for — OpenAI, OpenRouter, MiniMax, DeepSeek, GLM, Hermes, or your own server |
| **[Relay](plugins/relay/)** | Phone messages as a band over whatever you were looking at, answered out loud — plus an inbox for the ones you let go |
| **[Lens](plugins/lens/)** | Google-Lens-style live translation: the glasses camera streams to the phone, ML Kit OCR + translation run there (offline), translated overlays come back in real time — plus a freeze mode for full-resolution stills |
| **[Feeds](plugin-feeds/)** | Bluesky and X timelines — browse posts, open threads, and view the actual photos full-screen |
| **[Transit](plugins/transit/)** | Nearby stops and live departures (Transitous/MOTIS), with favourites |
| **[Lyrics](plugins/lyrics/)** | Time-synced lyrics for whatever is playing on the phone, from Spotify/Musixmatch/Netease/LrcLib |
| **[Media Deck](plugins/media/)** | Universal now-playing surface with album art and transport controls |
| **[Photos Sync](plugins/photosync/)** | Not a HUD plugin: copies the photos and videos you shoot on the glasses into the phone gallery by itself, and gives you the switches for it |
| **[Wireless ADB](plugins/wireless-adb/)** | Enables Android's real wireless debugging service and creates a short-lived pairing command, so a trusted computer can connect to the glasses over the LAN without a cable or Settings automation |
| **[Tasker](plugins/tasker/)** | Your named Tasker tasks on the HUD — swipe, tap, and the phone runs the automation. The glasses are the remote, Tasker does the work |
| **[Sample](plugins/sample/)** | Minimal copyable reference plugin |

And two that are not in this repository at all, written by
[beyondlevi](https://github.com/beyondlevi) against the same SDK:

| Plugin | What it puts on the HUD |
|---|---|
| **[Lume](https://github.com/beyondlevi/lume-nexus)** | A wearable speed reader: text arrives one word at a time at a fixed point, 150–700 wpm, with long words and sentence endings held a little longer. Import a PDF or a text file on the phone, read hands-free with the R08 ring |
| **[Shopping List](https://github.com/beyondlevi/nexus-shoplist)** | Your list on the HUD, ticked off with the ring, while you add to it on the phone — or paste a whole list and have every line become an item |

They install and run exactly like the ones above, under the same grants and the
same identity checks. Nothing in the platform is reserved for first-party code.

All of them install from the in-app **Nexus Store**, backed by the public
[RokidBrew-Registry](https://github.com/Anezium/RokidBrew-Registry) feed with
SHA-256 and signer pinning enforced before every install, and show update
badges when a newer release is published.

The phone hub also controls software that is already installed on the glasses.
**Glasses apps** lists and opens launchable native APKs; **Keyboard & remote**
provides previous/next/select/back navigation and sends transient keyboard
deltas into the focused glasses editor. Password fields are marked sensitive,
the phone window becomes secure, and Nexus never mirrors the editor's existing
text. These are trusted hub-to-hub controls, not plugin capabilities, and they
do not install native APKs.

## Screenshots

### Phone app

<p align="center">
  <img src="docs/assets/shot-phone-home.png" width="270" alt="Phone home screen with the plugin list and update badges" />
  &nbsp;
  <img src="docs/assets/shot-store.png" width="270" alt="Nexus Store showing installable plugins and available updates" />
</p>

<p align="center"><i>The phone hub: your plugins, glasses-app status, and one-tap updates — and the Store they install from.</i></p>

### Glasses launcher

<p align="center">
  <img src="docs/assets/shot-glasses-launcher.png" width="270" alt="Nexus launcher on the glasses HUD, listing installed plugins" />
</p>

<p align="center"><i>Triple-tap the touchpad from anywhere to bring this up — the only gesture Nexus claims. Pick a plugin, hit back to return to whatever was underneath.</i></p>

### Plugins on the HUD

<p align="center">
  <img src="docs/assets/shot-relay-notice.png" width="270" alt="Relay notice band arriving over the glasses home screen, showing a two-message thread with the sender kept on every line and a Reply action" />
  &nbsp;
  <img src="docs/assets/shot-relay-inbox.png" width="270" alt="Relay inbox on the glasses HUD listing the conversations still waiting for a reply, newest first" />
</p>

<p align="center"><i>Relay — a message you can answer arrives as a band over whatever you were already looking at, sender kept on every line. &nbsp;·&nbsp; Anything you let go waits in Messages, newest first.</i></p>

<p align="center">
  <img src="docs/assets/shot-lens-live.png" width="270" alt="Lens live mode translating a screen of English text into French in real time" />
  &nbsp;
  <img src="docs/assets/shot-lens-freeze.png" width="270" alt="Lens freeze mode showing a full-resolution still with the translated result" />
</p>

<p align="center"><i>Lens — live translation streamed to the phone and back, plus a freeze mode for full-resolution stills.</i></p>

<p align="center">
  <img src="docs/assets/shot-feeds.png" width="270" alt="Feeds plugin showing an X timeline post on the glasses HUD" />
  &nbsp;
  <img src="docs/assets/shot-media.png" width="270" alt="Media Deck plugin showing album art, track, and progress" />
</p>

<p align="center"><i>Feeds — Bluesky and X timelines. &nbsp;·&nbsp; Media Deck — now playing with album art and transport.</i></p>

<p align="center">
  <img src="docs/assets/shot-transit.png" width="270" alt="Transit plugin showing nearby stops and live departure countdowns" />
  &nbsp;
  <img src="docs/assets/shot-lyrics.png" width="270" alt="Lyrics plugin showing the current line time-synced to the playing track" />
</p>

<p align="center"><i>Transit — nearby stops and live departures. &nbsp;·&nbsp; Lyrics — time-synced to whatever is playing.</i></p>

## Setup — a phone is all you need

1. Install the Nexus phone app from this repository's
   [releases](https://github.com/Anezium/Rokid-Nexus/releases).
2. Follow the onboarding: seven in-context steps, including pushing the
   glasses app over the Rokid link — no cable, no PC.
3. On the glasses, enable the accessibility service when asked. Nexus
   bootstraps the rest of its glasses-side setup by itself.
4. Install your first plugin from the Store.

Both apps keep themselves current afterwards: the phone updates from GitHub
releases, the glasses update over the Rokid link, plugins update through the
Store.

Trust model: any APK may request bus access, but capabilities (`surfaces`,
`ink_surface`, `http_proxy`, `microphone`, `stt`, `tts`, `camera`, `mediasync`,
`assistant`, `wireless_debugging`, `video_playback`) are granted per
plugin by the user, keyed to package + plugin id + signing certificate. Installation alone never grants
anything. Developer mode adds package, signer, protocol, and route diagnostics
plus a live bus inspector.

## Build a plugin

A plugin is a headless phone APK against the published SDK:

```kotlin
repositories { maven("https://jitpack.io") }

dependencies {
    implementation("com.github.Anezium.Rokid-Nexus:bus-client:sdk-v0.15.0")
}
```

Start with [plugins/AGENTS.md](plugins/AGENTS.md) — the complete,
self-contained plugin contract — then [docs/PLUGIN_SDK.md](docs/PLUGIN_SDK.md)
(SDK reference) and [docs/PLUGINS.md](docs/PLUGINS.md) (structure + design
kit). [plugins/sample](plugins/sample/) is the canonical template. Publishing
is F-Droid-like: host the APK on your own releases, PR a manifest to the
registry ([plugins/README.md](plugins/README.md)).

## Repository layout

- `shared`: wire envelopes, paths, descriptors, capabilities, and route rules.
- `bus-client`: the public Android SDK — `NexusPluginService`, lifecycle
  callbacks, typed card/timed-lines/media/image surfaces, notice bands and
  activities, compiled Ink sessions, speech in and out, the NexusUi design kit,
  and explicit hub targeting.
- `ink-engine`: the bounded `.ink` compiler, data-binding engine, revisioned
  render document/patch codec, and strict compatibility limits shared by both
  hubs and the public SDK.
- `phone-hub`: discovery, consent, identity enforcement, the Nexus Store, app
  self-update, trusted native-app/keyboard controls, and the Rokid link.
- `glasses-hub`: the single HUD renderer/launcher anchor, the camera platform,
  native Ink renderer, Nexus IME/navigation bridge, and no-PC self-arm onboarding.
- `plugins/` and `plugin-feeds/`: the plugin APKs, one folder per plugin with
  its README and CHANGELOG.
- `phone-client-probe` and `glasses-client-probe`: validation modules.

## Local build

Use JDK 17 and the checked-in Gradle wrapper:

```powershell
.\gradlew.bat test lintDebug assembleDebug
.\gradlew.bat :shared:publishToMavenLocal :bus-client:publishToMavenLocal '-PversionName=0.1.0-SNAPSHOT'
.\gradlew.bat :plugin-sample:assembleDebug '-PusePublishedSdk=true' '-PversionName=0.1.0-SNAPSHOT'
```

The local `CxrGlobal` composite is used only when its sibling directory exists.
SDK publication and the published-coordinate sample build do not require it.

## More

[Product vision](VISION.md) · [roadmap](ROADMAP.md) ·
[wire specification](BUSSPEC.md) · [protocol guide](docs/PROTOCOL.md) ·
[verification matrix](TESTPLAN.md)

This project is licensed under the [Apache License 2.0](LICENSE).
