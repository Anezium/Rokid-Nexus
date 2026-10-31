# Plan 028 — Complete the Nexus launcher and interactive plugin activities

Implementation status (2026-10-12): source implemented, integrated and accepted by independent review on dev/launcher-complete; not released, installed or device-approved. Hardware acceptance remains the shipping gate. See docs/LAUNCHER_COMPLETION_REPORT.md.

Status: Approved for implementation by the owner on 2026-10-10. This amendment records the accepted r17 glasses UI and the subsequent Astra/Fable activity consultation. It supersedes conflicting presentation sketches and staged restrictions in plan 027 and the completed PR3a delivery contract for this new task. Delivered public contracts remain the starting point; evolve and document them explicitly.

## Owner authorization and reference

The owner accepted the simple Relay conversation, requested Dictate first, accepted the generic interactive activity proposal, and now explicitly requests documentation and full implementation by Opus 5.5, with delegated agents and separate worktrees.

Baseline: dev/launcher-rework at 5a05b50d97289c3a701c3e88575c2ad1da3edbf3 before this design checkpoint.
Accepted UI: docs/mockups/glasses-home/index.html r17, SHA256 a9063300f6fc226694726231860c3cac25d81bc98f677a50347b10b2bd17c0f9.
Mockup history: docs/mockups/glasses-home/CHANGES.md, SHA256 cce0b3890f1a451df50650cbbeecde775240f962254365f9673f2e9f49d7e63f.
Historical PR3a contract remains unchanged. Its old file prohibitions do not prohibit the SDK, activity store, renderer or input changes now expressly needed for this implementation.

The complete product decisions and acceptance cases are in docs/LAUNCHER_INTERACTIVE_ACTIVITIES.md. Plan 027 supplies underlying session/arbiter/ambient architecture, except where the new accepted design replaces old zoom/menu/composer rules. BUSSPEC.md is the wire authority and docs/PLUGIN_SDK.md the typed public API; update both with the actual delivered implementation.

## Scope: complete the accepted launcher, not just another mockup

Deliver the remaining launcher work needed for:
- Generic activity-to-plugin-page destinations and inline quick commands, with a working todo example and Media Deck integration.
- Bounded notifications, exact-thread destinations, and the approved simple Relay conversation and hub composer.
- Navigation map/minimap/panel/full-page view switching, supported geometry/data routing, and per-owner/foreground-app display preferences.
- Root/Application/Notification/Activity layouts and canonical icons matching the accepted design, with underlying apps/surfaces restored correctly.
- Capability negotiation, backward compatibility, user settings and third-party plugin integration documentation.
- Meaningful automated regression tests, real builds, and independent review/fix rounds.

Do not stop after a skeleton API, design document, stubbed demo, or just the first todo slice. Sequence the implementation to make each dependency reviewable, then integrate the complete authorized scope. If a concrete environment/dependency/device condition blocks a part, report it precisely, preserve the work, and continue independent authorized work rather than silently dropping the feature.

## Work lanes and dependency order

| Lane | Responsibility | Integration gate |
|---|---|---|
| A — Contracts/SDK/phone routing | Generic activity destinations and quick-action declarations; negotiated policy/settings; logical activity collection, leases, principal/revision validation, examples/docs | Pure wire/SDK/routing tests; documented old/new compatibility |
| B — Glasses launcher | Session card focus versus browsing, inline transport controls, destination navigation, supported rich page renderers, app-opening affordance, canonical icons, restoration | Input reducer/renderer tests and measurable 480x640 layout |
| C — Relay and notifications | Exact notice/thread references, bounded history, conversation roles, one retained draft, Dictate-first/Type capture, explicit handoff and stale/offline behavior | Notification/RemoteInput/composer tests with fresh-contact and no-replay cases |
| D — Navigation and media clients | Real route geometry/view switching/ambient preferences; Media Deck background opt-in/live data/actions; todo/sample adoption of public API | Plugin suites and both-hub end-to-end contract fixtures |
| Review/QA | Independent review of each integrated slice and final acceptance matrix | Findings fixed; final module tests/builds observed; device checks truthfully scoped |

Freeze the necessary shared contract shape before parallel consumers implement it. Assign one owner per cross-cutting file and serialize integration; agents must not overwrite each other's SDK/wire/registry/arbiter edits. Bound concurrency to the live provider/tool limits. Use worktree isolation for implementation lanes.

## Completion and evidence

Finish with all intended code integrated on a launcher-specific local branch, tests/build outputs and an English implementation report mapping each accepted workflow to implementation and evidence. Register any PR actually created or worked on with T3, but remote publication/merge/release is not required by this instruction.

Run shared, bus-client, both hubs and affected plugins; run ink-engine if Ink session/compiler/public profile changes. Use -PskipCxrGlobal=true only for modules that do not link the vendor CXR library. Hubs must use their configured sibling CxrGlobal checkout without that flag. No local.properties regeneration/copy/rewrite, SDK relocation/private cache, or build environment repair.

Use the already configured E:/Tools/Rokid/RokidNexus-launcher-rework checkout as the serialized integration/build checkout if new source worktrees have no machine configuration. Integrate committed source there before testing; do not call a missing-config worktree failure a green build, and stop any environment-failing build instead of repairing configuration.

Keep all source/docs/comments/commit messages in English; preserve existing public names/style. Root orchestrates and reviews; Opus/Fable author glasses UI changes. Preserve unrelated root and other worktrees, accounts/settings/signers and existing grants. Device installation/use is deferred until the parent coordinates access with the still potentially active Skills/Workspace/device work. No device QA claims without observed evidence.
