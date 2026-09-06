# Agents

Agents is the mission-control plugin for coding-agent sessions. It merges the
Claude Code and Codex sessions carried by `nexus-agentd` with OpenClaw
Gateway sessions, keeps the connections in a low-priority foreground service,
posts transition-only phone notifications, and renders a structured Nexus card
on the glasses.

## Configuration

The settings screen reads top to bottom: Monitoring (one switch for the
`nexus-agentd` link, which carries Claude Code and Codex, with a status line
that counts the watched sessions) and Computers (every linked machine with
its state). Each computer has its own screen with its anchored projects and
its Forget; the OpenClaw gateway is configured at the bottom of the Add a
computer screen.

### Projects

A project is a folder on a linked computer, anchored from the computer's
screen: the picker walks the computer's directories over the authenticated
link (`fs_list`/`fs_listing`, directories only, no file names or contents)
and stores the chosen `{name, path}` per machine on the phone. Projects are
the ground the glasses-side flow will offer when starting a session on a
computer.

Tapping a project opens New thread: one prompt and a harness — Claude Code or
Codex — and the daemon starts the session inside the project folder
(`thread_start`/`thread_started`; the prompt travels over the authenticated
link and, for Claude, reaches the CLI only through stdin). The new session
then flows through the ordinary monitoring to the board.

- Adding a computer happens on its own screen, reached from the Computers
  list. It opens on how to get `nexus-agentd` itself, then offers three
  roads as equals: automatic on the home Wi-Fi (a two-minute door with a
  visible countdown and a Cancel button), Tailscale for everywhere else,
  and a pasted pairing line for whoever wants neither.
  The pairing line is the single JSON line `nexus-agentd pair` prints; the
  plugin validates `v:1` and `kind:"nexus-agentd"` and stores one
  host/port/token/name slot.
- OpenClaw: enter the Gateway host, port (default `18789`), and token. A host
  may include `ws://` or `wss://`; a plain host uses `ws://`.
- **Agents holds no notification permission and posts nothing on the phone.**
  The permission is deliberately absent from the manifest, so Android keeps the
  monitoring foreground service running but never shows its notification.

Settings, auth tokens, the OpenClaw device seed, alert fingerprints, and the
Gateway-issued device token use the plugin's private `nexus_plugin_agents`
`SharedPreferences`. This follows Transit’s existing plain-`SharedPreferences`
pattern; no token or pairing payload is logged.

### Linking a computer

The daemon dials the phone, so the phone decides who is allowed in:

- The **first** computer ever links itself — nothing exists yet to impersonate,
  and this is what keeps the zero-setup path zero-setup.
- Every computer **after** that must arrive while the wearer holds the door
  open: *Open the door* on the Add a computer screen opens a two-minute
  window, counts it down on screen, and can be cancelled at any moment.
- A known computer presenting the **wrong token is refused**, and its stored
  token is never overwritten.
- Forgetting a machine is per machine, takes two deliberate taps, and drops
  its live connection on the spot.
- A new link is announced on the glasses, never on the phone.

### Away from home

