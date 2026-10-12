# Glasses hub

The Rokid Nexus app on the glasses: the bus endpoint, the accessibility service that owns every
key, and the HUD layers (notice band, activity islands, pins, plugin surfaces, launcher). The
repository [README](../README.md) describes the product.

## Launcher backend

Two launchers answer the triple tap, never both at once. `LEGACY` is the classic launcher
overlay. `SESSION` opens the Nexus session of plans 027 and 028 instead: an "Opening…" gate,
then the anchored root; BACK at the root closes it and leaves the app underneath as it was.
While a session is open the notice band is hidden (the notice keeps its deadline) and the
session owns the touchpad and the ring. The root holds the live activities, the retained
notice, `Notifications · n` and Applications. Pages the hub authors (`nexus:`) are answered on
the glasses and work without a phone; plugin pages are pulled over `/page/*` once both hubs
announce `pageSessionVersion: 1`, and drawn from the typed templates (the four base ones, plus
conversation, media and route over the launcher bundle). Plugins that offer
no pages appear in Applications as "Open the full app", behind a labelled launch step.

Nobody's choice means `SESSION` when the phone's last capabilities announcement negotiated the
launcher bundle, and `LEGACY` before any announcement or with an older phone. The persisted
announced version decides, never the live link, so an outage or an offline boot keeps the
launcher in use. An explicit choice outranks that default: the phone's (its "Glasses launcher"
setting), then the glasses-local preference `launcher.backend`, stored with the hub's other
settings. Either can roll back to `LEGACY`; nothing is reset. Switch the glasses' own choice at
runtime with a broadcast addressed to the receiver explicitly (Android drops the implicit form
for a manifest receiver with "Background execution not allowed"):

```
adb shell am broadcast -a com.anezium.rokidbus.glasses.action.SET_LAUNCHER_BACKEND -n com.anezium.rokidbus.glasses/.SetLauncherBackendReceiver --es backend SESSION
adb shell am broadcast -a com.anezium.rokidbus.glasses.action.SET_LAUNCHER_BACKEND -n com.anezium.rokidbus.glasses/.SetLauncherBackendReceiver --es backend LEGACY
```

Switching closes the active launcher first, an open session included. On both backends every
key goes through the one input arbiter (`input/InputArbiter.kt`), and activity islands never
take a key.
