# Final independent review (read-only; nothing executed — findings are from source reading plus the reported green build)

## Verdicts

| Area | Verdict | Blocking defects |
|---|---|---|
| Models (Luna/Sol/Astra, Codex clamp, Responses adapter) | **SOLID** | none confirmed |
| Transit (label match, anchors, ambiguity, candidates) | **SOLID** | none confirmed |
| Workspace (names-only follow-ups, policy, retriever tests) | **SOLID** | none confirmed |

All prior Astra/Fable items I can check against the shown source are resolved: spaced/dashed `M 10`/`M-10`/`ligne M 10` compact to the literal label; bare `métro 1` keeps M1/1 ambiguity with both candidates round-tripping; `coach 1`/`ferry 1`/`rer 14` candidates re-select exactly one row; RER variants (incl. legacy `METRO`) group for ambiguity/follow-ups while `identifies` stays raw-mode strict; `none` preserved for Luna and clamped at the wire for Sol/Astra; Responses only on canonical `api.openai.com` base, proxy URL and GPT-4o stay on Chat Completions; truncated streams fail; non-encrypted reasoning references are not replayed; Workspace retriever refuses `Orphee` and keeps `Aurora; Orphee` to Aurora only.

I traced the main paths by hand (e.g. `line M10` → `strippedMode` → `namedLine="m10"` → `exactLines` hit; `ligne M 10` → `SPACED_M_CODE` compaction; `metro M1` with `modes=SUBWAY` → inMode labels `["M1"]` → literal hit; `RER C` anchor → group `{REGIONAL_RAIL, SUBURBAN}` minus the anchor). Loops in `lines()`/`modes()`/`strippedMode()` all strictly shorten their input, so no termination/NPE issue.

## Remaining items (none blocking)

### Confirmed-by-reading, low impact

**T1. Line-only ambiguity uses raw `distinct()`, not `distinctDirections`.**
`lineSelector != null && directionSelector == null && selectedDirections.size > 1` counts raw strings, while every other direction check normalises.
- Reproducer: board `14 → "Aéroport d'Orly"` and `14 → "Aeroport d’Orly"` (feeds do vary typography across trips), `line="14"` → `ambiguous_direction`, `candidates` = two spellings of one destination. No rows are lost on re-selection (exact normalised match catches both), so cosmetic only: the wearer is asked a non-question.
- Smallest fix: `TransitLabelMatch.distinctDirections(selectedDirections) > 1` in that branch (candidates may still list both raw labels; acceptable).

### Judgment calls, not bugs (stating so you can confirm intent)

**T2.** Direction-only query whose exact headsign is served by two *different* lines (e.g. bus 183 "Orly" and T7 "Orly") returns `filtered` and sets `focus.line` to the first row. This is a focus on the departure the model actually reports, not a silent choice among unresolved alternatives, so it does not violate "ambiguity must not establish a focus". OK as is.

**T3.** Neutral `ligne 3a` with only `T3a` on the board → `no_matching_line` (tram-code stripping is gated on an explicit `tram`). The response's `lines` list lets the model retry with `T3a`. Consistent with the earlier "Tram3a without bus3a" decision.

### Speculative — depends on paid-endpoint / model behaviour, not verifiable here

**M1. Reasoning replay order.** `body()` always emits all encrypted reasoning items for a call *before* the assistant preamble message, and coalesces multiple reasoning items. Covered order (reasoning → text → call) is tested over real HTTP; other orders the live API may emit (text → reasoning → call, or two reasoning items) are replayed reordered. If the public API validates "reasoning item followed by its own output item", that would surface as a 400 in round ≥ 1 (no tools-off fallback there, so it would fail loudly, not silently). Hardening if ever needed: keep one ordered per-pass list of `(reasoning | message | function_call)` items and replay it verbatim — a refactor, so not for this round.

**M2. `usesResponses` compares against `preset.defaultBaseUrl` without `trimEnd('/')`** while the live URL is trimmed. Harmless if the catalog constant has no trailing slash (not shown). One-token fix: `== preset.defaultBaseUrl.trimEnd('/')`.

**M3. Round-0 tools-off fallback on any 4xx (401/429 included) also applies to Responses.** A 400 from the public API for `include`, `max` effort, or a rejected strict schema would re-run without tools and could yield a confident "I cannot look" answer. Already documented as the paid-endpoint follow-up; smallest containment when a key is available: flag the exception with the endpoint kind and skip the fallback for Responses (or only fall back on 400 whose body mentions `tools`/`schema`).

