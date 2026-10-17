# Assistant: persistent local Workspace index

Status: agreed for Delivery A implementation after Fable API review and Astra's
incorporation of all required corrections, 2026-10-10. Source baseline:
`fb450515`, branch `qa/skills-workspace-typed-160`. The architecture proposal was
read-only; subsequent delivery evidence is described below.

Implementation status, 2026-10-10: **Delivery A (A1-A4) is implemented in the
working tree of `qa/skills-workspace-typed-160`, received parent review and
corrections, and passes 552 local Assistant tests, debug/release builds and
focused written-mode USB smoke tests.** Assistant is 1.4.9 (code 18) for the
test APK; hubs were preserved. Evidence, corrections and open limitations are
in [the parent QA report](../docs/reviews/persistent-workspace-index-typed-qa-20261010.md).
Full release acceptance, the frozen 40+20 model benchmark and phone timing at
the caps remain pending.
**Delivery B (local semantic encoder and hybrid retrieval) remains outstanding:**
A is lexical keyword search only and must not be described as semantic.

Both completed direct API reviews returned `AGREEMENT: YES WITH REQUIRED
CHANGES` (actual model `claude-fable-5-1`, high effort, HTTP 200, `end_turn`).
The focused second review verified the first five corrections and expressly
accepted caller-based query qualification, the Hermes exclusion, and lexical A
plus deferred semantic B. Its remaining two corrections, evidence-dependent
final-effect suppression and retained-text legacy migration, are incorporated
below. Astra confirms agreement with the resulting executable contract; no
design exception remains open before the handoff to Opus.

## Decision and delivery boundary

Deliver a persistent document/page catalog and lexical inverted index in the
Assistant phone plugin first. Resolve an explicit file before the first model
request; retrieve cached evidence within that file; make its cataloged pages
viewable independently of text citations. Index on folder selection, explicit
refresh, and observed changes while Assistant or its settings is active. A
question queries the index; it never starts PDF text extraction or OCR.

This is a genuine persistent local search index, but **it is not the entire
accepted lexical-plus-semantic goal**. Delivery A below provides durable lexical
search and reliable scope. Delivery B adds a measured local multilingual text
encoder. Delivery B is explicitly outstanding until an actual encoder, runtime,
license, resource measurements, and quality results are approved. Do not describe
A as semantic search or mark the complete hybrid goal finished when A ships.

Keep hub versions 1.6.0, bus routes, SDK, capabilities, permissions, provider
selection, and glasses rendering unchanged. No cloud embeddings, remote OCR,
document uploads for indexing, downloaded model, or new model runtime in A.
Question-time evidence continues through the user's existing configured provider
path; "local indexing" must not imply that answering with that provider is offline.

The agreed first implementation is A1-A4 in the sequence below. Estimates for an implementer familiar with this
code: approximately two working days for A plus a focused device validation
session. These are planning estimates, not measured runtime or completion claims.

## Evidence and resolved review disagreements

The [Astra architecture](../docs/reviews/workspace-retrieval-architecture-astra.md),
[Fable review](../docs/reviews/workspace-retrieval-architecture-fable51.md), and
[literature/Orion controls](../docs/reviews/workspace-retrieval-literature-orion-20261010.md)
remain the background. The last document records that the full synthetic Orion
question already retrieves page 3; the names-only `Orion` fallback does not. Two
later device answers succeeded, but retained conversation history confounded the
restart observation. The original refusal's cause is still unproved.

| Earlier disagreement | Implementation decision |
|---|---|
| Four model-visible resolve/search/read/view steps | Resolve in runtime; extend existing search with nullable file scope; search returns text and page references together. No new resolve/read tool. Target at most two fallback rounds. |
| A large handle/ledger framework | One bounded turn object with a document map and sets of delivered text/pages. No persistent per-turn ledger or public protocol. Short references are necessary only where display labels cannot safely identify a source. |
| Page catalog versus small coverage additions | Compact page states are required for persistence and textless access. No verbose per-page object or retained raster is required. |
| Filename scope versus person identity | Removing the filename-body gate applies only to the resolved source. A filename never proves a value belongs to a person. Layout attribution remains a model/evidence limitation, tested adversarially. |
| First-request readiness hypothesis | Instrument load validation, the 150 ms root check, available tools, and prompt budget. Preserve checks and timeout. Do not fix an unproved cold-start cause by bypassing access checks. |

Current persistence is already meaningful: `workspace-index.json` v2 retains
chunks/OCR and resumes pending PDFs. `WorkspaceRetriever` reconstructs token maps
and scans passages after load/publication. Persisted postings are a modest load
optimization at 2,500 chunks. The substantial improvement is the durable page
catalog, precise resolution, scope, textless access, freshness fixes, and coverage.
This plan does not claim the current implementation re-extracts every PDF per
question, or that a derivable posting cache is more valuable than source text.

## A1. Durable catalog and index representation

Use the existing `noBackupFilesDir/assistant-workspace` directory, existing
`WorkspaceStore`, and its atomic JSON replacement. Upgrade the one index file to
version 3. Do not introduce SQLite/Room, FTS5 assumptions, a vector server, a
second independently committed sidecar, or a new dependency. At 2,500 retained
chunks a bounded Kotlin inverted index is sufficient and easy to unit-test.

Logical schema (names may follow surrounding Kotlin conventions):

```text
WorkspaceIndex v3
  generation, indexedAtMs, skippedFiles
  extractionVersion: 1, lexicalVersion: 2          // independent version domains
  inventoryComplete: Boolean, inventoryLimitReason: enum?
  documents: [WorkspaceDocument]                 // existing cap: 100
  lexicalPersisted: Boolean
  lexicon: [normalizedTerm]?                     // sorted and unique, optional cache
  postings: [[chunkIndex, termFrequency, ...]]?   // one flat pair array per term
  tokenCounts: [Int]?                            // one per retained chunk

WorkspaceDocument
  entry: existing authoritative provider documentId + metadata + display labels
  lookupSafe: Boolean                           // labels are lossless identifiers?
  sourceDigest: SHA-256?                         // bytes actually extracted
  status: existing document state
  pageCount: Int?                                // actual total, null if unknown
  coverageKnown: Boolean
  pageRuns: [[start, end, textState, visualState, renderState], ...]
  nextPage: Int?                                 // resumable cursor, separate from coverage
  chunks: existing ordinal/text/heading/paragraph/page/visual
```

