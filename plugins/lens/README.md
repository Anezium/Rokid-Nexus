# Lens plugin

Lens is the headless external camera consumer for Rokid Nexus. During a glasses
camera session it joins the hub-owned Wi-Fi Direct link, decodes H.264, runs
phone-side OCR and translation, and publishes structured live/frozen overlays.

It has no launcher activity and no launcher tile of its own. Once approved and
ready, the phone hub raises `CAMERA_CONSUMER_READY` with the consumer display
name, and the glasses launcher synthesizes a camera entry labeled with that
name ("Lens"); without a ready consumer the entry does not exist. Configure it
in Nexus, approve `camera`, and grant the nearby-device permission. Session
close destroys all engines, sockets, and Wi-Fi Direct state.

The glasses hub owns the camera Wi-Fi lifecycle: on session open it enables
Wi-Fi through the self-arm command bridge (silent signed shell channel), falls
back to the accessibility service when the bridge is unavailable, and disables
Wi-Fi again 40 seconds after the session closes if the hub turned it on.

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
