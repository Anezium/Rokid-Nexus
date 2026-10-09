# Final written-mode Skills and Workspace device pass

Date: 2026-10-09. Implementation: `25bd8e0f`.
Branch: `qa/skills-workspace-typed-160`.

## Deployment and test method

The phone and glasses were connected through USB ADB. The other device session
was completed before mutations. Both hubs already reported version 1.6.0 and
were retained. No launcher branch was merged and no hub APK was replaced.

Assistant 1.4.8 and Transit 1.0.4 release APKs were built from the reviewed QA
revision. Each installed plugin APK was backed up. The existing release signing
helper signed the new APK, verified its certificate against the installed
backup, then used `adb install -r --user 0`. Both updates returned `Success`.
App data, accounts, Workspace selection and existing preferences were retained.

Observed build command:

```text
.\gradlew.bat :plugin-assistant:assembleRelease :plugin-transit:assembleRelease :plugin-nav:testDebugUnitTest :plugin-media:testDebugUnitTest -PskipCxrGlobal=true --console=plain
```

Real output tail:

```text
BUILD SUCCESSFUL in 46s
247 actionable tasks: 58 executed, 33 from cache, 156 up-to-date
```

This plugin-only command does not build either vendor-linked hub. The preceding
correction report records the observed 1,163 Assistant/Transit/shared/bus-client
tests. Navigation (64 tests) and Media (20 tests) unit tasks were `UP-TO-DATE`
from successful existing results in this release-build command; they were not
executed anew.

All questions below used actual Futo AZERTY soft keys on the phone:
`StreamingEditText -> active Assistant glasses field -> normal ENTER control`.
The helper required an empty Nexus phone editor and an Assistant question field
before typing. The phone text and glasses screenshots independently recorded
the question. No debug question receiver and no glasses UIAutomator were used.
Tool progress labels were observed through the existing privacy-preserving log;
they contain step names, not arguments or results.

## Observed results

Workspace retained its ordinary Downloads subfolder and initially showed five
readable documents, twelve excerpts, two skipped files and zero truncations.
The protected PDF and the PDF without readable text were identified by the
folder-status message. Two isolated temporary fixtures were added for fresh
chart and full-subject checks; re-indexing showed seven documents and fourteen
excerpts with the same skipped/truncated counts.

| Written question/check | Observed result |
|---|---|
| Boreal code | `NIMBUS-3486`, citing `workspace-native-smoke.txt`. |
| Orion PDF locker code | `3912`, citing `orion.pdf`, page 2. |
| Scanned meeting PDF participants | Alice, Bruno and Chloe, citing `reunion-scan.pdf`, page 6. The six-page file has no text layer. |
| Fresh Polaris PDF chart: highest bar | Ouest, citing the Polaris PDF, page 1. The magnitude is absent from the text layer, the fixture was newly created, and `Looking at the page…` was observed. The answer shortened the file citation instead of retaining the full QA filename. |
| Hotel invoice JPEG total | `184,50 EUR`, citing `facture-hotel.jpg`; `Looking at the page…` was observed. |
| Jean Dupont's code with only Jean Martin in the fixture | Refused unsupported attribution, identified the different person and did not repeat `NEPTUNE-6048`. `Searching your workspace…` was observed. |
| General knowledge: Marie Curie | Answered with physicist/chemist; no Workspace search/page step occurred. Only the visible opening of the longer answer is recorded. |
| Code in protected PDF | No relevant excerpt found for `verrouille.pdf`, with a Workspace search observed; no invented code. |
| Transit favorite stops | Returned Paris Gare du Nord and Opera on the HUD; `Asking Transit…` was observed. |
| Bus 14 at Bibliotheque Francois Mitterrand | Explained that line 14 is metro rather than bus and requested Orly/Saint-Denis direction clarification; did not label metro departures as bus. Two Transit steps were observed. This does not independently expose the wire selector argument. |
| Metro 14 toward Orly | Displayed an Ink HUD card with the next departure at 22:10 after a Transit step and `Drawing the card…`. |
| "The one just after" following that card | Queried Transit again and stated that no subsequent Orly departure was displayed. It retained the stop/destination and did not invent a time. The finite live response does not establish advancement to a second departure. |
| Orion launch-meeting room after Assistant process restart | **Retrieval limitation observed:** refused because available excerpts did not cover the annex, although page 3 contains Lyon/Bellecour. A Workspace search was observed from the new process. This is not a successful answer check. |
| Fresh chart's smallest bar after Assistant process restart | Correctly answered Sud, citing the QA Polaris PDF, page 1; Workspace search and page-view steps were observed from the restarted process. This verifies retained folder/page access independently of the preceding text-retrieval miss. |
| Invoice after normal hub stop/start | Answered 2 nights, citing `facture-hotel.jpg`, through a fresh normal written question after reconnect. |
| Transit after normal hub stop/start | A fresh written favorite-stop question returned Paris Gare du Nord and Opera, with a new `Asking Transit…` step. |

