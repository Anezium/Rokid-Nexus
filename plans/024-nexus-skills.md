# Plan 024 — Nexus Skills: Media Deck, Transit, and native transit guidance

Status: IN PROGRESS — ratified 2026-09-26. The shared foundation (sections 1
to 5), the Transit operations and native journey guidance (section 7), the
shared guidance planner with Navigation and Sample moved onto it, and a Sample
skill provider are implemented and unit-tested on branch `synara/1f5e734c`,
2026-09-27. Unreleased, no version assigned, and not validated on a device.
The Media Deck operations (section 6) are not started. The routing-use
agreement (checkpoint 5) is not obtained. The wire contract is pinned in
[BUSSPEC.md, Skills v1](../BUSSPEC.md#skills-v1); deviations from this
proposal are recorded under "Implementation notes" at the end.

## Product outcome

Installing an approved plugin can expand what Assistant can do. A plugin
publishes typed operations; the phone hub discovers and authorizes them;
Assistant combines their results into a conversation. The plugin retains its
domain logic, Android permissions, and data ownership.

The first release must deliver these journeys:

| Journey | Expected behavior |
|---|---|
| "What is playing?" followed by "Pause it" | Identify the media session, then pause that same session without opening Media Deck. |
| "Pause the music" | Pause the uniquely identifiable active session without requiring a preceding metadata question. |
| "When is the next tram at my favorite stop?" | Read favorites, resolve the stop and direction, and retrieve departures. |
| "And the one after that?" | Continue with the same stop, line, direction, and referenced departure. |
| "Departures from Central Station" | Search stops, ask which one if needed, then retrieve the selected board. |
| "Take me home" / "Give me the way home" | Plan a public-transport journey from the current position to the saved home, answer with a summary, and guide it step by step on the glasses without opening any third-party app. |
| Opening Transit while a journey is active | Show the whole itinerary, every leg with its times, not only the step the activity shows. |

The wearer can speak or type. Operations use the same execution path in either
case. Assistant remains on screen throughout a background skill call.

## Existing foundations and gaps

Sources inspected for this proposal:

| Source | Current behavior and implication |
|---|---|
| [AssistantToolRegistry](../plugins/assistant/src/main/java/com/anezium/rokidbus/plugin/assistant/AssistantToolRegistry.kt) | A fixed registry, at most three executed calls per phase, and each side-effecting tool name at most once. It is not a plugin skill registry. |
| [OpenAiCompatProvider](../plugins/assistant/src/main/java/com/anezium/rokidbus/plugin/assistant/OpenAiCompatProvider.kt) and [ChatGptCodexProvider](../plugins/assistant/src/main/java/com/anezium/rokidbus/plugin/assistant/ChatGptCodexProvider.kt) | One client tool phase, followed by a final request without tools. Dependent tool rounds require a shared orchestration loop. |
| [ExternalPluginController](../phone-hub/src/main/java/com/anezium/rokidbus/phone/ExternalPluginController.kt) | Opening a different foreground plugin closes the previous one. A skill invocation needs a separate lifecycle. |
| [PluginRoutePolicy](../phone-hub/src/main/java/com/anezium/rokidbus/phone/PluginRoutePolicy.kt) | Plugins cannot send into another plugin's private namespace. Skills must be hub-mediated. |
| [MediaSessionMonitor](../plugins/media/src/main/java/com/anezium/rokidbus/media/session/MediaSessionMonitor.kt) | Controls currently include toggle, next, and previous. A deterministic pause operation and explicit outcomes must be added. |
| [TransitRepository](../plugins/transit/src/main/java/com/anezium/rokidbus/plugin/transit/TransitRepository.kt), [models](../plugins/transit/src/main/java/com/anezium/rokidbus/plugin/transit/TransitModels.kt), and [favorites](../plugins/transit/src/main/java/com/anezium/rokidbus/plugin/transit/TransitFavoritesStore.kt) | Search, departures, and favorites exist over `api.transitous.org`. There is no designated habitual stop, no saved home, no journey planning call, and no stable departure identity in the current model. |
| [TransitRuntime](../plugins/transit/src/main/java/com/anezium/rokidbus/plugin/transit/TransitRuntime.kt) | Owns the location foreground service behind Near Me. Journey guidance reuses that service; it adds no second location path. |
| [NavActivityPlanner](../plugins/nav/src/main/java/com/anezium/rokidbus/plugin/nav/NavActivityPlanner.kt) and [NavText](../plugins/nav/src/main/java/com/anezium/rokidbus/plugin/nav/NavText.kt) | Turn successive guidance into activity traffic (start, significant, urgent, silence) and own the maneuver and transit glyph vocabulary. Both are Nav-private today; a second guidance producer needs them in the SDK. |
| [PLUGIN_SDK.md, live activities](../docs/PLUGIN_SDK.md) and [Sample](../plugins/sample/src/main/java/com/anezium/rokidbus/plugin/sample/HelloPluginService.kt) | Any plugin with the `surfaces` grant on API version 3 can start an activity, and Sample already demonstrates a walk, board, ride, get-off sequence. The activity is a platform surface, not a Nav feature. |

[BUSSPEC.md](../BUSSPEC.md) and [PLUGIN_SDK.md](../docs/PLUGIN_SDK.md) remain
the shipped authorities. This plan does not change their present contracts.

## Scope of the first release

Deliver a common catalog, invocation lifecycle, authorization policy, typed
results, bounded Assistant loop, and conversation references. Implement Media
Deck and Transit against that same SDK path. Provide Sample coverage so external
authors can add operations without changing Assistant.

Transit additionally gains native journey guidance: planning over the routing
endpoint of the service it already queries, a saved home, an activity it owns,
and a full-itinerary view. The guidance planner Nav uses today moves into the
SDK so both plugins draw the same panel. Nav keeps reading Google Maps and
Citymapper; it neither plans nor talks to the routing service.

Tasker execution, routines, scheduled skills, event subscriptions, continuous
monitoring, and autonomous triggers are later consumers. No in-app routing
engine, no location permission or collection path beyond Transit's existing
Near Me service, no favorite editing, arbitrary intents, shell access, or
native-app control is part of this release. Launching Google Maps or Citymapper
from a skill is deferred: only driving, cycling, and walking start unattended,
and transit does not (see "Verified on 2026-09-26"). Existing local calendar
tools remain local Android integrations with their existing protections.

## 1. Discovery and the public operation contract

### Static declaration, live readiness

A provider declares a versioned catalog resource in APK metadata. The hub reads
it during ordinary discovery without starting the plugin. Discovery is bounded:
initial limits are 32 operations per provider, 64 KiB UTF-8 per catalog, and
2 KiB per operation description. Invalid catalogs disable skill exposure and
show a diagnostic; they must not invalidate an otherwise valid launcher plugin.

The hub binds the catalog to package, plugin id, signing certificate, installed
package revision, and catalog digest. Updates invalidate previous discovery
snapshots and live invocations; a changed operation contract requires renewed
operation approval. Newly added operations start disabled. Existing descriptor
capability changes retain the repository's current reapproval behavior.

Every operation declares:

| Field | Meaning |
|---|---|
| Identity | Plugin-local operation id and major contract version; the hub supplies the provider identity. |
| Description | Concise purpose, user-facing label, and bounded examples; never executable instructions. |
| Input and output schemas | Bounded JSON types, required fields, enums, and size/range constraints. Unknown arguments are rejected. |
| Effect | Read or action; whether cancellation and duplicate suppression are supported. |
| Access and data | Required Nexus access, plugin-owned Android prerequisites, and categories of data returned to the caller. |

Use a documented JSON Schema subset with no remote references, executable
validators, or model-specific features. The hub and SDK enforce the same subset;
provider adapters project it to their model's accepted tool format. A valid
declaration does not imply the operation is currently usable.

Tool aliases exposed to models are generated from the authenticated provider
and operation identities. They cannot collide with built-in tools. Do not make
developer-chosen descriptions or model-supplied plugin names routing authority.

### Catalog access

Discovery returns only operations approved for the authenticated caller.
Assistant receives the five first-party operations below directly in v1;
searching a large catalog is a later optimization, with bounded exposure from
the beginning. Do not add a universal arbitrary-route execution tool.

Availability distinguishes absent, disabled, incompatible, setup required,
temporarily unavailable, and ready. Descriptions can be cached; permissions,
package revision, and readiness are rechecked at invocation time.

## 2. Authorization and the local hub boundary

Proposed separate descriptor grants are `skills_provider` and `skills_client`.
They do not inherit from the existing `assistant` grant, which controls the
assistant role/button. These names require implementation in the capability
contract before any plugin can request them.

The user authorizes a caller/provider relationship and specific operations.
Media metadata, media control, and Transit reads are separate choices. Installing
or approving a plugin's ordinary HUD capabilities does not enable skills.
Disclosure explains that authorized result data may reach the AI provider chosen
in Assistant. Exact coordinates, media tokens, and unrelated private records are
not included just because the source plugin can access them.

The hub derives both identities from authenticated registrations, stamps the
invocation, validates arguments and grants, and delivers directly to the exact
provider. The result is returned only to its caller. Neither side can impersonate
the other or route through another plugin's namespace.

The logical SDK operations are catalog lookup, invoke, cancel, and result
delivery. Reserve a new capability-gated local `/skills/*` family with separate
caller requests and hub-only delivery callbacks during implementation. Pin exact
wire messages in BUSSPEC before coding the routers. Skill traffic stays on the
phone; it does not traverse CXR/SPP. Only ordinary display and user input do.

Immediately before dispatch and result release, check current grants, catalog
revision, caller session, provider identity, and deadline. The provider checks
its Android access and target identity at its action boundary. Revocation
prevents subsequent dispatch and cancels active work; it cannot undo a command
already delivered to Android or an external service.

Operation effect declarations are not proof that third-party code is harmless.
All providers require user trust and approval; the hub cannot sandbox their own
Android API calls. Plugin descriptions and result text remain untrusted data.
They never grant permission, approve a prompt, or authorize another action.

The first mutating skill is pause, authorized by an explicit user request and
the media-control grant. Higher-impact skills will require hub-owned confirmation
bound to exact arguments and target identity. They are not enabled by this plan.
The model cannot mint confirmation. Existing `/core/*` controls and Wireless
ADB operations remain outside the skill catalog.

## 3. Invocation without taking the HUD

Introduce an invocation lease separate from foreground open and background
audio. The hub binds a dormant provider, waits for authenticated registration,
and delivers a skill callback without sending `PLUGIN_OPEN` or adopting it as
foreground. The SDK keeps the provider alive only while authorized work remains.

Foreground, microphone, and invocation lifetimes must have independent ownership.
Finishing a call releases only its lease. It must not close an already-open
provider, interrupt its audio, or reset its UI state. Conversely, closing its HUD
does not strand a still-authorized bounded call. Release the binding when no
existing session or invocation needs it.

A skill-only lease does not authorize surfaces, notices, activities, microphone,
or camera use. Enforce this at the hub, not just in SDK helpers. A provider that
also has a legitimate display session retains that session's existing rights.
Assistant presents progress and results through its own existing display path.

Initial proposed limits are one executing skill per Assistant turn, at most four
live invocations globally, and 15 seconds per invocation including cold start.
Reject excess work as busy rather than retaining an unbounded queue. Registration
keeps the existing five-second ceiling, within the invocation deadline.
Android service-start refusal returns an availability error, with no lifecycle
bypass. Repository retries and I/O timeouts must fit the outer deadline.

Cancellation, caller close, revocation, package replacement, process death, and
expiry all release leases. Cancellation stops remaining work when possible; it
does not claim to reverse an already dispatched effect. Late results are fenced
by invocation id and session generation and cannot resume a newer conversation.

This is a new documented exception to dormant-plugin policy, not permission to
poll, auto-start, schedule itself, or remain running after the request.

## 4. Results, references, and duplicate handling

### Result envelope

Every result carries invocation id, contract version, status, typed data when
present, and observation time. The hub supplies authenticated provenance.
Initial JSON input/output limits are 16 KiB each, enforced before forwarding.
No binary payload is needed in v1.

| Status | Meaning |
|---|---|
| `completed` | The read finished or the requested postcondition was observed. An empty read is a valid result. |
| `accepted` | Dispatch is known to have happened, but completion was not observed. Terminal for this bounded v1 call. |
| `needs_input` | No effect occurred; a bounded choice is needed before a new invocation. |
| `failed` | A known error; include a stable code and dispatch state rather than assuming no effect. |
| `unknown` | Execution or completion cannot be established after interruption. Never describe this as success or as proof of no effect. |

Cancelled/expired calls retain whether dispatch occurred or is unknown. Stable
errors include permission required, setup required, unsupported operation,
invalid arguments, busy, unavailable, stale reference, and deadline exceeded.
The provider's arbitrary error prose is not a command to the model.

### Entity references

Results can return typed references to media sessions, stops, and departure
snapshots. The hub scopes handles to caller session, provider principal, catalog
revision, and entity type. Handles are opaque, expiring, and not authorization.
The model can pass back a handle it received, never create a valid one from a
name. Resolve it before dispatch and revalidate the target in the provider.

Keep bounded reference records in hub memory across individual provider leases.
For media, the Android token remains provider-private; a provider process restart
invalidates that reference. Transit may resolve a stop from its provider-owned
identifier, but must validate it again. No model-generated stop id is trusted.
Discard references on caller-session expiry, revocation, incompatible updates,
and hub restart. Use a proposed ten-minute maximum idle lifetime, additionally
bounded by the existing conversation/session lifetime.

Data freshness is separate from reference validity. A stop can remain identified
while its departures need refreshing. Do not treat an unexpired handle as proof
that a media session is still active or a tram departure is still upcoming.

### Duplicate policy

The hub assigns an operation id independent of provider-specific model call ids.
An exact duplicate within the live caller session reuses the running/cached
outcome; reusing the id with different arguments is rejected. Bound the ledger
to 128 operations per session and expire it with that session.

This is not durable exactly-once execution. After a hub/provider crash or an
uncertain dispatch, v1 does not automatically retry a mutation. Reads may be
retried within the turn budget. A new explicit pause request is allowed, with
fresh target validation. Never implement pause by replaying toggle.

## 5. Assistant orchestration and conversational context

Move the execute/result/continue loop into a shared Assistant runner. Provider
adapters translate tool declarations and messages; they do not own independent
authorization, retry, or loop policies. Existing local tools and new plugin
operations can participate in the same request.

Initial proposed budgets: four tool rounds, eight executed tool calls, and
60 seconds of wall clock for chaining them, model latency included. Calls run
sequentially in v1. Once the clock is spent no further tool round starts and
the next pass is the tool-free synthesis; a pass in progress is never cut, so
a plain question or a long final answer takes as long as the model takes.
Repeated invalid calls, budget exhaustion, or cancellation terminate the loop.

Keep built-in tools' existing per-request mutation protections, especially
calendar deletion. Repeated rounds must not recreate execution state and reset
those guards. Plugin operations use their invocation identity and duplicate
policy rather than a blanket once-per-name restriction.

Pause the task for a user choice without holding a provider lease. A choice
restarts a bounded turn, rechecking references and permissions. Text, voice, and
HUD selection all reach the same structured choice path; model text cannot
fabricate a hub selection event or approval.

For Transit, conversation context records stop, route/direction selection,
board observation time, and the departure just mentioned. "The next one" is
relative to that departure in that group, not the first row in a newly fetched
board. An unrelated result does not silently replace this focus.

Use structured tools where the provider supports them. The existing Hermes
text bridge may expose the new operations only through the same catalog,
validation, limits, and shared runner; no execution from ordinary displayed
prose. A backend that cannot complete the exchange reports the limitation and
must not claim success. Provider-specific compatibility is an acceptance gate.

## 6. Media Deck operations

| Operation | Input | Result |
|---|---|---|
| `media.get_now_playing` | No target required. | No session, a unique session reference with available title/artist/player/state, or bounded choices when selection is ambiguous. |
| `media.pause` | Optional session reference from prior context. Without one, resolve a uniquely identifiable playing session. | Confirmed paused/already paused, accepted, needs input, or a typed failure. |

Do not copy the current UI's best-session heuristic into a mutating operation
when multiple sessions could match. Ask which player. A reference to a destroyed
session returns stale reference; never redirect it silently to a new player.
Read and control grants are separate: pause can operate without exporting title
or artist. If a player choice is necessary, return only the permitted label and
opaque session reference needed for selection.

Recheck notification access, supported transport action, and session identity
immediately before sending explicit `pause()`. Already-paused is successful
without another transport command. After dispatch, observe the same session's
playback callback until paused or the invocation's remaining deadline. Report
accepted if dispatch is known but pause was not observed; unknown if that fact
was lost. Do not infer confirmation from a delay or from the Binder send alone.

Reuse media session/domain access without calling `MediaDeckRuntime.open()`.
Skill-only reads do not fetch artwork or create a media surface. Simultaneous
HUD use must keep its existing controller/listener lifecycle intact.

## 7. Transit operations

| Operation | Input | Result |
|---|---|---|
| `transit.list_favorites` | Optional name filter and bounded page cursor. | Stop references and display names; explicit empty result and continuation when needed. |
| `transit.search_stops` | Nonblank query, maximum 120 characters. | Up to eight stop references with name and city; truncation is explicit and asks for a narrower query. |
| `transit.get_departures` | Stop reference; optional returned line/direction selectors. | A timestamped board with at most twelve departures and explicit source/quality information where available. |

Favorite pages contain at most twenty stops. Their opaque cursors are tied to a
favorites revision; a changed list invalidates paging instead of skipping items.
An ordered favorite list does not identify a habitual stop. With several viable
favorites, ask the wearer; with none, offer name search. Do not infer a home,
location, or preferred direction. This release needs no location permission.

Departure data includes line, direction, mode, scheduled time when known,
reported departure time, cancellation, board observation time, and a
snapshot-scoped departure reference. Use absolute instants and format them in
the phone's local zone. Missing data is explicit; a scheduled time is not
described as a live prediction. The existing model may require a small extension
to preserve upstream quality/provenance rather than guessing from timestamps.

Keep favorite reads, search, and fetching separate from `TransitRuntime`'s HUD
and refresh loops. A skill fetches once and releases its lease. Repository retry
and network behavior must respect cancellation and the invocation deadline.

For a follow-up, a board observed at most 30 seconds ago can answer from its
remaining future departures. After that, refresh. Report unavailable freshness
honestly instead of silently presenting old departures as current.

The current model has no stable trip id. A snapshot reference is valid only for
that board. After refresh, match the prior departure only when source identity
or a unique stop/line/direction/scheduled-time combination establishes it. If
ambiguous, say the board changed and present current departures. Never identify
a trip by its row index or a changing estimated time alone.

Empty board, cancelled departures, no matching line, ambiguous direction,
network failure, and expired references remain distinct outcomes. Assistant
cannot infer that the wearer can catch a service: walking time is outside v1.

### Journey guidance, owned by Transit

| Operation | Input | Result |
|---|---|---|
| `transit.start_journey` | Destination: `home`, or a stop/place reference from a prior result. Optional departure instant. | `completed` with a journey reference and a summary (legs, lines, first departure, arrival, number of alternatives) once guidance has started; `needs_input` when home is unset or the destination is ambiguous; typed failures `no_route`, `location_unavailable`, `setup_required`, `unavailable`. |
| `transit.journey_status` | Journey reference from context, or none for the active one. | Current leg, next boarding or alighting, arrival estimate, and whether the plan was refreshed. No active journey is a valid empty result. |
| `transit.stop_journey` | Journey reference. | Guidance ended and the activity closed; already ended stays ended. |

Planning uses the `/plan` endpoint of the routing service Transit already
queries for stops and departures. Keep realtime flags and absolute instants per
leg, and format them in the phone's local zone. The first itinerary is guided;
the summary states how many alternatives exist so the wearer can ask for
another. That service permits non-commercial use only, requires an identifying
`User-Agent` with a contact, and asks to be contacted before routing use.
Obtaining that agreement is a release gate. If routing use is declined, the
provider seam accepts Google Routes as Rokid-GMaps already implements it, with
its own key and billing; the operations and the panel do not change.

Home is a Transit setting: a label and coordinates chosen once from the
service's geocoder results, next to favorites. Only the label and the itinerary
reach Assistant and its AI provider; home coordinates and the live position
never do. The wearer's position comes from the existing Near Me location
foreground service, which the journey holds for its duration and releases at
arrival or stop. Without location permission, `start_journey` returns
`location_unavailable` and Assistant says what to enable.

Transit starts and updates its own activity through the ordinary `surfaces`
grant. Assistant does not draw the journey; it reports the summary in its band
and stays on screen. Steps follow the itinerary and the position: a walk leg
shows direction and remaining time; a ride leg shows line, direction, and the
stops remaining as the track; alighting becomes the urgent moment at the stop
before; a transfer is a significant update; arrival ends the activity. When a
boarding time passes without the wearer aboard, replan once from the current
position and announce the new plan as a significant update; never keep
counting down a departure that has left. Refresh the next boarding within the
repository's existing rate.

Transit persists the active journey (itinerary, current leg, references) so a
process restart resumes guidance instead of dropping it, and expires it at the
arrival instant plus a margin. The hub already resends activity state after a
glasses reconnect.

### Shared guidance planner

Lift Nav's guidance-to-activity planner and its glyph vocabulary (maneuvers,
walk, bus, metro, tram, train, arrive) into the SDK as a typed helper: a
sequence of guidance steps in, activity start, update, or no-op with the
significant and urgent flags out. Nav adopts it without behavior change,
Transit's journey uses it, and Sample's demo route moves onto it. The panel is
therefore identical whichever plugin guides. Nav keeps its notification
parsers; Transit never reads notifications and Nav never plans.