**M4.** `max`/`xhigh` acceptance on the public API for Sol/Astra and empty `instructions` remain unverified live (documented limit).

**W1.** All Workspace guarantees beyond retrieval are prompt-level; the JeanDupont/JeanMartin two-pass probe is still a backend probe, outcome pending. Code-level consistency is fine: `maxExecutionsPerTurn = 1` matches both texts; `validate` enforces `WorkspaceLimits.MAX_QUERY_CHARS` while the schema says `maxLength: 240` — confirm those are equal (constant not shown).

**C1.** Skill-fingerprint renewal for the new `line` maxLength 80 is enforced by the catalog/hub path not included here; the contract↔catalog unit test passing implies the catalog changed, so renewal should trigger — unverified in this review.

### Out of scope, pre-existing (noting, not asking for a change)

- `beforeSend` is invoked twice per pass (`OpenAiCompatProvider.streamPass` and `OpenAiCompatApiClient.streamChat`). Only matters if the hook is non-idempotent.
- Chat Completions path still sends `reasoning: {effort}` (OpenRouter shape) rather than OpenAI's `reasoning_effort`; unchanged migration territory.

## Bottom line

No confirmed defect in the current source that would select another mode, establish a focus on ambiguity, drop Luna `none`, route a non-canonical URL to Responses, replay undecryptable reasoning, or pull unrelated Workspace documents. T1 is the only confirmed (cosmetic) nit; everything else is deployment/model behaviour to validate with a live key or device, which this run does not claim.
---

# Final delta review (read-only; traced by hand, nothing executed)

## Verdicts

| Area | Verdict | Change in this delta |
|---|---|---|
| Models | **SOLID** (carried forward, untouched) | none |
| Workspace | **SOLID** (carried forward, untouched) | none |
| Transit | **SOLID** | one-line change is correct; no new defect introduced |

## The delta, traced

`lineSelector != null && directionSelector == null && TransitLabelMatch.distinctDirections(selectedDirections) > 1`

- `distinctDirections` → `words()` → NFKD, strip marks, lowercase, split on `[^\p{L}\p{N}]+`.
- `"Aéroport d'Orly"` → `[aeroport, d, orly]`; `"Aeroport d’Orly"` (U+2019 is `Pf`, so it splits) → `[aeroport, d, orly]`. Count = 1 → branch not taken → falls to `filtered` with both rows; `focusRow` = first non-cancelled → `focus.departure` present. Matches the first half of the regression.
- Add `"Olympiades"` → `[olympiades]`; count = 2 → `ambiguous_direction`; `ambiguous` is true so `focus.departure`, `line`, `direction` are omitted. Matches the second half.
- `now += 31s` exceeds the 30 s board reuse so the second call sees the refreshed board; rows at +2/+3/+4 min are still upcoming. Test setup is sound.
- The branch now uses the same normalisation as the two direction checks above it (`distinctDirections(headsigns)`), so the three checks are consistent. No other caller of `selectedDirections.size` remains.

**No regression:** the `headsigns != null` ambiguity branch, the `ambiguous_line` branch, `candidates`, and the `focus` suppression are unchanged.

## Residuals (confirmed by trace, non-blocking, pre-existing rather than introduced)