## Capture limitations

The chart question wrapped over two lines. Windows OCR reordered its words and
the original helper did not recognize the contiguous `Enter to send` phrase,
so it stopped before submission. The screenshot showed the intact Assistant
field and the phone editor held the exact expected question. That existing
typed question was then submitted through the ordinary phone ENTER control.
The helper subsequently checked the separate action words and exact phone text
instead of relying on that OCR word order. This was a capture guard issue, not
an application failure or a debug-input substitution.

Step labels establish execution entry after tool validation. They do not record
the tool arguments, bytes sent or result object. Correct answers, fixture ground
truth and citations are assessed separately. The chart is fresh and differs
from the old chart; its result cannot be attributed to the old fixture's answer.

## Restart, reconnect and cleanup

Assistant was force-stopped after closing its HUD session. Its process was absent
before reopening from the glasses launcher. The native folder and page access
survived, as established by the new smallest-bar question and actual page-view
step. The Orion annex miss is separate and remains a retrieval limitation.

The phone hub's normal STOP HUB and START HUB controls showed their opposite
button states, and the restarted UI reported fourteen active plugins. A fresh
written invoice question returned a previously unasked fact after reconnect.
The fresh Transit favorite-stop call also succeeded after reconnect.

Both added fixture files were removed only after verifying their hashes still
matched the owned local fixtures. The seven original native-folder files were
verified byte-for-byte against their pre-test SHA-256 hashes. Re-indexing restored
five readable documents and twelve excerpts. Cleanup verification is retained
with the local evidence.

Transit favorite-stop and departure-read approvals were enabled temporarily
through normal Plugin access controls. Their observed initial state was off;
cleanup restored them to off. Transit's Offer skills and Search stops grants,
and Assistant's Use skills gate, retained their initial enabled state. Future
departure/favorite reads require enabling the corresponding operations normally.
No journey, Calendar or Media side-effect approval was added.

Fresh switch reads confirmed both temporary operations off and all three
initially enabled gates on. The normal hub remains running. The phone was
returned to Home and the glasses to the Nexus launcher; the QA-only remote
UI XML and screenshot files were removed. APK backups, screenshots, typed
records, fixture ground truth and cleanup hashes remain in the existing local
QA temporary directory under `final-25bd8e0f`. No secrets or device serials are
included in this repository report.

## Boundaries

This is stationary written-mode acceptance on the connected devices. It does
not establish speech recognition, navigation while moving, real boarding or
transfers, compatibility with every OEM document provider, or a full phone reboot.
The body-keyword gate and names-only follow-up policy can miss an attribute on a
page whose named subject appears only in file/heading metadata. The observed
Orion annex refusal is consistent with this documented precision/recall tradeoff;
folder access loss is excluded by the successful post-restart image view. This
pass therefore does not claim every question over every PDF page succeeds.

The provider was the existing configured ChatGPT connection. The paid-key
`api.openai.com` Responses route remains untested live; it has the previously
recorded local HTTP regression coverage. No new model review was requested for
this device-only pass. No product-source edits, main merge, push or public release
are part of the pass.
