# Workspace architecture: Fable 5.1 API review

Status: architecture review only; no implementation, build, or device test in
this pass. Reviewed on 2026-10-10 (Europe/Paris) against d23e6653 and the
untracked [Astra proposal](workspace-retrieval-architecture-astra.md).

## Provenance

- Direct Anthropic Messages API; explicitly authorized by the user after the
  Claude subscription task failed to produce an opinion.
- Requested and returned model: claude-fable-5-1; effort: high.
- HTTP 200; stop reason: end_turn; stream message-stop observed.
- Request started UTC: 2026-10-09T22:47:50.609965+00:00; elapsed: 187.8 seconds.
- Snapshot: 15 documents/source/test files, 146285 characters;
  private prompt and SHA-256 manifest retained in the existing QA temp directory.
- Usage: 58128 input tokens, 12180 output tokens
  including thinking. No model fallback or Cursor review.

## Verdict summary

Fable agrees with precise file resolution, in-file retrieval, page access
independent of citations, textless-page inventory, truthful coverage, and
deferring semantic ranking until benchmarked. It considers the first proposed
delivery too large and recommends diagnosis followed by a smaller scoped slice.

Its preferred first slice resolves files in the runtime before the initial
provider call, adds an optional file scope to the existing search tool, returns
matching text and page references together, and permits viewing valid pages of
a file resolved for the current turn. It would defer separate model-visible
resolution/read tools and elaborate handles/coverage infrastructure until
measurement establishes a need. A common request should target at most two
fallback tool rounds, rather than spending four dependent rounds on resolution,
search, read, and view.

The review identifies plausible restart-related prefetch gaps: a loaded index
starts unvalidated, and the current root-access check has a 150 ms timeout.
Neither is proven to have caused the observed Orion refusal.

## Parent verification and qualifications

The latency/dependency-depth objection and the recommendation for a smaller
first slice are accepted as design feedback. Keep Astra's source/identity,
page-discovery, and coverage principles, implemented with the smallest runtime
state needed to enforce them. Retain the original proposal as the broader
architecture reference rather than silently replacing Astra's recommendation.

Three parts of the review require qualification before implementation:

1. The proposed restart failure chain is not established. Parent inspection of
   AssistantPluginService.kt:882 and :911 shows that the prefetch version also
   binds tool availability. A null validated snapshot yields revision -1,
   which ordinarily suppresses the same turn's Workspace search tool. Trace
   prefetch readiness/version AND available tools; do not assert that a cold
   prefetch failure directly caused the observed search/refusal. Do not enlarge
   timeouts or bypass readiness/grant checks without the reproduction.
2. Explicit file scope proves source identity, not every person attribution in
   that source. All name tokens in a block are a useful necessary gate, not a
   guarantee for mixed-person tables. Relative path strings can be used only
   with unique runtime resolution and current-turn/version checks; normalized
   or truncated display names are not authoritative identities. Textless-page
   lookup/view availability should be distinct from text-search readiness.
3. A coverage line consumes context too: zero remaining prefetch budget cannot
   be bypassed by inserting metadata for free. Choose and account for any new
   interactive allocation explicitly. SkillLimits.MAX_RESULT_BYTES describes
   Skills results; it is not a blanket 16 KiB limit for private Workspace image
   results. Bound text and images according to their actual runtime/provider
   channels, with cumulative evidence limits.

## Fable's detailed review

The English technical portion below is retained from the completed API
response. Its findings are architectural recommendations and hypotheses, not
executed regression results. The original French verdict and full raw response
are retained privately alongside the prompt/metadata.

## Prioritized objections (snapshot analysis only; nothing built or run)

### 1. [High] The four-tool design spends model passes, not just call counts, and leaves no-tool providers unimproved

**Scenario.** "Où a lieu la réunion de lancement dans orion.pdf ?" under the proposal: round 1 `resolve_workspace`, round 2 `search_workspace`, round 3 `read_workspace_pages` (annex not in top hits), round 4 `view_workspace_page`. That is the entire `ASSISTANT_MAX_TOOL_ROUNDS = 4` (SkillLimits.kt:71) and four streamed model passes inside `ASSISTANT_TURN_DEADLINE_MS = 60_000` (AssistantToolLoop.kt:65-70). The loop only checks the deadline before *starting* a round, so a 4-deep chain can easily end past 60 s. Call-count limits ("8 calls") are the wrong unit; dependency depth is. Providers without tools or with a text bridge (`malformedToolRequest`, AssistantToolLoop.kt:12) get only the prefetch, so any design that puts resolution behind a tool call gives them nothing.

