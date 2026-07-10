# Rokid Nexus

Rokid Nexus is a phone-and-glasses shell for installable phone plugin APKs. One
glasses hub owns the Rokid transport and renders declarative surfaces; plugins
stay isolated in their own Android processes and appear only after explicit user
approval.

## Modules

- `shared`: wire envelopes, paths, descriptors, capabilities, and route rules.
- `bus-client`: public Android SDK with `NexusPluginService`, lifecycle callbacks,
  typed card/timed-lines surfaces, and explicit hub targeting.
- `plugin-sample`: copyable Hello plugin APK using only the public SDK.
- `phone-hub`: discovery, consent, identity enforcement, catalog, and Rokid link.
- `glasses-hub`: the single HUD renderer/launcher anchor.
- `plugin-lyrics`, `plugin-media`, and `plugin-transit`: feature modules being
  migrated from temporary built-in adapters to independent APKs.
- `phone-client-probe`, `glasses-client-probe`, and `lens-glasses`: validation and
  advanced integration modules.

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

See [Plugin SDK](docs/PLUGIN_SDK.md), [protocol guide](docs/PROTOCOL.md),
[wire specification](BUSSPEC.md), [product vision](VISION.md), and
[verification matrix](TESTPLAN.md).

No public SDK release is claimed by this repository state. A repository license
must be selected by the maintainer before public distribution; no license is
included yet.
