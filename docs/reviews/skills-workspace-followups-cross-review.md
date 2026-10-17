# Skills QA follow-up fixes and cross-review

Date: 2026-10-09. Worktree: `RokidNexus-skills-workspace-typed-qa`.
Branch: `qa/skills-workspace-typed-160`; starting HEAD: `d100c2ac`.
Scope: fix the findings from the Astra/Fable review of model migration,
Transit selectors, and Workspace follow-up searches, then review with Opus 5.5
and Fable 5.1 through the Anthropic API. No Cursor review was used.

## Corrections

- Transit retains an explicit bus, metro, tram, or RER mode constraint. Generic
  `line`/`ligne` remains neutral. Mixed modes with the same route number are
  ambiguous; a tram selector also recognizes `tram 3a` for a TRAM `T3a`.
- Without a named line, both exact and partial destinations are returned for
  clarification. `Orly` no longer hides `Aéroport d'Orly` behind an Orlybus.
  Exact destination precedence is retained within a named line.
- Ambiguous answers omit the selected departure, line and direction from focus.
  Follow-up departure anchors retain their transport mode. Focus groups include
  their mode, and the skill schema declares it.
- The Transit changelog explains required reapproval of "Departures at a stop".
  The canonical contract fingerprint intentionally covers descriptions and
  schemas; the consent mechanism is preserved.
- Workspace follow-up searches use missing subjects' proper names alone, without
  generic attributes, in every language. Separate subjects use semicolons.
  General-knowledge questions do not require a Workspace search, and document
  refusal follows the allowed search. The lexical retriever is unchanged.
- Tests now exercise names-only follow-ups and French distractors containing
  overlapping attribute words, rather than relying on zero French/English overlap.
- Luna preserves `none`; Sol and Astra clamp that saved choice to `low` at the
  request boundary. Settings explain that distinction.
- OpenAI GPT-6 requests use Responses rather than Chat Completions. The transport
  adapter converts history, tool calls/results and images, and maps streamed text,
  completed tool calls and errors into the existing provider loop. Other presets
  and legacy OpenAI models retain their existing Chat Completions path.
