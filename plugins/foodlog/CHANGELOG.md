# Changelog

## 3.1.0

- Open on a date-selectable journal with breakfast, lunch, dinner, snacks,
  meal totals, daily macros, and optional personal goals.
- Add searchable saved foods and one portion editor for grams or a known serving,
  meal, date, and time. Preserve drafts across rotation and confirm exact deletion.
- Replace recipe identifiers with named ingredient selection, weight fields,
  per-serving nutrition, and recipe editing. Preserve ingredient snapshots in
  SQLite and backups independently of the food catalog.
- Reject mismatched barcode responses and volume-based products instead of
  treating milliliters as grams. Keep unknown serving weights unknown.
- Separate Journal, Foods, and Settings, with optional details revealed on demand.
  Make the glasses daily log navigable with the existing swipe axis and debounce
  paired directional input.
- Add persistence, import identity, snapshot, portion, meal, and DST regression tests.
- Refresh the journal after returning from glasses logging, preserve consent requests
  through rotation, and serialize Health Connect writes using persisted revisions.
- Deliver inexact alarms through bounded phone notifications when a background
  foreground-service start is not permitted.
- Confirm removal of custom foods and recipes, clear their favorites, and preserve
  saved meals and recipe ingredient snapshots through backup and restore.
- Keep committed meal synchronization alive through phone rotation, and release
  barcode lookup controls when another editor takes over.

## 3.0.0

- Add the phone nutrition dashboard, meal classification, daily goals,
  micronutrients, and seven-day summaries.
- Add custom foods, favorites, recipes, and reusable quick logging.
- Add local glasses voice entry without retaining transcripts.
- Add optional write-only Health Connect synchronization with stable record IDs,
  update propagation, and exact deletion propagation while opted in.
- Add explicit one-shot meal and hydration reminders with pause, resume, cancel,
  boot restoration, and exact-alarm fallback.
- Add complete, bounded V3 JSON archive export/import while retaining V2 journal
  import compatibility.

## 1.0.0

- Add local food history and daily calorie/macronutrient totals.
- Add exact Open Food Facts barcode lookup and a local product cache.
- Add glasses snapshot barcode scanning, portion selection, recent foods, and
  exact undo confirmation.
- Add a Nexus-styled phone journal with manual barcode entry and deletion.
