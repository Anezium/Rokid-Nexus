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
