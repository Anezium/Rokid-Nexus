# Glasses hub

The Rokid Nexus app on the glasses: the bus endpoint, the accessibility service that owns every
key, and the HUD layers (notice band, activity islands, pins, plugin surfaces, launcher). The
repository [README](../README.md) describes the product.

## Launcher backend

Two launchers answer the triple tap, never both at once. `LEGACY` is today's launcher overlay
and the default. `SESSION` opens the Nexus session of plan 027 instead: an "Opening…" gate,
then the anchored root; BACK at the root closes it and leaves the app underneath as it was.
While a session is open the notice band is hidden (the notice keeps its deadline) and the
session owns the touchpad and the ring. Pages are requested on `/page/request` but no phone
hub answers them yet, so they end as `Unavailable`.

The choice is the glasses-local preference `launcher.backend`, stored with the hub's other
settings; an absent value means `LEGACY`. Switch it at runtime with a broadcast:

```
adb shell am broadcast -a com.anezium.rokidbus.glasses.action.SET_LAUNCHER_BACKEND --es backend SESSION
adb shell am broadcast -a com.anezium.rokidbus.glasses.action.SET_LAUNCHER_BACKEND --es backend LEGACY
```

Switching closes the active launcher first, an open session included. On both backends every
key goes through the one input arbiter (`input/InputArbiter.kt`), and activity islands never
take a key.
