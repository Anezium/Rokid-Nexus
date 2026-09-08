# Food Log plugin

Food Log 3.1 is a local-first nutrition and meal journal for Rokid Nexus.

On the glasses it can scan an EAN/UPC barcode, resolve the exact product through
Open Food Facts, accept a portion, add recent or favorite foods and recipes, take
a local voice entry, display today's totals, and undo the exact last entry.

The phone opens on **Journal**. Choose any day with Previous, Today, Next, or the
date picker. Breakfast, lunch, dinner, and snacks have individual calorie totals;
the daily summary shows calories and macros against goals only when you set them.
Entries with an older unassigned meal remain visible in their own group.

Choose **Add food** to open **Foods**, search your saved catalog or favorites, or
look up a barcode. Every food opens a portion preview before saving. Enter grams,
or a number of servings when a verified gram weight is available. Use **Edit** on
an entry to change its quantity, meal, date, or time. **Delete** asks for confirmation
and affects only the displayed entry. A stale entry must be reopened before editing.

Recipes use named ingredients from your foods, ingredient weights, and a number of
servings. Save a custom food or barcode product first if an ingredient is missing.
Ingredient weights are before cooking; servings divide those weights and nutrition
equally, without estimating water gained or lost while cooking. Recipes can be
edited; existing journal entries keep their original nutrition. **Remove** asks for
confirmation on custom foods and recipes, then hides them from the catalog and
favorites. Saved meals and ingredient snapshots remain intact. Barcode foods have
no removal action.

**Settings** contains optional goals, seven-day summaries, Health Connect,
reminders, backups, and uninstall. The phone also supports:

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

Exact reminders can also notify the glasses through a short foreground delivery
service. Without exact-alarm access, or if Android rejects a foreground start,
Food Log delivers a phone notification directly from the bounded alarm receiver.
Phone notification permission is required for that fallback. It does not start
a background polling or synchronization process.

Health Connect uses persisted entry revisions and one shared provider queue.
Queued synchronization reloads the current local entry, so an old batch cannot
replace a newer edit or recreate a deleted entry. Turning sync off cancels pending
batch work and prevents further dispatch; a provider request already sent may
finish. Permission requests and editor drafts survive activity recreation.
Committed saves and deletions finish their bounded provider dispatch independently
of activity callbacks, so rotating the phone does not discard synchronization.

All portions and recipe ingredients use grams. Open Food Facts fields named
`_100g` can also describe nutrition per 100 ml. Products labelled with volume units
are rejected with an explanation; Food Log never assumes that a milliliter weighs
a gram. Use a custom food with verified nutrition per 100 g for those products.
An unknown serving weight requires an explicit gram portion. The data contract is
documented by [Open Food Facts](https://github.com/openfoodfacts/openfoodfacts-server/blob/main/html/data-fields.txt).

Dates use the phone's local time zone. Editing a repeated autumn time preserves the
original offset; a nonexistent spring time is rejected for correction. This follows
Android's [zone rules](https://developer.android.com/reference/java/time/zone/ZoneRules).

V3 exports include optional ingredient snapshots and removed catalog identities.
Import accepts older V3 archives without those fields and V2 journal backups.
Reimporting an entry UUID never duplicates or replaces its history, and merging an
older archive does not restore foods already removed locally. Removed rows stay in
the archive to preserve recipe references; older app versions may display them.

Open Food Facts data is collaborative and can be incomplete. The package label
remains the source to check when nutrition data matters; Food Log is a tracking
tool, not a medical device.

```bash
./gradlew :plugin-foodlog:testDebugUnitTest :plugin-foodlog:assembleDebug :plugin-foodlog:lintDebug -PskipCxrGlobal=true
```
