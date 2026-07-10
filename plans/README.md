# Implementation Plans

Generated with the `improve` workflow on 2026-07-09.

Planning baseline: Git HEAD `da068ad`, plus the uncommitted Lens-related working-tree changes that were present during the audit. These plans must be executed from a clean, isolated worktree after those changes have either been committed, deliberately discarded by the owner, or reconciled into a new baseline. Do not run broad cleanup commands against the current dirty worktree.

## Product direction captured by these plans

RokidNexus should become a neutral and useful daily-driver shell for normal users while remaining an open platform for power users who build their own phone plugins. The hub starts empty, apart from the infrastructure needed to discover, authorize, configure, and launch plugins. Transit, Lyrics, Lens, and future experiences remain optional plugins; installing Nexus must not force any of them on the user.

The existing CXR-L/SPP transport and single glasses anchor are preserved. The plans improve the trust boundary and public plugin contract around that validated hardware path rather than replacing it.

## Execution order

| Plan | Outcome | Priority | Effort | Depends on | Status |
|---|---|---:|---:|---|---|
| [001](001-safety-and-verification.md) | Establish a safe, bounded, green verification baseline | P1 | L | — | DONE |
| [002](002-plugin-identity-and-capabilities.md) | Authorize plugin principals and enforce capabilities | P1 | L | 001 | DONE |
| [003](003-external-plugin-sdk.md) | Deliver the external SDK, lifecycle, sample, and publication path | P1 | L | 002 | DONE |
| [004](004-externalize-transit.md) | Ship Transit as the first independent phone plugin APK | P1 | L | 003 | TODO |

Status values are `TODO`, `IN PROGRESS`, `BLOCKED`, and `DONE`. Update both this table and the individual plan when execution status changes.

## Why this order is strict

1. Plan 001 makes unsafe queues, proxying, logging, storage fallbacks, and exported debug components bounded before the ecosystem surface grows.
2. Plan 002 establishes package/UID/signing identity, user approval, capabilities, and route authorization. A public SDK without this boundary would expose privileged hub operations to arbitrary apps.
3. Plan 003 packages that contract into a cold-start-safe SDK and demonstrates it with a separate sample APK. It also removes hard-coded plugin catalog assumptions.
4. Plan 004 proves the architecture by moving an existing daily-driver feature out of the hub without regressing the Rokid hardware flow.

Each plan has its own verification commands, manual scenarios, stop conditions, and maintenance notes. Complete and verify one plan before beginning the next.

## Decisions already made

- Keep the core hub empty and neutral; optional features are installed separately.
- Serve both normal users and developer-mode users. Normal mode emphasizes understandable plugin names and permissions; developer mode exposes package, signer, protocol, and diagnostic details.
- Do not require a hub-signature permission for third-party plugins. Trust is based on Android package/UID/signing identity, an explicit plugin descriptor, per-capability user grants, and server-side route enforcement.
- Do not load third-party code into the hub process. Plugins are independent Android packages communicating through the public IPC contract.
- Keep one glasses-side anchor and the existing CXR-L/SPP transport. Independent glasses APKs are not the initial plugin model.
- Keep Lens optional. Its experimental transport and private credentials do not define the public plugin API.
- Do not blindly raise the glasses modules' target SDK merely to silence lint; preserve device compatibility and isolate only the justified lint exception.
- Do not invent new artwork during extraction. Reuse repository-owned assets where suitable and replace them later through an explicit design task.

## Owner decisions still required

- Public repository license: Apache-2.0 is the recommended default for SDK adoption, but no license should be added until the repository owner explicitly approves it.
- Background-location fallback for Transit: if Android blocks a glasses-initiated location foreground service, the beta should require an explicit phone-side start unless the owner deliberately chooses a broader hub broker or background-location design.
- Public companion apps on the glasses-side transport: phone-plugin approval does not automatically solve authentication of arbitrary glasses clients. Keep this outside the first SDK release until pairing and device identity have a dedicated threat model.

## Follow-up planning queue

These findings are intentionally deferred until plans 001–004 establish the platform boundary:

- Extract Lyrics into an independent plugin APK, including encrypted credential migration and a plugin-owned settings screen.
- Stabilize the current Lens work, remove raw OCR data from release diagnostics, and extract it as an optional advanced plugin without making its transport a platform dependency.
- Add display arbitration, surface ownership epochs, microphone-in-use indication, and actionable failure feedback on glasses.
- Design no-ADB onboarding, pairing, plugin deep links, and RokidBrew distribution metadata.
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
