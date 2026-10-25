# Interactive activities and the completed Nexus launcher

Decision date: 2026-10-10. Owner-approved direction and implementation authorization. This document describes intended behavior; it does not claim the following extensions already ship. Implementation status (2026-10-12): implemented in source and accepted by independent review, not yet released or device-approved; docs/LAUNCHER_COMPLETION_REPORT.md maps each decision to code, tests and pending hardware checks. The implementation report must distinguish delivered code, tested behavior and device evidence.

## 1. A generic plugin feature

An activity is a user-enabled ongoing entry in Nexus, with a compact summary and optionally quick commands and a destination inside the launcher. The same public API must work for first-party and third-party plugins. A todo, media player, route or timer must not require a hardcoded hub integration merely to appear or open its page.

The phone plugin owns its data and operation; Nexus owns presentation, input focus, navigation and the back stack. A plugin declares:
- Its activity summary and current availability/state.
- Zero to three quick commands, with stable identifiers and optional default command.
- An optional destination referencing one of its own declared pages and bounded parameters.
- Whether first activation opens the destination or focuses inline commands, with an unambiguous supported default.
- An explicit full-app route through the existing hub-owned launch gate, when appropriate.

These are semantic typed contracts, not arbitrary Android Activities, executable views or WebViews injected into the launcher. Reuse existing page provider callbacks and owner-scoped routing. Implement the necessary conversation/media/route bodies as supported contracts rather than leaving accepted screens as reserved-template stubs. Keep existing Ink surfaces compatible; an embedded Ink page profile, if needed for accepted workflows, must respect its separate grant and the typed/wire authority and must not execute plugin code.

## 2. Two input modes, one reliable axis

Browse mode: swipe moves through cards or pages. Tap on a destination-first card opens its page directly. Tap on a quick-command card enters action focus; it does not execute a transport command at that same first tap.

Action-focus mode: the same swipe axis chooses commands and tap executes the focused command. A visible outline and short hint explain this mode. Back first returns to browse mode; subsequent Back follows the existing stack, eventually restoring the app or surface underneath.

No second swipe axis, long-press dependency, touch-screen selection or hidden gesture. Preserve firmware double-tap Back, armed-notice and focused-editor triple exclusions, the session opening gate and captured press ownership. Paired directional aliases must produce exactly one movement. A contact that began before a focus/capture transition cannot activate a newly exposed send/control target.

Nexus supplies an explicit Open app affordance; it is separate from the three plugin-command slots. A fresh tap opens the owner's full interface directly. Opening a consultation page must not call onNexusOpen/onNexusClose, replace an existing surface, or switch the app underneath.

## 3. Todo acceptance case

The plugin offers Show in activities as an explicit user setting. Enabling it publishes a truthful summary such as Courses — 4 remaining and an owner-scoped destination.

From Activities:
1. Swipe selects the todo card.
2. Tap opens the task list directly inside Nexus.
3. Swipe selects a task, keeping the selected row in the actual remaining viewport.
4. Tap requests an explicit desired checked state for that exact task and the visible revision.
5. The plugin acknowledges and supplies the updated state; Back returns to the card list at the same selection.

Use stable task ids and explicit desired state rather than replayable blind toggle semantics. Handle changed/deleted tasks and stale revisions honestly; do not invent a successful check before the result. A lost result is unconfirmed and never automatically resent. A simple sample/todo provider must demonstrate this through the public SDK, without requiring a standalone glasses app or opening the plugin immersion.

## 4. Media Deck acceptance case

A user-enabled Media Deck activity shows actual now-playing metadata even when its full surface is closed. Closing the surface must not cancel monitoring while the separate activity subscription remains enabled. Enabling/revocation/disabling and no-session states must have explicit lifecycles and preserve the plugin's permissions/settings.

From the card list:
1. Tap focuses Play/Pause.
2. Swipe selects Previous, Play/Pause or Next, using the same physical axis.
3. Tap acts on the current validated session and updates actual state.
4. The hub's separate Open app choice can explicitly enter the complete interface.
5. Back exits command focus; another Back returns through the launcher stack.

No playback on the focus-entry tap, stale session controls, guessed playback state or automatic command retry after reconnect. Treat disappearing/changed media sessions and a lost command result honestly.

## 5. Fast actions require a real policy change

Every launcher command, plugin page action and Open app choice runs immediately on a fresh tap, regardless of the action's confirm field. There is no confirmation or launch panel. The first tap on a commands-first card still enters command focus without executing.

Keep the hub-owned per-plugin quick-action policy and narrowly typed declarations as wire-mode validation. An action uses quick mode when the existing policy and stamp allow it, confirmed mode otherwise; confirmed names the wire mode, not a dialog. Plugin access no longer offers quick-action switches, and stored approvals are unchanged. Execution still requires the current owner, approved grant, declared action, live registration, visible page/card revision and a fresh invocation id/contact. Revoke permission immediately; never grandfather a newly requested privilege.

A plugin calling an action safe or reversible does not prove its callback's effects. Untyped operations, deletes and external effects run on a fresh tap in confirmed mode; full-app choices launch directly. Relay's deliberate send sequence remains separate: compose.send never becomes a row, and Send needs a fresh tap after the draft is visible. Do not add a redundant review menu or make sends implicitly eligible for checkbox exemptions.

## 6. Collection, persistence and notifications

Separate the logical activity collection from the two ambient corner slots. Start with the plan-027 bounded collection of eight live owner entries, one per plugin, with deterministic admission/overflow and few root promotions; the Activities page exposes the remaining entries. Negotiate this end-to-end rather than changing only a renderer while the phone still evicts at two.