The implicit `chunkIndex` is the position in deterministic document order,
then chunk ordinal. It is private to that committed snapshot, never a citation
or stable external ID. Do not persist duplicate provenance strings or repeat
the filename in every posting. Body terms are indexed; filename and heading
terms remain small, separately weighted metadata fields. Corpus document
frequencies and average token length derive from the stored arrays without
retokenizing source text when the cache is present. With `lexicalPersisted=false`,
rebuild the complete in-memory index once on load from retained text. Query
results must be byte-identical with either representation. Build/search use the
same versioned tokenizer. A lexical-version change rebuilds only the cache;
an extraction-version change schedules bounded re-extraction with the catalog
retained. No Porter stemmer or claimed multilingual semantics is introduced.

`textState` is one of `UNATTEMPTED`, `NATIVE`, `OCR`, `EMPTY`, `FAILED`,
`TRUNCATED`, `LEGACY_TEXT`, or `LEGACY_UNKNOWN`. `LEGACY_TEXT` records retained
text with unknown native/OCR method and page visual state; it does not assert
complete extraction. `visualState` is `PRESENT`, `ABSENT`, or
`UNKNOWN`; `renderState` is `UNKNOWN`, `AVAILABLE`, or `UNAVAILABLE`.
`EMPTY` means an extraction attempt succeeded with no text, not render failure.
A scanner that could not open must not silently become successful empty OCR.
Protected/unreadable documents may have unknown page count and document-level
failure; do not manufacture page records for a page count never observed.

For PDFs, page numbers are 1-based; an image has one catalog page numbered 1.
Keep existing image citation formatting without a printed page if desired, but
normalize page 0 in old image chunks to catalog page 1 internally. Text/Markdown/
DOCX retain paragraph/heading evidence and have no invented PDF pages.

Store page states as sorted, nonoverlapping runs with adjacent equal runs merged.
Pages in `1..pageCount` not covered by an explicit run are `UNATTEMPTED` with
unknown visual/render state. Thus **every known page exists in the catalog even
if OCR is empty or the text/page allowance has been exhausted**. An actual PDF
with more than 500 pages retains its actual count and an implicit unattempted
tail; extraction and interactive viewing remain capped at page 500. The UI and
coverage explicitly report that limit. No allocation proportional to an
untrusted arbitrarily large reported page count is permitted.

`WorkspacePagedText` must return the total page count and per-attempt page state,
not just strings and a complete flag. Change `PdfPageScanner.recognize` to
distinguish actual OCR output from a renderer that did not open:

| Observed event | Text state | Render state | Cursor |
|---|---|---|---|
| Renderer absent/refuses page | `FAILED` | `UNAVAILABLE` | Advance past this page |
| OCR completes with blank output | `EMPTY` | `AVAILABLE` | Advance past this page |
| Glyph budget raises `PageTooDenseException` | `TRUNCATED` | Actual observed state or `UNKNOWN` | Advance; continue later pages within pass budget |
| OCR times out or fails | `FAILED` | `AVAILABLE` if rendering succeeded | Advance past this page |
| Pass deadline expires before starting a page | `UNATTEMPTED` | Unchanged | Resume this page next pass |

Every terminal page state advances the cursor, including empty/failed/dense
pages. A `FAILED` page is not automatically retried until its source or
extraction version changes. An explicit user `Re-index now` may request one
retry; ordinary refresh/unchanged hash must not create an infinite retry.
Preserve successful earlier pages and allow extraction of later pages. Keep
at most 500 explicit page-state entries before run compression per document;
at most 50,000 across the 100-file catalog. Protected documents whose total
count could not be read retain document-level failure and unknown inventory.

### Growth, atomicity, and truncation

Retain all existing traversal and text limits: 1,000 entries, 100 directories,
depth 4, 100 catalog documents, 100,000 characters/file, 1,000,000 characters
total, 2,500 chunks, existing input-byte caps, and **8 MiB total UTF-8 JSON**.
Files beyond the 100-document inventory cap are counted as omitted; do not
describe them as cataloged. Unsupported/virtual entries remain excluded.

Add structural ceilings of 100,000 lexicon terms and 250,000 `(chunk,frequency)`
pairs for the optional persisted cache. Exceeding them omits the cache, not source
text. An in-memory rebuild remains complete for all retained searchable text,
bounded by the existing 1M-character/2,500-chunk limits rather than silently
stopping at the cache ceilings. Terms longer than the 240-character maximum
searchable query cannot be
queried as complete terms and are excluded consistently at index and query
time. Frequency values count occurrences; they do not repeat chunk IDs.

Before publication, budget the complete encoded snapshot, including escaped
labels, text, page runs, lexicon, postings, and numeric JSON. If adding postings
exceeds the structural limits or 8 MiB total, set `lexicalPersisted=false` and
omit the whole derivable cache. Rebuild it once on load. **Never remove retained
text to make room for postings.** Text retention follows today's 2,500-chunk,
1M-character total, and 100k-character/file limits. `TRUNCATED` means actual
source text was lost under those limits, never "the posting cache did not fit".
Every retained searchable term participates in the in-memory index. Use a
bounded sizing/writer pass and an exact UTF-8 size assertion at atomic write.
If even text plus compact catalog exceeds the existing total cap, fail the
publication safely and preserve the previous committed file; do not call that
an accepted schema. Required worst-case sizing tests must prevent this for
valid realistically indexed inputs before implementation is accepted.

