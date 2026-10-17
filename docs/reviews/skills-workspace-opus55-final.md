# Final read-only review

This review is based only on the source provided. I did not run any code. Statements about runtime behavior come from tracing the code by hand.

## Verdicts

| Area | Verdict | Blocking defects |
|---|---|---|
| **Models** | **SOLID** | None |
| **Transit** | **SOLID** | None. Two minor edge cases are listed below. |
| **Workspace** | **SOLID** | None. Correctness depends partly on the model following the prompt. |

## Models: SOLID

I checked the following and found them correct.

**Endpoint choice**
- `usesResponses` requires all three of:
  - the `openai` preset,
  - a `gpt-6*` model,
  - the canonical base URL (compared after trimming).
- The test confirms that `defaultBaseUrl` has no trailing slash, so the equality check holds.
- Proxy URLs, OpenRouter and `gpt-4o` stay on `/chat/completions`.
- `responsesOverride` is only used in tests.

**Effort mapping**
- On the Responses path:
  - `ultra` maps to `max`.
  - `none` is kept for `gpt-6-luna` and `gpt-6-sol`, and becomes `low` for Sol 6.1 and Astra.
  - An empty or unknown effort defaults to `none` (Luna or old Sol) or `low` (other models).
- This matches the test (Luna `none`, others `low`) and the Codex-side change of `DEFAULT_REASONING_EFFORT` to `none`.
- The Codex delta keeps `none` on Luna and clamps it to `low` on Sol and Astra.

**Reasoning replay**
- Encrypted reasoning items are emitted before the assistant preamble and the `function_call`.
- Items without `encrypted_content` are dropped.
- The map is local to one turn.
- With `parallel_tool_calls=false`, assigning all pending reasoning to the first `call_id` is correct.

**Stream integrity**
- `response.incomplete` and `response.failed` become errors.
- A stream that ends without `response.completed` fails the `check(...)`.
- An HTTP status outside 2xx throws.

**Speculative (deployment, not code)**
- I could not verify these against the public API without a paid key, and the prior round already documented them:
  - Whether `"instructions": ""` (no system message) is accepted.
  - Whether a streamed-200 schema rejection triggers the tools-off fallback.
  - Whether public effort caps match the official model pages.

## Transit: SOLID

**Prior blocker (spaced M10) is fixed.** I traced:
- `M 10`, `M-10` and `ligne M 10`:
  - `normalize` turns `-` into a space.
  - `SPACED_M_CODE` collapses the leading `m ` to `m10`.
  - Inside the loop, after `ligne` is stripped, the letter-led literal `m10` is tried exactly.
  - In `modes()`, the remainder `m 10` exact-matches the label `M10`, so no mode filter is applied.
- `tram M 10` keeps the TRAM filter and still exact-matches `M10`.
- `metro 10` and `metro M 10` correctly select subway `10`.
- Bare `metro1` and `métro 1`: the remainder `1` is not letter-led, so it falls through to loose matching and keeps the `M1`/`1` ambiguity required by the regression test.

**Also verified:**
- `tram3a` / `tram 3a` select `T3a` only.
- `busn01` selects `N01`.
- `Tramway`, `Busway`, `Linea`, `LINES`, `METROPOLE1` are not treated as lines.
- `modes()` returns null for neutral words (`ligne`, `line`).
- RER grouping is used for ambiguity, focus groups and anchor groups, while `identifies` stays strict on the raw mode.
- Ambiguous matches omit the focus departure, line and direction.

### Minor, confirmed by trace, non-blocking

**T1. A candidate cycle when the same code appears as `M1` and `1` in one mode and as `1` in another mode.**

Reproducer board:
- `M1` SUBWAY "A"
- `1` SUBWAY "A"
- `1` BUS "A"

Trace:
1. `line="1"` is an exact match on `1`. It returns SUBWAY 1 and BUS 1, so the result is `ambiguous_line` with candidates `metro 1`, `bus 1`.
2. `line="metro 1"` gives `M1` and `1`, so the result is `ambiguous_line` with candidates `M1`, `1`.
3. Choosing `1` returns to step 1.

Subway `1` cannot be reached with a line selector alone. Using a direction breaks the loop only if the headsigns differ.

Scope and fix:
- Real feeds rarely have this combination. Lausanne, for example, is `M1` SUBWAY plus `1` BUS, which resolves correctly.
- Any line-only fix conflicts with the explicit requirement that `métro 1` stay ambiguous.
- My recommendation is to document it as a known limitation rather than change matching now.
- If it ever needs fixing, the smallest change is a qualified exact-label form, used only in candidates when a mode candidate would re-expand.

**T2. Truncation mismatch in anchor identity (latent).**

