# Patcher device QA completed, 2026-10-09

## Final accepted local delivery

Subscription Opus 5.5 completed the Reddit tutorial migration. Parent inspected
the actual implementation, built compatible signed releases and installed both
Patcher and the phone hub with `install -r`. YouTube and Reddit setup now live
under Patcher; Glasses apps has inventory and a passive Patcher hint, with neither
app-specific tutorial card. Both tutorials use the existing Nexus UI and green
app logos. Opening Reddit setup preserves the prepared YouTube source/result.

Provider/model: `claudeAgent` / `claude-opus-5-5`. Completed child delivery:
`OPUS55_REDDIT_MIGRATION.md`; parent source review:
`PARENT_REDDIT_MIGRATION_REVIEW.md`. No Anthropic API calls or key retrieval
occurred after the user's stop instruction. No new Fable/API approval covers
this migration; historical approvals below apply only to their earlier rounds.

## Final observed builds and tests

Compatible source preserves completed Skills/Workspace/PDF work at
`2b439f08460161447fea5f43fc75d48229d3e12f`. The other device-owning thread was
completed with no active or queued run before installation. A hash-checked
16-file overlay was applied temporarily, then its own changes were restored;
the compatible checkout was clean afterward. No commit, reset, merge, signing
identity replacement or build environment repair was performed.

- Compatible hub: 770 tests, shared: 389, bus client: 149; all passed.
- Patcher: 158 tests, 146 passed and 12 existing assumption skips; no failures
  or errors. The parent independently checked its actual XML results.
- Combined final suites: 1,454 passed, 12 skipped, zero failures/errors.
  Child root hub/shared counts are separate evidence and are not added again.

Actual compatible test tail:

```
BUILD SUCCESSFUL in 40s
125 actionable tasks: 27 executed, 5 from cache, 93 up-to-date
```

Actual Patcher release tail:

```
BUILD SUCCESSFUL in 25s
123 actionable tasks: 25 executed, 98 up-to-date
```

Actual compatible hub release tail:

```
BUILD SUCCESSFUL in 44s
180 actionable tasks: 42 executed, 1 from cache, 137 up-to-date
```

Logs and signed artifacts:
`build/outputs/patcher-validated-release-20261009-reddit-migration/`.

## Final installed artifact verification

Both installed APKs were pulled back and matched the built bytes and existing
certificate. Existing application data was retained.

| Artifact | Verified installed SHA-256 |
| --- | --- |
| Patcher | 3c5c3e3feb293775065b514952615a1a23cd45d0b0470f6fa797dd53ab96e27e |
| Phone hub | 29d4a1798560972042e146a33954bf25c5c4e82984e49e8cd6d710bdd4b204f1 |

Existing signer SHA-256:
`f5e938e2e79b0526b31e40d36c8c19098450c1636b7e14a306681b4effddf81c`.
Evidence: `build/outputs/patcher-device-qa-20261009-reddit-migration/installed-verified.json`.

## Final actual phone QA and screenshots

All 72 observed checks passed on the actual Android SDK36 phone:

- 32 Reddit migration checks: no setup cards in Glasses apps, authenticated
  non-destructive tutorial entry, three steps, correct Patcher header, retained
  YouTube result, header/system Back, keyboard preference persistence/restoration,
  secure remote opening and actual `FLAG_SECURE`.
- 21 YouTube tutorial checks: four steps, expand/collapse, installation guidance,
  document picker opening/canceling, secure remote and protection, Advanced,
  footer and Android Back.
- 10 YouTube keyboard/navigation checks: change, reopen persistence, original
  choice restoration, tutorial -> Patcher -> Nexus and originating row retention.
- 9 header/inventory checks: retained result/preference, header/system Back,
  normal inventory opening and absence of both setup cards.

Both original auto-open keyboard choices were true and were restored. No secure
remote field was read, typed into or captured. The first Reddit harness run
expected one Back to leave the secure remote and encountered the open keyboard.
The harness was corrected to allow keyboard dismissal while remaining in that
activity; the complete clean rerun passed, requiring one Back on that run.
This was a harness expectation correction, not a product code change.

Fresh privacy-safe actual captures, check JSON and inspected image hashes are
under `build/outputs/patcher-device-qa-20261009-reddit-migration/`. Ten selected
1080x2340 captures were visually inspected and independently checked for nonblack
pixels. The gallery uses these new files only, including a fresh ordinary sign-in
tutorial page; protected remote screens and old black baseline frames are excluded.

T3 native HTML previews passed at 728px and 360px: all ten local images loaded,
all selector choices worked, no horizontal overflow or console errors. Observed
content heights were 915px and 934px respectively. The gallery contains actual
phone screenshots, not mocked or regenerated app pixels.
The finished gallery was rendered inline in this thread; preview console
results and the render attachment are recorded in `gallery-validation.json`.

The task-created `/sdcard/patcher-qa-ui.xml` was removed. The phone was left on
ordinary Nexus MainActivity. No monitor, pending child run or API process remains.

## Scope and limitations

