---
task: transit-direction-match
date: 2026-10-09
status: active
scope_globs:
  - "plugins/transit/src/main/java/com/anezium/rokidbus/plugin/transit/TransitSkills.kt"
  - "plugins/transit/src/main/java/com/anezium/rokidbus/plugin/transit/TransitSkillContract.kt"
  - "plugins/transit/src/main/java/com/anezium/rokidbus/plugin/transit/TransitLabelMatch*.kt"
  - "plugins/transit/src/main/res/raw/nexus_skills.json"
  - "plugins/transit/src/test/java/com/anezium/rokidbus/plugin/transit/TransitReadSkillsTest.kt"
  - "plugins/transit/src/test/java/com/anezium/rokidbus/plugin/transit/TransitSkillTestSupport.kt"
  - "plugins/transit/CHANGELOG.md"
forbidden_globs:
  - "shared/**"
  - "bus-client/**"
  - "phone-hub/**"
  - "glasses-hub/**"
  - "plugins/assistant/**"
  - "plugins/**/build.gradle.kts"
  - "settings.gradle.kts"
  - "local.properties"
  - "gradle/**"
  - "docs/**"
  - "plugins/transit/src/main/java/com/anezium/rokidbus/plugin/transit/Transitous*.kt"
  - "plugins/transit/src/main/AndroidManifest.xml"
test_commands:
  - "./gradlew :plugin-transit:testDebugUnitTest :plugin-transit:assembleDebug -PskipCxrGlobal=true"
max_failures: 2
---

# Goal
In the Transit plugin's `get_departures` skill operation, a follow-up that names a line and/or a direction the way a person says it ("14", "ligne 14", "M14", "Métro 14", "Orly", "vers Orly", "aéroport", "Aéroport d'Orly") returns the matching departures whenever the current board contains them. Ambiguity (a selector that plausibly matches several distinct lines or headsigns) is reported honestly via the `match` field with the candidates listed; nothing unrelated is ever matched. When nothing matches, the result tells the model which lines / directions the board actually has, so it can recover instead of saying "no departure". The `after` anchor keeps working in combination with line/direction filters.

Worktree: `E:\Tools\Rokid\RokidNexus-fu-transit`, branch `dev/transit-direction-match` (based on `qa/skills-workspace-typed-160` at `2b439f08`). Work only there. Read `AGENTS.md` and `plugins/AGENTS.md` at the worktree root before anything else.

# Non-goals
- No changes to how departures are fetched from Transitous (no client / network / caching changes).
- No changes to other skill operations, to stop search, journeys, or the `after` anchor semantics beyond keeping it working with filters.
- No model-side / Assistant-side changes; no prompt or description rewrites in `nexus_skills.json` beyond what is needed to describe the relaxed selectors and new output fields.
- No restyling or refactoring of code outside the matching logic.
- No device testing (adb/APK install forbidden; the orchestrator does that later).

# Constraints
- MUST reproduce the device case with a failing unit test BEFORE changing production code, and commit that test alone as the first commit on the branch (commit message states the observed failing assertion). The second (or later) commit contains the fix.
- MUST keep matching purely in-memory on the already-fetched departure list (no additional API calls at question time).
- MUST match line selectors by whole normalized label, with mode prefixes tolerated ("ligne", "line", "métro"/"metro", "m", "bus", "tram", "rer" followed by optional space/dash) on BOTH the selector and the board label; exact normalized equality takes precedence over prefix-stripped equality.
- MUST match direction selectors by whole normalized tokens contained in the headsign (e.g. "orly" ∈ tokens("Aéroport d'Orly")), after dropping leading connector words ("vers", "direction", "dir", "to", "towards", "toward"); exact normalized equality takes precedence over partial token containment. Substring matching inside a token is forbidden ("Or" must not match "Orly").
- MUST report `match = "ambiguous_direction"` when a partial direction selector matches several distinct headsigns among the candidate rows (after applying the line filter if given), and return those rows (capped by `MAX_BOARD_DEPARTURES`) so the model can ask or pick. The existing "line only, several directions" ambiguity behavior is unchanged.
- MUST, when a line selector matches nothing, keep `match = "no_matching_line"` and add the board's distinct available line labels to the result; when a direction selector matches nothing, return a distinct non-empty `match` value (e.g. `"no_matching_direction"`, additive to the enum) and list the distinct headsigns available (restricted to the selected line when a line matched).
- MUST keep `nexus_skills.json` backward compatible: no renamed or removed fields, no changed operation id/version semantics; new output fields are optional; new `match` enum values are additive; catalog stays < 64 KiB, every description ≤ 2048 chars, string bounds respected.
- MUST keep the `after` anchor working together with line/direction selectors (covered by a test).
- MUST add a bullet under `## Unreleased` in `plugins/transit/CHANGELOG.md` in the existing bold-title style.
- MUST run the full test command after the fix with 0 failures; MUST commit at every coherent step with the repo's configured git user, imperative English messages, no AI attribution, no `Co-Authored-By`.
- MUST NOT add network calls to tests (no `api.transitous.org` or HTTP client usage under `src/test`).
- MUST NOT modify any file outside `scope_globs`; MUST NOT touch `settings.gradle.kts`, `local.properties`, Gradle home/cache, or create a private SDK; if the build fails for an environment reason (SDK missing, download refused, cache not writable) STOP and report.
- MUST NOT use adb, install APKs, or touch devices. MUST NOT push, merge, rebase, or switch branches. MUST NOT bump `versionName`/`versionCode`.
- MUST NOT delete or weaken existing tests; existing assertions may only change where the contract explicitly changes behavior (document each such change in the commit message).
- MUST NOT change `lineOf`/`directionOf` sources (routeShortName / headsign) or `MAX_BOARD_DEPARTURES`.

