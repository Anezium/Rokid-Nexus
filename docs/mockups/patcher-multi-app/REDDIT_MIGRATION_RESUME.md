# Reddit tutorial migration completed

Final state, 2026-10-09: implementation, compatible build, installation and
actual phone QA completed. No follow-up work is currently pending.

## Completed subscription delegation

Subscription provider/model verified immediately before launch:
`claudeAgent` / `claude-opus-5-5`, child-task capable. Final task status was
completed with an available result and no pending child runs. Retained taskId:

`node:delegated-task:command%3Amcp%3A17087d20-c4fa-4170-8fca-b9b88b3f7336%3Adelegate-task%3Apatcher-opus55-reddit-tutorial-migration-20261009-round5`

Actual child report: `OPUS55_REDDIT_MIGRATION.md`; parent source acceptance:
`PARENT_REDDIT_MIGRATION_REVIEW.md`. No API call or key retrieval occurred
after the user's stop instruction. No schedule was created and none remains.
Do not duplicate the completed implementation or restart API reviews.

Final signed delivery:
`build/outputs/patcher-validated-release-20261009-reddit-migration/`.
Final actual phone evidence and screenshot gallery:
`build/outputs/patcher-device-qa-20261009-reddit-migration/`.
Read `DEVICE_QA_RESULTS.md` for exact installed hashes, real build tails,
1,454 passing tests with 12 existing skips, and all 72 passing device checks.

Both setup tutorials now open from Patcher, with neither tutorial card left
in Glasses apps. Header/system Back, retained YouTube source/result and both
keyboard choices were verified. Original choices were restored. Ten fresh
actual phone captures passed visual/privacy/nonblack inspection; T3 gallery
checks passed at 728px and 360px, without missing images or horizontal overflow.

The compatible hub preserves Skills/Workspace/PDF source at
`2b439f08460161447fea5f43fc75d48229d3e12f`; the temporary overlay was restored
clean. Other thread was completed with no active or queued run before device
work. No application data reset, new signer, environment repair, commit,
publication, login, real Reddit write or glasses installation occurred.
Phone left on Nexus MainActivity; task-created UI XML removed.

## Historical delegation brief (superseded)

The rest of this file records the original request and before-state. Its
remaining-work descriptions, old hashes and instructions to wait/delegate/build
are historical; the final delivery and QA above replace them. Do not rerun them.

## User authorization and model constraint

The user observed that "Reddit on glasses" still appears in Glasses apps and
clarified that ALL app-specific setup/tutorials should be under Patcher, for
Reddit as well as YouTube. They then explicitly instructed: resume at 15:50
Europe/Paris with Opus 5.5 from their Claude subscription, and STOP using the
API. Make no more Anthropic API calls, including Fable review calls. Do not
retrieve or reuse the previously supplied API key. Do not change provider
configuration. Do not silently substitute another implementation model.

The prior Opus API changes and Fable API approvals are completed historical
evidence; they do not verify the additional Reddit migration requested now.

The scheduler rejected the proposed fixed-time wakeup because it would be
recurring persistent work, exceeding one-time authorization. The list is
confirmed empty: NO wakeup/monitor was created. Do not retry that recurring
schedule or use indirect scheduler workarounds. The parent instead remains
in this current turn until 15:50 using bounded clock waits (at most 60 seconds
each, interrupted by new input), a single in-turn continuation. Discover the
live catalog at delegation time and verify
`claudeAgent` / `claude-opus-5-5` is child-task capable. Delegate one async
implementation child with clientRequestId
`patcher-opus55-reddit-tutorial-migration-20261009-round5`. Read this entire
brief into the prompt; a bare unsupported path reference is insufficient.
Retain the taskId, end the parent turn, and let completion wake the parent.
No top-level thread, duplicate task, busy polling, or API fallback.

## Remaining concrete defects

The installed Patcher has native YouTube and Reddit app cards, but only
YouTube opens the hub-owned setup tutorial. Reddit still opens PatchActivity
directly, and its tutorial remains in Glasses apps. These are actual source
findings, not a stale screenshot:

- `phone-hub/.../NativeAppsActivity.kt`, render(): the "Reddit on glasses"
  card with "Set up Reddit" starts RedditSetupActivity. Remove this setup
  card/action while preserving normal inventory/open/install controls and
  loading/empty/error states. A short passive hint to Patcher is acceptable.
- `shared/.../PatcherContract.kt`: `SETUP_TARGETS = setOf(TARGET_YOUTUBE)`.
  Extend the fixed allowlist to Reddit, never arbitrary target IDs.
- `phone-hub/.../PatcherSetupEntryActivity.kt`: authenticated OPEN always
  starts YoutubeSetupActivity. Route the validated exact target to the
  matching non-exported setup activity, with YouTube behavior preserved.
