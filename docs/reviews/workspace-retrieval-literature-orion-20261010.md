# Workspace retrieval: literature and Orion controls

Date: 2026-10-10. Baseline: QA branch qa/skills-workspace-typed-160 at d23e6653.
The installed Assistant remained version 1.4.8 (code 17). This pass researched
primary sources, repeated two actual written-mode questions, and added one
retriever control test. It did not implement a replacement retrieval system.

## Recommendation

Use precise file resolution and a document/page catalog as the foundation,
then contextualized lexical plus semantic candidate retrieval and bounded
text/page viewing. Keep the first delivery small, as recommended in the
[Fable review](workspace-retrieval-architecture-fable51.md), while preserving
the access/evidence/coverage separation in the
[Astra proposal](workspace-retrieval-architecture-astra.md).

No published method removes the need to validate file identity, current access,
source versions, and evidence actually supplied to the answer model. Improving
similarity scores alone cannot recover a page missing from the accessible index.

## Primary literature and its application

| Source | Published mechanism | Application to Workspace |
|---|---|---|
| [Contextual Retrieval, Anthropic, 2024](https://www.anthropic.com/engineering/contextual-retrieval) | Adds explanatory document context to chunks before lexical and semantic indexing, combining BM25 and embeddings and optionally reranking | Preserve real filename, section and page context. Start with deterministic source metadata; generated contextual text would be a separate, more expensive design |
| [Late Chunking, Gunther et al., 2024/2025](https://arxiv.org/abs/2409.04701) | Encodes text with surrounding document context before pooling chunk embeddings | A later semantic index could preserve references to a project or person outside an isolated passage; requires compatible long-context embeddings and measured resource use |
| [ColPali, Faysse et al., ICLR 2025](https://arxiv.org/abs/2407.01449) | Retrieves document pages using image embeddings and late interaction | Relevant to chart, table, scan, and layout discovery that text OCR misses; a candidate for a separate visual-retrieval evaluation |
| [RAPTOR, Sarthi et al., ICLR 2024](https://arxiv.org/abs/2401.18059) | Builds a tree of clustered text and recursive summaries for retrieval at different abstraction levels | Relevant to broad questions spanning long documents; summary trees should remain navigation aids with source evidence retained |
| [Lost in the Middle, Liu et al., 2023](https://arxiv.org/abs/2307.03172) | Tests context-position sensitivity in document QA and key-value retrieval | Evaluate evidence placement and answer quality rather than assuming that sending an entire long document guarantees inspection |

These are research directions, not measured improvements on Nexus. In
particular, prepending an existing filename or heading locally is an adaptation
of the contextualization principle, not the complete Anthropic implementation,
which generates chunk-specific context with a model. Do not quote its reported
retrieval gains as product performance.

ColPali's reported latency comparisons use an NVIDIA L4 GPU. Those measurements
are not Android latency or battery evidence. Its page representation is useful
conceptually; model deployment needs a separate benchmark. Existing local OCR
and scoped page viewing remain the practical first visual path.

Late chunking requires token-level representations before pooling; a generic
API that only returns a completed chunk embedding does not implement it. Its
contextual benefit does not establish identity in a mixed-person table.

The long-context study evaluated the models and tasks in its paper, not the
currently configured Nexus model. Use it to motivate controlled evaluation,
not as proof that every present-day model fails on long context.

## Device observations

Both USB devices became available after the ADB daemon started. Before
interaction, the other device-owning thread reported completed with no active
run. The phone hub was initially stopped and the glasses displayed their native
home screen.

The hub was started through its normal phone UI. The synced launcher initially
selected Lens; an initial activation opened Lens and was immediately closed.
Assistant was then explicitly selected and verified before typing. Only the
two subsequently guarded Assistant questions are counted below.

Questions used actual Futo soft keys, the phone StreamingEditText/Nexus field,
and the normal ENTER control. The helper checked the active Assistant field
and exact phone text. Glasses screenshots/OCR and privacy-safe tool-step logs
were collected. No glasses UIAutomator, debug-ask receiver, or adb input-text
submission was used.

| Control | Observed result | Boundary |
|---|---|---|
| Full Orion question after ordinary Assistant open | Answered Lyon, salle Bellecour; no new Workspace tool step observed | No file/page citation visible in the captured answer; initial prefetch payload was not captured |
| First question after Assistant force-stop and reopen | Answered Lyon, salle Bellecour and cited orion.pdf, page 3; no new Workspace tool step observed | The earlier answer could be in retained conversation history; this is not independent proof that page 3 was retrieved in the restarted turn |

Both questions were exactly: dans orion pdf ou a lieu la reunion de lancement.
The helper captured matching phone text before submission. The phone process
was force-stopped between them; pidof produced no process ID before reopening.

The original refusal was not reproduced in these two trials. That does not
invalidate the recorded failure or establish its cause. Absence of an extra
tool step does not reveal whether the correct fact came from initial excerpts,
history, or another context source.

The type-and-capture workflow takes time after opening Assistant. It does not
test submission within two seconds of a cold process start. This pass did not
change Memory, conversation retention, folder selection, or grants to create a
more isolated timing experiment.

Evidence is retained under the existing QA temp directory in
research-orion-20261010: orion-warm-full.json, orion-restart-full.json, typed
field screenshots, and answer frames. The device report from the earlier pass
remains the authoritative record of the original refusal.

## Actual retriever control

Added a test to [WorkspaceRetrieverTest.kt](../../plugins/assistant/src/test/java/com/anezium/rokidbus/plugin/assistant/WorkspaceRetrieverTest.kt)
using a three-page Orion fixture: overview, locker code, and the annex sentence
without Orion in the annex body.

The observed assertions establish:

- The literal full question retrieves Bellecour and a page-three citation when
  the retained fixture is present and the normal excerpt budget is available.
- Searching Orion alone returns the code passage but not the annex.
- A zero excerpt budget returns no evidence, even for the full question.

These controls separate a real names-only retrieval gap from the unproved
claim that the full initial question necessarily fails its lexical filter.
They do not exercise actual Android extraction or reproduce provider behavior.

Command executed:

```powershell
.\gradlew.bat :plugin-assistant:testDebugUnitTest --tests '*WorkspaceRetrieverTest' -PskipCxrGlobal=true --console=plain
```

Observed tail:

```text
BUILD SUCCESSFUL in 59s
58 actionable tasks: 2 executed, 56 up-to-date
```

JUnit XML reports 15 tests, zero failures, zero errors, zero skipped. The new
Orion control appears in the executed suite. The remaining Assistant suites
and APK assembly were not rerun in this diagnostic pass.

## Next implementation and acceptance

First delivery:

1. Add privacy-safe readiness, budget, selection, and tool-availability reason
   codes. Trace the initial question separately from fallback searches using
   controlled synthetic documents and fresh conversations.
2. Resolve unambiguous explicit filenames, including spoken forms such as
   Orion PDF, before the first model request. Search attributes within the
   established file scope and retain current grant/version guards.
3. Persist enough page inventory and extraction state to discover and view
   textless pages. Keep viewing scope separate from evidence citations, and
   invalidate changed or removed visual-only documents.
4. Return bounded text, page references, and truthful coverage together. Keep
   common fallback dependency depth within two model rounds when feasible.

Then compare scoped lexical retrieval with local multilingual semantic
candidates and reranking on the same frozen corpus. Measure evidence recall,
attribution, citation validity, latency, bytes, and heavy OCR/vision work.

The benchmark must contain annexes at varying pages, differently worded and
FR/EN questions, duplicate filenames, missing subjects with attractive unrelated
codes, mixed-person layouts, empty-OCR visuals, partial long PDFs, zero/low
context budgets, and fresh-start/revoke/change races. Numerical visual answers
must consult original pixels; generated summaries do not become ground truth.

## Cleanup

Assistant was closed. The phone hub was stopped through the normal UI and its
START HUB state was freshly observed, restoring the initial hub state. Both
devices were returned to home. No APK was installed and no Workspace file was
created or edited. Temporary remote XML/screenshot files were removed; local
evidence was retained. Production code, root main, signing material, SDK paths,
and private build caches were not changed.