# Acceptance tests
| # | Check | Command | Expected |
|---|-------|---------|----------|
| 1 | Build + tests pass | `./gradlew :plugin-transit:testDebugUnitTest :plugin-transit:assembleDebug -PskipCxrGlobal=true` | BUILD SUCCESSFUL, 0 failures |
| 2 | First commit is test-only | `git show --stat --format=%s $(git rev-list --reverse 2b439f08..HEAD \| head -1)` | Subject mentions the failing device case; file list contains only paths under `plugins/transit/src/test/` |
| 3 | Branch has ≥ 2 commits | `git rev-list --count 2b439f08..HEAD` | ≥ 2 |
| 4 | Only in-scope files changed | `git diff --stat 2b439f08..HEAD --name-only` | Every path matches `scope_globs` |
| 5 | Device-case test exists | `grep -n -i "orly" plugins/transit/src/test/java/com/anezium/rokidbus/plugin/transit/TransitReadSkillsTest.kt` | ≥ 1 test whose name/body uses line `14` + direction `Orly` and asserts `filtered` with only Orly-bound line-14 rows |
| 6 | Selector-form coverage | `grep -c -i -e "ligne 14" -e "M14" -e "Métro 14" -e "vers Orly" -e "aéroport" plugins/transit/src/test/java/com/anezium/rokidbus/plugin/transit/TransitReadSkillsTest.kt` | ≥ 5 (each form appears in at least one test) |
| 7 | Required scenario keywords in test names | `grep -n -i -e "linePrefix" -e "directionPartial" -e "ambiguousDirection" -e "noMatch" -e "afterWithFilter" -e "unrelated" plugins/transit/src/test/java/com/anezium/rokidbus/plugin/transit/TransitReadSkillsTest.kt` | Each of the 6 keywords appears in at least one `fun` test name |
| 8 | Unrelated never matched | test tagged `unrelated` asserts direction `Orly` does not match headsign `Olympiades` and `Or` does not match `Orly` | Test present and passing |
| 9 | No-match results list alternatives | test tagged `noMatch` asserts available lines (for no_matching_line) and available headsigns (for no_matching_direction) are present and non-empty | Test present and passing |
| 10 | No network in tests | `grep -rn -e "api.transitous.org" -e "OkHttp" -e "HttpURLConnection" plugins/transit/src/test` | No output |
| 11 | Catalog valid and bounded | `wc -c plugins/transit/src/main/res/raw/nexus_skills.json` and `python -c "import json;json.load(open('plugins/transit/src/main/res/raw/nexus_skills.json',encoding='utf-8'))"` (if python is absent, rely on the existing catalog-parsing unit test and state so in the report) | size < 65536; parse OK; no description > 2048 chars |
| 12 | Catalog backward compatible | `git diff 2b439f08..HEAD -- plugins/transit/src/main/res/raw/nexus_skills.json` | Only additions/description edits; no removed `"` keys, no removed enum values, operation id and version unchanged |
| 13 | New match value declared | `grep -n -e "no_matching_direction" plugins/transit/src/main/res/raw/nexus_skills.json plugins/transit/src/main/java/com/anezium/rokidbus/plugin/transit/TransitSkills.kt` (or the chosen name) | Present in both the JSON enum and the Kotlin producer |
| 14 | CHANGELOG entry | `git diff 2b439f08..HEAD -- plugins/transit/CHANGELOG.md` | One added bullet under `## Unreleased`, bold title, existing style |
| 15 | Commit hygiene | `git log 2b439f08..HEAD --format='%an%n%b'` | Author is the repo's configured user; no `Co-Authored-By`, no AI mention |

