# Food Log plugin

Food Log 3 is a local-first nutrition and meal journal for Rokid Nexus.

On the glasses it can scan an EAN/UPC barcode, resolve the exact product through
Open Food Facts, accept a portion, add recent or favorite foods and recipes, take
a local voice entry, display today's totals, and undo the exact last entry.

The phone dashboard adds:

- meal classification, daily goals, micronutrients, and seven-day summaries;
- custom foods, favorites, recipes, and their reusable nutrition snapshots;
- exact manual barcode lookup plus a link to contribute missing products to
  Open Food Facts;
- optional, write-only Health Connect synchronization;
- explicit one-shot meal or hydration reminders with pause, resume, and cancel;
- a bounded JSON V3 archive containing entries, the product catalog, favorites,
  goals, recipes, and reminders. V2 journal-only backups remain importable.

Each consumed entry stores its own nutrition snapshot, so later community or
catalog edits cannot rewrite history. Unknown nutrient values stay unknown rather
than becoming zero. Food history, favorites, goals, and recipes remain on the
phone unless the wearer explicitly exports a backup or enables Health Connect.
Voice matching is local against stored products and transcripts are never logged.

Open Food Facts data is collaborative and can be incomplete. The package label
remains the source to check when nutrition data matters; Food Log is a tracking
tool, not a medical device.

```bash
./gradlew :plugin-foodlog:testDebugUnitTest :plugin-foodlog:assembleDebug -PskipCxrGlobal=true
```