- `phone-hub/.../PatcherHandoff.kt`: setupEntry already validates action,
  Android callingPackage, fixed allowlist and approved installed Patcher
  identity. Preserve these checks and fail-closed behavior. Approvals and
  installs remain the hub's responsibility.
- `plugins/patcher/.../PatcherHomeActivity.kt`: Reddit's "Prepare Reddit"
  card currently takes the direct patch route. Use the existing authenticated
  hub setup handoff for Reddit, with a clear setup label and appropriate
  fallback for a missing/old hub. Keep active-job locks, running-job reopen,
  retained result/source, signing key and maintenance behavior unchanged.
  Opening the Reddit tutorial must NOT select/reset the patch job's target or
  clear a held YouTube source/result. Only the user's later explicit patch
  action may enter the existing guarded target-switch workflow. Verify this
  non-destructive tutorial entry in a focused test and parent device QA.
- `plugins/patcher/.../AppTargets.kt` already builds target-aware hub setup
  intents and checks the allowlist. Review whether anything else is needed.
- `phone-hub/.../RedditSetupActivity.kt`: Back currently says "Glasses apps".
  It must return to its Patcher origin and use the existing native Nexus
  header/design conventions. Keep all three Reddit steps (official complete
  APKM, patch/install, sign-in/replies), safe keyboard toggle/secure remote,
  source pins and explicit installation intact. Reddit needs NO MicroG.
  Use the existing green `ic_app_reddit` drawable, matching the real
  YouTube/Patcher header treatment rather than inventing another logo.
  Avoid a broad redesign or copying the whole YouTube implementation.

Relevant tests currently encode the old behavior and must be updated:
PatcherContractTest asserts a YouTube-only allowlist;
PatcherSetupEntryActivityTest explicitly rejects Reddit;
PatcherAppFlowTest expects Reddit's direct patch screen with no hub handoff.
Add meaningful checks for both targets, unknown/wrong callers, Back/origin,
and no app-specific setup cards in Glasses apps. Preserve existing security
tests. Code, tests and docs are English. Read root/plugin AGENTS, use
rokid-glasses-dev and frontend-design, and follow the real NexusUi kit.

## Child scope and validation

Scope edits to the native Patcher setup routing/UI files above, related
focused tests and relevant Patcher/tutorial documentation. Do not edit the
approved HTML mockups, unrelated core/Skills/Assistant code, Reddit glasses
patches/APKs, source pins, signing keys or build environment. Do not commit,
push, publish, release, log in or send real Reddit writes.

Run affected normal Gradle tests/debug builds and report their ACTUAL tails:
phone hub without skipCxrGlobal; plugins with skipCxrGlobal; shared tests for
the public handoff allowlist. Do not modify local.properties, SDK, Gradle
homes/cache or repair environmental failures. Child may build debug/tests
but must not install or control the shared phone/glasses. Parent owns the
compatible signed build and device QA after inspecting delivery.

Return actual changed files, behavior, observed tests and limitations, plus
an English delivery note at
`docs/mockups/patcher-multi-app/OPUS55_REDDIT_MIGRATION.md`.

## Installed baseline and successful QA already observed

Current release is installed with the existing signing identity and data
retained. Patcher and hub were pulled back from the phone and hash/cert
verified against frozen artifacts. Actual phone Android SDK is 36.

Final baseline delivery:
`build/outputs/patcher-validated-release-20261009-back/`.

- Patcher SHA-256:
  `bfcf0ac4fd28eeb3cb4d781890a725614f2eb9cf937087df388628bc9a249cf2`
- Hub SHA-256:
  `cf0befad6b26755a1e3a7da20f2d6b73f8834a87353e4e3fdf096ee19ebb1d37`
- Existing signer SHA-256:
  `f5e938e2e79b0526b31e40d36c8c19098450c1636b7e14a306681b4effddf81c`

Compatible tests: 763 phone hub + 388 shared + 149 bus client = 1,300,
zero failures/errors/skips. Root normal hub tests: 736 pass. Six native
measured text-wrap renders pass. These are baseline results; rerun affected
checks after the new migration and do not claim old results cover it.

Real phone checks at `build/outputs/patcher-device-qa-20261009/`:
21 tutorial checks, 10 keyboard opt-in/persistence/restoration checks,
9 header/inventory checks. Actual Android Back initially skipped overview
because targetSDK36 ignores legacy onBackPressed. Paid Opus added the
API33+ callback/cleanup and shared navigateBack; Fable approved it, and the
modern path is now actually verified on Android36. Preserve that fix.

