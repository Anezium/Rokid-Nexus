# Persistent Workspace index: Fable 5.1 plan review

Reviewed on 2026-10-10 against fb450515 and Astra's proposed plan 027.
This is a source-snapshot architecture review, not executed implementation or QA.

## API provenance

- Direct Anthropic Messages API, explicitly requested by the user; no Cursor.
- Requested and returned model: `claude-fable-5-1`; effort: high.
- HTTP 200, end_turn, message-stop observed, complete response.
- Started UTC: 2026-10-10T09:44:18.201625+00:00; elapsed: 194.1 seconds.
- Snapshot: 16 files, 182993 characters.
- Usage: 69617 input tokens, 14885 output tokens including thinking.
- Private prompt, source SHA-256 manifest, raw review and receipt are retained in the existing QA temp directory.

## Decision

Fable agrees with the staged lexical/indexing first delivery and explicit deferred
semantic milestone, conditional on the five required corrections below. Astra
must incorporate and confirm them before the parent delegates implementation.
No production code or device change was made by this review.

## Completed API response

AGREEMENT: YES WITH REQUIRED CHANGES

**Overall judgement**

Plan 027 resolves my earlier objections: runtime file resolution before the first provider call, nullable `file` scope on the existing search tool, text and page references returned together, ≤2 dependent rounds, a bounded turn object instead of a ledger, availability split for textless folders, the `workspaceRetracts` fix, page inventory, coverage in every result, and diagnostics that preserve the 150 ms gate without blind timeout changes. Budgets (10,002 / 2,500 / 4 rounds / 8 calls / 60 s), hub 1.6.0, bus/SDK/permissions, no cloud, no model install, no background lifetime, no alias dictionaries are all respected. The Orion cause remains explicitly unproved; the plan does not claim otherwise.

**Staging verdict:** lexical/scoped A plus a truthfully deferred B meets the accepted objective. The acceptance ordered "precompute at selection/refresh/change, reuse at query time, then lexical + meaning-based"; delivering lexical first behind a measured B gate is staging, not substitution — provided the UI says "Search by words", the completion report says lexical, and B's gates are not relaxed to force a result. One honesty note the plan should state plainly: persisted postings are a modest gain (tokenizing 2,500 chunks at load is cheap). The real indexing improvement is the page catalog, unique file resolution, scoped retrieval, textless page access, dirty/retract fixes and truthful coverage. Blocker 1 ensures the cache never displaces that evidence.

**Essential blockers (5)**

**1. Postings must never displace retained text; migration must not retract.**
Problem: §A1 removes text chunks from the retention tail when postings/bytes overflow. Postings are derivable from stored text with the versioned tokenizer; dropping text to store a cache withdraws real evidence and would bump the epoch at v2→v3 migration — violating "additive indexing should not withdraw evidence".
Correction: text retention follows exactly today's limits (2,500 chunks, 1M chars, 100k/file). Postings are an optional section flagged `lexicalPersisted`. If the full snapshot would exceed 8 MiB, write it without postings and rebuild the complete inverted index at load from stored text with the same tokenizer version. Results must be identical either way. `TRUNCATED` keeps its meaning (source text lost), never "postings did not fit".
Acceptance: 2,500 dense non-ASCII chunks with ~100k distinct terms publishes with all chunks retained; search results byte-identical with persisted vs rebuilt postings; migrating an unchanged v2 index leaves epoch unchanged; final UTF-8 assertion ≤ 8 MiB passes.

**2. No new question-time provider I/O in A; bound indexing re-hash.**
Problem: §A2 adds per-document `metadata()` provider calls to cached-text queries and re-hashes "previously indexed candidates even if metadata is unchanged" on any observed change. The first-question failure is undiagnosed; adding serial provider calls to the prefetch path before measurement risks recreating the suspected cold path. A DocumentsProvider notification cannot identify the changed file, so hashing up to 100 × 32 MiB per notification is disproportionate battery/I/O.
Correction: prefetch and `search` keep exactly today's checks (grant, validated, 150 ms root gate, generation/epoch). Text freshness relies on observer→dirty→reconcile→epoch; digest comparison stays on the view path where bytes are read anyway. Per-question metadata checks may follow only after A1 diagnostics report cold/warm root and metadata latency on the phone, as a separate reason-coded change. Full re-hash of metadata-unchanged files happens only on explicit `Re-index now` and for unknown-digest (legacy) documents, within existing pass deadlines; observer passes reconcile metadata and re-extract changed/unknown-digest files only.
Acceptance: fake gateway counters show zero `metadata`/`open` during prefetch and `search` on an unchanged index, one `root` call; a notification with unchanged metadata opens only legacy-digest files; `Re-index now` hashes within deadline and resumes PENDING next pass.