# Plan sketch
1. Read `AGENTS.md`, `plugins/AGENTS.md`, `TransitSkills.kt` (getDepartures ~114-210, followAnchor ~220-235, lineOf/directionOf ~289-293, sameLabel/normalizeLabel ~295), `TransitSkillContract.kt`, the `get_departures` entry in `nexus_skills.json`, and the fixture helpers in `TransitSkillTestSupport.kt`.
2. Optionally query Transitous read-only (use the endpoints the plugin's own client uses — grep `api.transitous.org` under `src/main` — e.g. geocode "Bibliothèque François Mitterrand" then stoptimes for that stop) to capture real `routeShortName` / `headsign` values. If unreachable, build fixtures with: metro `14` headsigns `Aéroport d'Orly` and `Saint-Denis Pleyel`, RER `C` headsigns `Pontoise` / `Massy-Palaiseau`, bus `62` headsign `Porte de France`, bus `325` headsign `Château de Vincennes`, and note the assumption in the report.
3. Write the failing test: board fixture above; call get_departures with `line="14"`, `direction="Orly"` (the most plausible payload the model sent — also add the `line="ligne 14"` variant). Run tests, observe failure, commit test-only.
4. Implement a small matcher (new file `TransitLabelMatch.kt` or private helpers in `TransitSkills.kt`): `lineMatches(selector, boardLabel)` = exact normalized equality, else equality after stripping mode prefix on both sides; `directionMatch(selector, headsign)` = exact normalized equality > all selector tokens (after removing connector words) present as whole tokens in the headsign. Resolve: filter candidate rows by line (exact first, then prefix-stripped; if the prefix-stripped form hits several distinct board labels, treat as no single line match and report alternatives); then direction (exact first; if none exact, partial; if partial hits several distinct headsigns → `ambiguous_direction` with those rows). Produce the alternatives fields on no-match.
5. Extend the JSON output schema with the new optional fields and enum value; extend input `description`s to say selectors may be given "as a person says it" (keep ≤ 2048 chars).
6. Add tests for every scenario in acceptance #5-#9 plus `afterWithFilter` (line 14 + Orly + `after` = first Orly-bound row → returns the next Orly-bound 14). Run the full command, add CHANGELOG bullet, commit.

# Context the executor cannot re-derive
- Device observation (docs/reviews/skills-workspace-device-qa.md): at Paris "Bibliothèque François Mitterrand", the unfiltered board (metro 14, bus, RER C) listed Orly-bound line 14 services; asking "the next line 14 service toward Orly" and then explicitly selecting the line (no `after`) both returned no matching departure; a later unfiltered board again showed an Orly service. The inspector does not show invocation payloads, so the arguments are unknown — most likely `line="14"`/`"ligne 14"` with `direction="Orly"` or `"vers Orly"`.
- Root cause (to be confirmed by the failing test): `sameLabel` is EXACT equality after `normalizeLabel` (NFKD, strip marks, lowercase, strip apostrophes, collapse spaces/dashes). "Orly" ≠ "aeroport d orly"; "ligne 14"/"m14" ≠ "14". With a line match and a direction miss, the current code returns `match="filtered"` with zero rows, which the model reads as "no departure".
- Current flow: line given and no row has sameLabel(lineOf(it), line) → `no_matching_line`, empty rows; otherwise filter by line AND direction → `filtered` (`ambiguous_direction` when only a line is given and rows span several headsigns); rows capped at `TransitSkillContract.MAX_BOARD_DEPARTURES` (12).
- Catalog validation by the hub: 64 KiB total, bounded strings, 2 KiB per description; `line.maxLength=16`, `direction.maxLength=80` — do not raise them.
- Paris labels to be careful with: tram lines are `T3a`/`T3b` (prefix-stripping "t" must not break exact equality — exact match is checked first); RER lines are single letters (`A`..`E`); "M" alone is never a line.
- Baseline test command for this plugin is verified on Windows (Git Bash or PowerShell, Gradle 9.5.1, Java 17); `-PskipCxrGlobal=true` substitutes the sibling `../CxrGlobal` checkout which exists.

# Escalation triggers (mechanical — never self-assessed by the executor)
- The test command fails for an environment reason (SDK not found, download refused, cache not writable, missing `../CxrGlobal`).
- The fix would require editing any file outside `scope_globs`.
- After the fix, the test command fails twice in a row (`max_failures: 2`).
- An existing test must be deleted or its expected `match` value changed for a case this contract does not explicitly redefine.
- The catalog would exceed 64 KiB or a description would exceed 2048 chars.
- `git status` shows unexpected modified files outside scope at any point (another session touched the worktree).
On any trigger: stop, leave the tree committed up to the last coherent step, and report.

# Autonomy
Proceed without asking questions. Choose field names, helper structure and test names freely within the constraints. Prefer the smallest change that passes all acceptance tests. Finish with a report containing: commit list (`git log --oneline 2b439f08..HEAD`), the confirmed root cause, the real Transitous labels observed (or the fallback assumption), the new JSON fields/enum values, and any open concern.

# Device checks (run later by the orchestrator, not by the executor)
- At Bibliothèque François Mitterrand: ask for departures (board shows metro 14, bus, RER).
- Then "the next line 14 toward Orly" → one Orly-bound line 14 departure, not "no departure".
- Then "and the one after that?" → the following Orly-bound line 14 departure (anchor + filter).
- A deliberately wrong direction ("line 14 toward Versailles") → the assistant names the directions line 14 actually serves.