**Correction.** Resolution and the first scoped retrieval happen *before* the first model request (the proposal says this in §4 but the tool table in §3 still lists `resolve_workspace` as a model-visible tool; drop it). Fold scope into `search_workspace` as a nullable `file` parameter (strict schema: `required:["query","file"]`, SearchWorkspaceTool.kt:25-26). Have scoped search return page references *with* the matching text so "search then read" is one round. Design target: ≤2 tool rounds for the common case; `read_workspace_pages` as a separate tool is a later slice, if measurement shows it is needed.

### 2. [High] The proposal designs around a miss it has not diagnosed, while the snapshot has restart-specific paths that could explain it

The Orion question was the **first question after the process restart** (device pass, line 68; the successful chart question came after, line 69). Two code paths matter only there:

- `WorkspaceStore.load()` builds `WorkspaceState(settings, index, problem)` with `validated` defaulting to `false` (WorkspaceStore.kt:111-113). `validatedSnapshot()` returns null when `!validated` (WorkspaceController.kt:328), so `contextForQuestion` yields **empty excerpts** until the attach-triggered refresh republishes.
- `validatedSnapshot()` races `gateway.root()` against `ACCESS_TIMEOUT_MS = 150` (WorkspaceModels.kt:24, Controller 329-338). A cold DocumentsProvider on a fresh process can exceed 150 ms; the result is `store.failed(CHECK_FAILED)`, again empty prefetch.

Either produces: no prefetch → the model calls `search_workspace` with a names-only query per the tool description ("Orphee", not "Orphee code", SearchWorkspaceTool.kt:18-19) → `qualifies` requires a body hit (`terms.any { it in passage.terms }`, Retriever 142-144) → page 3, where "Orion" is only in the filename, is excluded. This is a *consistent chain*, not an established cause; the body gate explains the follow-up, the empty prefetch would explain the first miss, and neither was recorded.

**Correction.** Before any architecture work: add reason codes to `contextForQuestion` (validated, access-timeout, budget, candidate count, rejection reason per top-N passage) and to tool execution (query length, matchCount, scope). Warm/validate the provider on `attach()` rather than at question time; make the first post-load access timeout longer or adaptive. The proposal's step 1 already asks for a repro; it should also name these two paths explicitly so the fixtures cover them.

### 3. [Medium] Opaque handles, a persistent coverage ledger and a full catalog are more than the first slice needs

Existing guards already bind a turn to a folder generation and index epoch: `searchVersion()`, `workspaceTurnGuard`/`beforeSend` (WorkspaceRequestGuard.kt), `hasSameContent` before any bytes leave the phone (Controller 254-256), and epoch checks after render (258). The model cannot widen access today because `viewPage` resolves a *name* against the index (237-241); a handle adds no security property over that. The genuine gaps are small and local:

- `isSearchAvailable()` demands `chunkCount > 0` (Controller 217), so a folder of textless scans has no search and therefore no view, even though `hasViewablePages()` would accept `NO_TEXT` (225, 395-396).
- `workspaceRetracts` ignores documents with empty chunks (WorkspaceStore.kt:129), so a replaced textless PDF does not bump the epoch — the proposal notes this correctly.
- `WorkspaceDocument` has `pagesRead` but no `pageCount` or per-page extraction state (WorkspaceModels.kt:83-89).

**Correction.** Add `pageCount` and a compact set of textless/failed page numbers to `WorkspaceDocument`; make availability = validated ∧ grant ∧ (chunks > 0 ∨ viewable document exists); extend `workspaceRetracts` to entry/pageCount changes for chunk-less documents. Keep handles as `(relativePath, page)` validated by the runtime. Defer the per-turn ledger to a *coverage line in tool results* ("searched 14 passages across 6 of 7 pages of orion.pdf; page 5 has no text") — that is what the model needs to make truthful partial claims.

### 4. [Medium] Attribution promises outrun what the extractor can enforce; replace the names-only prompt policy with two mechanical modes

