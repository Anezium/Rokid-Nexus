# Nexus plugins

Every Rokid Nexus plugin is a **headless phone APK**: it installs like an app,
has no launcher icon, renders its settings with the shared NexusUi kit, and
lives entirely inside Nexus — launched from the glasses launcher, configured
from the phone hub, removable from its own settings screen. How to build one
is documented in [docs/PLUGINS.md](../docs/PLUGINS.md) (structure + design
kit) and [docs/PLUGIN_SDK.md](../docs/PLUGIN_SDK.md) (SDK reference).

Each plugin owns one folder here with its module sources, a `README.md`
explaining what it does and how it is built, and a `CHANGELOG.md` tracking its
releases. The Gradle project names keep the historical `:plugin-<id>` form
(mapped in `settings.gradle.kts`).

## Catalogue

| Plugin | Id | What it does |
|---|---|---|
| [Feeds](../plugin-feeds/) | `feeds` | Bluesky and X timelines on the HUD |
| [Transit](transit/) | `transit` | Nearby stops, departures, and favourites (Île-de-France) |
| [Lyrics](lyrics/) | `lyrics` | Live synced lyrics for whatever is playing |
| [Media Deck](media/) | `media` | Universal now-playing surface with transport controls |
| [Sample](sample/) | `hello` | Minimal copyable reference plugin |

Feeds still sits at the repository root until the in-flight feeds branch
lands; it moves here afterwards. Lens is the last remaining in-hub built-in —
it needs a glasses-side companion API before it can become an APK.

## Releases

Plugins release from this repository as GitHub releases with **namespaced
tags**, one stream per plugin, separate from the app's `v*` releases:

- Tag `lyrics-v1.1.0` → release `Lyrics 1.1.0` carrying
  `lyrics-phone-release.apk`, created with `--latest=false` so the app's
  `releases/latest` pointer is never disturbed.
- The release notes are the matching section of the plugin's `CHANGELOG.md`.
- The RokidBrew registry ingests the release (`--kind nexus-plugin`) and the
  Nexus Store serves it from `dist/nexus-plugins.v1.json`.

Before pushing a plugin tag, set that module's `versionName` and add the
matching `## <version>` changelog section; release CI rejects either mismatch.
