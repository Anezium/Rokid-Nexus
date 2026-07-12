# Transit

Transit puts Île-de-France public transport on the glasses: nearby stops with
live departures ("Near Me"), and a favourites board for the stops you actually
use.

## How it works

- `TransitRuntime` owns the domain logic (repository, location provider,
  favourites store) behind a small host interface; `TransitPluginService` is
  the `NexusPluginService` adapter that renders it through SDK cards with
  rich lines.
- Near Me runs a location foreground service (`foregroundServiceType=
  "location"`) that the plugin starts and stops itself, permission-guarded.
- `TransitSettingsActivity` (NexusUi kit) manages the location permission,
  stop search, favourites, and uninstall.
- On first approval the hub offers a one-shot migration of favourites saved
  by the old built-in Transit (`TransitLegacyMigrationReceiver`).

## Requirements

- Location permission for Near Me (favourite boards work without it).