**3. Exact view scope and turn plumbing.**
Problem: "general-topic hits may register their own documents as candidate scopes" widens today's cited-page rule without a boundary; tools bind only `(generation, epoch)` while `turnCitations` is a @Volatile global replaced per question — insufficient to separate two turns at one epoch.
Correction: view scope = (a) explicitly resolved file: any page `1..min(pageCount,500)` whose metadata and digest verify; (b) any other hit: cited pages only, as today. Ambiguity/metadata choices grant nothing. `contextForQuestion` returns the `WorkspaceTurnAccess`; the plugin-internal `bindToTurn` (not SDK) receives it; both tools check captured turn identity plus version in `isAvailable`/`execute`; delete the global `turnCitations`; a new question or any invalidation kills the old object.
Acceptance: two turns at the same epoch — a tool bound to turn 1 cannot view a page cited only in turn 2; unscoped hit on page 4 of a 7-page PDF cannot view page 5; "orion pdf" resolved → empty-OCR page 5 viewable; same size/time but different digest → `source_changed`, no image sent.

**4. Query grouping contract.**
Problem: the plan removes the "and/et split" instruction but not the parser; `QUERY_PARTS` still splits "Jean et Marie Dupont".
Correction: `QUERY_PARTS` becomes `[;?]` only; tokenizer/lexical version bumps. Compound questions joined by et/and become one group under the existing ≥half-terms rule; document this recall change and update the compound test (the prompt already teaches semicolons). Entity mode remains "all tokens of the stated group in the body", not a person extractor.
Acceptance: "Jean et Marie Dupont" is one group and a Jean Martin passage is not returned; "code de Vega; horaire d'Aurora" satisfies both groups; the existing literal-Orion control still returns page 3.

**5. Per-page state in the reader and resume cursor.**
Problem: `PdfPageScanner.recognize` returns `""` both when the platform renderer is unavailable and when OCR found nothing; `PageTooDenseException` stops before the page, so a resumed pass retries it forever. The plan's `EMPTY/FAILED/TRUNCATED` states cannot be populated from the current reader.
Correction: `WorkspacePagedText` returns `pageCount` and per-page `(textState, visualState, renderState)`: renderer null → text `FAILED`, render `UNAVAILABLE`; OCR ran, blank → `EMPTY`, render `AVAILABLE`; too-dense → `TRUNCATED` for that page; OCR timeout → `FAILED`. The cursor always advances past a terminal page state; `FAILED` is not retried until source version changes. Images: one page numbered 1; legacy page 0 normalized internally only.
Acceptance: fixtures for protected-render PDF, blank scan, too-dense page 2 of 5: page 2 marked, pages 3–5 extracted on resume, `refresh()`'s progress guard terminates; coverage distinguishes "empty OCR 2" from "not rendered 2".

**Contract confirmations (agreed, with precisions)**

- Storage: single v3 JSON in the existing directory and atomic writer; 8 MiB incl. one 8 MiB temp; 100k terms / 250k pairs are structural caps subject to blocker 1; fail-closed load validation of sorted postings, ranges, duplicate IDs, generation.
- Pages: sorted merged runs, implicit `UNATTEMPTED` tail, ≤500 explicit entries/doc, ≤50k total, no allocation from untrusted `pageCount`; >500 pages keep the true count, extraction/view capped at 500, UI says so.
- Migration: settings/grant kept; v1 paged dropped as today; v2 chunks kept with `LEGACY_UNKNOWN` on their pages, `pageCount=null`, `coverageKnown=false`, `lookupSafe=false`, `sourceDigest=null`, `nextPage=pagesRead+1` for PENDING; legacy PDFs remain viewable on cited pages exactly as today until reconciled, wider scope only after `pageCount`+digest are known; failed write keeps v2.
- Versions: `extractionVersion` and `lexicalVersion` independent — tokenizer change rebuilds postings only; extraction change re-extracts within budgets, catalog retained.
- Coverage: per-turn counters; known/attempted/retained/searched/supplied/viewed kept distinct; `no_match` never "absent".
- Ranking: existing BM25-like body weighting plus 0.1 metadata bonus; resolved-file mode strips only filename/extension tokens, lifts the 2-per-file cap, whole document in page order when it fits, else ≤3 of top 20; deterministic ties.
- Tools: strict schema `required:["query","file"]`; second search only on distinct normalized `(query,fileRef)`, identical retry served from the turn cache; one view, 8 s, 1,600 px, 1,000,000-byte JPEG on its own channel; search result ≤2,500 chars and ≤12 KiB incl. framing; zero prompt budget injects nothing; Hermes text-bridge subset test must cover nullable `file`.

