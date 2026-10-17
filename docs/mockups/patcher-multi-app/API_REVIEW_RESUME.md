# Patcher UI work completed

Final state, 2026-10-09: subscription Opus 5.5 completed the Reddit migration;
the parent reviewed, built, installed and verified compatible Patcher and hub
releases. Both tutorials now belong to Patcher and neither remains in Glasses
apps. No more implementation, device QA or model delegation is pending.
The user stopped API usage: do not retrieve/reuse the key or run any API review.

Completed provider/model: `claudeAgent` / `claude-opus-5-5`; exact taskId is in
`REDDIT_MIGRATION_RESUME.md`. No pending child runs, schedule or API process.
Read `DEVICE_QA_RESULTS.md` for final hashes, build tails, 1,454 passing tests
(12 existing skips), 72 actual phone checks and ten fresh inspected screenshots.
T3 gallery previews passed at 728px and 360px. Existing data, signer, original
keyboard choices and prepared YouTube result were retained.

The compatible hub includes latest completed Skills/Workspace/PDF source at
`2b439f08460161447fea5f43fc75d48229d3e12f`; its temporary overlay was restored
clean. The shared-device thread was completed, with no active/queued run before
device work. No new Fable/API approval was requested for the migration.

## Historical handoff and API rounds (superseded)

Everything below is an earlier record. Its RUNNING/pending work, old installed
hashes and further API-call instructions are superseded by the completed state
above and the user's instruction to stop API usage. Do not execute those steps.

Completed historical API work: Opus supplied two text-wrap fixes and then the
Android36 Back callback fix. Fable API explicitly approved both rounds in
FABLE_API_VALIDATION_ROUND4.md and FABLE_API_VALIDATION_ROUND5_BACK.md. The
model lookup and response IDs matched. These approvals do not cover the new
Reddit migration. Six native wrap checks and 736 normal root hub tests pass.

Compatible signed hub and Patcher ARE installed and their pulled APK bytes
and certificates match the frozen artifacts in
`build/outputs/patcher-validated-release-20261009-back/`. Completed
Skills/Workspace/PDF code at `7e412847` was preserved using a reversible
13-file overlay; the PDF checkout was restored clean. Compatible baseline:
763 hub + 388 shared + 149 bus tests, zero failures/errors/skips.

Actual phone QA completed: 21 tutorial, 10 keyboard and 9 header/inventory
checks; see `DEVICE_QA_RESULTS.md` and
`build/outputs/patcher-device-qa-20261009/`. Android Back is verified fixed
on SDK36. Original keyboard preference and prepared YouTube result retained.
The inventory test expecting one Reddit setup card is now SUPERSEDED: the
user wants no app-specific tutorial card in Glasses apps. Screenshots there
are baseline evidence; final presentation awaits the migration. Exclude the
black tutorial captures: MicroG, MicroG extras, source, patch and sign-in.
Use only fresh inspected frames for those pages; retain secure-window rules.

No QA interval monitor remains. The other shared-device thread was last
failed with activeRunId null and pendingRequestCount zero, but its quota
also resumes after 15:40: recheck durable state before any future device work.
Historical sections below are superseded by this current state.

## Delivered implementation and failed corrective round

Task ID:
`node:delegated-task:command%3Amcp%3A17087d20-c4fa-4170-8fca-b9b88b3f7336%3Adelegate-task%3Apatcher-opus55-ui-adjustments-20261009-round3`

Provider/model: `claudeAgent` / `claude-opus-5-5`, confirmed in the live catalog.
Round 3 is completed. Its actual delivery is `OPUS55_ADJUSTMENTS.md`, with 24
native Robolectric render scenarios under
`build/outputs/patcher-ui-adjustments-20261009/`. Parent verification observed
145 Patcher tests passed / 12 skipped and 736 phone hub tests passed; no failures.
These are debug builds and simulated native renders, not phone screenshots.

The child receives the complete actual Fable review and all five priorities,
instructions to use frontend-design and the native Nexus kit, and the correction
that image pixels must not be treated as dp. Its scope is the native Patcher home,
YouTube setup activity, related focused tests, and an English delivery note plus
native render artifacts. Approved HTML and its DESIGN.md remain unchanged.
Debug compile/tests needed for render evidence are allowed; release build and
device install wait for parent Fable API validation.

The completed API validation is `FABLE_API_VALIDATION_ROUND3.md` (lookup and
response model `claude-fable-5-1`, `stop_reason=end_turn`). It accepts all five
adjustments but returns CHANGES REQUIRED for two text-layout defects:

1. In `YoutubeSetupActivity.advanced()`, allow the amber running-job lock sentence
   to wrap: `maxLines = Int.MAX_VALUE; ellipsize = null` on that `rowSub`.
2. In `stepCard()`, allow the status `rowTitle` to wrap with the same properties,
   preserving the complete prepared-but-offline installation instruction.

Render source-locked, prepared-and-disconnected patch/install and running-job
Advanced at 360 x 740 dp, density 2, font scales 1.0 / 1.3; measure ellipsis and
geometry. No other optional polish is needed for this correction.