### Full itinerary while a journey is active

The activity shows one step. Opening Transit from the launcher while a journey
is active, or center-tapping the idle activity layer, lands on the journey view
first: every leg with line, direction, boarding and alighting stops, and times,
the current leg marked, one leg per page with the usual forward and backward
paging. Back or exit returns to the launcher and leaves the activity running.
Near Me and favorites stay one action away from the journey view. Ending
guidance is explicit, from a "Stop guidance" action in that view or through
`transit.stop_journey`; closing the surface never ends it.

## 8. HUD behavior and user control

Reuse the current Assistant band for brief progress and answers. Fast reads and
pause need no Activity. Show stop and direction in a departure answer so the
wearer can catch a wrong selection. Use existing Notice/card choice primitives
for ambiguity, with at most three visible choices per page and normal back/exit.

Back/cancel terminates the pending Assistant work and further tool dispatch.
Already completed effects remain completed. Rejected or stale choice callbacks
cannot resume a replacement request. No whole-plugin launch is needed for a
skill result; explicit presentation handoff is deferred. The transit journey is
the designed exception: Transit's own activity carries the guidance, and its
journey view is reached through the ordinary launcher and activity-tap paths.

No new glasses protocol or renderer is planned for these journeys. Existing
notice identity, foreground ownership, and display-wake rules still apply.
Activity v2 can serve future genuinely long operations; it is not a reason to
keep a short skill lease alive.