The mismatch:
- `encodeDeparture` stores `tripId.take(160)` and `mode.take(24)`.
- `identifies` compares against the untruncated `departure.tripId` and `departure.mode`.

Consequence:
- A trip id longer than 160 characters would never match.
- Because a mismatch returns false with no fallback when both sides have trip ids, every "after" follow-up would return `board_changed`.

Scope and fix:
- Known enum modes are at most 18 characters, and typical Transitous trip ids are well under 160, so this is unlikely in practice.
- Smallest fix, in `identifies`:
  - Compare `departure.mode.take(24) != anchor.mode`.
  - Compare `departure.tripId.take(160) == anchor.tripId`.

### Semantic observation, not a defect

- **Direction-only queries across several lines still set a focus line.**
  - With `14` SUBWAY "Orly" and `Orlybus` BUS "Orly", `direction="Orly"` returns `filtered` and sets the focus to line `14`.
  - "The one after that" would then follow line 14 and skip the Orlybus.
- No line was requested, so this is not the "ambiguity sets a focus" bug. Whether the follow-up should mean "same line" or "same destination" is a product decision.

## Workspace: SOLID

- The policy and the tool description agree on:
  - Do not search for general knowledge.
  - Search proper names alone, with no attribute words.
  - Separate subjects with semicolons.
  - Require the full subject identity; a shared first name is not enough.
  - Do not quote personal details from unrelated excerpts.
- The retriever test deltas cover:
  - French attribute overlap that stays below 50% coverage.
  - `Orphee` alone returning empty.
  - `Aurora; Orphee` returning `aurora.docx` only.

**Residual (speculative, already acknowledged):**
- The lexical retriever is unchanged, so passages that hold the attribute only in metadata can be missed.
- Proper names containing "and" or "et" are split.
- Identity checks rely on the model following the prompt, which is not a guarantee. The live two-pass JeanDupont/JeanMartin probe is a backend probe, not proof on a device.

## Required before merge

None. T2 is a two-token hardening change and is optional. T1 should be documented, not fixed in this round.
---

# Final delta review (read-only; traced by hand, nothing executed)

## Verdicts

| Area | Verdict | Notes |
|---|---|---|
| **Models** | **SOLID** | Carried forward. The delta does not touch Models. |
| **Workspace** | **SOLID** | Carried forward. The delta does not touch Workspace. |
| **Transit** | **SOLID** | The delta is correct. There is one non-blocking follow-up interaction, described below. |

## The delta is correct

**Typographic variants are now one direction.** `distinctDirections` compares `words(...)`, which applies NFKD, strips marks, lowercases and splits on non-letters and non-digits:
- `"Aéroport d'Orly"` becomes `[aeroport, d, orly]`.
- `"Aeroport d’Orly"` becomes `[aeroport, d, orly]`.
- The count is 1, so the result falls through to `filtered` and `focusRow` gets a departure.

**Different destinations are still ambiguous.**
- Adding `Olympiades` makes the count 2, so the result is `ambiguous_direction`.
- The focus departure, line and direction are omitted, so ambiguity still does not establish a focus.

**Other branches are unchanged.**
- `ambiguous_line` is checked earlier and is unaffected.
- The line+direction path uses the `headsigns` branch, which already used `distinctDirections`.
- An empty `selected` list gives 0, which is `filtered` with no rows, the same as before.

**The regression test is adequate.**
- It checks both sides of the boundary.
- `now + 31s` forces a re-fetch past the board cache.
- The earlier rows are still upcoming after that shift.
- It does not assert the candidate list. With a third destination, `candidates` will list both spellings plus Olympiades. That cosmetic duplication was accepted in round 3.

## One confirmed, low-severity interaction (pre-existing, now reachable from a line-only query)

The delta now sets a focus on a row whose "next one" follow-up uses stricter direction equality than the check that established the focus. `followAnchor` builds `group` with a raw comparison: `directionOf(it) == anchor.direction`.

**Reproducer.** Use the test's first board: `14 "Aéroport d'Orly"` at +2 and `14 "Aeroport d’Orly"` at +3.
1. Call with `line="14"`. The result is `filtered`, and the focus departure is the +2 row.
2. Call with `after=<that departure>`. `matches` is the +2 row. `group` contains only the raw `"Aéroport d'Orly"` rows, so the result is `after_anchor` with **no rows**.
3. The +3 departure to the same destination is skipped.

**Scope.**
- Before this delta, the same outcome was reachable after the model answered the direction question, because the exact normalized `directions()` match also yields `filtered` with a focus.
- So this is not a new class of bug, but the delta makes it reachable in one step.
- It is rare in practice, since it needs mixed typography for the same line at one stop.
- It is in scope (anchored follow-ups) but not a blocker.

