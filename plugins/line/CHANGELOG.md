# Changelog

## 0.1.0 — unreleased prototype

- Add an embedded Go LINE engine, email/password/PIN sign-in and secure phone settings.
- Browse conversations and compose text through Nexus launcher pages.
- Require Letter Sealing for sending; persist ambiguous sends without auto retry.
- Accept modern uppercase conversation IDs and verify saved-session history reads
  with a read-only device test.
- Distinguish an account on standby from an interrupted LINE connection in settings.
- Display bounded photo and sticker thumbnails in conversations, with authenticated
  decryption for sealed photos and a static frame for animated stickers.
