# Parent review of subscription Opus Reddit migration

Parent review: **ACCEPT** for local compatible build and the already authorized
non-destructive device QA. Reviewed 2026-10-09 by GPT-6.1 Sol.

The user requested complete Reddit setup under Patcher and explicitly stopped
all API usage. Implementation was delivered by subscription provider/model
claudeAgent / claude-opus-5-5; task completed with no pending child runs.
This migration has NOT received a new Fable/API review. Historical Fable
approvals cover the earlier native UI/text and YouTube Back changes only.

Observed source checks:

- Both fixed target IDs use the existing authenticated result-mode setup
  entry. Action, Android caller package and approved installed Patcher identity
  remain validated; unknown IDs and untrusted callers are refused. Both actual
  tutorial activities remain non-exported and there is no new bus capability.
- Manifest metadata advertises supported setup targets only for compatibility
  routing, clipped to the same fixed allowlist. A legacy hub falls back to
  local Reddit patching with an explicit update explanation.
- Tutorial entry does not select a new patch target or clear a held source,
  result or signing key. Existing active-job locks and explicit patch/install
  actions are unchanged. Root Patcher test covers retained YouTube state/file.
- Glasses apps loses both setup cards; inventory/open/install and state handling
  stay. Native Reddit uses existing NexusUi/header/green drawable, all three
  steps, existing secure remote route and the unchanged preference key.
- Reddit's API33+ Back callback is registered/cleaned up and shares finish with
  the header/legacy path. Actual phone Back subsequently passed QA. YouTube
  activity SHA-256 is unchanged from the previously verified Back build:
  8ca0e739766308440b9179c3eedad367a5421ab2f778ecc0c07725ca2b53cd21.

Child observed normal root tests: hub 743, shared 366; Patcher 158 run with
12 pre-existing skips, 0 failures/errors. Root debug APKs lack completed Skills
and must not be installed. Parent inspected actual source and test delivery.

Latest completed Skills/PDF checkout is clean at
2b439f08460161447fea5f43fc75d48229d3e12f. Other thread completed its review/merge,
activeRunId null and pendingRequestCount zero. Its final report was read.
The compatible build preserves these latest PDF fixes and all Skills source.

Four expected overlay conflicts were inspected as diffs. Manifest and contract
only add the explicit setup declaration/allowlist and retain all prior content.
NativeAppsActivity differs only by removing two setup cards and adding the
passive Patcher hint. RedditSetupActivity differs only by Patcher header/Back
and its factory; install/identity/keyboard logic stays byte-for-byte in the
reviewed diff. Resolver pins audited before/incoming hashes for the two UI
files, rejects unexpected changes and removes no unrelated code. Reversible
build restores only its own unchanged overlay, without commit/merge/reset.

No API key retrieval/call, new signer, local.properties/SDK/Gradle repair,
device input, real Reddit content or publication was performed by this review.
Final compatible release and installation completed. `DEVICE_QA_RESULTS.md`
records actual tails, 1,454 passed tests plus 12 existing skips, both verified
installed APK hashes/certificates, and 72 passing actual phone checks. All ten
fresh selected screenshots were inspected and T3 gallery previews passed at
728px and 360px. No new API review was performed.
