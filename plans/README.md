# Implementation Plans

Generated with the `improve` workflow on 2026-07-09.

Planning baseline: Git HEAD `da068ad`, plus the uncommitted Lens-related working-tree changes that were present during the audit. These plans must be executed from a clean, isolated worktree after those changes have either been committed, deliberately discarded by the owner, or reconciled into a new baseline. Do not run broad cleanup commands against the current dirty worktree.

## Product direction captured by these plans

RokidNexus should become a neutral and useful daily-driver shell for normal users while remaining an open platform for power users who build their own phone plugins. The hub starts empty, apart from the infrastructure needed to discover, authorize, configure, and launch plugins. Transit, Lyrics, Lens, and future experiences remain optional plugins; installing Nexus must not force any of them on the user.

The existing CXR-L/SPP transport and single glasses anchor are preserved. The plans improve the trust boundary and public plugin contract around that validated hardware path rather than replacing it.

## Execution order

Historical plan order below; selected statuses and the current work note were
refreshed on 2026-09-22. The individual plan headers remain the detail of record.

| Plan | Outcome | Status |
|---|---|---|
| [001](001-safety-and-verification.md) | Establish a safe, bounded, green verification baseline | DONE |
| [002](002-plugin-identity-and-capabilities.md) | Authorize plugin principals and enforce capabilities | DONE |
| [003](003-external-plugin-sdk.md) | Deliver the external SDK, lifecycle, sample, and publication path | DONE |
| [004](004-externalize-transit.md) | Ship Transit as the first independent phone plugin APK | DONE |
| [005](005-nexus-store-registry.md) | Nexus Store from the RokidBrew registry (plugins) + app self-update | DONE |
| [006](006-video-playback-on-glasses.md) | Video playback on the glasses | TODO — Feeds video in the timeline builds on it |
| [007](007-camera-platform-and-lens-plugin.md) | Camera as a platform capability + Lens as its consumer | DONE |
| [008](008-plugin-devex.md) | Plugin developer experience, developer mode | DONE |
| [009](009-stt-capability.md) | Speech to text | IN PROGRESS — slices 1–3 shipped; slice 4 is the roadmap's continuous speech |
| [010](010-pin-surface.md) | Pin surface | DONE |
| [011](011-notice-surface.md) | Notice surface | DONE |
| [012](012-activities.md) | Activity tier | DONE |
| [013](013-hud-motion.md) | HUD motion layer | DONE |
| [015](015-notice-pages-and-images.md) | Notice pages and images | DONE |
| [016](016-display-wake.md) | Waking the display | DONE |
| [016](016-assistant-camera-tool.md) | Assistant `take_photo` camera tool | DONE |
| [017](017-relay-notifications.md) | Relay notifications | DONE |
| [018](018-notice-lines.md) | Notice lines | DONE |
| [020](020-ink-surface.md) | Ink Surface: native port of the AIUI page format as a Nexus surface tier | IN PROGRESS — public implementation shipped in 1.4.1; M1–M4 complete, M5 hardware conformance and measurement remain |
| [023](023-activity-v2.md) | Activity v2: autosized panel, badge, track, urgent tone | DRAFT — awaiting go |

Status values are `TODO`, `IN PROGRESS`, `BLOCKED`, and `DONE`. Update both this
table and the individual plan when execution status changes. Current priorities
are tracked on the [roadmap](../ROADMAP.md). Planning and implementation on a
development branch do not establish integration, hardware validation, or release.

## Current work — 2026-09-22

The implemented maintenance slice extracts HUD routing from `BusHubService` into
focused handlers while preserving the current notice identity fix, ownership
checks, error routing, and background-audio lifecycle. The earlier router
extraction is a reference to adapt, not a patch to merge with its old epoch
dependencies. Combined verification passed 2,063 unit tests with no failures,
errors, or skips, including 49 new router tests, 10 notice-input tests, and
24 new Ink navigation tests and 11 review-follow-up regressions; Sample's 14
tests are included. The requested
debug builds completed. Shared lint was not rerun; its earlier `PropertyEscape`
failure in the unchanged `local.properties` remains unresolved.

The [review follow-up](../docs/reviews/hud-interaction-review-follow-up.md)
corrects SDK callbacks across SPP interruption, authoritative hides after lost
replacement shows, and the documented close-callback contract. This follow-up
has automated verification only; the hardware evidence below covers the earlier
review baseline. Relay's pre-existing hide-fallback race and transport recovery
remain separate follow-ups.

Data-preserving QA upgrades were installed on the phone and glasses. Hardware
checks passed for Ink rendering/ready, pin replay across reconnect, notices over
Ink and cards, and background microphone detach/resume/stop. An existing
focused-window path bypassing notice input priority was fixed and retested.
The wearer confirmed physical launcher swipes and text readability, then notice
selection, confirmation, and Back with Ink preserved. Transport traces confirm
the notice action, update, and user close.