Allow at most 2 MiB of the 8 MiB for catalog/framing; document IDs and names
retain existing 1,024/96/160 limits. Worst-case alternating page states must
fit this reservation with numeric tuples. Test this bound; if it fails, compact
the encoding before accepting the schema. The remainder is available to text
first and only then optional postings, not an additional 8 MiB allowance. One
temporary replacement can use
another 8 MiB on disk, as with the existing atomic writer. No persistent PDF
bytes, rendered pages, model output, or embeddings in A.

Publish catalog, chunks, and optional postings as one immutable snapshot only
after the atomic file replacement succeeds. An interrupted/failed write leaves the prior
committed snapshot intact. Do not expose new in-memory evidence with old disk
state. Invalid counts, duplicate IDs, invalid references/ranges, unknown schema,
or mismatched generation fail closed on load. Validate sorted posting IDs,
positive frequencies, and every chunk reference before constructing a retriever.

## A2. Incremental indexing, freshness, migration, and UI

Reuse the existing attached-owner lifecycle. Folder selection and `Re-index
now` enqueue an indexing pass; opening an attached Assistant/settings owner
validates/reconciles the persisted catalog and resumes pending work. The local
provider observer debounces changes and coalesces another pass if one is already
running. A dirty notification must not be lost merely because `refresh()` finds
an active job. Final owner detach cancels pending work and removes observers;
the next attach resumes from the last committed cursor. No polling, boot work,
WorkManager, foreground service, or unrequested background lifetime is added.

Perform traversal/metadata reconciliation before expensive changed-file
extraction. Metadata-unchanged documents with known digests reuse text and
postings without opening their PDFs on observer/ordinary attach passes;
changed/removed entries become unavailable in a committed reconciliation
snapshot before replacement extraction. Preserve inventory entries for
unreadable, textless, capped, and pending documents. Index extraction may reopen
a pending PDF once per pass, as today; that is separate from question lookup.

An unknown change notification makes the active snapshot dirty immediately and
withdraws the turn's Workspace access before debounce: tools become unavailable,
the source map is invalidated, and pending Workspace searches/views return
`source_changed`. Grant/folder changes do the same. This withdrawal does not by
itself cancel an unrelated answer: final HUD/TTS/history suppression depends on
the turn's `evidenceSupplied` flag below. A changed/replaced textless document
must invalidate the epoch too:
remove `old.chunks.isNotEmpty()` as the prerequisite in `workspaceRetracts`.
Withdrawal/alteration of source identity, reliable metadata, previously known
page inventory/render eligibility, or existing text retracts the old view.
Adding formerly unknown coverage/digest metadata for the same validated source,
adding/dropping/rebuilding the derivable posting cache, or appending extraction
does not retract existing evidence. Compare those semantics explicitly, not
whole data-class inequality. Unchanged v2-to-v3 migration must not bump the
epoch. A dirty notification withdraws that turn's Workspace access even if
reconciliation later proves content unchanged; the old access object cannot
become live again. Pure additive publication preserves already supplied evidence
only when no invalidating notification fired; publication alone does not retract.
Turn coverage describes the captured snapshot, not later additions.

Source identity is `(folder generation, provider documentId)`; the observed
source version includes reliable modification time, byte length, and the digest
computed while extraction already reads the bytes. Keep rejecting missing
reliable metadata for content reuse. For a known-digest document, a view reads
bytes once, checks current metadata, compares their digest to the indexed
digest, and renders those same bytes. This prevents old text being combined
with replacement pixels even when size/time happen to match. Changed
metadata/digest makes the document dirty and returns `source_changed`, never
fresh pixels under an old citation. The legacy cited-page exception below
retains baseline metadata-only verification while its digest is unknown.

Cached prefetch and `search` add **no new question-time provider I/O**. Retain
exactly the existing grant, validated-state, local/root access, 150 ms root
timeout, generation, and epoch checks. Do not call source-document `metadata`,
`open`, hash, PDF extraction, or OCR from those cached query paths. The gateway's
`root()` itself delegates to `metadata(rootDocumentId)`; the counter requirement
is one root check and zero additional source-document metadata/content calls,
not zero internal metadata calls of every kind. A separate per-question source
metadata change may be considered only after A's diagnostics measure cold/warm
root and metadata latency on the phone; it is outside A.
The request guard repeats current grant/epoch/active-turn checks before every
Workspace-bearing provider send, including tool images. No synchronous
unbounded I/O in that guard. On access withdrawal, drop pending unsent Workspace
content and make its tools unavailable for the rest of the turn. If the turn
already supplied Workspace text/pixels, suppress subsequent final HUD, speech,
and retained-answer publication with a reason code. If `evidenceSupplied=false`,
continue the unrelated answer without Workspace access; do not let an obsolete
`workspaceVersion` guard abort that answer merely because tools had been offered.
Metadata/filename/choice-only context is not document evidence.

Cached-text freshness relies on observer -> dirty -> metadata reconciliation ->
epoch validation, with the existing reliable-time/size assumption. An edit that
preserves size/time may remain undetected even when a notification triggers
reconciliation. Do not promise cryptographic content freshness for cached text.
Observer passes open/re-extract metadata-changed files and establish missing
legacy digests; they do not hash every metadata-unchanged known-digest file.
Only explicit `Re-index now` requests a full hash verification of unchanged
files. Keep a resumable verification cursor under the existing pass deadline;
do not hash up to 100 x 32 MiB in an unbounded notification job. Re-extract only
changed digests and legacy chunk-less pages needing missing extraction state;
retained legacy text pages follow the no-re-extraction rule in Migration.
Ordinary attach trusts unchanged reliable metadata after reconciliation.

Cancellation/revocation/folder switching is checked before reading, after
extracting/hashing, before store publication, after rendering, and before any
Workspace-bearing provider effect. User-facing final effects use the explicit
evidence-dependent suppression rule; ordinary user cancellation/new-question
cancellation remains unchanged. A late native render may finish detached as today;
it cannot publish bytes or resurrect an old catalog. Allow only one heavy page
operation at a time: extend the current view-only busy guard into a shared gate
for indexing page operations and views. A timed-out native operation retains
that slot until it actually ends. Foreground views must not create an additional
unbounded extraction/render queue.

