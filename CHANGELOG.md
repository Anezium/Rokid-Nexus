# Changelog

## 1.0.5

- The glasses install step now checks that phone Wi-Fi is on first — the APK travels over a direct Wi-Fi link — and offers to turn it on instead of failing with an opaque error.
- On the glasses, enabling the accessibility service flows straight into the secure self-arm; no second tap needed.

## 1.0.4

- Split the glasses onboarding into an install-only card and a dedicated "Set up your glasses" card that owns the How it works guide; drop the Manual download link.
- The glasses report their self-arm setup state to the phone, so the setup step completes exactly when the launcher appears on the lens.

## 1.0.3

- Fix onboarding steps hiding their main button whenever a secondary action was shown — the automated "Install Nexus" glasses install and the notifications "Allow" were invisible.
- Drop the redundant Skip button from the notifications step; denying the system dialog already moves the setup along.

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
