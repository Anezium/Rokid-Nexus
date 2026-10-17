# Persistent Workspace index: Delivery A implementation report

This is the Opus implementation handoff record. The subsequent corrections,
final 552-test result and actual USB tests are in
[the parent QA report](persistent-workspace-index-typed-qa-20261010.md).

Date: 2026-10-10. Branch `qa/skills-workspace-typed-160`, baseline `fb450515`.
Implementer: Claude Opus 5.5 (high effort), working from
[plan 027](../../plans/027-assistant-persistent-workspace-index.md) and the
[Fable review and final agreement](persistent-workspace-index-fable51-plan.md).

This report covers local implementation and JVM unit tests only. Nothing was
committed, pushed, signed, installed, or run on a device. **Delivery B (a local
semantic encoder and hybrid retrieval) is not implemented and remains
outstanding.** Delivery A is lexical keyword search.

## What was built

### A1. Catalog, page states, and the v3 snapshot

- `WorkspaceDocument` now carries the true `pageCount`, sorted merged
  `pageRuns` (text/visual/render state), a separate resume cursor `nextPage`,
  `sourceDigest` (SHA-256 of the bytes actually read), `lookupSafe`, and
  `coverageKnown`. `pagesRead` is derived from the cursor. Images are catalog
  page 1; their chunks keep page 0 for citation formatting (`WorkspacePages.kt`,
  `WorkspaceModels.kt`).
- `WorkspacePageReader.read` returns per-page states and the actual page count,
  takes a `skip` predicate checked before extraction, and a new `inspect`
  returns the page count without text extraction, OCR, or rendering. The
  Android reader distinguishes a missing renderer (FAILED/UNAVAILABLE), OCR
  failure or timeout after rendering (FAILED/AVAILABLE), blank OCR
  (EMPTY/AVAILABLE), and a glyph-budget page (TRUNCATED, then continues with
  the next page). Every terminal state advances the cursor.
- `workspace-index.json` is version 3: compact array encoding, a deterministic
  JSON writer whose escaping is identical on Android and the JVM, and an
  optional posting cache (`lexicon`, `postings`, `tokenCounts`,
  `lexicalPersisted`). The cache is written only within 100,000 terms, 250,000
  pairs, and the 8 MiB file cap; otherwise it is omitted and rebuilt once on
  load. Text is never dropped for it. Catalog plus framing beyond 2 MiB, or text
  plus catalog beyond 8 MiB, fails the publication and keeps the previous file
  (`WorkspaceIndexJson.kt`, `WorkspaceLexicon.kt`, `WorkspaceJsonText.kt`).
- Load validates schema, extraction version, counts, duplicate ids, runs,
  digests, and every posting reference before a retriever trusts it.
- v1 and v2 files migrate on load: v1 paged entries are dropped as before; v2
  chunk pages become `LEGACY_TEXT`, page count and digest stay unknown, labels
  are unsafe for lookup, and a pending file resumes at `pagesRead + 1`.

### A2. Indexing, freshness, migration, and UI

- Each pass commits a metadata reconciliation first when a changed or removed
  file would retract evidence, then reads new, changed, and pending files, then
  establishes missing legacy digests and page counts through `inspect`, then an
  explicit verification, then backfills chunk-less `LEGACY_UNKNOWN` pages, all
  under the existing pass budget (`WorkspaceIndexer.kt`).
- Retained legacy text pages are never re-extracted. A legacy PDF with text on
  every page gains its digest and count with zero page extractions.
- `Re-index now` creates a `WorkspaceVerification`: unchanged files are hashed
  once more, a digest change re-reads the file, and each FAILED page is retried
  once. Ordinary and observer passes never hash metadata-unchanged files with a
  known digest and never retry FAILED pages.
- Observer notifications mark the snapshot dirty at once, withdraw the active
  turn, debounce, and coalesce into a rerun if a pass is running. Detaching the
  last owner cancels work; the next attach resumes from the committed cursor.
  No polling, boot work, or background lifetime was added
  (`WorkspaceController.kt`).
- `workspaceRetracts` now compares semantics: identity and reliable metadata,
  retained text, a known digest or page count, render eligibility, and text
  states. A textless document with known pages retracts when replaced;
  additive coverage, a first digest or page count, and cache changes do not.
  An unchanged v2-to-v3 upgrade keeps the epoch.
- Indexing reads and view renders share one heavy-operation slot
  (`WorkspaceHeavyGate.kt`); a waiting view asks indexing to stop at the next
  page boundary, and a timed-out native render keeps the slot until it ends.
