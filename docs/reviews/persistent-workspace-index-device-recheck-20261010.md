# Persistent Workspace index: USB written-mode recheck

Date: 2026-10-10. Branch: `qa/skills-workspace-typed-160`.
Device work completed at 12:06 UTC. This supplements the
[earlier Delivery A report](persistent-workspace-index-typed-qa-20261010.md).

The final installed Delivery A APK was tested again on the USB phone and
glasses. The pass found one visual tool-selection refusal, corrected the
Assistant policy, and tested the corrected APK on an unseen drawing. Retrieval
is still lexical; this is focused device evidence, not release qualification
or the pending semantic/hybrid delivery.

## Availability, input, and controlled sources

The other device threads were inspected before mutation. Launcher work had
completed; the Reddit/Patcher thread was preparing code/builds, with no active
device test. Both devices were at their home/idle surfaces. The installed
Assistant hash matched the previously tested final APK. Explicit USB serials
were used throughout, including when a wireless glasses connection was listed.

Questions used Futo soft-key taps through the phone's RemoteInputActivity and
StreamingEditText InputConnection. The helper checked Assistant HUD ownership,
the empty Nexus phone editor, the observed keyboard, and the actual editor text
before the normal ENTER action. No voice, debug receiver, `adb input text`, or
glasses UIAutomator was used. Only the phone used UIAutomator.

Ground truth and hashes were recorded locally before questions. Two new files
were added to the already selected `NexusWorkspace-20261008` QA folder:

- `silex1010.pdf`: four pages; the meeting annex on page 3 says Tours, salle
  Ambre; page 4 is a green square without PDF text. The owned source was later
  changed to Poitiers, salle Topaze before process/session restart.
- `rivage1010.pdf`: a different meeting location on page 1 (Calais, salle Jade)
  and an orange five-pointed star on page 2, without PDF text.

The seven existing files were never replaced. Synthetic QA conversation
records were retained as evidence.

## Observed results and correction

| Case | Observed result | Evidence and limit |
| --- | --- | --- |
| Initial scoped Silex meeting question | Tours, salle Ambre; page 3 | Verified typed field and actual answer HUD. The other file's Calais/Jade fact was not substituted. |
| Initial natural Rivage page question: `que montre la page 2` | Refusal: no readable excerpt; cannot confirm the page | Failed visual expectation. The model did not view the page. The refusal was captured and retained; it is not counted as a success. |
| Explicit opening of the same Rivage page | Orange five-pointed star on white; page 2 | Actual HUD and `Looking at the page` trace establish that rendering/vision worked before the correction. |
| Corrected APK, natural Silex page-4 question | Large solid green square on white; page 4 | First question/view of this drawing. Actual HUD and page-view trace. The test used `que montre la page 4`, without an explicit open command. |
| Corrected APK, missing `le pdf absent1010` | File unavailable; cannot verify the meeting location | Verified typed field; HUD captured the refusal prefix. Normal Conversations UI then confirmed the full answer: `Le fichier absent1010.pdf n'est pas disponible dans l'espace de travail, donc je ne peux pas verifier le lieu de la reunion.` Accents/apostrophe are normalized here. A read-only search was attempted; no other location was supplied. |
| Changed Silex source after process/session restart | Poitiers, salle Topaze; page 3 | Actual typed HUD answer and search trace. The new fact replaced the earlier Tours/Ambre answer. The page-4 question ran first after restart, so this is not an isolated no-history retrieval benchmark. |
| Workspace disabled just after sending a written question | `Workspace changed during this answer. Ask your question again.` | Normal settings Off action, recorded timing, actual HUD error, and `turn_send_blocked evidence=true`. This exercises withdrawal at the request-send guard, not every mid-stream or subsequent-action race. |