### Migration

Read v1/v2 conservatively and keep Workspace settings/grants. Preserve valid
nonpaged text; preserve v2 paged chunks as legacy evidence with unknown coverage
until their source has been reconciled. Keep the existing invalidation of v1
paged content, whose visual information was never recorded. Never infer total
page count from the largest chunk page or from `pagesRead`.

Build v3 postings from retained legacy text once; persist them only if they fit.
Initialize pages carrying v2 chunks as `LEGACY_TEXT`, with
`pageCount=null`, `coverageKnown=false`, `lookupSafe=false`, and
`sourceDigest=null`; for PENDING documents, `nextPage=pagesRead+1`.
`LEGACY_TEXT` means "text retained, extraction state unknown". Preserve any
existing chunk visual hint, but do not promote it to complete page-level
native/OCR/visual knowledge. Known pages without chunks become `LEGACY_UNKNOWN`
("not verified") after the true page count is discovered. Neither state claims
complete extraction or successful empty OCR.

Add a reader inspection seam distinct from text extraction, for example
`inspect(type, bytes): WorkspacePageInfo`, returning the actual page count and
document-level readability/protection outcome. It must not invoke
`PDFTextStripper`, page OCR, or render-to-OCR. During indexing-time migration,
open bytes once, compute their digest, inspect count, and check reliable
metadata before publishing, all under the existing pass budget. An image has
one catalog page; do not OCR it simply to establish its count/digest.

An initial v2 digest is a new observation, not a comparison against a historical
hash: v2 did not store one. Reuse retained text on validated reliable metadata
continuity and state the same metadata freshness limitation already documented.
Never claim that the initial hash proves old text matches historical bytes.
Once a digest is established, subsequent differences, reliable source-version
changes, or extraction-version changes trigger the normal re-extraction path.

Pages with retained chunks stay `LEGACY_TEXT` without page extraction/OCR while
the source/extraction version remains unchanged. Only chunk-less
`LEGACY_UNKNOWN` pages inside the known page range are candidates for migration
backfill, resumably after new/changed-file work in that pass. Use bounded selected
page/range reads, or a reader skip predicate checked before text extraction/OCR,
so walking across a retained page does not extract it again. A legacy PENDING
file first resumes at `pagesRead+1`; earlier chunk-less unknown pages are lower
priority backfill, not a reset to page 1. Terminal states/cursors prevent repeated
visits. Keep `coverageKnown=false` while any retained legacy page still has an
unknown extraction state; accurate partial coverage is preferable to re-OCR.

Do not require folder reselection or silently start a background job. An
unchanged upgrade preserves retained text and epoch throughout digest/count
inspection and additive backfill; the new representation alone retracts nothing.

Before pageCount/digest are known, preserve v2 **cited-page viewing exactly as
the baseline**: only a page whose text was supplied this turn, valid existing
grant/root/version checks, current metadata equality, and successful bounded
render. Do not enable all-page explicit-file scope yet or infer total page count
from citations. This transitional path has baseline metadata-only freshness;
it is deliberately not described as digest-verified. Once reconciliation records
count and digest, full explicit-file scope may be minted in a new turn. A legacy
view does not itself promote that scope. A successful v3 atomic write replaces
v2; a failed write keeps the old file. No downgrade support is promised. Disable,
change folder, or revoke access removes index/temporary content through the
existing clearing path.

The Workspace card reports catalog documents separately from searchable-text
documents, processed/known pages, pending/partial/no-text counts, and last
successful indexing time. Use concise states: `Checking folder`, `Indexing
12/40 pages`, `Ready`, `Partially indexed`, `No readable text; pages can be
viewed`, `Folder unavailable`. `Ready` means all work within the declared limits
finished, not "every question is answerable". Leaving all owners pauses work;
the UI explains how to resume. Keep the existing Memory-full warning. Display
"Search by words" for A if the UI describes retrieval; never "semantic ready".

## A3. Runtime file resolution and query contract

Create a lightweight `WorkspaceTurnAccess` for each user question. It captures
one immutable snapshot, a new turn identity, generation/epoch, a bounded map
`fileRef -> (documentId, sourceVersion, viewScope)`, delivered text citation IDs,
viewed page IDs, and `evidenceSupplied: Boolean`, initially false. `viewScope` is either explicit-file/all-catalog-pages or
cited-pages-only; the former requires known count and digest. It becomes invalid
on a new question, cancellation, scope withdrawal, or folder/grant change.
Delete the controller's global `turnCitations`; no tool may read a replacement
turn's citation set through a lazy lambda.

The existing `workspaceVersion` pair still binds tool availability and provider
requests; it is not sufficient alone to distinguish two turns at the same epoch.
The turn facade's availability/guard checks also enforce its active identity.
Implement this inside Assistant rather than extending the public SDK or shared
tool-loop budgets. A source map is access/navigation state; only text or pixels
actually returned to the model count as evidence.

Set `evidenceSupplied=true` only when validated source text or pixels are
supplied to the provider for this turn: nonempty text evidence in the dispatched
prefetch, or an actual search/view evidence result handed to the provider path.
Preparing a local candidate, advertising tools, resolving a filename, or returning
only metadata/choices/no-match/coverage does not set it. Keep this delivery update
and access-invalidating notification ordered under the turn's synchronization:
if withdrawal wins, pending evidence is dropped; if evidence delivery wins,
withdrawal records that final effects must be suppressed. No later successful
reconciliation clears that suppression for the same turn. An invalid access
object with `evidenceSupplied=false` disables Workspace while preserving the
general answer. Final-effect guards must distinguish these two cases.