- The settings card shows `Checking folder`, `Indexing N/M pages`, `Ready`,
  `Partially indexed`, or `No readable text; pages can be viewed`, and a
  summary of cataloged versus searchable files and processed pages, prefixed
  `Search by words`. The Memory-full warning is unchanged
  (`WorkspaceStatusText.kt`, `AssistantSettingsActivity.kt`).

### A3. File resolution and the turn object

- `WorkspaceFileResolver` resolves, before the first model request, an exact
  path, a full file name written or spoken (`orion.pdf`, `orion pdf`), or a base
  name next to a file word (`le fichier orion`). Collisions, including accent
  and case folding, are `AMBIGUOUS`; a named but absent file is `MISSING`; a
  unique match whose label is not lossless is `UNAVAILABLE`. None of these
  falls back to another file.
- `WorkspaceTurnAccess` (`WorkspaceTurnAccess.kt`) is created by
  `contextForQuestion`, passed through `ChatRequest.workspaceTurn`, the
  registry's `bindToTurn`, the request guard, and every fallback round. It holds
  the captured snapshot, the resolution, permitted file references, delivered
  citations, viewed pages, the search cache, and `evidenceSupplied`. The
  controller's global `turnCitations` is gone. A new question, a notification,
  a folder or grant change, or Off withdraws it permanently.
- `QUERY_PARTS` is `[;?]`. The question prefetch keeps the half-terms rule; the
  model's unscoped search requires every non-stopword term of a group in one
  passage body; a resolved file is searched alone with its name words removed,
  returns the whole retained document in page order when it fits, an overview
  for a names-only query, or up to three of its twenty best passages.

### A4. Tools, guards, providers, budgets

- `search_workspace` has the strict schema with required nullable `file`. Two
  different searches run per turn; an identical retry returns the first result
  without another root check or search. The result is one JSON text with
  `status`, `matchCount`, `excerpts`, `files`, `coverage`, and `reason`, at most
  2,500 characters and 12 KiB.
- `view_workspace_page` keeps one view, 8 s, 1,600 px, and 1,000,000-byte JPEG.
  An explicitly resolved file with known page count and digest may show any page
  up to `min(pageCount, 500)`; every other case only pages cited by text this
  turn supplied. The view reads bytes once, checks metadata, compares a known
  digest, and renders those bytes. The image carries runtime provenance in the
  provider envelope (`AssistantToolResult.Image.caption`: Codex `input_text`
  before `input_image`; compat tool message `source` and the attached user
  message text).
- Search and view availability are separate: a zero-chunk folder does not
  advertise search but can view a named file's pages. The service strips both
  Workspace tools when it drops Workspace from a prompt.
- The guard records supplied prefetch text at the first send. After withdrawal
  it throws only if Workspace text or pixels were supplied; final HUD, speech,
  and retained history are withheld (reason `workspace_source_changed`) only in
  that case. An unrelated answer still publishes.
- All prefetch framing, scope, choices, and coverage fit
  `workspacePromptBudget`; zero injects nothing. Shared 4 rounds, 8 calls, and
  the 60 s round-start deadline are unchanged. Hermes still receives neither
  Workspace tool.
- Diagnostics (`WorkspaceDiagnostics.kt`) accept only event codes, numbers,
  booleans, and fixed codes; tests check no name, query word, or digest leaks.

## Plan points interpreted or tightened

- A bare base name resolves only beside a file word or its extension word; a
  question naming two distinct files gets no scope and uses the ordinary
  prefetch. `MISSING` is detected from `name.ext` or `<word> <extension>`; a
  form such as `le pdf orphee` is not recognized as missing.
- While a change notification awaits reconciliation, new questions get no
  Workspace access at all.
- If access is withdrawn between dispatch and the first send of a prompt that
  already carries excerpts, that answer fails closed with the existing
  "Workspace changed" error; text in a built prompt cannot be stripped there.
- The explicit verification cursor lives in memory for the attached session;
  leaving every owner drops a pending verification.
- When inspection fails after the bytes were read, the digest is kept and the
  page count stays unknown, so the file never gains all-page scope.
- A migrated document keeps `coverageKnown=false` and shows as partially
  indexed while any `LEGACY_TEXT` page remains; `Re-index now` does not
  re-extract those pages.

## Verification