**Smallest fix.** Normalize only the group membership test. Keep `identifies` strict on raw values.

```kotlin
val group = upcoming.filter { TransitLabelMatch.modeGroup(it.mode) == TransitLabelMatch.modeGroup(anchor.mode) &&
    lineOf(it) == anchor.line &&
    TransitLabelMatch.distinctDirections(listOf(directionOf(it), anchor.direction)) == 1 }
```

- The found row still raw-matches the anchor, so it remains in `group`.
- `indexOfFirst { it === found }` still works.

**Regression to add.** In the new test, before the Olympiades step:
- Pass `after = sameDestination.focus.departure`.
- Assert `after_anchor`.
- Assert that the first row is `"Aeroport d’Orly"` at +3.

## Bottom line

The delta does exactly what was intended and introduces no mode-selection or ambiguity-focus defect. The only finding is the follow-up grouping above. It is optional for this round, but it is a two-line hardening that pairs naturally with this delta. No speculative deployment concerns are reopened.

---

# Round 5 delta review

This review is read-only. I traced the code by hand and did not execute anything.

## Verdicts

| Area | Verdict | Delta impact |
|---|---|---|
| **Models** | **SOLID** | Carried forward. The delta does not touch Models. |
| **Workspace** | **SOLID** | Carried forward. The delta does not touch Workspace. |
| **Transit** | **SOLID** | The delta is correct and introduces no defect. |

## Delta trace

**1. `distinctDirectionLabels` = `distinctBy(::words)`**
- `upcoming` is sorted by departure, and `distinctBy` is stable.
- So the label it keeps is the spelling of the earliest departure. That is deterministic and matches the test's expected `"Aéroport d'Orly"`.
- `distinctDirections(selectedDirections)` gives the same count as computing it on the raw list, so the ambiguity branch behaves exactly as in round 4.
- `candidates` for `ambiguous_direction` now lists one label per destination.
- When a direction selector is given, this is consistent with the `headsigns` branch, because `selected` is already filtered by `headsigns`.

**2. `followAnchor` group membership uses `words` equality**
- Grouping is by exact word sequence, not `containsAll`. So `"Orly"` and `"Orly Ouest"` stay separate, and no real destinations are merged.
- The identified row always passes its own group test, so `indexOfFirst { it === found }` still resolves.
- `identifies` remains raw-strict, as decided earlier.
- `anchor_departed` and `board_changed` now return the widened group, which is consistent.

**3. Regression test**
- The first call is `line="14"`. It returns `filtered` with 2 rows and focuses on the +2 row.
- The `after` call, with an unchanged clock, uses the cached board.
- Only the +2 row raw-matches the anchor (`identifies`), so it is the unique match. The group contains both spellings, so the result is `after_anchor` with the +3 row `"Aeroport d’Orly"`. This is the R1 fix, exercised directly.
- After 31 s and with Olympiades added, the board is re-fetched. The result is `ambiguous_direction` with no focus departure, and exactly two candidates. This is the R2 fix, exercised directly.

## Remaining item

This is pre-existing and not introduced by this delta. It is optional and non-blocking.

**`directions()` uses `normalize` as its first exact match.** `normalize` deletes apostrophes, while `words` splits on them. As a result, spelling variants that differ by apostrophe versus space or dash are one destination for ambiguity, candidates and follow-ups, but not for an explicit re-selection.

**Reproducer:**
1. Board:
   - `14 "Aéroport d'Orly"` at +2
   - `14 "Aéroport d Orly"` at +3
   - `14 "Olympiades"` at +4
2. `line="14"` returns `ambiguous_direction` with candidates `["Aéroport d'Orly", "Olympiades"]`.
3. `line="14", direction="Aéroport d'Orly"`:
   - The exact `normalize` step keeps only `"aeroport dorly"`.
   - The result is `filtered` with the +2 row only, so the +3 row is omitted from that answer.
4. A later `after` call does recover the +3 row through the words-based group.

**Assessment:**
- This is low severity. It omits a departure but never reports a wrong one, and it requires mixed apostrophe and space typography on the same line.
- The same behaviour existed before this round.

**Smallest fix, if wanted:** in `directions()`, expand an exact hit to every headsign with the same words:
```kotlin
headsigns.filter { normalize(it) == wanted }.takeIf { it.isNotEmpty() }
    ?.let { exact -> return headsigns.filter { h -> exact.any { words(it) == words(h) } }.toSet() }
```

## Cosmetic, not defects

- The board-level `directions` list and `focus.groups` still dedupe by raw headsign.
- So the model may still see both spellings there. This does not affect selection, focus or follow-ups.

## Required before merge

None. The delta fixes both round-4 residuals (R1 and R2) as intended, and its test covers both. No speculative deployment concerns are reopened.