Concrete plumbing: `contextForQuestion` returns a `WorkspacePromptContext`
containing this `WorkspaceTurnAccess?`; `AssistantPluginService` forwards that
same object through registry `availableDefinitions` to the plugin-private
`AssistantToolDefinition.bindToTurn`. Other definitions can ignore the optional
argument. Search and view capture it directly and check both active turn identity
and the existing version pair in `isAvailable` and before/after `execute`.
Request/pre-send/final-effect guards capture the same object and its evidence
delivery state. Do not make a
second turn object during tool binding or infer it from the version pair.

Reuse the same turn object across fallback rounds, so `search(query, null)`
retains the initial explicit-file scope. Do not rebuild it per provider pass.
Cross-turn pronoun resolution (`this file`, `ce fichier`) is deferred from A;
a new question naming Orion resolves it afresh. A later conversation-local
navigation hint would require fresh snapshot/grant validation and a new turn
reference. Never retain an old access object, evidence set, or page handle
across turns. A model's historical citation does not establish current access
or current evidence.

Resolve a clear explicit file reference before ranking the full initial
question. Match exact indexed relative path, then unique full filename, then
unique normalized basename with optional spoken extension (`orion pdf`). Use a
name normalizer independent of the stopword tokenizer: preserve all name words,
case-fold/accent-fold deliberately, and keep normalized collisions ambiguous.
Do not fuzzy-match a missing file, infer person aliases, or promote a partial
first name to an identity. A bare name can resolve only if it is a unique whole
catalog basename; a substring of a longer filename is insufficient.

Existing `WorkspaceEntry.name` and `relativePath` are sanitized/truncated display
labels. They are **not authoritative raw paths**. During traversal set
`lookupSafe=false` if truncation, normalization collision, synthetic path
disambiguation, or loss in an ancestor prevents lossless matching. Use provider
documentId for all effects, never rebuild a URI from a label. Legacy entries
start unsafe until reconciled. A collision produces `ambiguous`, not a first
match, including collisions introduced by label shortening.

Ordinary safe paths can remain the model's `file` reference. For an unsafe or
overlapping display label, emit a bounded turn-local reference such as `w1`
with its display label and an explicit ambiguity state. Choosing among duplicate
file names requires an unambiguous user reference; the model cannot authorize
an arbitrary candidate merely by copying `w1`. References need no cryptographic
token or persistence. When the user later disambiguates, create a new turn map.
If retained metadata cannot distinguish the choices, ask for a better source
reference/reselection instead of pretending the truncated labels are sufficient.

Resolution states: `resolved`, `ambiguous`, `missing`, `unavailable`, and
`none` (no explicit file request). A missing/ambiguous explicit target does not
fall back to searching other files for a generic code. Return at most five
metadata choices with a count of additional candidates; choices contain no
document body. Filenames and labels remain untrusted quoted data.

In resolved-file mode, strip only the resolved file-name/extension tokens from
the ranking query, search attribute terms inside that exact document, and lift
the two-passages-per-file cap. If the complete retained document and framing
fit the available budget, return its text in page order with truthful coverage.
An empty remaining query (`Orion` in that scope) returns deterministic
page-ordered passages from the beginning plus the catalog line, within the same
budget; it must not require Orion in each page body. For larger
files, choose at most three delivered passages from the top 20 candidates.
Keep exact BM25-like body weighting and a small metadata bonus initially;
metadata establishes context, not answer evidence. Tie-break deterministically
by authoritative document order and ordinal.

### Query grouping and exact qualification (agreed clarification)

Set `QUERY_PARTS = Regex("[;?]")` everywhere. `and/et` no longer split a name or
a query into groups. Bump the logical lexical/query version to 2 and rebuild
any incompatible posting cache without re-extracting documents or withdrawing
text. The tokenizer may still discard `and/et` as stopwords; grouping occurs
before tokenization. Remove the obsolete splitting instruction in the tool
description and teach explicit semicolons for independent requested groups.

Use these explicit internal query paths; do not infer a semantic "entity mode"
from capital letters, a file basename, or an unlabelled natural-language query:

| Caller and scope | Qualification |
|---|---|
| Initial full-question prefetch, no resolved file | Existing per-group rule: at least one body term and at least half the group's terms in body/metadata. Preserve missing-subject controls; never lower this gate. |
| `search_workspace`, no resolved file | Constrained keyword groups. A passage qualifies for a group only if **all of that group's non-stopword query terms occur in its body**; metadata cannot supply missing terms. Each `;`/`?` group is independent; existing group-diverse selection applies. |
| Initial or private search, uniquely resolved file | Scoped attribute ranking described above; remove only filename/extension tokens. A filename-only scoped query can return a bounded overview. No global fallback to another file. |

Ignore token-empty groups. If an unscoped query has no nonempty group, return
`no_match`; an empty all-terms predicate must never qualify every passage.

This is the agreed clarification of Fable's earlier ambiguous phrase
"entity mode ... all tokens of the stated group". It adds no model-visible
subject flag or unimplemented person recognizer. For a names-only private query,
the all-terms condition mechanically requires the complete stated name. For
`code de Vega; horaire d'Aurora`, it requires `code`+`vega` in a matching body for
one group and `horaire`+`aurora` for the other: the attributes are **query
constraints, not identity tokens**. A group can have no matching passage; do not
weaken it or fill the gap with another group's details.

Two recall changes are explicit. A natural initial question joined with `et/and`
now has one larger group under the original half-term rule, so the old guarantee
that both clauses are separately selected no longer applies; update the compound
test to this rule, and test separate-group coverage with actual semicolon input.
Unscoped private search becomes stricter and may miss synonym/translation or
multi-keyword passages; describe it as constrained keyword search, keep scoped
recovery available, and measure the lost recall. The full literal Orion control
must still retrieve page 3. No claim that the full initial question's people
were mechanically recognized follows from these rules.

Required private-tool controls: `Jean et Marie Dupont` is one token group and
cannot return a body containing only Jean Martin; the two attribute groups above
retrieve synthetic bodies containing their respective literal words; a body
with only the person's first name fails. Run the same qualification with cached
and rebuilt postings. Fable expressly accepted this caller-based distinction
in the focused review; implementation must preserve it as written.