## 9. Delivery slices

Specify both consumers now and implement them against one common foundation.
Parallel development is possible after that contract exists; neither consumer
may ship a private Assistant-to-plugin shortcut.

| Slice | Concrete deliverable | Exit condition |
|---|---|---|
| A — contract | Shared schemas, proposed routes/callbacks finalized, grants, lease/ref/result state machines, Sample fixtures. | Both journeys can be represented without arbitrary payload escape hatches. |
| B — hub and SDK | Discovery, consent, invocation leases, bounded results, references, revocation, duplicate handling, and the shared guidance planner with Nav moved onto it. | Fake providers complete calls without changing foreground or audio ownership; Nav emits unchanged activity traffic. |
| C — both consumers | Media reads/pause, Transit favorites/search/departures, and Transit journey planning, guidance activity, saved home, and journey view, using domain code independently of HUD runtimes. | Typed calls satisfy both consumer acceptance matrices; a journey guides end to end on the glasses. |
| D — conversation | Shared bounded loop, provider adapters, context and clarification continuation. | Both voice/typed journeys, including "the one after that", work end to end. |
| E — validation and docs | Hardware checks, external Sample, SDK/BUSSPEC/plugin contract updates, feature negotiation, and the routing-use agreement. | Observed results support release claims; old plugins and unsupported peers degrade cleanly. |

