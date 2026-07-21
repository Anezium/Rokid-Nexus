# Lens plugin

Lens is the headless external camera consumer for Rokid Nexus. During a glasses
camera session it joins the camera link (Wi-Fi Direct, or a phone-hosted
hotspot when the phone's own Wi-Fi is off — see below), decodes H.264, runs
phone-side OCR and translation, and publishes structured live/frozen overlays.

It has no launcher activity and no launcher tile of its own. Once approved and
ready, the phone hub raises `CAMERA_CONSUMER_READY` with the consumer display
name, and the glasses launcher synthesizes a camera entry labeled with that
name ("Lens"); without a ready consumer the entry does not exist. Configure it
in Nexus, approve `camera`, and grant the nearby-device permission. Session
close destroys all engines, sockets, and Wi-Fi Direct (or hotspot) state.

The glasses hub owns the camera Wi-Fi lifecycle: on session open it enables
Wi-Fi through the self-arm command bridge (silent signed shell channel), falls
back to the accessibility service when the bridge is unavailable, and disables
Wi-Fi again 40 seconds after the session closes if the hub turned it on.

## Works with the phone's Wi-Fi off

A normal Android app cannot enable its own Wi-Fi (API 29+), so if the phone's
Wi-Fi is off when a session starts, it can't join the glasses' Wi-Fi Direct
group as a client. `PhoneLohsImageServer` (this plugin) handles that case: the
phone starts a `LocalOnlyHotspot` itself and sends a reverse offer over the
existing Bluetooth bus channel; the glasses enable their own Wi-Fi (same
self-arm bridge as above) and join the phone's hotspot by credentials, then
connect as the TCP client instead of the server — `CameraLinkProtocol`'s wire
framing doesn't change, only which side listens and which side dials. The
phone announces this requirement (`CAMERA_LOHS_REVERSE_REQUIRED`) as soon as
its own Wi-Fi state changes, so the glasses usually already know before a
session even opens and can skip the normal Wi-Fi Direct group setup entirely
(`CameraLinkStartupPolicy`, glasses-hub) instead of standing up a group that
would just be torn down once the reverse offer arrives. If no reverse offer
shows up within a bounded window, the glasses fall back to the normal
Wi-Fi-Direct startup on their own.

Measured cold-open time (phone Wi-Fi off, glasses Wi-Fi off) on the reference
hardware: ~5.5s with a warm Wi-Fi radio, degrading to ~9-14s if the glasses'
Wi-Fi chip needs a full cold boot (firmware load) — a hardware cost, not
something this code controls.

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