**Optional later work (not A):** cross-turn source hint; separate read tool; interactive allowance above 7,500; per-question metadata checks after measurement; hashed TF-IDF experiments; B encoder evaluation under its stated 64 MiB / 128 MiB / 200 ms gates.

**Decisions to carry into implementation**

1. Text retention unchanged; postings optional and rebuildable; migration never retracts or bumps epoch for unchanged v2 content.
2. Prefetch/search I/O identical to today; digest check only on view; re-hash only on `Re-index now` or unknown digest.
3. View scope: resolved file → all cataloged pages verified by metadata+digest; otherwise cited pages only.
4. `WorkspaceTurnAccess` passed through `contextForQuestion` → plugin-internal `bindToTurn`; global `turnCitations` removed.
5. `QUERY_PARTS = [;?]`; compound test updated; lexical version bumped.
6. Reader returns per-page states; cursor advances past terminal states; `EMPTY` ≠ `FAILED`.
7. Legacy v2 PDFs: `LEGACY_UNKNOWN`, no inferred `pageCount`, cited-page view preserved.
8. 150 ms gate, Memory 10,002, prefetch 2,500, 4/8/60 s unchanged; diagnostics first, no timeout change without reproduction.
9. UI/report say "Search by words"; B remains deferred and so labelled.
10. Opus integrates on `qa/skills-workspace-typed-160` only after Astra confirms items 1–5 are folded into plan 027.

## Focused second API review

The first five required corrections were incorporated by Astra. A focused
second review resolved the caller-based grouping rule and the existing Hermes
exclusion. It agreed with staged lexical A and deferred semantic B, with two
remaining concrete corrections: suppress stale final effects only after text
or pixels were supplied, and reuse retained legacy text without re-OCR.

- Actual model: `claude-fable-5-1`; high effort, direct Anthropic API.
- HTTP 200, end_turn, message-stop observed, complete response.
- Started UTC: 2026-10-10T09:55:38.643468+00:00; elapsed: 132.5 seconds.
- Snapshot: 9 files, 150492 characters, with full prior review and parent/Astra responses.
- Usage: 55975 input tokens, 9745 output tokens including thinking.

Its conditional agreement requires incorporation and Astra confirmation of
those two corrections before Opus starts; it does not require another routine
review round. No implementation, build or device QA was performed by this review.

### Completed second API response

AGREEMENT: YES WITH REQUIRED CHANGES

**Overall judgement**

The revised plan 027 incorporates all five prior blockers faithfully, and I verified each against the snapshot rather than the summary:

1. §A1 "Growth, atomicity, and truncation" makes postings an optional derivable cache (`lexicalPersisted`), keeps text retention at today's 2,500 / 1M / 100k limits, reserves `TRUNCATED` for lost source text, and states that unchanged v2→v3 migration never bumps the epoch. Byte-identical results with persisted vs rebuilt postings are a stated gate.
2. §A2 adds no question-time provider I/O; the `root()`→`metadata(rootId)` delegation is correctly separated from extra source-document calls in the counter requirement. Full re-hash only on `Re-index now`, resumable under the pass deadline. Freshness is honestly described as metadata-dependent, not cryptographic.
3. §A3/§A4 define exact view scope (resolved file → `1..min(pageCount,500)` with metadata+digest; everything else → cited pages only), pass `WorkspaceTurnAccess` through `contextForQuestion` and plugin-private `bindToTurn`, and delete the global `turnCitations`. Legacy keeps baseline cited-page view only.
4. `QUERY_PARTS = [;?]`, compound test updated, lexical version bumped.
5. The reader table gives per-page `(textState, visualState, renderState)`, every terminal state advances the cursor, `EMPTY` ≠ `FAILED`, no infinite retry.

**Grouping clarification: accepted.** The caller-based distinction is the right resolution of my phrase "entity mode". It uses no name recognizer, no capital-letter heuristic, no schema flag. Initial prefetch keeps the existing ≥half-terms gate (not lowered); the unscoped private tool requires all non-stopword terms of a group in the body, which mechanically enforces the complete stated subject for names-only queries and makes attribute terms constraints rather than identity claims; resolved-file search lifts the subject gate only inside the exact source. Recall loss for stricter private search is explicitly documented and benchmarked. Device negatives remain the attribution gate; no runtime theorem is claimed. This is consistent with the user's "no weakening unscoped entity protection" and "file identity does not prove person/row attribution".