No reliable general person/entity extractor exists in this code. The first
delivery must not claim that every natural-language question has mechanically
identified name tokens, or that flat PDF text proves a table row. Full-subject
matching is a necessary check where a subject is explicitly known, not proof
of a value's ownership. For person-specific facts in mixed-person/visual
layouts, require original-page inspection or abstention; never answer using
another person's row merely because the requested name occurs elsewhere.
This final interpretation is enforced by prompt policy plus adversarial
end-to-end acceptance, not falsely advertised as a runtime theorem. If it
fails the fixture, block the change rather than weaken the identity gate.

## A4. Existing tools, evidence limits, and coverage

The turn access facade separates `isCatalogAvailable()`, `hasSearchableText()`,
and `hasViewablePages()`. All require current validation/grant/turn scope; zero
chunks do not invalidate a legitimate textless-page turn. Update request guards
and `AssistantPluginService`'s include/remove-Workspace paths for **both** tools.
Currently several paths key exclusively on search availability/name; retaining
that coupling would advertise or strip the wrong tools for a textless folder.

`search_workspace` uses strict JSON schema:

```json
{"type":"object","properties":{"query":{"type":"string","minLength":1,"maxLength":240},"file":{"type":["string","null"],"minLength":1,"maxLength":160}},"required":["query","file"],"additionalProperties":false}
```

`file=null` means use the already resolved explicit scope if present; otherwise
use the constrained unscoped private-search rule above. A non-null value must
resolve uniquely within the current turn's permitted source map or a clear,
user-specified source in that
same question. It is not a raw filesystem path/document ID or permission to
override an ambiguous initial target. Returned general-topic hits register only
the pages whose text was actually supplied this turn as viewable; they do not
grant access to other pages in the document. A returned file reference can narrow
a later text search, but doing so does not promote its view scope to all pages.
Metadata-only ambiguity results grant no body or page access to an arbitrary
choice.

Return one bounded JSON text result containing `status`, `matchCount`,
`excerpts`, `files` (refs/display labels/page counts), `coverage`, and a reason
when unavailable/partial. Search returns its text evidence and page references
together; no separate read call is needed. It may return an empty-text catalog
result for an explicitly named textless source. Do not advertise global text
search for a folder with zero searchable chunks merely to enable page viewing;
direct resolved-file view availability is separate.

Permit two executed searches per turn only when normalized `(query, fileRef)`
differs; an identical retry returns the cached result after current turn/grant/
version checks, without another index/provider operation. It cannot reset the
shared execution budget. Both still share the existing total executed-call
ceiling. `view_workspace_page` retains `{file,page}`, one view per turn,
existing 8-second wait, 1,600-pixel edge, and 1,000,000-byte JPEG cap. A unique
explicitly user-resolved file with known pageCount and digest permits any
cataloged page in `1..min(pageCount,500)` within this budget, including empty-OCR/
unattempted text pages whose source metadata and digest verify before rendering.
It need not have a text citation. In every other case, including a general-topic
hit, only pages cited by text actually supplied in this turn are viewable.
Unknown-digest legacy documents use the narrow baseline exception in Migration;
known-digest cited-page views still compare digest. Ambiguity choices grant
neither scope. No on-demand OCR here.
The rendered image must be accompanied by authoritative file/page metadata in
the provider's existing tool-result envelope; do not accept a model-supplied
label as provenance. Preserve provider tool/vision capability gating.

Keep four shared tool rounds, eight total executed calls, and the existing
60-second round-start deadline. The deadline does not interrupt a provider pass
or final answer. Do not raise these shared limits. A typical scoped text miss
uses one search; a visual follow-up uses search then view, at most two dependent
rounds. A clearly specified file/page can view directly in one round.

Initial Workspace content, **including metadata, choices, coverage, source
rules, and fences**, fits `workspacePromptBudget`: at most 2,500 characters
inside the remaining 10,002-character personal-context allocation. At budget
zero inject nothing, not a free coverage line. Runtime scope may still be
prepared without injecting text. Providers with tools can request bounded
results; providers without usable tools have no recovery when no prompt space
remains and must report that limitation honestly.

For A, each interactive search result is at most 2,500 Unicode characters
including serialized JSON/framing and at most 12 KiB UTF-8. Two searches yield
at most 5,000 new interactive text characters; plus first prefetch at most
2,500 means at most 7,500 newly supplied Workspace text characters/turn. Reserve
coverage/provenance space before passage truncation. Images retain their
separate existing binary/base64 budget; do **not** use Skills' result-size cap
as a private Workspace image limit. Count transmitted prompt repetitions and
base64 expansion in provider-cost measurements, not just newly added text.
No additional 16,000-character interactive allowance in this delivery.

Example coverage meaning, compressed to fit the same budget:

```text
orion.pdf: 7 known pages; text indexed on 1-4,6; empty OCR 5;
not extracted 7. Searched 12 cached passages; supplied pages 3,6;
viewed none. Partial source coverage.
```

Coverage distinguishes known pages, attempted extraction, retained searchable
text, searched candidates, supplied text, and actually returned pixels. Do not
call omitted candidates cited, empty OCR inspected, or an indexed-but-unsent
page read by the model. A zero-hit search is `no_match`, never proof of absence
even with complete lexical coverage. Return partial/budget/source-changed reasons
instead of an absolute assertion about the document. This is a small per-turn
set/counter calculation, not a persistent coverage event database.

## B. Semantic indexing: concrete extension and activation gate

The Assistant dependency list contains PDFBox and ML Kit Latin OCR, with no
sentence encoder/tokenizer/inference runtime or bundled semantic model. An
unmeasured library/model addition cannot honestly be slipped into A as a small
implementation detail. The literature report motivates semantic retrieval but
does not supply an Android-qualified model or prove Nexus quality gains.

