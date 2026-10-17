# Workspace document retrieval architecture

Status: proposal, not implemented. Prepared on 2026-10-09 from a delegated
GPT-6 Astra source analysis and parent verification at baseline d23e6653.
This design pass did not build, test, install, or modify product code.

## Recommendation

Build Workspace around precise document/entity resolution, bounded scoped
search/read/view operations, and explicit evidence coverage. Add hybrid lexical
and semantic ranking after those foundations have passed a frozen benchmark.

Keep the feature in the Assistant phone plugin. Preserve the existing folder
grant, local extraction, configured-provider integration, and HUD answer path.
The proposal needs no hub/SDK/bus changes, new permissions, cloud embeddings,
remote OCR, or computation on the glasses.

## What is established, and what is not

The [final device pass](skills-workspace-final-device-pass.md) observed a refusal
for the launch-meeting location in Orion, although its third PDF page contains
the answer, Lyon, salle Bellecour. Subsequent page viewing worked after restart,
so that pass did not demonstrate lost folder access.

The exact failed turn's indexed chunks, first-request excerpts, prompt budget,
and search arguments were not captured. Its root cause remains unproved.
The full question has the meaningful terms Orion, PDF, lieu, reunion, and
lancement. The supplied page-three sentence contains three of those terms and
its filename supplies the other two. If retained and considered with sufficient
prompt space, that passage should qualify under the current lexical filter.
An Orion-only follow-up would exclude the page when Orion occurs only in the
filename, but that does not explain the first-request miss on its own.

Current source establishes broader limitations:

- The retriever selects at most three passages, with at most two per file,
  inside a first-request Workspace allocation of 2,500 characters. Remaining
  Memory/context space can reduce that allocation further.
- Search and page view each permit one execution per turn.
- Viewing requires a citation created by selected text passages. Discovery,
  relevance, evidence, and access authorization are therefore coupled.
- A page with empty OCR has no text chunk to generate the prerequisite citation.
  A folder containing only such pages cannot expose search through the current
  positive-chunk-count availability check.
- The index retains chunks and a temporary pending-page cursor, rather than
  durable page count, page extraction states, and coverage.

The earlier filename anchoring commit 4e6fed1a is not the proposed solution.
It still required the filename word in the passage body and relied on that
word being unique across unrelated documents. The revert 8557f391 provides no
additional rationale; do not invent one.

## 1. Resolve a source before searching attributes

Treat these targets separately:

| Target | Resolution | Evidence scope |
|---|---|---|
| Explicit file, such as “dans orion.pdf” | Exact relative path, then unique full filename, then an unambiguous normalized basename/extension or established alias | That exact document version |
| Named person or project | Preserve full identity and establish source-backed anchors | Blocks, sections, or rows attributed to that identity |
| General document topic | Discover bounded candidate documents | Candidate scopes with separate provenance |

Use an identity normalizer separate from the keyword tokenizer. Do not drop
name words or split identities on and/et. Normalize case, accents, and
apostrophe variants deliberately, while keeping collisions ambiguous.
Duplicate basenames must return bounded path choices, rather than select the
first file. Truncated display labels are never identifiers.

Aliases require user input or explicit source provenance. Approximate voice
transcription can propose candidates; it must not silently merge identities.

Document identity is not person identity. In a directory containing Jean Dupont
and Jean Martin, a code must belong to the requested person's block, enclosing
section, or table row. A filename match or a name elsewhere in the document
cannot establish that attribution. Ambiguous layout requires viewing or
abstention. Model interpretation remains fallible; prompt guidance alone is
not an authorization guarantee.

After resolving Orion.pdf, search for the meeting within that document. Its
name no longer needs to recur on every page. An absent Orphee must not trigger
an unrestricted search for generic codes in unrelated files.

## 2. Retain a document and page catalog

Catalog availability must be independent of extracted text. Persist:

- Document identity, full path, type, source version, known page count, and
  indexing/availability state.
- Page identities and compact extraction coverage: native text, OCR, empty
  output, not attempted, failed, protected, truncated, and renderable states.
- Evidence blocks with page/section/paragraph or row context, offsets,
  extraction method, truncation, and source version.

Empty OCR still produces a page record. Visual hints can suggest exploration,
but do not establish what a chart or image means. Record observable OCR
signals rather than invent confidence probabilities. The current Latin OCR
recognizer's script coverage and cross-language question retrieval are separate
concerns.

Keep inventory limits distinct from text-cache limits. A cataloged document
outside the text-index allowance can be discoverable as unindexed and eligible
for bounded extraction. Use compact ranges and exceptional page records rather
than thousands of verbose objects. Respect index size and folder traversal
bounds; report unknown or partial coverage when those bounds are reached.