Persist the user's enabled/pinned preference separately from live service registration and action authority. An unavailable/disconnected enabled entry may remain as a truthful inert snapshot, with an offline hint and no actionable old revision. Do not hold a service alive forever simply to preserve a preference. Fresh registration/capabilities/grants must revalidate before controls revive. Disable, uninstall or revoked access removes/inerts the entry promptly. Existing Activity v1 peer behavior remains compatible.

Notifications represent events; activities represent ongoing things to consult/control. Implement the bounded notification history and unarmed root preview without changing the existing armed ambient notice behavior. Preserve arrival/expiry/redaction and the user's choice of content visibility. A notice removed from the phone must invalidate its reply authority and update its promoted/root state; it must not remain falsely replyable until an arbitrary 24-hour deadline. Removal is not universal proof that an app marked a conversation read or delivered a reply.

## 7. Relay: the accepted simple chat

Use the r17 mockup as the target, not the obsolete detail/mode/review menu chain:
- Open the exact authenticated conversation directly, on its newest captured page.
- Received messages left, our replies right, transparent outline bubbles; capture time only, bounded history.
- Reading swipe pages the chat. Tap focuses inline Dictate first, Type second. Dictate uses the glasses microphone; Type uses the phone keyboard.
- Capture stays in the same chat. Tap finishes to an unsent draft in reading mode; Back cancels capture and restores the earlier draft. No silent phone-microphone fallback.
- Only when a draft exists is Envoyer available. A fresh tap enters action focus; an additional intentional activation after that displayed focus hands the answer to the exact current recipient. No residual contact or automatic send on capture completion.
- Successful handoff says transmise à l'app; uncertain acknowledgement says non confirmée, never invented delivered/read states. Unconfirmed recovery focuses Type to edit the retained text.
- One global recipient-bound draft survives root/Back. Another capture replaces it only when completed, with an explicit replacement hint; cancel keeps the previous text.
- Lost reply authority preserves the text but prevents sending the old draft as-is. Pure link loss preserves the draft, requiring a new voluntary revalidated Send after return. A new replyable notice for the same exact thread can reopen Type pre-filled as a new capture, then OK and a new Send. Never infer another recipient or resend on reconnect.
- Triple tap is not recognised during capture/focused input; finish or cancel first. The fresh-contact send guard must now be implemented and tested, not merely claimed by the mockup.

Adapt the existing Reply/Type notice behavior and relevant providers through the shared composer only where the accepted workflow requires it; keep armed-band gesture semantics and resource ownership intact. Preserve settings, accounts, signers and grants.

## 8. Navigation and the accepted HUD

Implement the accepted Navigation text/minimap/panel and full-map excursion, using view switching rather than making zoom buttons the primary interaction. The older plan's zoom-menu sketch is superseded by this decision.

Use bounded hub-rendered vector geometry from phone-side route/street data: the same route/heading/position at small, panel and full-page sizes. Respect the documented point/path/label and byte budgets, geometry clipping, heading smoothing and truthful north-up/degraded position behavior. Use a stop ribbon for transit rides as designed. Keep routing/lifecycle inputs from dev/nav-activity-widgets where useful, reviewing and adapting their contract rather than blindly merging its obsolete presentation.

Persist Navigation's ambient size and synchronize its phone editor/foreground-app ambient policy. Only Navigation currently offers user-selected Text/Minimap/Panel forms (owner correction, 2026-10-18); other plugins keep their developer-defined presentation. Show the full map without mutating the preferred ambient size, and return to the same card/context. Native apps and existing surfaces keep their input while ambient islands are passive.

All accepted root/Application/Notification/Activity views carry their canonical app or place icons. Keep a 480x640 black/transparent HUD, outline-only controls, readable layout and one canonical selection model across touchpad, ring/accessibility, scrolling and activation. Never solve overflow with clipping or a theoretical fixed visible-row count.

## 9. Compatibility and verification

Retain a usable legacy fallback and old-plugin full-app path. Evolve negotiated capabilities before exposing new payloads/actions to old hubs. Make the new launcher ready as the intended default after complete local acceptance; preserve an explicit existing user backend selection and a rollback route. Do not reset user settings to force migration.

Meaningful checks include:
- Old/new phone/glasses capability combinations, invalid payloads, unknown fields, cross-owner and stale registration/ref/revision rejection.
- Card focus-entry cannot execute; paired swipes move once; Back restores focus/stack/underneath; no armed band or focused capture triple shortcut.
- Eight-entry collection versus two ambient slots, overflow and frozen root membership, end/remove/disconnect/reconnect/revocation.
- Todo desired-state action, cold provider binding, page updates and no immersion lifecycle calls.
- Media closed-surface activity updates, session replacement, command result and no implicit replay.
- Notification removal during capture/focus/send; exact-thread authority; global draft retention; glasses-only PCM/timeouts/cancel; phone typing; fresh-contact Send gate.
- Route/street geometry limits, clipped and degraded rendering, mode preference sync, navigation/excursion restoration and real viewport overflow.

Run the affected pure/Robolectric/module suites and actual APK builds. Final reports must paste actual build tails and distinguish previous historical 2,368-test PR3a evidence from newly observed results. Device/optics checks require coordination with other active device work and observed results; none is implied by a browser mockup.

## 10. Implementation ownership

Opus 5.5 is implementation lead and may delegate implementation, testing and independent reviews in separate isolated worktrees. Contracts must settle before dependent lane integration. Keep cross-cutting files under one integration owner. All source/docs/comments/commits are English; owner-facing summaries are French. Preserve all unrelated checkouts and all machine build configuration. Local feature commits and integration are authorized. Remote release/merge and device installation are separate from this source implementation.