The previous inventory test's "Reddit setup card stays singular" was an old
expectation and is SUPERSEDED by the user's requirement to remove it entirely.
Patcher's direct Reddit target switch showed an honest destructive switch
guard and was canceled with Keep YouTube to retain the user's prepared
YouTube source/result. Do not approve a target switch merely for QA.

Screenshots are ACTUAL phone captures: patcher-home.png, youtube-overview.png,
youtube-microg.png, youtube-microg-more.png, youtube-source.png,
youtube-patch.png, youtube-advanced.png, reddit-switch-guard.png,
glasses-apps.png. The final one is now a BEFORE image; parent inspected it
and found ordinary app inventory, no credentials/account/device identifiers.
Further inspection at 15:41 found FIVE unsuitable black captures:
youtube-microg.png, youtube-microg-more.png, youtube-source.png,
youtube-patch.png and youtube-signin.png. The source frame was visually
inspected; all five have zero sampled RGB channels in an independent image
read. EXCLUDE these unless replaced by fresh inspected non-protected captures.
Safe non-black baseline frames: home, overview, Advanced, switch guard and
Glasses apps. Preserve secure remote restrictions; never disable FLAG_SECURE
for a shot. Recapture normal tutorial routes after the new compatible update
from a fresh main navigation task; if still protected, report that limitation.
No account login, real Reddit content, source/result replacement, voting,
save, key import/export, repatching or glasses installation was needed.

## Preserve the completed Skills/PDF build and shared device ownership

The installed phone hub includes completed Skills/Workspace/PDF changes from
`E:/Tools/Rokid/RokidNexus-workspace-pdf`, branch dev/workspace-pdf, commit
`7e412847a61de624076d26fd416bdf4cb63291bc`. A raw root hub would lose them.

Before ANY phone/glasses inputs or install, read durable status of thread
`847cd5a6-e515-40c8-8fed-ecc634ad020c` and inspect active/pending runs. Last
observed: failed, activeRunId null, pendingRequestCount 0; completed child
review has an unrelated PDF deadline blocker. Do not fix/merge that work.
The thread's subscription also resumes after 15:40, so recheck at device QA.
If running/preparing/queued/waiting, no shared device activity; report or
defer without a polling loop or recurring failure monitor.

Existing credential-free helpers in `build/tmp/patcher-fable-review/`:

- prepare_overlay.py + resolve_overlay.py: audit a reversible compatible
  source overlay while preserving Skills code. Resolve helper is currently
  hard-coded for the OLD two identical Reddit cards; update this narrow
  compatibility rule for the NEW removal and inspect the merged source.
  Never reintroduce the old card from the PDF checkout during a merge.
- compatible_build.py: applies hash-checked overlay temporarily to the
  existing clean PDF checkout, tests, uses existing release signer, freezes
  artifacts, then restores ONLY its own unchanged overlay bytes. It currently
  gates on the historical Fable API report; do not falsely label the new
  migration API-approved. User has stopped API usage. Document the actual
  subscription delivery and parent review separately.
- device_qa.py: selects the single phone by dimensions, hides serials,
  safe label taps/Back/screenshots, install-r and pulled APK verification.
  Delivery paths currently point to older artifacts; use a new output folder
  and preserve baseline evidence. Do NOT rerun preflight to overwrite the
  original before-APK backups. Do not approve Replace/Switch dialogs.
- smoke_tutorial.py, smoke_keyboard.py, smoke_header_inventory.py contain
  reusable existing checks; update old Reddit-card expectations and preserve
  original preference values.

Some Windows exec_command calls need require_escalated because the default
helper reports setup-refresh errors. Authorized previous build/install calls
were approved; no auto-review rejection is pending. Escalate narrowly as
needed, never change machine setup. No scheduler wakeup or monitor exists.
No active API process or secret store is present.

## Finish the parent task after delivery

Inspect actual diff/security/Back/retained state, build compatible hub AND
Patcher with the same release signer, install-r only after device ownership
check, verify installed APK bytes/cert and preserved preferences/grants.
Check Patcher -> YouTube and Reddit tutorials; correct header/system Back;
Glasses apps contains inventory and no YouTube/Reddit setup tutorial entry;
Reddit keyboard toggle persists and its original value is restored. Cancel
any patch/source replacement. Capture privacy-safe actual updated screens.
Remove only the task-created `/sdcard/patcher-qa-ui.xml` after QA.

Update DEVICE_QA_RESULTS.md and API_REVIEW_RESUME.md honestly with current
results/limitations and actual build tails. Inspect captures. Make an inline
gallery using actual local screenshot files, run T3 html_preview at 728px
and 360px, then html_render before the final reply. No fabricated mockup
passed off as device evidence. Prior black/secure capture must be excluded.
