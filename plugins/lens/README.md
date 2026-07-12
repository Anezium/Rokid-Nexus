# Lens plugin

Lens is the headless external camera consumer for Rokid Nexus. During a glasses
camera session it joins the hub-owned Wi-Fi Direct link, decodes H.264, runs
phone-side OCR and translation, and publishes structured live/frozen overlays.

It has no launcher activity. Configure it in Nexus, approve `camera`, and grant
the nearby-device permission. Session close destroys all engines, sockets, and
Wi-Fi Direct state.

```powershell
.\gradlew.bat :plugin-lens:assembleDebug :plugin-lens:testDebugUnitTest
```
## Release steps

1. Set the module `versionName` and add the matching changelog section.
2. Push a namespaced tag in the form `lens-vX.Y.Z`; release CI produces
   `lens-phone-release.apk` after enforcing SemVer, `versionName`, and changelog
   consistency.
3. In the sibling RokidBrew-Registry repository, ingest the release with
   `--kind nexus-plugin`.
4. Set the registry descriptor's `minHostVersionCode` to the first Nexus host
   build that carries the generic camera contract.
