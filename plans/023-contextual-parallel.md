# Contextual Nexus implementation

Baseline: `19c9d760` (Nexus 1.4.7), 2026-09-08.

The owner authorized parallel implementation with Astra xhigh agents and real
phone/glasses validation. Navigation is deferred. Agents targets a Nexus plugin
compatible with current Litter servers, not a modification of Litter's Android app.

## Workstreams

| Work | Implementation branch | Status |
|---|---|---|
| Contextual Lyrics and display cohabitation | `dev/contextual-lyrics` | Integrated; hardware scenarios passed |
| Assistant typed questions | `dev/assistant-keyboard` | Integrated; phone and HUD questions passed |
| Litter-compatible Agents | `dev/agents-litter` | Integrated; server history validation limited |
| Food Log, resuming PR #19 | `dev/foodlog-complete` | Integrated; journal, catalog and barcode flows passed |

Integration branch: `dev/contextual-parallel`.

Contextual Lyrics appears when playback has lyrics, yields to engaged surfaces,
the launcher and notices, and resumes when the interruption ends. A dismissal
suppresses the current track. Glance is the default; only an explicit Karaoke
choice may hold the display. No stock ROM widget is replaced.

Food Log reuses `agent/food-log-v3`: barcode lookup, meal/date journal, explicit
portions, calorie and nutrient totals, editable goals, custom foods, favorites,
recipes, reminders, optional Health Connect and bounded backup/restore. Validation
must distinguish missing nutrition data from zero and estimates from measured
portions. No dietary target is prescribed automatically.

## Integration and verification

Implementation agents work in isolated worktrees and commit only their assigned
files. The coordinator alone integrates commits and runs Gradle from the original,
configured checkout. `local.properties` is never copied, created or modified.

The coordinator exclusively owns both physical devices. Agents may propose test
recipes but never run ADB, install APKs, trigger camera/microphone, or drive either
UI themselves. Device tests run one scenario at a time; phone and glasses are
selected explicitly. Existing application data is preserved.

Required verification includes shared/SDK and both hub unit tests, changed plugin
unit tests and APK builds, structured review, and serial device smoke tests:

1. Typed Assistant question, cancellation and ordinary voice/note compatibility.
2. Lyrics playback, Assistant foreground, Relay notice, return to lyrics, dismissal.
3. Agents connection, session stream, prompt/follow-up and request-bound approval.
4. Food Log add/edit/delete across meals/dates, portions/totals and barcode flow.

## Observed verification, 2026-09-08

All results below were observed in the configured original checkout. The tests
workflow now includes Food Log and Agents. No CI run is claimed before a push.

| Module | Passing unit tests |
|---|---:|
| Ink engine | 57 |
| Shared contracts | 319 |
| Bus client | 105 |
| Phone hub | 522 |
| Glasses hub | 590 |
| Assistant | 300 |
| Lyrics | 60 |
| Food Log | 59 |
| Agents | 65 |

Each suite reported zero failures, errors and skipped tests at its successful
verification. Food Log also passed Android lint. APK builds passed for all touched
apps. Existing release signing was used for both hubs, Assistant, Lyrics and Food
Log; Agents retained its existing debug installation identity. Installs used `-r`
and preserved application data. Hub/SDK suites used the sibling CxrGlobal checkout;
plugin-only runs used `-PskipCxrGlobal=true`.

Actual final command tails:

```text
# Shared, SDK, Ink, both hubs, Assistant and Lyrics unit tests; both hub debug APKs
> Task :ink-engine:test
> Task :phone-hub:testDebugUnitTest
BUILD SUCCESSFUL in 17s
210 actionable tasks: 18 executed, 192 up-to-date

# Food Log unit tests, debug APK and lint
> Task :plugin-foodlog:lintDebug
BUILD SUCCESSFUL in 8s
133 actionable tasks: 6 executed, 127 up-to-date

# Agents unit tests and debug APK
> Task :plugin-agents:testDebugUnitTest
BUILD SUCCESSFUL in 9s
84 actionable tasks: 8 executed, 76 up-to-date

# Final signed Food Log and phone hub APKs
BUILD SUCCESSFUL in 19s
209 actionable tasks: 30 executed, 179 up-to-date
```

Earlier failing assertions and compiler issues were corrected and the affected
commands rerun. In particular, the widget capability remains bit 4096 (editable
fields retain 2048), and archived recipe tests compare the persisted nutrition
snapshot rather than a catalog fetch timestamp.

### Serial device checks

- Assistant: real ChatGPT response to a typed phone question; canceled draft
  clears; two successive HUD questions return the requested answers; follow-up
  opens a fresh editor; stale editor hints clear; Back closes the session.