**Hermes qualification: accepted.** `HERMES_TEXT_TOOL_NAMES` excludes both Workspace tools today; test that unsupported behavior as-is and strict nullable schema only on providers that support Workspace. Do not add a surface to satisfy a test.

**Staging verdict (unchanged):** lexical/scoped A plus a truthfully deferred B is a sensible staged rollout, not a substitution. The plan now states plainly that persisted postings are a modest load optimization and that the genuine indexing improvement is the page catalog, unique resolution, scoped retrieval, textless access, freshness fixes and coverage. The UI says "Search by words"; B's gates (64 MiB / 128 MiB / 200 ms p95) are targets, not claims; completion of the hybrid goal is explicitly not declared by A.

**Remaining essential blockers (2)**

**1. Dirty-notification invalidation must not suppress answers that used no Workspace evidence.**
Problem: §A2 says "an unknown change notification makes the active snapshot dirty immediately and invalidates current turn scopes before debounce" and "an observed source change during a provider pass must also suppress final HUD, speech, and retained-answer publication from that stale turn", while §A3 creates a `WorkspaceTurnAccess` for every question. Combined, a user adding an unrelated file (or a provider's spurious notification) during "what time is it" would suppress the answer. That is a regression for the dormant-plugin case and is not required by fail-closed source guards, which protect evidence, not unrelated speech.
Correction: invalidation withdraws the turn's Workspace access (tools become unavailable, pending views/searches return `source_changed`, the resolved-file map dies) unconditionally. Final-effect suppression applies only when the turn actually delivered Workspace text or pixels to the provider (prefetch excerpts non-empty, or a search/view result returned) before the notification. A turn with an empty evidence set keeps its answer. Record the distinction in the turn object (`evidenceSupplied: Boolean`), set only when text/pixels are returned — matching the plan's own rule that "only text or pixels actually returned to the model count as evidence".
Acceptance: fake-gateway notification during (a) a question with empty prefetch and no tool call → answer published, Workspace tools unavailable for the remainder; (b) a question after prefetch excerpts were injected → final HUD/speech/retained answer suppressed, reason coded; (c) notification between search and view → view returns `source_changed`, no image sent. The "pure append same source" race case (§regression table) must document that evidence already supplied is kept only when no notification fired; publication alone never retracts.

**2. Legacy v2 reconciliation must reuse retained text pages, not re-OCR them.**
Problem: §Migration says "pages lacking extraction state may be revisited to establish coverage". Every legacy page lacks per-page state (`LEGACY_UNKNOWN`), including pages that already carry retained chunks, so a full upgrade could re-extract the whole corpus. That contradicts the accepted "precompute, reuse" objective and the plan's own statement that unchanged upgrade reuses validated text.
Correction: during legacy reconciliation, read bytes once to record `sourceDigest` and `pageCount` (indexing-time, under pass budget). Pages that already have retained chunks become a text-present legacy state (`LEGACY_TEXT`: text observed, OCR/native and visual unknown) with no re-extraction unless `extractionVersion` changes or the digest differs. Only pages with no chunk inside `1..pageCount` remain `LEGACY_UNKNOWN` and are revisited, resumably, after all new/changed-file work in the pass. Neither state claims completeness; coverage reports them as "text retained, extraction state unknown" and "not verified".
Acceptance: fake reader counters show zero page extractions for a legacy INDEXED PDF whose digest/count are established and whose every page has a chunk; a legacy PENDING PDF resumes at `pagesRead+1`; a legacy PDF with chunk-less pages revisits only those pages; retained legacy text and epoch unchanged throughout; explicit-file scope minted only after count+digest exist.

**Contract confirmations for Opus (agreed, with precisions)**

- Storage: one v3 JSON in `noBackupFilesDir/assistant-workspace`, existing atomic writer, ≤8 MiB UTF-8 asserted at write, ≤2 MiB catalog/framing reservation tested with 100×500 alternating runs, 100k-term / 250k-pair cache ceilings; exceeding omits the cache, never text; fail-closed parse of sorted postings, positive frequencies, ranges, duplicate IDs, generation, before large allocation.
- Pages: 1-based, sorted merged runs, implicit `UNATTEMPTED` tail, ≤500 explicit entries/doc, ≤50k total; >500 pages keep the true count with extraction/view capped at 500 and UI/coverage saying so; no allocation from untrusted `pageCount`; images are catalog page 1 with page 0 normalized internally.
- Resume: `nextPage` separate from coverage; every terminal page state advances it; `FAILED` not retried until source version/extraction version changes or explicit re-index; progress guard in `refresh()` terminates.
- Migration: settings/grant kept; v1 paged dropped as today; v2 chunks kept with blocker 2's states, `pageCount=null`, `coverageKnown=false`, `lookupSafe=false`, `sourceDigest=null`; failed write keeps v2; no epoch bump for unchanged content.
- Versions: `extractionVersion` and `lexicalVersion` independent. Note the tokenizer is unchanged; the `[;?]` change is query-time, so the one-time cache rebuild on lexicalVersion 2 is harmless but not required for correctness.
- Coverage: per-turn counters only; known/attempted/retained/searched/supplied/viewed distinct; `no_match` never "absent"; coverage consumes the same budget; zero budget injects nothing.
- Ranking: existing BM25-like scoring and 0.1 metadata bonus; resolved-file mode strips only filename/extension tokens, lifts the 2-per-file cap, whole document in page order when it fits, else ≤3 of top 20. Precision: an empty remaining query in resolved scope returns page-ordered passages from the start plus the catalog line, deterministically.
- Tools: strict schema `required:["query","file"]`; two searches only on distinct normalized `(query,fileRef)`, identical retry served from the turn cache without resetting shared budget; one view, 8 s, 1,600 px, 1,000,000-byte JPEG on its own channel; search result ≤2,500 chars and ≤12 KiB including framing; metadata choices ≤5 with count, no body; rendered image carries runtime file/page metadata; provider vision gating preserved.
- Guards: 150 ms root gate, Memory 10,002, prefetch 2,500, 4 rounds / 8 calls / 60 s round-start unchanged; diagnostics first; no timeout change without reproduction; Orion cause stays unproved.

**Optional later work (not A):** cross-turn source hint; separate read tool; per-question metadata checks after measured latency; interactive allowance above 7,500; hashed TF-IDF experiments; B encoder evaluation under its stated gates.

**Decisions to carry into implementation**

1. Blockers 1 and 2 folded into plan 027 with Astra's confirmation before Opus starts on `qa/skills-workspace-typed-160`.
2. Notification: always withdraws access; suppresses final effects only when `evidenceSupplied`.
3. Legacy reconciliation: digest+count first, `LEGACY_TEXT` for pages with retained chunks, no re-OCR without extraction-version/digest change.
4. Postings optional and rebuildable; text retention unchanged; `TRUNCATED` = source text lost.
5. Prefetch/search I/O identical to today; one root check; digest only on view, re-index, or unknown digest.
6. View scope: resolved file → cataloged pages verified by metadata+digest; otherwise cited pages only; ambiguity grants nothing.
7. `WorkspaceTurnAccess` via `contextForQuestion` → private `bindToTurn`; global `turnCitations` removed; same object across fallback rounds.
8. `QUERY_PARTS=[;?]`; caller-based qualification table as written; compound test updated; recall changes benchmarked.
9. Reader per-page states; cursor always advances; `EMPTY` ≠ `FAILED`.
10. Hermes exclusion preserved and tested as unsupported.
11. UI/report say "Search by words"; B deferred and labelled; no gate relaxed to force a result.

## Final conditional agreement and handoff

Astra read both complete API responses, incorporated every required correction
into [plan 027](../../plans/027-assistant-persistent-workspace-index.md), and
explicitly confirmed agreement with no unresolved design exception on 2026-10-10.
The parent verified the final delivery flag, legacy inspection contract and
handoff section before delegating implementation.

The last two corrections add `evidenceSupplied` to distinguish withdrawn access
from stale final output, and a reader inspection seam to discover legacy page
count/digest without re-extracting retained text pages. A v2 index has no known
historical digest; establishing its first digest does not prove cryptographic
continuity of old text. The documented provider metadata/notification limitation
remains. Neither metadata choices nor omitted text count as supplied evidence.

Fable's conditional agreement is satisfied by those incorporated corrections
and Astra's confirmation. This is the parent's handoff decision, not an invented
third Fable API verdict. The agreed implementation scope is Delivery A. Delivery
B, the actual local semantic encoder and hybrid search, remains outstanding.
Implementation and device acceptance are not established by architecture review.