- Review follow-ups preserve literal tram/bus labels such as `M10`/`M41`, emit
  selectable mode candidates, and accept suburban/regional rail vocabulary.
  Spaced/hyphenated `M 10`/`M-10` and explicit `metro M1` preserve literal codes;
  `metro 1` still requires clarification when both `M1` and `1` exist.
  Coach/ferry and other supported feed modes have selectable prefixes. Input
  selectors allow 80 characters; raw output line labels retain their 16-character
  bound. RER follow-ups and focus groups use the same commuter-mode grouping,
  while anchor identity verification retains strict raw-mode equality.
  The Transitous v1 `METRO` commuter mode belongs to RER, while `SUBWAY` is metro;
  [MOTIS schema](https://github.com/motis-project/motis/blob/master/openapi.yaml)
  documents the later rename from `METRO` to `SUBURBAN`.
- Responses encrypted reasoning items are retained in memory within the tool
  turn and replayed before assistant preambles and function calls. Items lacking
  encrypted content are omitted. The adapter requests the
  compatible encrypted-content include and rejects a premature stream EOF.
- Saved custom OpenAI base URLs retain Chat Completions routing; automatic
  Responses routing applies to GPT-6 on the canonical OpenAI URL. This avoids
  silently changing the protocol of an existing proxy configuration.
- Workspace instructions require the full named subject to match and prohibit
  repeating personal details from unrelated excerpts when coverage is missing.
- Line-only destination ambiguity compares normalized words, so accent or
  apostrophe variants do not ask the wearer to distinguish the same destination.
  A genuinely different destination still requires clarification without a focus.
  The subsequent-departure group uses that same normalization, retaining strict
  raw verification of the actual anchor. Clarification candidates retain one
  original spelling per normalized destination.

The OpenAI endpoint change follows the official model documentation: Sol/Astra
require Responses for tool calling; Luna accepts `none`.
Sources: [Luna](https://developers.openai.com/api/docs/models/gpt-6-luna),
[Sol](https://developers.openai.com/api/docs/models/gpt-6.1-sol),
[reasoning](https://developers.openai.com/api/docs/guides/reasoning).
These public API contracts are distinct from the ChatGPT Codex backend catalog.

## Observed validation

Command:

```text
.\gradlew.bat :plugin-assistant:testDebugUnitTest :plugin-assistant:assembleDebug :plugin-transit:testDebugUnitTest :plugin-transit:assembleDebug :shared:testDebugUnitTest :bus-client:testDebugUnitTest -PskipCxrGlobal=true --console=plain
```

Real output tail:

```text
BUILD SUCCESSFUL in 7s
134 actionable tasks: 8 executed, 126 up-to-date
```

JUnit XML: Assistant 498, Transit 130, shared 386, bus-client 149 tests;
1,163 total, zero failures/errors.
The new real-HTTP local server test exercises two Responses passes: a streamed
photo function call executes once; its output and image are replayed; the final
text reaches `MessageDone`. Request routing, effort selection, failure and
incomplete events are covered separately. This is wire-level regression
coverage, not a paid OpenAI endpoint validation.
The two-pass fixture now also verifies encrypted reasoning replay. A truncated
HTTP stream produces `Failed`, never a completed answer.

Live requests to the ChatGPT Codex Responses backend, using a synthetic
"Reply with OK only" question and existing local authentication:

| Model | Effort | HTTP | Completed answer | Wall time |
|---|---|---|---|---|
| gpt-6-luna | none | 200 | OK | 1.24 s |
| gpt-6-luna | low | 200 | OK | 1.20 s |
| gpt-6.1-sol | low | 200 | OK | 1.99 s |
| gpt-6-astra | low | 200 | OK | 3.20 s |

This confirms accepted ids and efforts on that backend/account. One short
request per combination is not a comparative latency benchmark and does not
establish a twofold speed difference. No credential values are recorded here.

The compiled `NexusAgentPolicy` prompt and `SearchWorkspaceTool` description were
exported from the built classes and sent to the same backend with the actual
search schema. Nine first-pass policy checks completed successfully:

| Scenario | Luna / none | Sol / low | Astra / low |
|---|---|---|---|
| General question: Who is Marie Curie? | No search | No search | No search |
| Orphee's code in Workspace, no excerpt | `Orphee` | `Orphee` | `Orphee` |
| Vega/Aurora without a conjunction, only Vega excerpt | `Vega; Aurora` | `Aurora` | `Aurora` |

All requests returned HTTP 200 and a completed stream. This verifies the actual
first-pass prompts and tool arguments, not the final answer after retrieval or
the device keyboard flow. Names-only retrieval refusal is covered by unit tests.

A further live two-pass check used the compiled final prompt and a synthetic
search result for Jean Martin when the question asked for Jean Dupont's phone
number. Luna/none, Sol/low and Astra/low each searched `Jean Dupont`, completed
with HTTP 200, refused the unsupported attribution and did not repeat Jean
Martin's number. Before the final instruction, Luna had correctly identified
the mismatch but unnecessarily quoted the unrelated person's number. These
three observations validate that example, not all possible model responses.

## Independent API review

Opus 5.5 and Fable 5.1 receive the same immutable source snapshot, the original
review brief, prior findings, responses, current diff and complete relevant
files. No server-side model fallback is requested; returned model identity and
completion status are checked. Results below are from completed reviews.

Round 1 identified literal M-label filtering, unselectable mode candidates and
stateless reasoning replay concerns; these were corrected. Round 2 returned
Models/Workspace SOLID from both reviewers. Fable marked Transit SOLID; Opus
found the remaining spaced-M regression. Both supplied minor follow-ups on
preamble order, proxy URLs and rail grouping, corrected and regression-tested.
The single-argument effort helper was verified to be preference validation
only; the wire path uses the model-aware clamp.

Round 3 returned **SOLID for Models, Transit and Workspace from both reviewers**,
with no blocking defects. Both API calls returned HTTP 200 and `end_turn`;
actual model identities were `claude-opus-5-5` and `claude-fable-5-1`, with no
fallback. Their complete source-review responses are retained in
[Opus final review](skills-workspace-opus55-final.md) and
[Fable final review](skills-workspace-fable51-final.md).
Fable's final cosmetic finding about typographic destination duplicates was
then corrected with a regression covering both equal and different destinations.
Round 4 confirmed that delta as SOLID, then identified that subsequent departures
still grouped raw destination spellings. This was corrected, together with
duplicate clarification candidates; the same regression now exercises the
follow-up and the two distinct candidate destinations. **Round 5 confirmed SOLID
for all three areas from both reviewers**, with no introduced defect and nothing
required before merge. Both returned their exact requested model identity,
HTTP 200 and `end_turn`. Their round 4/5 responses are appended to the review
files linked above. All API reviews completed before the user's 20:45 deadline;
no subscription fallback or Cursor delegation was used.
Workspace's query-length constant was checked against its schema (both 240),
and the canonical OpenAI catalog URL was verified to have no trailing slash.

## Boundaries

- The new paid-key OpenAI route has local HTTP coverage and official contract
  verification, but has not been exercised against `api.openai.com` in this run.
- Workspace search is still lexical. Prompt guidance mitigates shortened-query
  false attribution; it is not a semantic identity guarantee. Names containing
  standalone `and`/`et` remain subject to the existing query-group separator.
- A rare board containing SUBWAY `M1`, SUBWAY `1` and BUS `1` can cycle between
  `1` and `metro 1` candidate sets when directions coincide. Keeping `metro 1`
  ambiguous on an `M1`/`1` board is intentional; a future qualified exact-label
  selector would be needed to distinguish that combination without changing
  the existing spoken alias contract.
- Overlong raw trip ids (over 160 characters) or unknown mode names (over 24)
  cannot validate a truncated anchor and produce `board_changed`. This existing
  behavior fails closed. Comparing truncated live identities, as suggested by
  Opus, is rejected because distinct identities may share the same prefix.
- An existing exact-direction preference can omit a spelling using a space
  instead of an apostrophe (`d Orly` versus `d'Orly`) when the wearer explicitly
  reselects the other spelling. The normalized follow-up group still includes
  both. Opus classified this as a low-severity pre-existing issue, optional for
  this round. Board-level direction/focus lists can also retain duplicate raw
  spellings. These do not select a different transport mode or weaken anchor
  verification; they are not presented as fully solved semantic equivalence.
- These fixes have not been installed or retested on the phone/glasses in this
  run. Earlier device results apply to their recorded revisions only.
- Root main, unrelated worktrees, `local.properties`, SDK/cache configuration,
  installed accounts and device preferences remain untouched.
- No main merge, public release or push is part of this correction run.
