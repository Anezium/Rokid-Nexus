# Rokid Nexus

Rokid Nexus is a phone-and-glasses shell for installable phone plugin APKs. One
glasses hub owns the Rokid transport and renders declarative surfaces; plugins
stay isolated in their own Android processes and appear only after explicit user
approval.

## Modules

- `shared`: wire envelopes, paths, descriptors, capabilities, and route rules.
- `bus-client`: public Android SDK with `NexusPluginService`, lifecycle callbacks,
  typed card/timed-lines/media/image surfaces, the NexusUi design kit, and
  explicit hub targeting.
- `phone-hub`: discovery, consent, identity enforcement, the Nexus Store backed
  by the public RokidBrew registry feed, app self-update, and the Rokid link.
- `glasses-hub`: the single HUD renderer/launcher anchor, plus the no-PC
  self-arm onboarding.
- `plugins/` and `plugin-feeds/`: the plugin APKs — Feeds, Lens, Transit,
  Lyrics, Media Deck, and the copyable Sample — one folder per plugin with its
  README and CHANGELOG (see [plugins/README.md](plugins/README.md)). All
  plugins are external headless APKs; the hubs ship none built in.
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

Normal users see plugin names and requested access. Developer details additionally
show package, signer, protocol, and route diagnostics. Installation alone never
grants a capability.

Distribution is self-contained: plugins install and update from the Nexus Store
(public [RokidBrew-Registry](https://github.com/Anezium/RokidBrew-Registry)
feed, with SHA-256 and signer pinning enforced before install), and the apps
update themselves — the phone from this repository's GitHub releases, the
glasses over the Rokid CXR link. First-run onboarding walks through seven phone
steps and a two-card glasses flow that arms the accessibility service without a
PC.

See [Plugin SDK](docs/PLUGIN_SDK.md), [protocol guide](docs/PROTOCOL.md),
[wire specification](BUSSPEC.md), [product vision](VISION.md), and
[verification matrix](TESTPLAN.md).

This project is licensed under the [Apache License 2.0](LICENSE).
