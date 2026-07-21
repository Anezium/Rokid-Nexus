# Changelog

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
