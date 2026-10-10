# Changelog — Media Deck

## 1.0.3

- Expose approved now-playing and playback operations to Assistant through
  Nexus Skills. Requires Nexus hubs 1.7.0, notification access, and operation
  approvals; a call does not open the plugin's HUD.
- Keep media references tied to the observed session and reject stale targets.
- Device tests covered now-playing and pause with an Android test player.
  Compatibility with real music players remains unverified.

## 1.0.2

- Android 11 support: the plugin now installs on Android 11 (API 30) phones.

## 1.0.1

- The app icon in Android settings and installer dialogs is now the plugin's own glyph on the Nexus dark background (adaptive icon), instead of a washed-out or generic mark.

## 1.0.0 — unreleased

- First release as a headless plugin APK (previously an in-hub built-in).
- Typed media surface with playback anchor and 96×96 one-bit artwork.
- Transport controls from the glasses: tap play/pause, swipe previous/next.
- Own notification listener ("Nexus Media Deck") and kit settings screen with
  notification-access onboarding and uninstall.