The proposal requires a code to sit in "the requested person's block, enclosing section, or table row". The text layer comes from `PDFTextStripper` with no position sorting (WorkspacePageReader.kt:103, 115-116) and is re-chunked at ~600 chars (WorkspaceModels.kt:21-22); columns and multi-person tables interleave. Row attribution is therefore **model judgment**, not a runtime guarantee, and should be stated as such.

What the runtime *can* enforce:

- **Explicit-file mode** (resolver found a unique document): no subject gate at all; qualify on attribute terms within that file; lift the 2-per-file cap (Retriever 91-92). This directly removes the Orion annex miss class.
- **Entity mode** (no file resolved, question carries name tokens): require *all* name tokens in the passage body (necessary, not sufficient). This replaces "Names containing and or et are themselves split" (tool description, line 21; `QUERY_PARTS` Retriever 139) — "Jean et Marie Dupont" must not be split into topics.
- **Mixed-person tables:** when the page is flagged `visual` and two distinct name sets co-occur in the chunk, return the passage with a "verify layout by viewing" marker rather than silently.

Benign cross-references ("responsable : Jean Dupont" inside Jean Martin's file) remain reachable in entity mode; the final attribution stays with the model and the benchmark's wrong-person distractors.

### 5. [Low] Selection and resolution details that will bite the first slice

- Silent drops: when the budget is small, candidates after the first get an empty `body` and are skipped *without* a citation (Retriever 121-126), so the page can never be viewed this turn. Record them as uncited candidates and expose their page numbers in the coverage line.
- Voice/typed references lack the dot: "orion pdf" must resolve to `orion.pdf`; `WorkspaceTokenizer` already splits the filename into `orion`,`pdf`, so the resolver can match name-token sequences. Duplicate basenames must yield an ambiguity with both relative paths, never the first match (Controller 240 already uses `singleOrNull` for names — keep that).
- Memory budget: `workspacePromptBudget` can reach 0 (test line 117); when the question *explicitly names a file*, a zero prefetch should at least carry the resolved file's coverage line so the model knows to search, not to refuse.

---

## Minimal first deliverable

**S0 — Diagnose (no behavior change).** Reason-code diagnostics in `contextForQuestion`, `search`, `viewPage`; private synthetic fixtures: Orion-like 3-page PDF with "Orion" in body on pages 1-2, annex on page 3 without the name; a second file whose name/body share "reunion"; runs for: full question, "Orion" follow-up, Memory at 0/5 000/9 500 chars, first question ≤2 s after process start, question during a PENDING pass.

**S1 — Scoped mechanics.**
1. Runtime file resolver (unique name/basename/path; ambiguity → bounded choices).
2. Prefetch in explicit-file mode: in-file ranking, no subject gate, no per-file cap, whole document if the whole fits the remaining budget.
3. Entity mode gate (all name tokens in body); `and/et` no longer splits inside a name.
4. `search_workspace`: nullable `file`, two executions per turn when the normalized query or scope differs.
5. `view_workspace_page`: any valid page of a file resolved *this turn*; textless pages included.
6. Page inventory (`pageCount`, textless pages), availability and retract fixes.
7. Coverage line in every tool result.

**S2 — Measure, then decide** on `read_workspace_pages`, on-demand OCR beyond the cache, and the interactive character cap. **S3 —** frozen benchmark; semantic ranking only against demonstrated misses.

## Key acceptance checks

- End to end on the Orion fixture: full question answers "Lyon, salle Bellecour" citing page 3 at full *and* reduced Memory budgets, and as the first question after process start; "Orion" follow-up returns page 3.
- Wrong-person fixture (Dupont asked, only Martin present) still refuses and does not emit Martin's code; "Jean et Marie Dupont" is not split.
- Missing-file fixture ("Orphee") returns no generic code from other files.
- Textless-only folder: search tool unavailable but view tool available; replacing the scan bumps the epoch and invalidates the turn.
- Duplicate basenames: ambiguous result listing both paths; nothing fetched.
- Budget: common case ≤2 tool rounds; every tool result ≤ `MAX_RESULT_BYTES`; prefetch never exceeds `workspacePromptBudget`.
- Coverage line present in every result; an exhausted-budget answer says "not found in the examined pages (1-4 of 7)", never "absent".

**Not enforceable by runtime (model judgment, benchmark only):** row-level attribution inside flat PDF text, truthful phrasing of partial coverage, deciding when to view rather than answer from text.