Old plugins publish no catalog and keep working normally. Gate new SDK helpers
on a hub feature/version rather than assuming availability from installation.
Minimum released versions are assigned only when implementation is delivered.
No migration of existing user data or calendar access is required.

## 10. Acceptance and verification plan

These are required future checks, not tests executed for this document.

| Area | Required scenario and observable result |
|---|---|
| Media cold start | Ask what is playing with Media Deck dormant; receive metadata while Assistant stays foreground; provider lease ends. |
| Media mutation | Pause a referenced playing session; success only after its state becomes paused. Already paused stays paused. |
| Media ambiguity | Two viable players or a destroyed reference require a choice/error; no silent retargeting. |
| Media access | Metadata and control grants are independently enforced; missing notification access yields setup required. |
| Transit chain | Favorite lookup then departure query occur in separate model/tool rounds with the actual returned stop reference. |
| Transit ambiguity | Multiple favorites or same-name stops require selection; continuation uses the selected identity and direction. |
| Transit follow-up | "The one after that" preserves group/anchor; stale or changed boards refresh without pretending row index is trip identity. |
| Transit failures | Empty results, cancellation, network failure, missing quality, and scheduled-only data produce distinct honest answers. |
| Transit journey | "Take me home" with home set: plan returned, summary in the Assistant band, Transit's activity started within the invocation deadline, Assistant still foreground. |
| Journey prerequisites | Home unset yields `needs_input`; missing location permission yields `location_unavailable`; no itinerary yields `no_route`; none of them starts an activity. |
| Journey progress | The ride track counts down from observed position; alighting is urgent once at the previous stop; a missed boarding replans once and announces it; arrival ends the activity. |
| Journey view | Opening Transit from the launcher or tapping the idle activity during a journey shows every leg with the current one marked; back leaves the activity running; "Stop guidance" ends it. |
| Journey persistence | Transit process restart and glasses reconnect resume the same journey and step; an expired journey is not resumed. |
| Planner extraction | Nav on the shared planner emits the same activity traffic as before for recorded Google Maps and Citymapper transcripts. |
| Ownership | Both providers work while dormant or already open; completing a call never closes another session or enables skill-only display traffic. |
| Revocation | Revoking either side or replacing a package blocks subsequent dispatch and drops unauthorized result delivery. |
| Isolation | Forged provider/caller ids, foreign handles, wrong entity types, replayed choices, and non-owner results are rejected. |
| Interruption | Caller close, timeout, and process/link interruption leave no lease leak; late results never enter a newer turn. |
| Duplicates | Same operation id reuses its outcome; changed arguments are rejected; uncertain mutations are not automatically replayed. |
| Orchestration | All provider paths obey common budgets, cancellation, result semantics, and built-in calendar safeguards. |
| Compatibility | Old plugins/hubs fail gracefully; normal launcher, Media Deck HUD, Transit board, notices, and microphone lifecycle keep working. |