Command (Windows, from the worktree root), last run after the final edit, with
`compileDebugKotlin`, `testDebugUnitTest`, `packageDebug`, and `assembleDebug`
all executed rather than up to date:

```
.\gradlew.bat :plugin-assistant:testDebugUnitTest :plugin-assistant:assembleDebug -PskipCxrGlobal=true --console=plain
```

Tail:

```
BUILD SUCCESSFUL in 31s
84 actionable tasks: 7 executed, 77 up-to-date
```

JUnit XML: 548 tests, 0 failures, 0 errors, 0 skipped, 56 classes. The debug
APK reports `versionName 1.4.9`, `versionCode 18`. No other module was changed,
so no other suite was run.

New test classes (45 tests): `WorkspaceIndexV3Test`, `WorkspacePageStatesTest`,
`WorkspaceFileResolverTest`, `WorkspaceTurnTest`, `WorkspaceLifecycleTest`, plus
the shared `WorkspaceTestReaders.kt` (counting page reader and turn double).
Existing Workspace, tool, provider, guard, and loop tests were adapted to the
turn API. They cover, among others:

- persisted versus once-rebuilt postings returning identical results across
  modes and a scoped search, with zero body re-tokenization on reload or query;
- the worst case of 100 PDFs with 500 alternating page states, 2,500 dense
  Cyrillic chunks, and 100,000 distinct terms within 8 MiB and the 2 MiB catalog
  reservation; one term more drops only the cache;
- corrupt, future, and inconsistent files failing closed; v1/v2 migration; a
  failed atomic write keeping the v2 file; an unchanged upgrade keeping the
  epoch;
- one root check and zero source metadata, open, extraction, inspection, OCR,
  or render calls for cached prefetch and search;
- the Orion annex at pages 1, 3, and 17 reached by the full question and by a
  names-only follow-up; Memory at 0, 5,000, 9,500, 9,900, 10,002, and 12,000;
- missing, ambiguous, unverified, duplicate-basename, and accent collisions
  with no fallback; `Jean et Marie Dupont` versus Jean Martin; semicolon groups;
- dense, empty, renderer-unavailable, and OCR-failed pages; cursor progress;
  one retry on explicit Re-index; a 501-page PDF; a deadline-bounded,
  resuming verification; a digest change under unchanged metadata;
- legacy migration with zero page extraction, pending resume at
  `pagesRead + 1`, chunk-less backfill only, and legacy cited-page viewing
  before digest and count are known;
- textless-only folders, general hits limited to cited pages, explicit scope
  viewing an empty-OCR page, same size and time with different bytes;
- the three notification cases (no evidence, prefetch evidence, change between
  search and view), a withdrawal winning a search race, additive publication,
  same-epoch turns, revoke, Off, coalesced notifications, and detach/resume;
- strict nullable schemas, Hermes exclusion, image provenance envelopes, the
  2,500-character and 12 KiB result bounds, status text, and log hygiene.

Desktop unit-test timing (about 20 s for the whole suite) says nothing about
phone performance.

## Not done and open limitations

- No device QA, no phone timing (warm lookup p95, cold validation, load time,
  JSON bytes, peak memory), and no frozen 40 + 20 question manifest. These are
  the parent workflow's acceptance gates and remain open.
- Retrieval is lexical. The unit fixture shows the recall change the plan
  predicted: a French question joined by `et` whose words differ from English
  passages now prefetches nothing and depends on a semicolon search. Unscoped
  model searches are stricter and will miss synonym and translation matches.
- Cached text freshness still relies on notifications and reliable size and
  time; an edit preserving both stays undetected until `Re-index now`.
  A notification cannot name the changed file, so any change suspends
  Workspace for new questions until reconciliation commits.
- A view can wait at most its 8 s deadline behind one in-progress indexing page
  operation (OCR may take up to 30 s) and then fail.
- Views of a legacy document whose digest is still unknown keep the baseline
  metadata-only check.
- org.json still parses the whole file (at most 8 MiB) into a tree before the
  posting arrays are length-checked.
- The cause of the original Orion refusal remains unproved; this change makes
  the explicit-file path deterministic in fixtures, it does not demonstrate the
  historical failure was this.
- CHANGELOG, README, and public docs were not updated; this is a test build,
  not a release.
# Parent follow-up

The implementation-time findings and 548-test result below are retained as the
Opus delivery record. The subsequent parent corrections, final 552-test result,
signing, actual written-mode USB tests and remaining acceptance gates are in
[the parent QA report](persistent-workspace-index-typed-qa-20261010.md).
