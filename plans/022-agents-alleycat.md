# Plan 022 — Agents over Alleycat

Status: TODO. Direction decided 2026-08-23 (plan 021, parallel track):
the Agents plugin drops its hand-rolled protocol (agentd + homemade
Ed25519) and becomes a thin, original client of the Alleycat daemon —
the thing `kittylitter` installs and that the litter mobile app already
talks to. The phone pairs by scanning the QR `kittylitter pair --qr`
prints; the glasses only ever see Nexus surfaces, as with every plugin.

Protocol facts below come from a read-only investigation of
[0xSero/litter](https://github.com/0xSero/litter) and
[0xSero/alleycat](https://github.com/0xSero/alleycat) dated 2026-08-23.
The daemon is young and the wire may drift; treat `v:1` / ALPN
`alleycat/1` as the contract and fail with a clear error on mismatch —
do not chase their `main`.

---

## Why

The current Agents stack (`plugins/agents`) carries its own daemon
protocol: `AgentdProtocol`/`AgentdClient`/`AgentdLinkServer` plus a
hand-written `Ed25519.kt`. That is a private wire nobody else runs,
with homemade crypto we do not want to be responsible for. Alleycat
already solves the same problem — one daemon on the computer,
QR pairing, session multiplexing over NAT — and has an installed base
(litter's users). Riding it deletes our crypto, deletes our daemon,
and makes the glasses one more client of a stack that is already
maintained.

## The license wall (read first)

Litter and Alleycat are **GPLv3**. Therefore:

- **Never copy, vendor, or link their source** — not the Rust core
  (`codex-mobile-client`), not Alleycat crates, not generated UniFFI
  bindings. GPL code in the APK contaminates the whole plugin.
- **Reimplementing the documented wire protocol in original Kotlin is
  the entire approach.** Sources allowed while implementing: the
  Alleycat README (a real wire spec), the Codex app-server JSON-RPC
  docs/schemas, and this plan. Do not read their `.rs` files to copy
  logic; if a behaviour is unclear, test against a live daemon instead.
- The `iroh` transport crates themselves are n0's, not Alleycat's.
  Their license (expected Apache-2.0/MIT) must be **verified before**
  any dependency on `iroh-ffi` is added. If it is not permissive,
  slice 3 falls back to ws:// only.

## Decisions already made

1. **The phone scans the QR** (owner, 2026-08-23). Pairing UX lives in
   the plugin's phone app (`AddComputerActivity` territory). Manual
   paste of the same JSON is the fallback for no-camera setups. The
   glasses display nothing during pairing.
2. **Target the kittylitter default path** — QR + Alleycat over Iroh —
   because that is what their users already run. A direct `ws://`
   URL to `codex app-server` (LAN/Tailnet) ships first as slice 2:
   it proves the whole session layer end-to-end with zero transport
   risk, and it remains a supported "Add computer" option afterwards.
3. **`:plugin-agents` joins the root Gradle tree** in slice 1, mapped
   to `plugins/agents` like every other plugin. That closes plan 021's
   "unbuildable module" gate; the separate-repo alternative is dead.
4. **The token is a bearer secret.** Whoever holds it owns the host's
   agents. It lives in Keystore-backed encrypted storage on the phone,
   is never logged, never shown after pairing, and never sent to the
   glasses.
5. **OpenClaw support is untouched by this plan.** `OpenClawClient` /
   `OpenClawProtocol` stay as they are; whether Alleycat's multiplexer
   subsumes them is a later decision, not this chapter's.

## Non-goals

- Glasses-side code, as always.
- Slingshot (OpenAI's cloud remote-control path). Never.
- Local Studio, voice/WebRTC, terminal rendering — litter app features
  we do not need for a HUD.
- Embedding or spawning agents on the phone.
- Store listing. The plan 021 gates (honest background rule in
  `plugins/AGENTS.md`, no `POST_NOTIFICATIONS` surprise) still stand
  and are part of slice 4, not skippable.

## Current state

- `plugins/agents/` builds nothing (not in the root Gradle tree).
- Hand-rolled stack to retire: `AgentdProtocol.kt`, `AgentdClient.kt`,
  `AgentdLinkServer.kt`, `Ed25519.kt` (+ `Ed25519Test.kt`).
- Worth keeping: the whole UX and session layer —
  `AgentsPluginService` (surfaces, notices, pin),
  `AgentSessionStore`, `AgentsMonitorService`,
  `AttentionDecisionEngine`, the activities, `WebSocketSupport`.
- README describes flows that no longer match the code; slice 4
  rewrites it against what actually ships.

---

## Wire contract (from the 2026-08-23 investigation)

### Pairing payload

`kittylitter pair --qr` prints (QR is just this JSON encoded):

```json
{"v":1,"node_id":"<iroh public key>","token":"<32-byte hex>","relay":null}
```

Optional `host_name` / `hostname` / `display_name` aliases may be
present. The client stores `node_id` + `token` (+ relay if pinned).
`kittylitter rotate` mints a new token for the same node id — re-pair
handles that; do not try to be clever.

### Alleycat handshake (layer 1)

- Transport: Iroh QUIC, ALPN `alleycat/1`.
- Framing: big-endian u32 length prefix + JSON, max 1 MiB.
- Every stream's first frame carries the token.

```json
{"op":"list_agents","v":1,"token":"..."}
{"op":"restart_agent","v":1,"token":"...","agent":"codex"}
{"op":"connect","v":1,"token":"...","agent":"codex","resume":{"last_seq":42}}
```

Responses: `{v, ok, agents?, session?, error?}`.
`session.attached` ∈ `fresh` | `resumed` | `drift_reload`. On
`drift_reload` the replay ring missed events: reload state via
`thread/list` / `thread/read` instead of trusting the stream.

### Session layer (layer 2)

After a successful `connect`, the same stream becomes the agent's
native wire — for `codex`, Codex app-server JSON-RPC; other agents are
bridged by the daemon into the same shape. What the HUD needs:

| Goal | RPC |
|---|---|
| List sessions | `thread/list` (cursor-paged) |
| Create | `thread/start` |
| Open live | `thread/resume` (attaches a live turn stream — prefer over `thread/read`) |
| Send a task | `turn/start` (steer with `turn/steer`) |
| Live UI | server notifications: item deltas, command execution, approval requests |
| Approvals | reply `Accept` / `AcceptForSession` / `Decline` / `Cancel` |

Note: hosts sometimes run Claude with `bypass_permissions = true`; then
approval prompts simply never arrive. The UI must not assume every
session produces them.

### Android caveat

litter ships its own TLS trust + a hardcoded nameserver for Iroh on
Android instead of system DNS/CA. Expect the same class of problem in
slice 3; it is a known cost, not a surprise.

---

## Slices

### 1. Gradle + wire library

- `include(":plugin-agents")` in the root tree, module builds and its
  existing tests run in CI.
- New package `…agents.alleycat`: pairing-payload parser, u32+JSON
  framing codec, handshake request/response types, and a minimal
  app-server JSON-RPC client (the table above, nothing more) — all
  against an abstract transport interface, no Iroh yet.
- Unit tests from hand-written frame fixtures: framing round-trip,
  oversize frame rejected, each handshake response variant, an
  approval round-trip, `drift_reload` triggering a reload.

### 2. Direct ws:// backend, end-to-end on hardware

- "Add computer" gains a direct-URL option: a `ws(s)://` address of a
  reachable `codex app-server` (LAN/Tailnet). Reuse
  `WebSocketSupport`.
- The session layer from slice 1 feeds the existing stores and
  surfaces: session list, live transcript, approval notices with
  Accept/Decline (the four verdicts), progress pin.
- Hardware check: start a thread from the phone, watch it live on the
  HUD, answer one approval from the glasses.

### 3. Kittylitter path — QR pairing + Iroh

- Verify the `iroh-ffi` Kotlin bindings license (gate; if not
  permissive, this slice stops and ws:// remains the transport).
- Phone-side QR scan + manual-paste fallback; store per decision 4.
- Iroh connect (ALPN `alleycat/1`, optional pinned relay),
  `list_agents` → picker, `connect` with resume, then the exact same
  session layer as slice 2.
- Hardware check: pair against a real `kittylitter` install with the
  QR, then repeat the slice-2 scenario over Iroh, including a
  phone-network change mid-session.

### 4. Retire the hand-rolled stack

- Delete `Agentd*` and `Ed25519*`, migrate or clear stored configs
  that referenced them.
- Rewrite `plugins/agents/README.md` against what ships.
- Close the plan 021 gates: the background-exception sentence in
  `plugins/AGENTS.md` written honestly, `POST_NOTIFICATIONS` asked at
  the point of use or not at all.

### Verification

```
./gradlew :plugin-agents:testDebugUnitTest :plugin-agents:assembleDebug -PskipCxrGlobal=true
```

plus the hardware checks named in slices 2 and 3. Agents stays a
private alpha; nothing here touches the Store registry.

### STOP

- No GPL source in the tree, ever — original code from the wire
  contract only.
- No token or transcript in any log, and no token on the glasses.
- No hub or SDK changes; this is plugin-only work (it can run in
  parallel with plan 021 wave A — the file sets are disjoint).
- Do not "fix" protocol drift by tracking their `main`; version-gate
  and error clearly.
- Do not delete the agentd stack before slice 4; slices 1–3 add
  alongside it.
