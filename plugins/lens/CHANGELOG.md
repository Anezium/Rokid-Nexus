# Changelog

## 1.0.1

- Non-Latin OCR script models (Japanese, Chinese, Korean, Devanagari) now download
  on demand via Google Play Services instead of being bundled, cutting the app size
  from ~120 MB to ~20 MB. Latin recognition stays bundled and instant.

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