**R1. `followAnchor` groups by raw `directionOf(it) == anchor.direction`, not by normalised words.**
Reproducer: board `14 → "Aéroport d'Orly"` (+2), `14 → "Aeroport d’Orly"` (+3). `line="14"` → `filtered`, 2 rows, focus on row 1. Follow-up `after=<row 1>` → `group` contains only row 1 (row 2's raw headsign differs) → `after_anchor` with **zero** rows, after the wearer just heard two.
- Pre-existing: `direction="Orly"` already reached the same state before this delta (containsAll match → both rows, focus on row 1). The delta widens exposure to line-only queries but does not create the inconsistency.
- Fails closed (no wrong departure is reported), so not a blocker.
- Smallest fix, in `followAnchor`'s `group` filter: replace `directionOf(it) == anchor.direction` with `TransitLabelMatch.distinctDirections(listOf(directionOf(it), anchor.direction)) == 1`. Leave `identifies` raw-strict as decided.

**R2. `candidates` for `ambiguous_direction` still lists raw `selectedDirections`.**
In the Olympiades case the model receives three candidates for two destinations (`Aéroport d'Orly`, `Aeroport d’Orly`, `Olympiades`). Cosmetic; re-selection of either spelling resolves to both rows via normalised exact match. Optional fix: a small public `distinctDirectionLabels()` in `TransitLabelMatch` keeping the first raw label per `words()` key, used only for `candidates`. Fable already called this acceptable; I agree.

## Speculative items

None new. Prior documented paid-endpoint / device limitations are unchanged by this delta and I am not reopening them.

## Required before merge

None. The delta is correct and its regression test exercises exactly the intended distinction. R1 is a two-line optional hardening; R2 is cosmetic.

---

# Round 5 final delta review (read-only; traced by hand, nothing executed)

## Verdicts

| Area | Verdict | This round |
|---|---|---|
| **Models** | **SOLID** | Carried forward, untouched. |
| **Workspace** | **SOLID** | Carried forward, untouched. |
| **Transit** | **SOLID** | Delta is correct; no defect introduced. |

## The delta, traced

**1. `distinctDirectionLabels` = `headsigns.distinctBy(::words)`.** Keeps the first raw spelling per normalized word-key; also subsumes the old raw `.distinct()`. `::words` is a bound reference to the object's private member, same pattern `distinctDirections` already used — compiles, and the reported green build confirms.

**2. `selectedDirections` now deduped by words.** Consequences:
- Line-only ambiguity branch: `distinctDirections(selectedDirections) > 1` is now equivalent to `selectedDirections.size > 1` (already word-distinct). Redundant but harmless and self-documenting.
- `candidates` for `ambiguous_direction`: one entry per destination, first raw spelling retained. Resolves round-4 R2.

**3. `followAnchor` group membership by `distinctDirections(listOf(directionOf(it), anchor.direction)) == 1`.** `identifies` untouched (raw-strict). Resolves round-4 R1/Opus interaction:
- The `found` row still raw-matches the anchor in the no-tripId path, so it remains in `group` and `indexOfFirst { it === found }` works.
- tripId path where the live headsign drifted: `found` may be outside `group`; falls to the pre-existing `isAfter(found.departure)` fallback — unchanged behavior.
- Empty headsigns: `words("") == []` on both sides → grouped. Fine.

**Test trace.**
- Board `14 "Aéroport d'Orly"` +2, `14 "Aeroport d’Orly"` +3. `line="14"`: `modes("14")` → null; `lines` exact `14`; `selectedDirections` = `["Aéroport d'Orly"]` (both → `[aeroport, d, orly]`; U+2019 and `'` are both `NOT_WORD`); count 1 → `filtered`, 2 rows, `focus.departure` = +2 row (grouped → line/direction also set). ✔
- `after=<that>`: same `now`, cached board; `group` = both rows (word-equal); `matches` = exactly the +2 row (raw direction / tripId); index 0 → `drop(1)` = `[+3 "Aeroport d’Orly"]`, `after_anchor`. ✔ Asserts exactly the round-4 reproducer.
- Append `Olympiades` +4, `now += 31s` → past `BOARD_REUSE_MS`, refetch; `selectedDirections` = `["Aéroport d'Orly", "Olympiades"]`; count 2 → `ambiguous_direction`; `ambiguous` suppresses `focus.departure`; `candidates` set matches. ✔

**Nothing else changed:** `ambiguous_line`, the `headsigns`-based ambiguity branch, focus suppression, and `identifies` are byte-identical in behavior.

## Residuals (none blocking, none introduced by this delta)

- **Cosmetic, pre-existing:** the `directions` output (`onLine.map(::directionOf).distinct()`) and `focus.groups` (`distinctBy Triple(..., directionOf(it))`) still use raw distinctness, so both typographic spellings can still appear there. Harmless (re-selection of either spelling resolves to both rows); optional one-token alignment via `distinctDirectionLabels` if ever desired. Not in scope of this delta.
- **Judgment, consistent with existing design:** word-level grouping treats headsigns differing only in symbols/punctuation (e.g. `Orly ✈` vs `Orly`) as one direction. Digits and letters still distinguish (`Platform 1`/`Platform 2`, `(A)`/`(B)`). This is the same rule `directions()` already applies for its second-tier exact match, so the three checks now agree.
- Raw-strict `identifies` still fails closed (`board_changed`) if the anchored row's own headsign flips typography between fetches without a tripId — accepted in prior rounds, unchanged here.

## Required before merge

None. The two code lines plus the helper do exactly what round 4 asked, the regression covers both the anchored follow-up and the genuine-ambiguity boundary, and no mode-selection, focus-on-ambiguity, or anchor-identity property is weakened. No speculative deployment concerns reopened.