Ink action navigation is implemented and installed on the glasses. Injected
RIGHT/DOWN selects Sample's second action exactly once; confirm updates REV 0
to 1 and SYNC 72 to 75 through the phone compile/patch path, retaining selection.
LEFT/UP and RIGHT/DOWN return to the expected controls. A notice's Later action,
confirmation, and Back preserve the selected second control and REV 1 below.
The 24 navigation tests cover node identity, reorder/removal, resync, current
callbacks, paging, oversized controls, accessibility, custom Select, and focus.
The wearer confirmed physical left/right navigation and activation of the
second action. The captured screen shows REV 2, SYNC 78, and the retained
selection outline; transport logs confirm the action and Sample update.

Validation remains partial: R08 ring behavior is not covered, no delayed callback
was deliberately reinjected on-device, and the HUD activity tier has no current
device producer.
No publication is claimed. Broader display-policy
and ownership-epoch work is deferred; the ordinary image-handoff example is not
an established defect. New plugins and PoCs remain after this maintenance slice.

## Why this order is strict

1. Plan 001 makes unsafe queues, proxying, logging, storage fallbacks, and exported debug components bounded before the ecosystem surface grows.
2. Plan 002 establishes package/UID/signing identity, user approval, capabilities, and route authorization. A public SDK without this boundary would expose privileged hub operations to arbitrary apps.
3. Plan 003 packages that contract into a cold-start-safe SDK and demonstrates it with a separate sample APK. It also removes hard-coded plugin catalog assumptions.
4. Plan 004 proves the architecture by moving an existing daily-driver feature out of the hub without regressing the Rokid hardware flow.

Each plan has its own verification commands, manual scenarios, stop conditions, and maintenance notes. Complete and verify one plan before beginning the next.

## Decisions already made

- The public repository is licensed under [Apache-2.0](../LICENSE).
- Keep the core hub empty and neutral; optional features are installed separately.
- Serve both normal users and developer-mode users. Normal mode emphasizes understandable plugin names and permissions; developer mode exposes package, signer, protocol, and diagnostic details.
- Do not require a hub-signature permission for third-party plugins. Trust is based on Android package/UID/signing identity, an explicit plugin descriptor, per-capability user grants, and server-side route enforcement.
- Do not load third-party code into the hub process. Plugins are independent Android packages communicating through the public IPC contract.
- Keep one glasses-side anchor and the existing CXR-L/SPP transport. Independent glasses APKs are not the initial plugin model.
- Keep Lens optional. Its experimental transport and private credentials do not define the public plugin API.
- Do not blindly raise the glasses modules' target SDK merely to silence lint; preserve device compatibility and isolate only the justified lint exception.
- Do not invent new artwork during extraction. Reuse repository-owned assets where suitable and replace them later through an explicit design task.

## Owner decisions still required

- Background-location fallback for Transit: if Android blocks a glasses-initiated location foreground service, the beta should require an explicit phone-side start unless the owner deliberately chooses a broader hub broker or background-location design.
- Public companion apps on the glasses-side transport: phone-plugin approval does not automatically solve authentication of arbitrary glasses clients. Keep this outside the first SDK release until pairing and device identity have a dedicated threat model.

## Follow-up planning queue

This is the historical audit queue, not the current execution order. Several
items have since shipped; the current-work note above and the roadmap take
precedence. These findings were deferred until plans 001–004 established the
platform boundary:

- Extract Lyrics into an independent plugin APK, including encrypted credential migration and a plugin-owned settings screen.
- Stabilize the current Lens work, remove raw OCR data from release diagnostics, and extract it as an optional advanced plugin without making its transport a platform dependency.
- Add display arbitration, surface ownership epochs, microphone-in-use indication, and actionable failure feedback on glasses.
- Design no-ADB onboarding, pairing, and plugin deep links. (RokidBrew distribution metadata is now scoped as [Plan 005](005-nexus-store-registry.md).)
- Add compatibility fixtures for multiple SDK generations once the v3 identity contract has shipped.

## Approaches considered and rejected

- **Signature-only plugin permission:** rejected because external developers cannot be signed with the Nexus key.
- **Bundling Transit, Lyrics, or Lens in the hub:** rejected because it conflicts with the empty-core product direction and keeps permissions coupled to Nexus.
- **Loading plugin code dynamically into the hub:** rejected because it weakens isolation and makes dependency/version failures hub failures.
- **Rewriting CXR-L/SPP before the plugin boundary:** rejected because it risks the already validated hardware path without solving plugin trust or developer experience.
- **Treating phone-side approval as glasses-client authentication:** rejected because the two trust boundaries are different.
- **Raising target SDK as a lint-only fix:** rejected because Android behavior changes can break the glasses runtime and require device validation first.

## Definition of roadmap completion

The initial roadmap is complete when all four plans are `DONE`, the hub can run with no feature plugins installed, the sample and Transit APKs can be built outside the monorepo against published-local SDK artifacts, grants are enforced at the hub boundary, and the physical phone-to-glasses smoke matrix passes without relying on ADB-only shortcuts for normal operation.