The zero-setup link covers the home Wi-Fi. Everywhere else it rides a
[Tailscale](https://tailscale.com) network, and the setup is deliberately
short:

1. Install Tailscale on the phone — the plugin's *Away from home* section
   offers the Play Store button — and on the computer: Windows and macOS from
   <https://tailscale.com/download>, Linux with
   `curl -fsSL https://tailscale.com/install.sh | sh` followed by
   `sudo tailscale up`.
2. Sign in with the same account on both.
3. There is no step three. `nexus-agentd` watches the tailnet, spots the
   phone, and dials it with the identity it already earned at home — no
   re-pairing, no address to type.

`agentd link-phone <host[:port]>` stays available as a manual override, and
pasting the pairing line stays available as the no-Tailscale fallback.

The current OpenClaw protocol requires device identity in addition to the
shared Gateway token. On the first remote connection, approve the new
**Nexus Agents** device in OpenClaw. The plugin persists its identity so this is
normally a one-time step.

## HUD and background behavior

The HUD is a typed `NexusCard` with `NexusCardLine` list rows: a title, an
optional second line saying what the session is doing to you or for you, a tone
carrying its urgency, and a selection flag. No preformatted text surface is used.

DPAD up/down moves the selection, ENTER opens the selected session's
conversation, and BACK leaves the conversation or hides the surface. The
selection is held **by session key, not row index**: the board re-sorts itself
as agents work, so a positional cursor would drift onto a different session
between looking and pressing.

### Answering a held tool call

Claude Code's `PreToolUse` hook blocks while it waits, and Codex's app-server
raises an approval request; `nexus-agentd` offers either wait to the phone. A session with a live request shows what it wants on the
board; ENTER opens the question rather than the transcript, with the command in
the agent's own words and two answers — Allow and Deny. There is no *always
allow*, no third path, and nothing that turns one glance into a standing
permission.

ENTER is ignored for the first 600 ms of that screen: the touchpad's double tap
is two ENTER downs a few dozen milliseconds apart, and the second would
otherwise land on a decision the wearer had only just opened.

If nobody answers, the daemon's timeout expires and Claude asks on the computer
as it always would. Being away means being asked later, never being decided for.

A session that starts asking for the wearer raises an **interactive notice** —
the band from hub 1.0.46 — rather than fighting for the whole surface. Several
sessions asking at once become one band, and a tap on it opens the board on the
session that rang. The fingerprint that stops an alert repeating is committed
only once the hub has taken the notice, so an alert that could not be delivered
is offered again on the next update instead of being silently marked as sent.
Nothing is queued for glasses that are asleep: the session simply stays at the
top of the board, which is where the wearer will look.

Unlike ordinary dormant Nexus plugins, this product explicitly requires a
monitoring foreground service and phone alerts while its HUD is closed. The
network owner is therefore a separate, non-exported `AgentsMonitorService`;
the one exported `AgentsPluginService` remains the normal SDK adapter.

## Implemented protocols

### nexus-agentd (`nexus-agents/1`)

Implemented exactly:

- client `hello` and server `hello_ack`;
- authoritative `snapshot`;
- contiguous `session_upsert` and `session_removed`;
- a single `refresh` request on any sequence gap, with deltas ignored until the
  replacement snapshot;
- `ping`/`pong`;
- close `4401` as non-retrying `auth_failed`, and jittered 1/2/4/5-second
  reconnects for other closes and network failures;
- ignored unknown message types;
- the conversation exchange: the plugin sends `detail_open` with a session id
  when the wearer opens a session and `detail_close` when they leave, and the
  daemon answers with one `detail` carrying the recent messages followed by
  `detail_append` for each new one while the view stays open.

Every reconnect resets sequence state, sends a fresh hello immediately, and
waits for a new authoritative snapshot.

### OpenClaw Gateway v4

The implementation was derived from the source clone at
`C:\Users\saim2\.codex-detached\rokidnexus\agents-p1\openclaw`:

- Protocol version 4 and minimum general-client version 4:
  `packages/gateway-protocol/src/version.ts:2`.
- `connect` parameters (protocol range, client, role/scopes, device, and token
  auth) and `hello-ok`:
  `packages/gateway-protocol/src/schema/frames.ts:33` and
  `packages/gateway-protocol/src/schema/frames.ts:75`.
- `req`, `res`, and `event` frame envelopes:
  `packages/gateway-protocol/src/schema/frames.ts:155`,
  `packages/gateway-protocol/src/schema/frames.ts:163`, and
  `packages/gateway-protocol/src/schema/frames.ts:172`.
- Generic `gateway-client` identity and `backend` mode:
  `packages/gateway-protocol/src/client-info.ts:23` and
  `packages/gateway-protocol/src/client-info.ts:50`.
- Challenge-first Android handshake and connect request:
  `apps/android/app/src/main/java/ai/openclaw/app/gateway/GatewaySession.kt:986`,
  `apps/android/app/src/main/java/ai/openclaw/app/gateway/GatewaySession.kt:1042`,
  and
  `apps/android/app/src/main/java/ai/openclaw/app/gateway/GatewaySession.kt:1242`.
- Canonical signed device-auth payload v3:
  `apps/android/app/src/main/java/ai/openclaw/app/gateway/DeviceAuthPayload.kt:7`.
- Persistent Ed25519 device identity semantics:
  `apps/android/app/src/main/java/ai/openclaw/app/gateway/DeviceIdentityStore.kt:11`
  and
  `apps/android/app/src/main/java/ai/openclaw/app/gateway/DeviceIdentityStore.kt:174`.
- `sessions.list` parameters and server handler:
  `packages/gateway-protocol/src/schema/sessions.ts:285` and
  `src/gateway/server-methods/sessions-read.ts:220`.
- `sessions.subscribe` and its `sessions.changed` subscription:
  `src/gateway/server-methods/sessions-subscriptions.ts:35` and
  `src/gateway/server-methods/session-change-event.ts:51`.
- Session title/activity/run/error/CWD fields:
  `packages/gateway-protocol/src/schema/sessions-row.ts:27`,
  `packages/gateway-protocol/src/schema/sessions-row.ts:43`,
  `packages/gateway-protocol/src/schema/sessions-row.ts:45`,
  `packages/gateway-protocol/src/schema/sessions-row.ts:54`, and
  `packages/gateway-protocol/src/schema/sessions-row.ts:75`.
- Active-run projection returned by `sessions.list`:
  `src/gateway/server-methods/sessions-read.ts:333` and
  `src/gateway/server-methods/sessions-read.ts:364`.
- `exec.approval.requested` request fields and event timestamps:
  `packages/gateway-protocol/src/schema/exec-approvals.ts:235` and
  `src/gateway/server-methods/approval-shared.ts:255`.
- Approval event scope and session-change read scope:
  `src/gateway/server-broadcast.ts:43` and
  `src/gateway/server-broadcast.ts:71`.

Wire traffic sent by this plugin is limited to:

1. the signed `connect` request with token auth;
2. `sessions.list`;
3. `sessions.subscribe`.

It listens for `sessions.changed`, `exec.approval.requested`, and
`exec.approval.resolved`. A session-change event triggers an authoritative
re-list instead of locally guessing a patch. The approval events are observed
only; the plugin never sends an approval resolution, session message, command,
or agent-spawn request.

Mapping is deliberately conservative:

- `hasActiveRun:true` or Gateway status `running` → `working`;
- a live exec-approval request for that session → `needs_you`;
- Gateway status `failed`, `killed`, or `timeout`, or `lastRunError` →
  `error`;
- everything else → `idle`, with Gateway status notes kept as
  `statusDetail`.

The connection requests `operator.read` plus `operator.approvals`; the latter is
required by the Gateway event guard to receive approval-request events. The
implementation contains no approval method or resolution frame.

## Build and test

This folder is a standalone Gradle root that points read-only at the repository
SDK projects, so no root Gradle file needs an Agents entry:

```powershell
cd plugins\agents
$env:GRADLE_USER_HOME = (Join-Path (Get-Location) ".gradle-user")
..\..\gradlew.bat testDebugUnitTest assembleDebug
```

The module uses the same first-party namespace, `minSdk 30`, SDK project
dependency, signing script, AndroidX Core/coroutines versions, JUnit stack, and
JSON choice as Transit, plus the repository’s existing OkHttp `4.12.0`.

## Phase 1 limits and risks

- OpenClaw approval events are live broadcasts. The permitted Phase 1 method
  subset has no authoritative pending-approval list/replay, so an approval
  already pending before a reconnect may not appear until OpenClaw emits a new
  related event.
- `sessions.list` is capped at 200 rows per refresh. The HUD can show 63 session
  rows because a Nexus card is limited to 64 rows including the header.
- Plain `ws://` sends credentials over an unencrypted tailnet connection.
  Configure `wss://` when the Gateway has TLS; the plugin does not weaken TLS or
  install a custom trust manager.
- No boot receiver is present. Monitoring starts after configuration, an
  explicit connection test, or a Nexus HUD open, as required for Phase 1.
- No approve/deny, prompt answering, session spawning, voice, or command send
  path exists.
- **The phone may stop running Agents altogether, not merely throttle it.**
  Measured on a Vivo device: Android froze the process outright
  (`cgroup.freeze=1`, no CPU time accruing) while the service still reported
  `isForeground=true`. A frozen process is worse than a slow one — the kernel
  keeps completing TCP handshakes from its backlog, so `nexus-agentd` saw a
  phone that connected and then never answered `hello`, timed out after 15
  seconds, and redialled forever. Holding no notification permission is what
  invites this: a foreground service whose notification never appears is not
  treated as a protected one on every ROM.

  Monitoring therefore asks for a battery exemption on its own screen, and
  because that platform flag is not authoritative everywhere — this same device
  keeps the real decision in its vendor power manager and leaves
  `isIgnoringBatteryOptimizations` false regardless — the screen also watches
  its own heartbeat and reports a freeze it actually observed rather than one
  the flag predicts.

  The setting that actually binds on such a phone lives behind App info rather
  than the platform dialog: on the measured device it is *Battery usage > Allow
  background power usage*, and choosing it stopped the freezing. The vendor's
  own high-power screen cannot be deep-linked — it is guarded by a signature
  permission — so the screen offers App info as the nearest door it is allowed
  to open.

- Doze remains a separate, milder limit on top of that: even unfrozen, a
  screen-off, stationary, unplugged phone may not see a transition until a
  maintenance window. With no phone notification by design, the alert arrives
  when the wearer next looks at the board.
- The device identity is signed by a hand-written Ed25519 implementation. Its
  signatures are RFC 8032 conformant but the arithmetic is not constant-time,
  which is acceptable on a private tailnet and must be replaced with a vetted
  library before any public release.