Corrective round 4 task:
`node:delegated-task:command%3Amcp%3A17087d20-c4fa-4170-8fca-b9b88b3f7336%3Adelegate-task%3Apatcher-opus55-ui-wrap-fixes-20261009-round4`

It failed before editing with the exact provider error:
`Claude API rate limit reached. Try again later.` No pending child work remains;
no corrective delivery or render artifacts exist. The live catalog still lists
`claudeAgent` / `claude-opus-5-5` as child-task capable. Do not silently substitute
another model or paid API provider for the requested subscription implementation.
The parent asked the user whether to wait for subscription Opus, allow Sol to
apply only these fixes, or use paid API Opus. No answer was received when this
handoff was written. Another thread using the same configured Claude provider
reported a session reset at 15:40 Europe/Paris; the failed child itself did not
provide a reset time. No retry timer has been created.

After actual fixes, obtain a new explicit Fable API approval with the fixed source
and targeted renders. Preserve round 3 validation and use a distinct round 4
validation artifact. Its conditional approval is not final approval.

## Completed API review

Actual complete response: `FABLE_API_REVIEW.md`. Both live model lookup and
Messages response confirmed `claude-fable-5-1`; `stop_reason=end_turn`.
The complete frontend-design skill, native kit/old Patcher references, approved
HTML/design notes, and six inspected screenshots were included.

Verdict: minor adjustments. Native implementation is already more faithful to
Nexus than the HTML. Five priorities: card type roles, short headers, coherent
status placement, measured overview density/repeat paths, secondary actions.
Some overview sizing and footer availability claims are assumptions to verify
against source and actual density, not instructions to blindly shrink controls.

The first API attempt stopped at the configured 12000-token limit. A corrected
call with `output_config.effort=medium`, `max_tokens=24000` completed. Do not report
the incomplete attempt as an independent completed review.

Local credential-free helper: `build/tmp/patcher-fable-review/review.py`.
The credential was supplied only through masked process input whose lack of echo
was first checked with fictional input. No credential file, provider config or
machine environment was written. No API process remains running. Do not put keys
in prompts, source, commands, reports or logs. The user already authorized use of
their provided key for the later Fable validation; do not ask for authorization
again or silently use another model. If a securely accessible authorized key is
unavailable, report the actual obstacle.

After corrective completion, inspect the actual edits/tests/renders and call Fable API
with the full review context, revised source/diff, accurate density-labelled
native renders and the remaining objections. Ask for a specific accept/reject
decision on the revised implementation, preserving native APIs and kit fidelity.
Do not infer validation from the previous review. Address actual objections and
repeat a review only if necessary. Then continue authorized local delivery/QA.

## Device QA and compatibility

The user's other thread `847cd5a6-e515-40c8-8fed-ecc634ad020c` subsequently resumed.
Latest read at 12:30 UTC: run 32 completed, and run 33 failed with the Claude
session limit, activeRunId null, pendingRequestCount 0. Run 32 reports new fixes in
`dev/workspace-pdf`, commit `7e412847`, in
`E:/Tools/Rokid/RokidNexus-workspace-pdf`, 477 Assistant tests and updated installed
APKs. A delegated Sol review of those fixes was still running in that report;
the requested merge into `qa/skills-workspace-typed-160` had not happened.
The thread metadata's typed QA checkout is therefore not the authority for the
installed PDF features. Re-read durable status and the final review/merge report
before any shared-device interaction or compatible overlay. Skills and PDF
Workspace must survive the Patcher UI update.

The temporary five-minute wakeup monitor was deleted after completion. No QA
schedule remains. No device UI inputs, captures or APK installations for the new
Patcher UI have occurred yet; read-only ADB discovery found phone and glasses.

Do not install the frozen root hub APK over the Skills-enabled hub. A reversible
overlay was PREPARED ONLY under `build/tmp/patcher-fable-review/compatible-overlay`.
No files in another checkout were changed. Regenerate it after the child changes.
Prepared merges preserve Skills additions in BusHubService, but inspect semantic
duplicates: NativeAppsActivity's staged automatic merge duplicated the existing
Reddit setup card. Two addition conflicts remain: the PatcherSetupEntryActivity
manifest entry and PatcherContract setup/job constants. Comparing the PDF checkout
to root confirmed these two files differ only by the intended Patcher additions.
The helper `prepare_overlay.py` normalizes Windows line endings before merging;
without normalization every file falsely conflicted.

The existing PDF checkout has no local.properties, while ANDROID_HOME is present.
Do not create local.properties, modify SDK/Gradle configuration or repair an
environment failure. Use existing build configuration, test/build without
skipCxrGlobal for hubs, and report actual failures/tails. Preserve release signer,
accounts, settings and grants. No commits, pushes or releases are authorized.

Read `DEVICE_QA_PLAN.md` and `NATIVE_IMPLEMENTATION_REVIEW.md` for local signing,
frozen APKs and the five privacy-safe phone checks. No real Reddit posting,
account login, voting/saving or signing-key replacement is required.