Maintain a per-turn ledger distinguishing indexed, searched, delivered to the
model, and visually inspected evidence. Searching every cached chunk does not
mean inspecting every page of the PDF.

## 3. Use private scoped tools

| Tool | Inputs | Results |
|---|---|---|
| resolve_workspace | Bounded structured targets: kind and name | Resolved, ambiguous, missing, or unavailable; metadata choices and opaque scope handles |
| search_workspace | Scope handle, attribute/question, bounded variants or cursor | Evidence, page references, completeness, and remaining coverage |
| read_workspace_pages | Scope handle and bounded page references | Text/blocks, extraction state, truncation, and evidence IDs |
| view_workspace_page | Scope handle and page reference | Image plus authoritative document/page/version metadata |

The runtime mints handles bound to folder generation, document version, and
turn. Model-supplied raw paths, URIs, and guessed IDs cannot widen access.

An explicitly resolved document allows valid pages to be requested within the
budget without first obtaining a keyword hit. Broader discovery returns bounded
candidate/page references. Keep three concepts separate:

1. Access comes from the established source scope and current grant/version.
2. Evidence is a passage or image actually supplied in this turn.
3. Citations refer to supplied evidence; the runtime validates IDs and renders
   filename/page labels from authoritative metadata.

## 4. Keep the default path cheap; allow bounded recovery

Resolve clear file targets and retrieve scoped text locally before the first
model request. General knowledge does not activate Workspace. Ambiguous targets
can receive metadata choices and use the resolver fallback.

Preserve the first-request allocation of at most 2,500 Workspace characters
inside the remaining 10,002-character Memory/context allocation. Complete short
documents can be included only when their evidence and framing fit. Larger
documents need targeted or interactive reading.

The following interactive bounds are proposals to benchmark, not measured
device performance or current implemented limits:

| Resource | Proposed initial bound |
|---|---|
| Shared tool loop | Retain four rounds, eight total executed calls, and the existing 60-second round-start deadline |
| Workspace calls | Resolve at most once; search twice; text-read batches twice; page views twice; all compete for the shared call budget |
| Text exploration | Four pages per batch, eight distinct pages per turn |
| Heavy work | One heavy operation at a time; at most two new OCR pages and two rendered vision pages |
| Interactive evidence | An explicit cumulative text and provider-token budget; 16,000 characters is a candidate to evaluate, not an approved or implemented setting |
| Images | At most two; retain existing edge/byte caps |

Count metadata/framing and repeated transmissions in cost measurements. Page
counts alone do not bound context size or provider cost. The existing
60-second limit prevents starting another tool round; it is not a hard deadline
for every provider request or final answer.

Use an internal pool of roughly 12–20 candidate blocks before delivering a
bounded, diverse selection with adjacent context. Remove the two-passages-per-
file selection constraint for an explicitly targeted file while retaining
overall evidence bounds. A second search must change wording, language,
section, or explored coverage; identical retries do not consume work repeatedly.

Small documents can be read completely when they fit. Long documents use
headings, candidate sections, neighboring pages, and bounded exploration of
unsearched areas, including annexes. Scoped on-demand reads can reach pages
beyond the text-cache cap. A budget stop returns a continuation/partial state;
it must not imply the answer is absent. A sustained whole-document job would
need a deliberate user-started lifecycle, rather than background polling.

## 5. Add semantic retrieval against demonstrated misses

| Approach | Benefit | Boundary | Order |
|---|---|---|---|
| Precise resolution and scoped search/read | Filename-only references, ambiguity, page access, and attribution | Does not solve every paraphrase or anonymous visual page | First |
| Hybrid lexical and semantic ranking | Synonyms and multilingual discovery | Similarity does not prove identity or recover missing OCR text | After benchmark |
| Full selected document sent to the model | Comprehensive reading of a small file | Provider-specific transport and context/image cost; long context does not prove complete inspection | Optional bounded path |