The visual refusal exposed a policy gap: the mandatory page-view instruction
was tied to a visual marker on a supplied text excerpt. A cataloged page with
empty OCR could have no such excerpt. The policy now explicitly requires
`view_workspace_page` when the wearer asks what a particular page of a named
file shows, including pages with no supplied excerpt or indexed text. Failed
views must be reported without substituting another page/file. Existing
file/page authorization and freshness checks are unchanged.

The corrected natural-page smoke test passed. A policy instruction and this
single unseen example do not establish a general model success rate.

## Restart and reconnection

The hub was stopped through its normal UI. Assistant was force-stopped and
`pidof` returned no process. Phone Bluetooth was switched Off and On through
normal Android settings: the observed global value was 1 -> 0 -> 1, and the
adapter changed from enabled/ON to disabled/BLE_ON and back to enabled/ON.
Android BLE scanning remained available while normal Bluetooth was off.
Pairing was not removed.

After installing the corrected Assistant and restarting the hub, the old
Assistant HUD anchor did not establish editable-field ownership. The helper
aborted before typing. Returning Home, reopening the Nexus launcher, and
selecting Assistant restored a fresh session; subsequent real phone-keyboard
questions and tool replies succeeded. This validates recovery through normal
reopening, not automatic restoration of the previous Assistant field. It is
also a combined Bluetooth/session/process restart, not an isolated Bluetooth
latency or repeated-disconnection benchmark.

## Build and installation evidence

Actual command:

```powershell
.\gradlew.bat :plugin-assistant:testDebugUnitTest :plugin-assistant:assembleDebug :plugin-assistant:assembleRelease -PskipCxrGlobal=true --console=plain
```

Actual output tail:

```text
BUILD SUCCESSFUL in 2m 40s
205 actionable tasks: 15 executed, 190 up-to-date
```

JUnit XML: **552 tests, 56 classes, 0 failures, 0 errors, 0 skipped**. Only the
Assistant policy changed; independent hub/dependency suites were not rerun by
this pass. No build configuration, SDK, or cache location was changed.

The installed Assistant APK was backed up. An initial copied historical
signing helper pointed at an older QA checkout. Android rejected its version
17 APK twice with `INSTALL_FAILED_VERSION_DOWNGRADE`; the installed version
18 hash was verified unchanged. The helper's temporary checkout path was then
corrected, and candidate version 1.4.9/code 18 checked before installation.
No downgrade bypass, uninstall, or data clear was used.

The corrected APK was signed with the existing Nexus release key. Certificates
were compared before `install -r`, and checked again against the backup.
The installed APK hash matched the signed file:
`c93d833f147b1afd13031f8b2469101fcb731307655be42ead5ab518fb610ce4`.
Version remains **1.4.9/code 18**, locally installed for QA only.

## Restoration and remaining qualification

Both owned PDFs were removed after checking their expected hashes, including
the changed Silex source. All seven original files were verified unchanged.
Workspace was restored On with the same folder; normal indexing reported
Ready, 7 cataloged files, 5 searchable files, 12/12 known pages processed,
1 without text, and 2 skipped. The deliberate Off action cleared the derived
index; normal On rebuilt it without reselection.

Installed phone/glasses hub, Reddit, and Patcher APK hashes matched the start
baseline. No Reddit account, draft, preference, provider credential, or Skills
grant was edited. Hub and Bluetooth remained On; both devices returned to
native homes. Owned remote screenshot/XML scratch files were removed.

Evidence is in the existing QA temp directory under
`persistent-index-recheck-20261010/`: baseline/fixture/signing manifests,
build log and JUnit summary, typed records and HUD screenshots, the missing
file's normal-history evidence, Bluetooth-cycle/withdrawal records, safe
diagnostics, and cleanup checks. No private signing material is in the repo.

The full frozen 40 answerable + 20 negative benchmark, maximum-catalog phone
resource/performance measurements, semantic/hybrid retrieval, and broader
mid-stream/action-race and repeated-reconnect qualification remain open.
No main checkout change, push, merge, or public release was performed by this
QA pass.