- Lyrics: both listener grants enabled through settings; actual Spotify playback
  supplies synchronized lyrics; the contextual card leaves the stock home intact.
  Launcher, Assistant and a local Relay harness notification hide it; closing
  each returns to the progressing lyric clock. Hide-current-track removes it.
  Music was paused after testing. No external message was sent by the Relay fixture.
- Food Log: custom food at 400 kcal/100 g previews a 50 g serving at 200 kcal.
  Adding breakfast, editing to 150 g lunch on the previous date, canceling deletion
  and confirming exact deletion produce the expected daily and meal totals.
  A two-serving recipe from 100 g of that food previews/logs 50 g and 200 kcal;
  rotation preserves its draft. Removing the ingredient from the catalog preserves
  the recipe nutrition. Confirmed catalog removal hides both synthetic QA objects
  and clears favorites. Synthetic journal entries were deleted explicitly.
  Real Open Food Facts barcode 3017620422003 resolves to its product and a portion
  preview; canceling that preview adds no journal entry. Logging 90 g from the
  glasses favorites produces 485.1 kcal in the phone journal on resume; confirmed
  HUD Undo removes exactly that entry and the resumed phone returns to zero.
  The temporary favorite was removed after testing.
- Agents: a real loopback Codex app-server accepts initialization and listing.
  The phone creates two dedicated QA sessions and receives actual model responses;
  the glasses board receives their progress and answers. Persisted history resume
  returns JSON-RPC -32603 while the server logs `database disk image is malformed`
  for its own history database. That database was not modified or repaired.
  Failed resumes now settle loading with an internal-server error on phone/HUD,
  retain only the same session's cached transcript, and disable sending until
  recovery. Sparse result pages retain their actual loaded window within the
  consistent 20-page and 200-session limits.
  Live updates preserve both failed-resume errors and pending retry state; scoped
  successful loads clear them. The final regression covers failure, streamed
  updates, a held retry response, recovery and a late obsolete failure.

### Review and remaining limits

Structured reviews used Astra xhigh. Assistant's second review and Food Log's final
catalog/lifecycle review report no actionable findings. Agents' fifth review also
exited successfully with no actionable findings after the resume-state fix.
Accepted display-ordering,
display-hold, typed-input, session hydration, approval and nutrition persistence
findings were fixed. The last two display review candidates were rejected after
tracing automatic admission through `adopt`, which establishes foreground ownership
before publication and policy cleanup; regression assertions cover that invariant.
Reviewer access to local adjacent source was blocked, so review reports are limited
to the supplied bundles; the coordinator and implementation agents inspected source.

The final focused review command was:

```powershell
python -X utf8 E:\CodexData\.codex\skills\autoreview\scripts\autoreview --mode commit --commit ea30fbad --engine codex --model gpt-6-astra --thinking xhigh --prompt 'Review the scoped Agents resume-state fix and its regression tests. Live transcript updates must preserve both failed resume errors and pending resume loading state; only successful scoped loads clear these. Late obsolete failures must not affect a newer selected session. Report only concrete actionable findings. Use repository-relative forward-slash paths in every finding. No nested reviewers, builds or device operations.' --output scratchpad/parallel-roadmap/agents-review-5.md --json-output scratchpad/parallel-roadmap/agents-review-5.json --stream-engine-output
```

It ran with `--commit HEAD` while HEAD was `ea30fbad`. Its actual result was
`autoreview clean: no accepted/actionable findings reported`. The 65-test Agents
suite and debug APK build passed in parallel; the reviewer did not run those tests.

Health Connect writes, real spoken food capture, a physical barcode camera scan,
and elapsed reminder delivery were not exercised on the devices. Their unit tests
use fakes/Robolectric and do not write health records or post physical notifications.
Agent approvals and race cases use deterministic WebSocket fixtures; broad server
transport compatibility is not claimed. Food Log portions currently use grams;
volume-only nutrition is rejected rather than assuming density.

The local QA server configuration was forgotten from Agents, its exact USB reverse
was removed, and only the temporary app-server process on port 8390 was stopped.
The local Relay test notification was dismissed; no real conversation was answered.
Temporary device UI dumps were removed. Food Log remains installed with its journal
free of the test entries; the looked-up barcode product remains as a catalog cache.

The local Android SDK configuration hash remained unchanged throughout validation.
No private SDK, Gradle home or dependency cache was created. Device operations were
exclusive to the coordinator; screen rotation was restored to its original lock.

No release publishing, store changes, pushes or external messages are included.