| Local route | Decision |
|---|---|
| Metadata + BM25 postings | Ship in A; deterministic, persistent lexical retrieval. It is not embeddings. |
| Hashed TF-IDF, hand-written FR/EN synonyms, or locally fitted LSA | Dependency-light experiments at most. Hashing does not add meaning; synonyms are limited vocabulary; a tiny personal corpus is an unreliable multilingual semantic training set. Do not market these as the accepted semantic solution. |
| Quantized multilingual sentence encoder via a small local inference runtime | Recommended B evaluation. Pin model/tokenizer/runtime artifacts and licenses first; measure on the actual phone. No silent install/download. |
| Cloud embeddings, visual embedding model, LLM-generated summaries | Outside A and this authorization; require a separately reviewed resource/privacy design. |

B's internal seam can be specified now without shipping an unused framework:

```kotlin
interface WorkspaceTextEncoder {
    val fingerprint: String // model + tokenizer + runtime-format + pooling + normalization
    val dimension: Int
    suspend fun encode(text: String): FloatArray
}
```

The model evaluation must verify actual French/English query support, input
token limits, normalization, pooling, and any required query/passage prefixes.
Embed deterministic `file display name + heading + page + original chunk text`
once during indexing; context is source metadata, not generated facts. Key a
cached vector by source identity/version, chunk-text/context digest, and encoder
fingerprint. Changed source/context invalidates its vectors; a model/tokenizer
change rebuilds vectors while validated lexical search remains usable. Persist
no query vectors/history; vectors are private document-derived data and follow
the same deletion/no-backup rules as text.

With at most 2,500 chunks, compare a query vector against the bounded vectors
directly. No approximate-neighbor service is justified. Use normalized cosine
top 20 plus lexical top 20, fuse deterministically with RRF `k=60`, deduplicate,
then apply the same scope, identity, evidence, and character limits. Lexical
exact names/codes stay available; similarity never expands an explicit file
scope or makes a missing person match another person. Empty-OCR pages have no
invented text vector; discovery still uses catalog and viewing. No visual
embeddings, late-chunking claim, or generated answer-as-evidence in B's text
encoder implementation.

Before implementation of B, choose an actual model and review a separate vector
storage revision: fixed-width binary vectors or an equally bounded compact
encoding, tied atomically to the committed source snapshot. Do not stuff float
arrays into v3 JSON and accidentally double the 8 MiB cap. Model artifacts and
vector bytes have an explicit additional, approved budget. A's schema need not
include empty vector fields or placeholder "semantic enabled" settings.

Proposed B entry gates, to approve with measured candidate data: at most 64 MiB
incremental installed artifacts, 128 MiB peak additional working memory, warm
query embedding plus ranking p95 at most 200 ms, and no question-time PDF/OCR.
These are acceptance targets, not claims that a qualifying model already exists.
If none qualifies, B stays deferred and the UI/report continue to say lexical.
Do not raise budgets, install a heavier model, or use a remote endpoint silently.

## Validation and implementation sequence

| Step | Concrete work | Completion gate |
|---|---|---|
| A1 | Diagnostics, v3 model/page reader, compact catalog/postings, atomic persistence/migration | Restarts reuse persisted index; bounded-size and migration/race tests pass. |
| A2 | Reconciliation, observer dirty state, cancel/resume, no-text retraction, source checks, settings status | Changes/revocation cannot expose stale text/pixels or publish late work. |
| A3 | Runtime resolver, turn facade, scoped ranking, references | Orion annex reachable without globally weakening identity; ambiguity/missing targets fail closed. |
| A4 | Search/view contracts, guards/provider framing, coverage and budgets | End-to-end scoped text/visual paths, textless-only folders, no-tool/zero-budget behavior pass. |
| B | Freeze benchmark; evaluate and approve local encoder/artifacts; implement measured hybrid extension | Independent semantic quality/resource/privacy gates pass; only then claim hybrid completion. |

Keep new logic in small `Workspace*` files: catalog/index codec and builder,
file resolver, turn access/coverage if needed. Extend existing controller/store/
indexer/retriever classes at their boundaries. Avoid growing `AssistantPluginService`
with indexing/ranking code. Tool schema changes require native Workspace provider
adapter/strict-schema tests for nullable `file`. The current
`HERMES_TEXT_TOOL_NAMES` contains neither Workspace tool: preserve and test that
unsupported behavior. Do not add a Hermes Workspace surface merely to satisfy
a nullable-file schema test, or pretend every provider supports recovery.

### Required regression cases

| Area | Required observation |
|---|---|
| Persistence and real lookup | Index, kill/recreate store/controller, reconcile unchanged metadata, query repeatedly. One root check and zero extra source-document metadata/open/extraction/OCR/retokenization calls per cached query; root's own internal metadata call is allowed. Byte-identical search results with persisted versus once-rebuilt postings. Unchanged observer passes open only unknown-digest legacy files; explicit full hash verification honors deadline and resumes. |
| Size and partial catalog | 100 PDFs x 500 alternating page states, 1M text characters, 2,500 dense non-ASCII chunks, about 100k distinct terms. Final UTF-8 JSON <=8 MiB; omit cache if needed with all originally retained chunks kept; complete in-memory search either way; all catalog page identities retained. `TRUNCATED` never caused by dropping postings. Oversized/corrupt arrays rejected before large allocation. |
| Migration and transactions | v1 nonpaged/v1 paged/v2 partial/no-text/corrupt/future versions; power-loss-style temporary file; atomic write failure; no folder reselection; old committed file survives failed upgrade. Legacy PDF with chunks on every page: count/digest inspection makes zero page-extraction/OCR calls. PENDING resumes at pagesRead+1; only chunk-less legacy pages are backfilled. Retained text/epoch stay unchanged; count is not inferred; legacy cited-page view remains usable before broad scope becomes available. |
| Orion/source scope | Three-page annex plus variants at pages 1/3/17; full question and scoped names-only follow-up; filename absent in annex; warm, fresh conversation/process, pending indexing, Memory 0/5,000/9,500/10,002. Exact fact and page citation when evidence fits; explicit unavailable/partial at readiness/zero-budget boundaries. |
| Identity and ambiguity | Same basename in two directories; accent/case/96-/160-character label collisions; missing Orphee with attractive unrelated code; Jean Dupont versus Jean Martin; constrained private `Jean et Marie Dupont`; semicolon keyword groups; mixed-person table. No guessed file and no wrong-person code; view or abstain for ambiguous layout. Natural initial compound recall and stricter private-search recall measured separately. |
| Textless and vision | Empty-OCR-only folder, image, renderer-unavailable PDF, blank scan, OCR timeout, too-dense page 2 of 5, and partially indexed 501-page PDF. Failed/dense page cursor advances; pages 3-5 extract; controller progress loop terminates; failed unchanged page is not retried. General hit citing page 4 cannot view page 5; explicit Orion scope can view empty-OCR page 5. Reject out-of-range/stale refs, no-vision provider, and unavailable rendering. |
| Effects and races | Revoke/disable/switch folder/delete/replace while extracting, searching, rendering, sending, and finalizing; replace a zero-chunk PDF; same size/time but different known view digest; two turns at the same epoch with different cited pages. Turn 1 never uses turn 2's access/evidence. No new Workspace effect after withdrawal; final suppression only when actual Workspace text/pixels were supplied. Pure additive publication preserves supplied evidence only absent an invalidating notification. Metadata-preserving cached-text edits retain the documented freshness limitation. |
| Cost and policy | All framing included at every small/zero budget, one view/two distinct searches, shared 4/8/deadline unchanged, repeated identical search, source prompt injection, cancellation while native render continues. Common fallback dependency depth <=2. |

