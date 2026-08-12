# Food Log plugin

Food Log is a local-first nutrition journal for Rokid Nexus. From the glasses,
scan an EAN/UPC barcode, review the Open Food Facts product, choose the amount,
and add it to today's log. The phone settings screen supports manual barcode
lookup, history review, exact-entry deletion, and local daily totals.

Product reads use the Open Food Facts API. Each consumed entry stores its own
nutrition snapshot so later community edits cannot rewrite the wearer's
history. Unknown nutrient values remain unknown rather than being treated as
zero. No food history is uploaded by the plugin.

```bash
./gradlew :plugin-foodlog:testDebugUnitTest :plugin-foodlog:assembleDebug -PskipCxrGlobal=true
```
