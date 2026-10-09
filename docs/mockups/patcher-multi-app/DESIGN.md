# Patcher phone UI — YouTube and Reddit (round 2)

Design notes for `index.html`, an interactive mockup of the Patcher plugin's phone
interface with two app targets and, new in this round, the complete YouTube setup
living inside **Patcher → YouTube**. The mockup is a simulation: it reads no files,
generates no keys, installs nothing, calls no API and makes no network request.

Round 2 applies two corrections from the owner:

1. Every YouTube setup and tutorial screen moves out of the Nexus hub's **Glasses
   apps** screen into Patcher → YouTube. Glasses apps keeps listing and opening the
   installed YouTube like any native app, but has no "Set up YouTube" card.
2. YouTube and Reddit are app targets, not Nexus plugins, so their cards carry the
   real brand icon geometry from official assets, adapted to Nexus green.

The two-app structure, the Phosphor × Mono phone surface, Reddit's preview flow, the
job engine and the maintenance screen from round 1 are kept.

## Proposed UI ownership vs. unchanged hub authority

This is the one place the migration is explained; the phone screens do not repeat it.

**What the mockup proposes to move into Patcher's UI**: the four-step YouTube setup
(MicroG on the glasses, official download, patch and install, sign in and open), the
install-on-glasses screen with its connected / transferring / checking / confirmed
states, the phone-keyboard auto-open switch, and the Advanced routes (Morphe Manager
alternative, already-patched APK import, refresh, reinstall).

**What does not change**: the trusted phone hub still performs every privileged
operation behind those screens. Downloading and validating the MicroG release,
copying an APK to the glasses, installing it, reading the glasses inventory, opening
an app on the glasses, and the Keyboard & remote are hub-to-hub controls on the
`/core/native-apps/*`, `/core/remote-input/*` and related routes. The mockup gives
Patcher no installer capability, no keyboard capability and no bus route. Patcher
remains a phone-only plugin with empty capabilities; the hub keeps launching it by
explicit intent and reading the patched APK back through a `content://` URI, and the
hub keeps re-validating that APK before any transfer.

How the proposed screens could be realised without changing that boundary is an
implementation question, listed at the end: the screens may be hub-owned activities
that Patcher opens by explicit intent, or hub fragments embedded under Patcher's
navigation, or Patcher views that ask the hub through the existing activity-result
hand-off. The mockup only fixes what the user sees and where Back goes.

## Established vs. proposed

Taken from public repository documents (README, `docs/YOUTUBE_GLASSES.md`,
`plugins/patcher/README.md`, `shared/.../PatcherContract.kt`) and kept as-is:

- The user downloads the official YouTube themselves (APKMirror in an external
  browser); Nexus never inspects browser downloads and never marks the step done
  because a link was opened. 32-bit-only builds are refused.
- MicroG is downloaded, validated and installed by Nexus in one action from the
  official Morphe MicroG-RE release with a launcher icon. A `noicon` build shows
  "Needs attention". The card is Done only when the glasses report it installed.
  Under More: Open MicroG, Check for MicroG updates, which can turn the primary button
  into Update MicroG.
- Nexus re-checks the patched APK (version, HUD marker, package, manifest, hash,
  glasses API, installed signer) before transfer and confirms version and signer from
  fresh inventory afterwards. Package checks cannot prove which patches went in.
- Signer mismatch stops; Nexus never uninstalls to bypass it; a portable encrypted
  key backup can be imported.
- Sign-in happens in MicroG on the glasses with the phone keyboard; the sign-in
  checklist item is a manual user choice that survives reopening and can be reset
  with "Mark sign-in to do".
- Reddit: official 2026.14.0, keeps `com.reddit.frontpage`, no MicroG, preview is
  unpublished. The phone-keyboard opt-in is off by default and belongs to Nexus.
- Patching runs in the background; locking the phone slows it down.

Proposed by this mockup:

- The four-step overview with per-step status lines, a "Patch now / Patch an update
  / Install the patched APK" shortcut in the footer for experienced users, the
  keyboard row and the collapsed Advanced section.
- The copy of every state, the stage and phase names (Load / Patch / Build / Sign /
  Save for patching; Downloading / Validating / Transferring / Installing / Checking
  glasses for MicroG; Validating APK / Transferring / Installing / Checking glasses
  for the install), and which phases show a known fraction.
- Putting the install-on-glasses step and the keyboard switch under Patcher →
  YouTube. Reddit's install still hands off to Nexus as in round 1 (see questions).