This verifies native Patcher/tutorial navigation and the paired local update.
It does not freshly validate Reddit native APIs, account login, live writes,
media playback, physical ring input or AR optics. No real Reddit sends, posts,
comments, votes/save, account login, key import/export, source replacement,
repatching, glasses app install or publication was performed. Skills/Workspace
source was preserved in the hub build; this QA did not rerun every Skills flow
or update the separate Assistant plugin.

## Historical pre-migration baseline (superseded)

The following records the earlier installed build only. Its pending-work text,
hashes and Reddit-card expectation do not describe the final delivery above.

This is the completed pre-migration QA baseline. Final acceptance is pending
the user's additional request to move Reddit setup entirely into Patcher.
The old Glasses apps Reddit card is still installed and must be removed.
See REDDIT_MIGRATION_RESUME.md for the 15:50 subscription continuation.

## Observed build and model evidence

Paid Opus 5.5 supplied two text-wrap fixes and later the Android36 Back
callback fix. Parent inspected/applied exact edits. Fable 5.1 API explicitly
approved each completed round: FABLE_API_VALIDATION_ROUND4.md and
FABLE_API_VALIDATION_ROUND5_BACK.md. Lookup and response model IDs matched.
These approvals do not cover the newly requested Reddit migration. User has
now stopped all API use and requested subscription Opus at 15:50.

Six measured native render checks pass; normal root hub suite has 736 passing
tests. Compatible Skills/PDF build has 763 hub, 388 shared and 149 bus tests,
all passing with zero failures/errors/skips. The unchanged shared/bus suite
results were reused for the final hub-only Back correction; hub 763 was rerun.

Actual final hub test tail:

```
BUILD SUCCESSFUL in 35s
115 actionable tasks: 8 executed, 107 up-to-date
```

Actual final compatible hub release tail:

```
BUILD SUCCESSFUL in 22s
180 actionable tasks: 8 executed, 172 up-to-date
```

Actual unchanged Patcher release tail:

```
BUILD SUCCESSFUL in 30s
123 actionable tasks: 9 executed, 114 up-to-date
```

All logs are under build/outputs/patcher-validated-release-20261009-back
or the earlier patcher-validated-release-20261009 output folder.

## Installed APK verification and compatibility

The hub was built using a reversible 13-file overlay on the existing
Skills/PDF checkout at 7e412847. Completed Skills/Workspace/PDF code was kept;
the temporary overlay was restored and the checkout was clean afterward.
No commit, merge, signing-key replacement or build environment repair occurred.
Both apps were updated with install-r and existing data retained. Their
installed APKs were pulled and verified against the frozen final artifacts.

| Artifact | Verified installed SHA-256 |
| --- | --- |
| Patcher | bfcf0ac4fd28eeb3cb4d781890a725614f2eb9cf937087df388628bc9a249cf2 |
| Phone hub | cf0befad6b26755a1e3a7da20f2d6b73f8834a87353e4e3fdf096ee19ebb1d37 |

Both retain signer SHA-256
f5e938e2e79b0526b31e40d36c8c19098450c1636b7e14a306681b4effddf81c.
Evidence: build/outputs/patcher-device-qa-20261009/installed-verified.json.

## Actual phone checks

Phone SDK36; existing display density override was left unchanged.

- 21 tutorial checks: authenticated entry, four YouTube steps, expand/collapse,
  explicit installation UI, secure remote route and actual FLAG_SECURE bit,
  native document picker opening/canceling, Advanced, footer and Android Back.
- 10 keyboard/navigation checks: preference changes, reopen persistence,
  restoration of the original true value, tutorial -> Patcher -> Nexus,
  and retention of the originating Patcher row.
- 9 header/inventory checks: preference survives update, header Back and
  system Back, prepared result retained, Glasses apps opens, YouTube setup
  removed and passive hint points to Patcher. The ninth check expected one
  Reddit setup card; this expectation is now superseded and must become NONE.
- The Reddit source switch dialog was opened and canceled using Keep YouTube.
  The prepared YouTube result remained. Signing-key More/Less was exercised;
  key import/export was not activated.

JSON check records and ACTUAL PNGs are under
build/outputs/patcher-device-qa-20261009/. No secure remote field was read,
typed into or captured. Fresh parent image inspection at 15:41 identified five
black captures: youtube-microg.png, youtube-microg-more.png, youtube-source.png,
youtube-patch.png and youtube-signin.png. The source frame was visually checked;
all five have zero sampled RGB values. These MUST NOT be presented as useful
UI evidence. Safe non-black baseline frames are home, overview, Advanced,
switch guard and Glasses apps. New normal tutorial captures will be needed;
never disable secure-window protection to obtain them. glasses-apps.png is a
BEFORE capture; parent inspected it and found no account, credentials or
device identifiers.

## Limits and pending work

No real Reddit send/post/comment/vote/save, login, key replacement, target
source replacement, new patch engine run or glasses install was performed.
No new media/optical/physical ring tests are implied by this native phone QA.
The complete Reddit Patcher route was deliberately not entered through its
destructive source-switch guard. The pending migration will provide a
non-destructive tutorial entry from Patcher and needs fresh device coverage.

Legacy Back has API32 unit coverage; modern Back was verified on the actual
API36 phone. The old mixed activity task after update was normalized by a
standard MainActivity clear-top launch before testing real Nexus navigation.
No application data or global settings were reset.
