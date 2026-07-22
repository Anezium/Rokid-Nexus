# Changelog

## 1.0.37

- Wi-Fi activation now opens the full YodaOS Wi-Fi Settings page first, matching the proven R08 Access Bridge flow.
- The incompatible Android Wi-Fi panel remains only as a final fallback instead of bouncing users back to the launcher.

## 1.0.36

- Manual setup step 3 now enables glasses Wi-Fi through the privileged local command bridge before falling back to Settings accessibility.
- The flow still waits for a connected Wi-Fi network and the real Wireless debugging page before reporting success.

## 1.0.35

- Manual setup step 3 now turns on glasses Wi-Fi before opening Wireless debugging directly.
- The phone waits until the real Wireless debugging page is visible instead of treating a Settings launch request as success.

## 1.0.34

- Manual setup now confirms the Build-number taps only after Developer options are truly enabled on the glasses.
- Developer options and Wireless debugging no longer bounce back to the launcher when step 1 did not complete; the phone explains exactly which step to retry.

## 1.0.33

- Manual pairing now has three explicit controls: six rapid Build-number taps to enable Developer options, direct Developer options, and direct Wireless debugging positioning.
- The six-tap helper targets the displayed build identifier instead of relying on the Settings language and does not automate the rest of the Settings menus.

## 1.0.32

- Direct manual Settings buttons now clear any stale Settings sub-screen before opening, so **Open Developer options** reliably returns to the main developer screen and **Show Wireless debugging** reliably positions the Wireless Debugging row even when Settings was already open.

## 1.0.31

- Manual setup is now always available from onboarding while the glasses app is installed but setup is incomplete, even when the failing transport never delivers a diagnostic.
- The manual wizard no longer drives the glasses Settings menus automatically. It provides separate **Open Developer options** and **Show Wireless debugging** buttons; the latter opens the public Developer options screen already positioned with Wireless Debugging visible for the wearer to select.

## 1.0.30

- Automatic glasses setup now has a clear recovery path: after the initial secure-transport attempt and two internal retries fail, the phone surfaces a guided **Manual setup** action.
- The manual wizard opens the required Wireless Debugging pairing screen on compatible glasses, waits for their acknowledgement, and guides the user through the remaining values without storing the six-digit code.

## 1.0.29

- Failed automatic setup now offers a guided phone-side pairing fallback that opens the required glasses settings itself. The phone waits for a glasses acknowledgement and asks for an app update instead of showing a pairing form when the glasses build is too old.
- Developer mode on the phone now exposes the manual glasses setup wizard for support and testing.

## 1.0.28

- First-run glasses setup is much more resilient: if the secure channel drops mid-arm (including during the planned adbd restart), Nexus reconnects and resumes instead of failing with a support code.

## 1.0.27

- Setup failures on the glasses now show a short support code on the retry card, so a photo of the lens is enough to diagnose what went wrong.

## 1.0.26

- Glasses app updates now ask you to turn on phone Wi-Fi before starting instead of failing during delivery.
- First-run glasses setup now waits for the glasses to join a Wi-Fi network and explains how to recover when none is connected.

## 1.0.25

- The phone now surfaces the glasses' AI-assist button presses and wearing status to plugins, opening the door to features that react to them.

## 1.0.24

- The phone now checks for app and plugin updates on its own, even if you never open Rokid Nexus — you'll get a notification the moment one is available.

## 1.0.23

- Lens opens noticeably faster when the phone's Wi-Fi is off: the glasses no longer set up a Wi-Fi Direct group they were just going to discard, connecting straight to the phone's hotspot instead.

## 1.0.22

- Lens now works even when the phone's Wi-Fi is off: the phone hosts the camera link itself, brings the glasses onto it automatically, and no setting has to be toggled by hand.
- Faster Lens connection when the phone's Wi-Fi is off — the glasses join on the first try instead of retrying.
- Long lyric lines no longer lose their last words; the text shrinks to fit instead of clipping.
- Lens is steadier in longer sessions: it recovers after a plugin update mid-session, handles camera rotation more gracefully, keeps its adaptive text layout in more cases, and reconfigures its video decoder without a brief stutter.

## 1.0.7

- The "Set up your glasses" step can open the Nexus app on the lens directly, so the wearer never hunts through the glasses launcher.

## 1.0.6

- Fix a launch crash in 1.0.5: the install Wi-Fi check needed the ACCESS_WIFI_STATE permission.

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