Use unit tests for contracts, hub policy/lifecycle, provider-domain operations,
and Assistant orchestration with fake model transcripts. Hardware validation
covers Android cold binding, real media-session confirmation, real Transit
responses, permissions, foreground coexistence, and glasses input/display.

Expected implementation suites: `:shared:test`, `:bus-client:testDebugUnitTest`,
phone hub tests/build, and Assistant/Media/Transit/Sample tests/build. Run glasses
hub tests/build if shared wire or rendering/input integration changes affect it;
hardware regressions still cover the existing HUD path. Follow repository build
instructions: no skip-CXR flag for hubs, no machine configuration repairs.

Keep diagnostic journals to operation identity, provider identity, duration,
status/error, and dispatch state. Do not log prompts, media metadata, favorite
names, raw tool arguments/results, coordinates, or private entity handles.

## Verified on 2026-09-26

Device probes on the owner's phone (Android 16, Google Maps 26.38) and a live
routing request, recorded so the scope above is not relitigated:

- `google.navigation:q=<lat,lng>` starts unattended turn-by-turn driving in
  about three seconds. The documented modes are driving, cycling, two-wheeler,
  and walking; `mode=t` silently falls back to driving.
- The Maps URL with `travelmode=transit&dir_action=navigate` to a served
  station (bus 1611 then RER D) stops at the route list: no navigation
  notification within 25 seconds, two taps left. To a destination without a
  transit route it started walking guidance, i.e. the first suggestion of the
  transit tab.