Notification tests must distinguish these exact cases:

| Turn state when notification fires | Required observation |
|---|---|
| Empty text prefetch and no evidence-bearing tool result | General answer still publishes to HUD/TTS/history; all Workspace tools/access are withdrawn for the remaining turn. Metadata-only choices/coverage do not change this outcome. |
| Workspace text prefetch was supplied to the provider | Final HUD/TTS/history publication is suppressed with a reason code; later reconciliation cannot revive the turn's evidence. |
| Search supplied evidence; notification arrives before view | View returns `source_changed`; no image is sent; prior evidence means final-effect suppression applies. |

Diagnostics contain counts, elapsed times, readiness/migration/budget/selection
reason codes, and operation counts. Production logs contain no raw query,
document name/path/ID, text, OCR, digest, or image. Bounded synthetic QA traces
may record fixture identifiers and delivered page IDs outside production logs.
Trace prefetch availability and tool declarations together; a null validated
snapshot yields a -1 version today and usually suppresses tools too.

Freeze a versioned synthetic manifest before tuning: at least 40 answerable
questions spanning scoped annex/exact text/paraphrase/FR-EN/visual cases, plus
20 missing/ambiguous/wrong-person controls. Record expected file/page and allowed
facts, then keep at least one-third held out from tuning. Report lexical-only
semantic misses honestly in A; B must improve held-out paraphrase/FR-EN evidence
page recall@5 by at least 10 percentage points, without reducing exact-name/code
recall or creating any wrong-source/wrong-person/invalid-citation result in the
control set. Small fixture counts are engineering gates, not statistical proof
of general accuracy. Human-review table/visual answers against original pixels.

For A's deterministic gates require 100% of in-budget scoped-annex cases to
supply the expected page, zero wrong-source cases, and zero stale-reference
acceptance. Measure warm p50/p95 local lookup, cold validation, indexing time,
actual JSON bytes, peak memory, and extraction/open/OCR counts separately from
network model latency. Provisional device target: warm lookup/ranking p95 <=50 ms
at the cap, excluding the separately reported existing root/local-provider checks; no
regression >20% in restart loading versus baseline without explanation. Measure
on the actual phone, never substitute desktop unit-test timing. If a gate fails,
report it and adjust the bounded representation/implementation before declaring
acceptance; do not silently increase the budgets.

Run the authorized Assistant verification after implementation:

```powershell
.\gradlew.bat :plugin-assistant:testDebugUnitTest :plugin-assistant:assembleDebug -PskipCxrGlobal=true --console=plain
```

Run meaningful new deterministic tests in the existing Workspace test suites;
use fake gateway/reader counters and controllable race barriers rather than
sleep-based guesses. Device acceptance uses a clean synthetic conversation to
avoid retained-history answers, and captures which exact pages reached the
provider and final citation. Installation and device use belong to the parent
workflow, not this design pass. Report real command tails and observed failures.
If Java/SDK/cache/network environment prevents verification, stop and report it;
never edit `local.properties` or create private SDK/Gradle/cache directories.

## Review resolution and implementation handoff

Fable's focused review confirmed the first five corrections and accepted the
caller-based qualification, existing Hermes exclusion, and staged A/B delivery.
Its last two required changes are incorporated: notification always withdraws
Workspace access but suppresses final effects only after actual evidence
delivery; legacy count/digest inspection preserves retained text without
re-extracting/OCRing those pages. The unknown historical v2 digest is explicitly
not treated as verifiable content continuity.

Astra confirms these corrections satisfy the conditional agreement, with no
unresolved design exception. Delivery A can proceed on
`qa/skills-workspace-typed-160` under this plan. This approval is not a build/test
result and does not declare the hybrid goal complete: B remains an explicit
measured milestone. Changes that materially depart from this contract must be
identified before implementation is accepted; another routine review round is
not required for the corrections above.
# Parent verification follow-up (2026-10-10)

The implementation received a parent code review and corrections after the Opus
delivery. The final Assistant suite has 552 tests without failures; debug and
release builds passed. Actual USB written-mode tests verified a scoped PDF
annex, pages with no text layer, missing-file abstention, a changed fact after
process/session restart, and a Transit Skills operation. See
[the parent QA report](../docs/reviews/persistent-workspace-index-typed-qa-20261010.md)
for evidence and limits. Delivery B, the frozen 40 + 20 model benchmark and
phone resource/latency measurements at the caps remain pending. This follow-up
does not change the approved architecture or claim public release acceptance.