- The Advanced routes and the already-patched import validation (official download
  rejected, missing Rokid marker rejected, other-signer accepted with a warning).
- The Glasses apps review screen, which stands in for the hub screen and is only
  there to show the removal of the setup card.

Version numbers, file names, sizes, hashes, dates, MicroG versions, inventory results
and the "1.7.0" plugin version are labelled "example" and are not release claims.
Patch rows are illustrative, not a verified inventory.

## App marks and provenance

Both marks are inlined once as `<symbol>` elements and referenced with `<use>`. No
runtime request is made.

- **YouTube**: the `video-youtube` symbol exactly as served by
  https://brand.youtube/ (viewBox 0 0 24 24), one path. The mockup fills it with
  `currentColor` and sets `fill-rule="evenodd"` so the play triangle stays a
  negative-space cutout. Geometry unchanged.
- **Reddit**: from `Reddit_Lockup_Logo.svg` linked by https://redditinc.com/brand
  (https://redditinc.com/hubfs/Reddit%20Inc/Content/Brand%20Page/Reddit_Lockup_Logo.svg).
  The mockup keeps the speech-bubble path, the `Snoo` symbol with every path, circle
  and ellipse, the eight radial gradients with their centres, radii and transforms,
  and the `<use>` placement `translate(21.56 31.55) scale(.4)`; it omits the wordmark
  group and sets the viewBox to 0 0 216 216. Gradient ids are prefixed to avoid
  collisions in the host page.
- **Colour adaptation**: the bubble takes `currentColor` (phosphor green on the
  cards). The Snoo head, ears, body and antenna map their white gradients to deep
  green shades, the eye sockets to the darkest green, the eyes to a bright green
  gradient, the eye highlights to the light ink tone, the mouth rim to phosphor and
  the mouth interior to deep green. Shading survives; nothing is flattened to one
  blob. These green renditions are this mockup's adaptation for the phone surface,
  not an official colourway and not brand-approved.

During this session the brand sites were unreachable from the sandbox (proxy policy
denied `brand.youtube` and `redditinc.com`); the owner fetched the two assets from
those official pages and supplied them verbatim. An earlier fallback (an older
Google-shipped YouTube icon from Chromium's `default_apps/youtube.crx` and a Snoo
drawing from Reddit's archived codebase) was dropped in favour of the current
official geometry.

## Navigation

Routes are a stack. Back (header chevron, Escape in the mockup, the system Back
gesture on Android) pops one route; an open sheet closes first. Back from any YouTube
step returns to the overview, and every step keeps its own state: chosen file,
toggles, drafts in the key sheets, the running job and the keyboard switch survive
leaving and reopening.

```
home ─┬─ yt (overview) ─┬─ yt-microg ──────────── ext: MicroG source · Open MicroG
      │                 ├─ yt-source ──────────── ext: APKMirror
      │                 ├─ prepare(youtube) ── running ── result ── yt-install
      │                 ├─ yt-install ─────────── (connected / transfer / check / done / failed / mismatch)
      │                 ├─ yt-signin ──────────── ext: Open MicroG · Keyboard & remote · Open YouTube
      │                 ├─ yt-keyboard
      │                 └─ Advanced: yt-advanced (Morphe) · yt-import · refresh · reinstall · how patches work
      ├─ prepare(reddit) ── running ── result ── handoff (Nexus stand-in, unchanged)
      └─ maintenance ── sheets: backup · import · mismatch help · uninstall
glasses (Nexus · Glasses apps review route) ── app details sheet → pointer to Patcher → YouTube
```

**Entry contexts.** "Patcher settings" opens the two-app home. "Nexus request" with
YouTube locked makes the YouTube overview the root, with the band "Requested by
Nexus for YouTube · App choice is fixed until you return to Nexus"; Back at the root
goes to the Nexus stand-in, and reopening restores the locked root with any running
job or result. With Reddit locked the root is Reddit's Prepare screen, as in round 1.

**External stand-ins.** Buttons that would open the browser, MicroG, YouTube, Morphe
Manager or the Nexus remote open a dashed "stand-in" screen that says what would
happen outside Patcher and returns to the tutorial. Opening APKMirror flips the
step-2 panel to "Download page opened" but never to Done.

## The YouTube setup, step by step

**Overview.** Four numbered rows with a status line each (To do, Done, Needs
attention, Update available, Failed, Offline, Patching…), the keyboard row with its
on/off value, and a collapsed Advanced section. The footer offers the one shortcut
that fits the state: Patch now, Patch an update, Install the patched APK, or Open
running job. Beginners go top to bottom; an experienced user with a kept file taps
the footer.

**1 · MicroG on the glasses.** States: missing (Install MicroG), installed (Done, with
the reporting inventory and Open MicroG / Check for updates), installed but not
launchable (Needs attention, Install icon-enabled release), update available (Update
MicroG), failed (Retry, Check glasses), glasses offline (Check again). The install
runs Downloading (known fraction) → Validating → Transferring (known fraction) →
Installing → Checking glasses; the status stays "missing" until the last phase
reports. More: Check for MicroG updates, Source (MorpheApp/MicroG-RE, external),
Refresh glasses apps. The hint says only the inventory marks the step done.

**2 · Official YouTube.** A: Open APKMirror for the example version in the external
browser, with copy that Patcher does not download YouTube. B: Choose APK or bundle,
validated as in round 1 (other app, wrong version, incomplete arm64, not an app) plus
a new rejection for already-patched files that points to Advanced. Continue to
patches is enabled only by a valid file.

**Job lock.** While a patch job runs, its target and input are immutable until it
completes or is cancelled. On the phone this shows as disabled controls with a
"Locked while … is being patched" hint on step 2's two pickers, the Advanced
already-patched picker, the patch toggles and any wrong-app "Prepare the other app"
switch; Back, reopening the job from the band, the cancel confirmation and read-only
tutorial navigation stay available. A guard at the action boundary ignores the same
mutations even when a picker sheet was opened before the job started or a tap
arrives late: the chosen file, toggles and result are left untouched and a toast
says why. A second Start while a job runs is ignored the same way. The review
strip's scenario controls are external simulation tools and may deliberately reset
scenarios; ordinary phone UI is locked.

**3 · Patch and install.** The Prepare screen is round 1's patch review, retitled
"3 · Patch YouTube", with a note on whether MicroG is confirmed on the glasses. The
running screen, cancel confirmation, Back retention, reopen and the failed /
cancelled / interrupted recoveries are unchanged. The success screen's primary action
is now **Install on the glasses** (or Update on the glasses when a confirmed install
exists), with Save APK, Share APK and Patch again kept. The install screen shows:
connected or offline, the ready APK with its signer, phases Validating APK →
Transferring → Installing → Checking glasses, then confirmed (the glasses report the
package, version and matching signer) or failed (retry) or stopped on signer mismatch
with the two recoveries (import the key backup, or uninstall on the glasses yourself
and lose its data), never an automatic uninstall. A three-line checklist under the
panel separates "APK on the phone", "transferred" and "glasses report the new version
and signer · the only confirmation that counts".

**4 · Sign in and open.** A guide: Open MicroG on glasses → Add account there → type
with Keyboard & remote (password fields sensitive, phone screen secure, never
mirrored) → finish Google's verification on the glasses → Open YouTube and check the
account, controls and Morphe / SponsorBlock settings. The footer toggles "Done
(marked by you)" / "Mark sign-in to do". The copy states that Patcher and Nexus never
see the account or whether the sign-in worked, and that this checklist is separate
from the install confirmation. The overview shows both statuses side by side.

**Phone keyboard.** One switch, Auto-open YouTube keyboard, off by default and
persisted across navigation. The explanation changes with the state: when on, the
keyboard screen comes forward for YouTube text fields and leaves when the field is
done; when off, open Keyboard & remote yourself. MicroG sign-in always uses the
manual secure remote. The "blocked by Android" scenario shows the notification
fallback band ("Keyboard ready for YouTube · Android blocked opening it
automatically · tap to open"). Technical details say the owner is Nexus Keyboard &
remote.

**Advanced.** Patch with Morphe Manager instead: open the Rokid patch source
(Anezium/morphe-patches, external), open Morphe Manager (stand-in), then import the
result. Already patched YouTube APK: a separate picker and validation that rejects
the official download (go to step 2), rejects a patched APK without the Rokid marker,
accepts a Patcher-signed APK, and accepts an other-signer APK with the warning that
it can only update an app already using that key. Refresh glasses apps; Reinstall or
update from the ready APK; How the Rokid patches work (plain-words sheet). The signer
protections apply on both routes.

## Reddit

Unchanged from round 1: equal card on the home screen with the Preview badge, the
official-file picker and validation, the three proposed patch rows (Rokid Reddit
controls, Signature compatibility, Hide ads), no MicroG mention beyond "does not use
MicroG", running job, success with Open Nexus / Return to Nexus hand-off, failure,
cancel, interruption, and the locked-target context. Reddit has no setup object in
the state; YouTube's setup, keyboard and install states never appear on Reddit
screens, and the check script asserts that Reddit flows leave YouTube's state
byte-for-byte unchanged.

## Glasses apps (review route)

A Nexus-styled screen reachable only from the review controls and from the Reddit
hand-off stand-in. It lists installed apps with Open (YouTube and MicroG when the
simulated inventory has them, plus two example native apps), Refresh and Keyboard &
remote. It has no setup card and no "Set up YouTube" shortcut. YouTube's details
sheet is ordinary app details (package, version, signer, Open on glasses) plus one
informational sentence, "Setup and updates live in Patcher → YouTube", with no
button behind it. Every actionable setup or tutorial entry is inside Patcher →
YouTube; the review strip's "YouTube setup" route is the reviewer's shortcut, not a
phone control.

## Visual hierarchy

Phosphor × Mono as in `design-mockups/nexus-phone-v2.html`: near-black green
layers, thin hairlines, one bright phosphor accent, system sans for names and body,
monospace for labels and meta. Amber marks Preview, warnings, offline and
interruption; red marks rejection, failure, mismatch and Cancel. One filled button
per screen is the next action. Package ids, hashes and the signer fingerprint stay in
collapsed Technical details. Nexus-owned stand-ins (Glasses apps, hand-off, external
screens) use a slightly tinted header so the user can tell when a screen is not
Patcher's.

## Mapping to Android and Nexus components (proposed)

| Mockup element | Android / Nexus component |
|---|---|
| Overview and step screens | `PatchActivity` with the Nexus UI kit; one route stack, or one fragment per step |
| Nexus request band | `ACTION_PATCH` + `EXTRA_TARGET_ID` locks the target; plain launch does not |
| MicroG card, install flow | Hub's existing MicroG download/validate/install path and inventory read; surfaced through a hub-owned screen or an activity-result call, never a plugin capability |
| APKMirror button | `ACTION_VIEW` on the download page from the hub's version table |
| File picker and validation | `ACTION_OPEN_DOCUMENT` plus the plugin's stock-signer and split checks |
| Patch review, job, result | Existing `PatchJobService`, FileProvider result URI |
| Install on glasses | Hub import path (copy, hash, validate, transfer, install, inventory recheck); `EXTRA_RETURN_TO_HUB` style hand-off in the locked context |
| Sign-in checklist | Hub's persisted manual checklist choice |
| Open MicroG / Open YouTube / Keyboard & remote | Hub `/core/native-apps/*` open and `/core/remote-input/*`; trusted hub-to-hub only |
| Keyboard auto-open switch | Hub's existing YouTube keyboard opt-in setting, default off; notification fallback when the automatic launch is blocked |
| Already patched import | Hub import path with the same validation as a Patcher result |
| Signing-key backup / import | Existing portable key export and import; passphrase fields mark the window secure |
| Glasses apps | Existing hub screen minus the setup card |

## Observed verification

A Playwright script drove the file in the pre-installed headless Chromium; nothing
was installed. All 96 checks passed on the final run. Observed:

- Home shows both apps with Set up YouTube and Prepare Reddit, the Preview badge, and
  no package ids or hashes; both cards reference the official symbols; the YouTube
  path starts with the brand.youtube coordinates and uses evenodd; the Reddit symbol
  keeps the `<use>` transform and the 216 viewBox.
- YouTube overview lists the four steps, the keyboard row (off by default) and
  Advanced. Keyboard toggled on persists across Back and reopen; the Android-blocked
  scenario shows the notification fallback.
- MicroG: missing, installing (phases, status still "missing" during transfer), Done
  only after the inventory phase with Open MicroG and Check for updates, not
  launchable, update available, offline, and transfer failure with Retry; the source
  button opens the external stand-in and Escape returns to the step.
- Source: APKMirror stand-in says it is not a step done; after returning, the panel
  reads "Download page opened" with no file and no Done; already-patched, incomplete,
  wrong-version and other-app files are rejected; the valid bundle enables Continue;
  the overview then shows step 2 Done and offers Patch now.
- Patch: toggles, confirm sheet with the sign-in-off warning, running screen with
  known-fraction progress and no ETA, Back retains the job and the overview shows the
  running band, reopen, cancel, retry to success; the success screen's primary is
  Install on the glasses, with Save, Share and Patch again; Save shows its toast.
- Install: ready panel, disconnected panel when the glasses switch is off, phases in
  order, status not done after the transfer phase, confirmed only after Checking
  glasses; transfer failure with Retry install; signer mismatch stop naming the two
  recoveries and leading to the maintenance import.
- Sign-in: guide with the three stand-ins (MicroG, remote with the secure-password
  note, YouTube), Done (marked by you) / Mark sign-in to do; the overview shows that
  status next to the inventory-confirmed install status.
- Advanced: Morphe route with the patch source, manager stand-in and import link;
  import rejects the official download and the APK without the Rokid marker, accepts
  the other-signer APK with its warning and the Patcher-signed APK; step 2's file
  state stays untouched; the install screen takes the imported APK.
- Glasses apps: fresh state lists only native apps and says the setup lives in
  Patcher; installed state lists YouTube and MicroG with Open; neither has a setup
  card; YouTube's details carry only the informational sentence and no setup action
  (`go-yt-setup` no longer exists in the document).
- Reddit: Prepare with Preview, no MicroG, three rows; build failure, interrupted,
  Open Nexus hand-off that does not claim an install. YouTube's whole state is
  identical before and after the Reddit flows; Reddit has no setup object.
- Nexus locked context: YouTube root is the overview with the band and a one-entry
  stack, Back goes to the hand-off, reopen keeps the locked root, no app picker;
  Reddit root is Reddit's Prepare.
- Maintenance: key explanation, backup passphrase errors with typed input kept,
  export toast, signer-mismatch state, wrong-passphrase import error, import clears
  the mismatch, uninstall warns about the key and stops at the simulated dialog.
- `:focus-visible` outline on phone controls; no horizontal overflow at 360 px on
  home, the overview and the mismatch screen; reduced motion disables the
  indeterminate animation; no page errors, console errors or non-file requests.

Round 2b regression, same harness, after the parent's review found that Back from
a running job landed on step 2 with the pickers still enabled. Observed, all 25
checks passing on the final run:

- Valid source → Continue → confirm → Start → Back lands on step 2 with the job
  still running; both source pickers are disabled with the lock hint; the APKMirror
  button (read-only) stays available.
- A picker sheet forced open through the review hook while the job runs: choosing a
  file is ignored, the chosen file is preserved, the lock toast appears and the stale
  sheet is dismissed. Same for the Advanced already-patched picker (disabled, hint,
  late pick ignored) and for a synthetic late toggle tap (toggles unchanged).
- A wrong-app "Prepare YouTube" switch on Reddit's screen while YouTube runs is
  disabled, and forcing it enabled still leaves both apps' files unchanged.
- The overview with the running band, a read-only step, reopen from the band and
  the cancel confirmation stay reachable; after cancel the job is cleared, the file is
  kept, step 2 is editable again and the source can be changed; a second Start while
  a job runs is ignored with a toast.
- Glasses apps has no setup card; YouTube's details show Open and the informational
  sentence, no `go-yt-setup` anywhere in the document; no horizontal overflow at
  360 px with the sheet open; no page errors, console errors or non-file requests.
- The full round-2 suite was rerun after the change: 96 of 96, with the Glasses apps
  step updated to assert the absence of the setup action.

Screenshots at 728 px and 360 px were inspected by eye for the marks, hierarchy,
badge placement, footer fit and the install, sign-in, keyboard and Glasses apps
screens.

## Limitations and open questions

- HTML checks validate nothing about Android, the hub's installer path, FileProvider
  hand-off, the inventory protocol or physical glasses input. No install on glasses
  is proven by this mockup.
- Where the migrated screens live in code is undecided: hub-owned activities opened
  by Patcher, hub fragments under Patcher's navigation, or Patcher views asking the
  hub through activity results. All three keep the capability boundary; they differ in
  Back behaviour and in how a running install survives Patcher being killed.
- Reddit's install still hands off to the hub's Reddit setup screen. Whether it should
  also move into Patcher → Reddit for symmetry is for the owner to decide.
- The sign-in checklist and the keyboard switch are hub-persisted settings shown in
  Patcher; the mockup does not say how Patcher reads them without a new surface.
- Patch rows, phase names, durations, counts, messages and example versions are
  illustrative; the real bundle metadata, hub milestones and MicroG release data must
  replace them.
- Only one patch job runs at a time in the mockup; a MicroG install and a patch job
  can overlap, which may or may not be desirable.
- Fonts are system stacks; the product's Sora / Chivo Mono pairing is not embedded.
  The outer review strip's T3 variable names are a guess with verified fallbacks.