- `citymapper://directions?endcoord=…` opens the results screen with the
  destination filled; GO stays two taps away. Citymapper's public API is
  discontinued and its documentation domain redirects to Via's enterprise page.
- The routing service's `/api/v1/plan` from Goussainville to Louvres returned
  three itineraries with `realTime=true` on the bus 1611 and RER D legs; leg
  instants are UTC.
- The glasses side could not be observed: the phone hub was disabled during the
  probe.
- Nav's Maps parser logged `unreadable` for the driving notification "Prendre
  la direction de …"; unrelated to this plan and tracked separately.

## Design checkpoints before implementation

1. Freeze the exact wire messages, schema subset, catalog metadata, and SDK
   callback signatures in the contract slice; public names above are provisional.
2. Validate the invocation lifecycle against the existing SDK foreground service
   and independent foreground/audio sessions before connecting real providers.
3. Verify that each supported AI adapter can preserve multiple tool rounds and
   cancellation; explicitly gate unsupported combinations.
4. Measure the proposed budgets on device. Adjust declared limits consistently
   across contracts and tests, without silently extending background lifetime.
5. Obtain the routing service's agreement for Nexus's non-commercial journey
   planning, or switch the provider seam to Google Routes, before slice C ships.
6. Confirm the journey activity and the Near Me location service share one
   foreground service and one notification.

