# Persistent Workspace index: parent review and written-mode USB QA

Date: 2026-10-10. Branch: `qa/skills-workspace-typed-160`.

Delivery A is implemented and the focused device smoke tests below passed.
Retrieval remains lexical. Delivery B (qualified local semantic encoder and
measured hybrid retrieval), the full frozen 40 answerable + 20 negative model
benchmark, and phone performance at the catalog/text caps remain open. This is
a locally installed test build, not a public release acceptance report.

The architecture received Astra's agreement and two direct Fable 5.1 API plan
reviews. Opus 5.5 high implemented A. This parent review then inspected and fixed
the implementation; no additional Fable/Cursor review was run.

## Corrections after the Opus delivery

- Preserve `;` / `?` query grouping in the turn search cache. `Vega Aurora` and
  `Vega; Aurora` are different searches; an equivalent grouped retry uses its
  cached result without another root check.
- Resolve whole bare basenames as agreed, while keeping partial names and
  collisions unresolved. Recognize missing spoken names before and after file
  cues, including `le pdf orphee`, even in an empty catalog. A missing explicit
  target takes precedence over incidental bare names such as `code`.
- Equally ranked named candidate files now return metadata choices instead of
  unrestricted prefetch. Choices grant no document/page access.
- Check the turn's current access before each tool execution and partial HUD
  update. Withdrawn evidence blocks a subsequent action, while a turn that
  supplied no Workspace evidence can still finish an unrelated task.
- Persist a pending explicit verification request. Detaching cancels work;
  reattaching or restarting reconstructs verification instead of losing it.
  Verification conservatively starts again from the catalog after cancellation;
  extraction still resumes from its durable page cursor. Off/folder replacement
  clears the request. A failed request write reports `STORE_FAILED`, retains the
  previous index/settings, and does not crash the settings click handler.

Regression tests exercise these behaviors, including unchanged metadata with
changed bytes after detach/process restart and failed atomic settings writes.
Two existing fixtures were adjusted to ask unscoped questions instead of
accidentally selecting a filename under the agreed bare-name behavior.

## Local verification

Actual final command:

```powershell
.\gradlew.bat :plugin-assistant:testDebugUnitTest :plugin-assistant:assembleDebug :plugin-assistant:assembleRelease -PskipCxrGlobal=true --console=plain
```

Actual output tail:

```text
BUILD SUCCESSFUL in 36s
205 actionable tasks: 16 executed, 189 up-to-date
```

JUnit XML: **552 tests, 56 classes, 0 failures, 0 errors, 0 skipped**.
Only Assistant production code changed. The dependency modules were built by
this command; their independent suites and hub suites were not rerun for this
delivery. Hubs were not rebuilt or replaced. No machine build configuration,
SDK, or cache location was created or changed.

The release APK was signed with the existing private signing helper. The prior
installed Assistant APK was backed up, the certificates were compared before
`install -r`, and the installed final APK hash was checked against the signed
file. Version: **1.4.9 / code 18**. No uninstall, app-data clear, main checkout
change, push, merge, or public release was performed.

## Actual written-mode device evidence

Both devices used their USB serials. The other device session was inspected and
had explicitly released the devices before mutation. Tests used normal Nexus
UI, the phone RemoteInputActivity/Futo soft keys, verified phone editor text and
Assistant HUD field ownership, then the normal ENTER button. They did not use
voice, an Assistant debug receiver, `adb input text`, or glasses UIAutomator.

Two new controlled PDFs were added to the already selected QA folder:
`luciole20261010.pdf` (three native-text pages; subjectless meeting annex on page
3) and `formes20261010.pdf` (two drawings, no PDF text layer). Existing files
were not replaced. Ground truth was recorded before the relevant questions.

| Case | Observed result | Evidence/limit |
| --- | --- | --- |
| First unseen Luciole meeting question | Dijon, salle Safran; citation to page 3 | Phone text and typed HUD verified. The first answer HUD capture was missed by slow log polling; its exact answer was then read in the normal Conversations UI. It is not claimed as a captured first-answer HUD frame. |
| Formes page 2, with no PDF text | A large solid blue circle on white | Actual HUD capture and `Looking at the page` tool trace. |
| Missing explicit `le pdf introuvable20261010` | File unavailable; cannot confirm the meeting place | Actual HUD capture; no unrelated Dijon/Safran answer and no tool execution. |
| Changed PDF + process/session restart | Nancy, salle Corail; citation to page 3 | The owned annex was changed to this previously unasked fact. Hub STOP, Assistant force-stop (no PID), then hub START. New process and actual typed HUD answer; prior Dijon history cannot establish the new fact. |
| Formes page 1 on the final APK | A large solid red upward triangle on white; page 1 | Fresh visual fact, actual HUD capture and page-view tool trace. |
| Skills/Transit regression on the final APK | Transit was called for Bellecour and returned multiple stops; Assistant asked which one to choose | Actual typed HUD answer and `Asking Transit` trace. The truncated result and ambiguity were stated. No favorites or departures request was made. |

The page-2 and missing-file runs used the first reviewed APK. The final APK adds
only the verification-request write failure handler; its changed-source restart,
fresh page-1 image, and Skills cases were tested after installation. Both APKs
have version 1.4.9/code 18; local signing/evidence files distinguish their hashes.

Safe diagnostic observations:

- Installed v2 index loaded in 8 ms, retaining 12 chunks, then migrated without
  folder reselection. Five paged documents were inspected, with only one
  previously chunkless page extracted during the migration pass.
- Test catalog: 9 files, 15 chunks, 17/17 known pages processed, 3 without text.
  The index was 5,316 bytes with persisted lexical cache.
- After restart, schema 3 loaded in 3 ms with `cache_used=true`; the unchanged
  reconciliation pass took 35 ms with **0 opens, 0 pages, 0 inspections**.
- The restarted scoped prefetch took 29 ms including a 23 ms root check; the
  follow-up search took 13 ms. The final page view took 119 ms and supplied a
  20,188-byte JPEG.

These few observations on a small synthetic catalog are not p95 measurements
at 2,500 chunks, peak-memory results, or a general accuracy benchmark. The unit
suite verifies no query-time source opens/extraction/OCR and worst-case index
bounds; it does not substitute for the remaining phone/resource qualification.

## Cleanup and evidence

Local evidence is under the existing QA temp directory's
`persistent-index-20261010/`: build logs, JUnit/signing summaries, typed input,
HUD screenshots/OCR, normal-history evidence, safe Workspace diagnostics,
fixture manifests, and cleanup checks. No private signing material was copied
into the repository.

Both owned PDFs were removed only after verifying their expected SHA-256.
All seven original QA files were verified unchanged, and the selected folder
was reconciled back to its original catalog. Installed phone/glasses hub and
Reddit APK hashes were checked against the pre-test baseline. No grant,
provider credential, Reddit account, draft, or preference was edited. Test
conversation records were retained as review evidence. Hub remained on as
observed at this pass's start; phone and glasses returned to their native homes.

The full physical Bluetooth reconnect and a device-level in-flight change race
were not exercised in this pass. The normal hub session reconnect and actual
Assistant process restart were exercised; the race guards were tested locally.
Legacy extraction state remains honestly partial, and an edit that preserves
size and timestamp still needs explicit verification unless a notification or
page-view digest check detects it. The historical Orion refusal's original
cause remains unproved.
