# Changelog

## Unreleased

- New opt-in "Read frozen photos with Gemini" setting: with a Gemini key, Lens sends the frozen photo to Gemini, which reads and translates it in one call. This reads scripts the on-device recognizer cannot, such as Thai, Arabic, Cyrillic, Greek and Hebrew. Frozen photos only, never the live overlay, and never while the On-device engine is selected; if Gemini fails, Lens falls back to reading the photo on the phone.
- Thai is now available as a translation target language.
- The default Gemini model is now "Gemini 3.5 Flash Lite": it answered in about a second and a half where the larger flash models took up to 45 seconds or were overloaded. Google refuses Gemini 2.5 Flash to new API keys, so a freshly created key could not translate with the old default; it stays selectable for keys that still have access.
- When the chosen Gemini model is unavailable to the key or overloaded, Lens retries once on Gemini's latest lite model before giving up.
- Settings now say which scripts Lens reads on the phone.

## 1.1.2

- Android 11 support: the plugin now installs on Android 11 (API 30) phones. Declares ACCESS_COARSE_LOCATION alongside FINE for the Wi-Fi Direct link.

## 1.1.1

- Greek is now available as a translation target language.

## 1.1.0

- Lens now works even when the phone's Wi-Fi is off: the phone hosts the camera link itself and the glasses join it automatically, no setting to toggle by hand.
- Recovers after a plugin update mid-session instead of requiring a hub restart.
- Camera rotation falls back to a software path instead of tearing the stream down on a hardware mismatch.
- The live overlay keeps its adaptive text layout in more cases instead of silently downgrading.
- The video decoder reconfigures without a brief stutter in the live feed.

## 1.0.4

- The app icon in Android settings and installer dialogs is now the plugin's own glyph on the Nexus dark background (adaptive icon), instead of a washed-out or generic mark.

## 1.0.3

- API-key fields are now full-height inputs instead of a thin line.

## 1.0.2

- Settings polish: larger, clearer API-key fields that read as real inputs, and a
  version label that tracks the installed build.

## 1.0.1

- Ship an arm64-only APK, dropping the duplicated 32-bit and x86 copies of the
  ML Kit native libraries. Cuts the download from ~120 MB to ~47 MB with no change
  in behaviour: all OCR scripts stay bundled, offline, and instant.

## 1.0.0

- Camera platform: generic `camera` capability with the phone-side OCR pipeline
  over an app-owned Wi-Fi Direct link (freeze and live translation).
- Cold-link reliability: discovery-primed joins, identity-aware watchdogs, and
  group-owner protection so a healthy join is never torn down by recovery.
- Live overlay reconciliation, adaptive OCR cadence, and multi-script frozen results.

## 0.9.0

- Headless external Lens camera consumer.
- No-surface H.264 decode with pooled NV21 buffers and one-in-flight live OCR.
- Translated structured live overlays and multi-script frozen-image results.
- Encrypted plugin-owned settings, offline packs, permission onboarding, and uninstall.