The first releasable milestone is the three journeys on the shared path. Routines,
Tasker, and additional providers extend that path after its behavior is proven.

## Implementation notes

Recorded 2026-09-27 with the first implementation. Where the plan and the code
disagreed, the smaller change that keeps the plan's guarantees was taken.

- **Grant declaration.** `skills_provider` and `skills_client` are real
  descriptor grants, but a plugin requests them through dedicated metadata
  keys (`SKILLS`, the catalog resource, and `SKILLS_CLIENT=true`) rather than
  `.CAPABILITIES`. Hubs before this change reject unknown capability names and
  would have refused Transit and Assistant outright. Requesting either grant
  returns an installed plugin to Pending, the existing reapproval behavior.
- **Feature gate.** The phone hub announces `skillsVersion: 1` in registration
  metadata; every SDK skills call returns `CAPABILITY_NOT_AVAILABLE` without it.
- **Aliases.** Model-facing names are `sk_` plus the provider and operation
  ids (hashed when they collide or exceed 64 characters). Built-in Assistant
  tools may not use the prefix.
- **Lease and display.** The plan says a skill-only lease authorizes no
  activity, and also that Transit's journey starts its activity from a skill
  call. The code makes the exception explicit per operation: while only a
  lease keeps a provider running, the hub refuses its surface, Ink, notice,
  pin, microphone, speech, TTS, and camera traffic, and allows activity
  traffic only while an operation that lists `surfaces` in `requires` is
  running (`start_journey`, `stop_journey`). A provider already running or
  holding a display session keeps its ordinary rights. The lease binds through
  its own `ServiceConnection`, so it is independent of the foreground and audio
  bindings in both directions.
