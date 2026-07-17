# Changelog

## 1.0.2

- Fix a first-launch crash: the hub no longer starts its foreground service before the Bluetooth permission is granted, and it starts automatically once the permission arrives.
- Ask for each permission from its own onboarding step — Bluetooth, notifications (skippable), and app installs — instead of prompting cold at launch.
- Only mark the first-plugin onboarding step done once a plugin is approved.

## 1.0.1

- Fix the self-arm watchdog script line endings so the secure bootstrap installs a working watchdog.
- Return to the Nexus HUD automatically after the accessibility service is enabled in Settings.
- Slim the glasses launcher, scroll it AR-clean, and show plugin icons.
- Add a phone-side "How it works" walkthrough of the on-glasses setup.
- Report the glasses app version to the phone and offer glasses updates from there.

## 1.0.0

- First public signed release of the Rokid Nexus phone and glasses apps.
- Provides the headless plugin platform, including the Store and developer mode.
- Supports camera and Lens capabilities plus feeds, transit, lyrics, and media plugins.
