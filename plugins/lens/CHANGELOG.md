# Changelog

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