Retain lexical matching for exact names, codes, and numbers. A later
multilingual local embedding model can generate additional candidates and
combine rankings before optional scoped reranking. Reciprocal Rank Fusion is
one published rank-combination method; its paper is not evidence of improved
results on this corpus. [Original RRF paper](https://plg.uwaterloo.ca/~gvcormac/cormacksigir09-rrf.pdf)

At the current 2,500-chunk cap, start with a bounded local comparison before
adding a vector service or approximate-neighbor infrastructure. Measure APK
size, working memory, cold/warm latency, indexing cost, and retrieval quality
before selecting an embedding model.

SQLite/Room may become useful for persistence and search, but need not block
the first slice. Verify actual driver/dependency support before selecting FTS5.
The SQLite Porter stemmer is English-specific, rather than a multilingual
retrieval solution. [Room documentation](https://developer.android.com/training/data-storage/room/defining-data),
[SQLite FTS5 documentation](https://www.sqlite.org/fts5.html)

Visual discovery begins with page inventory, filenames/sections, and scoped
viewing of empty-OCR pages. Later image embeddings or generated descriptions
can help locate anonymous visuals, but remain discovery hints. Exact visual or
numeric answers must inspect the original image. Summaries likewise locate
source evidence rather than replace it.

## 6. Preserve freshness and trust boundaries

Retain local-provider, grant, generation, epoch, and pre-send guards. Extend
their identity to document/page versions and turn scopes. Reuse one immutable
bounded source snapshot for a turn rather than combine cached old text with
newly opened pixels. Digests computed while indexing are one way to verify
content when metadata alone is insufficient; they require an explicit storage
and validation design.

Removal, replacement, revocation, and folder changes invalidate handles and
derived summaries/embeddings. Extend workspaceRetracts to visual-only documents:
its current old.chunks.isNotEmpty condition is insufficient once textless
documents receive handles. Additive indexing of the same source version can
retain existing evidence. Reloaded catalogs require grant revalidation; turn
handles never survive process restart.

Treat filenames, document text, OCR, captions, and summaries as untrusted source
data. They cannot change scope, budgets, or tool authority. Runtime checks
enforce those restrictions independently of prompt framing.

## 7. Expose truthful outcomes and validate the complete path

Distinguish missing or ambiguous documents, unsupported evidence, partial
coverage, unavailable/protected/changed/revoked sources, and exhausted budgets.
Even a complete extraction with zero keyword hits does not prove a fact is
absent. Prefer “I did not find it in the examined evidence” over an absolute
claim about all pages or files.

Deliver in this order:

1. Reproduce the exact Orion failure with controlled fixtures: full question,
   names-only follow-up, low/full Memory allocation, restart during indexing,
   and stable-index restart. Inspect retained page text/state, initial budget,
   selected ordinals/pages, ranking/rejection reasons, and synthetic tool
   arguments. Production diagnostics retain counts, timings, and reason codes;
   private synthetic QA can retain bounded traces without logging user content.
2. Ship catalog/page coverage, precise resolution, scoped search/read/view, and
   bounded recovery together. Version the index conservatively; preserve valid
   text, mark old coverage unknown, and rebuild metadata while active without
   requiring folder reselection.
3. Freeze a benchmark containing annexes at varied pages, synonym and FR/EN
   questions, duplicate names, wrong-person distractors, missing answers,
   textless visuals, long partially indexed PDFs, mixed-person tables, and
   change/revoke/restart races. Measure evidence-page recall, attribution,
   citation validity, truthful partial coverage, latency, bytes, and heavy work.
4. Add semantic/visual discovery only for demonstrated remaining gaps, using
   the same held-out corpus and requiring no regression in attribution or
   ambiguous-file handling.

Acceptance is end to end: the correct page reaches the model, the final answer
uses and cites that evidence, and a missing-answer counterpart refuses without
repeating another subject's sensitive details. A passing isolated Orion-only
search test is insufficient.

## Source basis

- [WorkspaceRetriever.kt](../../plugins/assistant/src/main/java/com/anezium/rokidbus/plugin/assistant/WorkspaceRetriever.kt)
- [WorkspaceController.kt](../../plugins/assistant/src/main/java/com/anezium/rokidbus/plugin/assistant/WorkspaceController.kt)
- [WorkspaceModels.kt](../../plugins/assistant/src/main/java/com/anezium/rokidbus/plugin/assistant/WorkspaceModels.kt)
- [WorkspaceIndexer.kt](../../plugins/assistant/src/main/java/com/anezium/rokidbus/plugin/assistant/WorkspaceIndexer.kt)
- [WorkspacePageReader.kt](../../plugins/assistant/src/main/java/com/anezium/rokidbus/plugin/assistant/WorkspacePageReader.kt)
- [WorkspaceStore.kt](../../plugins/assistant/src/main/java/com/anezium/rokidbus/plugin/assistant/WorkspaceStore.kt)
- [SearchWorkspaceTool.kt](../../plugins/assistant/src/main/java/com/anezium/rokidbus/plugin/assistant/SearchWorkspaceTool.kt)
- [ViewWorkspacePageTool.kt](../../plugins/assistant/src/main/java/com/anezium/rokidbus/plugin/assistant/ViewWorkspacePageTool.kt)
- [AssistantToolLoop.kt](../../plugins/assistant/src/main/java/com/anezium/rokidbus/plugin/assistant/AssistantToolLoop.kt)
- [Original Workspace plan](../../plans/026-assistant-workspace.md), whose initial PDF/OCR exclusions have been superseded by delivered source and device testing.