- **Ongoing journey.** An activity ends with its owner's registration, so the
  journey cannot live on the lease. The SDK gains `holdNexusOngoingWork`,
  which promotes the plugin's single session foreground service (with the
  location type for Transit) and also starts the service so it survives the
  hub unbinding; the plugin contract lists it as a fifth dormancy exception.
  Transit returns `START_STICKY` while a journey runs, and resumes a persisted
  journey whenever its service is created. Whether Android lets a sticky
  restart promote a location foreground service from the background is a
  device check; if it refuses, the journey resumes on the next open or skill
  call. A resume started under a lease that does not declare `surfaces` has
  its activity refused, which Transit notices and retries on its next tick.
- **Sessions.** "One executing skill per Assistant turn" is enforced as one
  per caller session. Two limits the plan did not name were needed and are
  constants beside the others: at most four sessions per caller, and a session
  closes after 30 idle minutes.
- **Departure identity.** The routing service's stop times do carry `tripId`
  and `realTime`; `TransitDeparture` now keeps both. A follow-up matches its
  anchor by trip id when both sides have one, otherwise by a unique line,
  direction, and scheduled time, and reports `board_changed` or
  `anchor_departed` otherwise.
- **References.** A stop reference's provider value holds the feed id, name,
  and the stop's public position, so a stop can be a journey destination. It
  never reaches the caller: the hub swaps it for a handle.
- **Assistant loop.** Continuation requests keep the tools declared while
  rounds remain (the old behavior dropped them after one phase); a tool-free
  synthesis happens only once the budget is spent. The 60-second budget is
  wall clock and gates the start of a tool round: a pass in progress, plain
  answers included, is never cut. Codex declares plugin operations with
  `strict: false` because their schemas have optional properties.
- **Hermes.** The text bridge does not expose plugin operations: it keeps its
  single exchange for built-in tools and cannot carry the shared validation and
  limits, so it is gated off rather than given a weaker path.
- **Choices.** A `needs_input` result reaches the model with its choices and
  handles, so text and voice answers flow through the model. Up to three
  choices also appear as chips on the answer band; a chip carries a token bound
  to the choices it was offered for and starts a new turn with the chosen
  handle in the plugin context, so no text can stand in for it.
- **Fixes and time.** A fix leads the journey and never runs it ahead of the
  wearer; the timetable moves it only without a fix, and only along the trip
  that was planned. A fix older than two minutes no longer counts as one, so
  the last outdoor fix cannot hold an underground wearer at a stop they have
  left. A walk that a fix never completes ends by time past the leg's end.
  Without real-time data a boarding waits four minutes, not ninety seconds,
  before counting as missed. A missed leg still turns into a ride when the
  wearer boards a later vehicle; that ride then follows the fix alone and
  shows no arrival estimate.
- **Routing requests.** The planner sends `fromPlace`, `toPlace`, and an
  optional `time`; the service returned several itineraries without a count
  parameter, and the summary counts at most five alternatives. Two manual
  requests between public Paris stations were made during development, one of
  which became the trimmed test fixture.
- **Media Deck** (section 6) is out of scope for this implementation thread;
  nothing in the contract is specific to Transit.
