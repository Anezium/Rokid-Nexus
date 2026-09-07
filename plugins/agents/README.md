# Agents — Litter-compatible servers

Agents is a headless Nexus phone plugin for the **direct Codex app-server path
supported by current [Litter](https://github.com/0xSero/litter)**. Configure one
server on the phone, list and resume its sessions, read streaming messages, start
a session in a server folder, send follow-ups, stop a turn, and answer a command
or file-change approval once. The glasses retain the session board, reader,
editable follow-ups where the hub supports them, and attention notices.

## Connect

1. Run an existing, signed-in Codex app-server on the computer. Litter's
   [documented manual example](https://github.com/0xSero/litter/blob/94b1b04fd27bfc1a996120bc3742b66230dcc866/docs/DEVELOPMENT.md)
   is `codex app-server --listen ws://127.0.0.1:8390`.
2. In Nexus → Agents settings, enter a server name and the explicit `wss://`
   endpoint or a `ws://127.0.0.1` tunnel endpoint. A loopback address refers to
   the phone; it requires a separately established tunnel to the computer.
   For a development phone attached by USB, `adb reverse tcp:8390 tcp:8390`
   forwards that address to the computer. The plugin does not run ADB or SSH.
3. Enter the server's bearer token if required, optionally a folder filter,
   and choose **Save and connect**. The token is sent only in the WebSocket
   handshake's `Authorization` header. A non-loopback `ws://` URL requires the
   explicit unencrypted-network switch; TLS is preferred. URL credentials,
   query parameters and fragments are rejected.
4. Open a session, or choose **New session**, enter a folder on the server and
   a prompt. A follow-up uses `turn/steer` with the current turn's exact id while
   running, and `turn/start` when idle. Prompts are never automatically resent.

The endpoint must expose Codex app-server JSON-RPC v2, not the Codex REST API,
the obsolete Nexus `agentd` protocol, or a generic web page. Server-side account
login, model availability, sandbox policy and execution permissions remain the
server's responsibility. No API key is bundled or required separately by Nexus.

## Runtime and privacy

- A WebSocket exists only while Nexus has Agents open or an Agents phone
  activity is visible. Closing the last owner disconnects and invalidates
  every approval. Existing work continues on the server. There is no boot
  receiver, hidden monitoring service, general network discovery or background
  polling. While open, session summaries refresh every 20 seconds; the selected
  conversation streams through its resumed session.
- The new endpoint record, including token, is encrypted with AES-GCM under an
  Android Keystore key. Backup is disabled. Token and conversation windows use
  `FLAG_SECURE`; credentials are not logged or copied to the clipboard.
- Approvals bind a local, unique request id to the original server request id,
  connection generation, thread, turn and item. They expire after five minutes,
  disappear on server resolution, item/turn completion and disconnect, and can
  be sent once. This client never grants session-wide or rule-based permission.
  Unknown, incomplete, stale and unsupported requests fail closed.
- Full file diffs or command previews must be available to enable approval.
  Large requests can be read and approved on the phone; the glasses disable
  Allow if the full review cannot fit their reader budget. Broader permission
  requests are declined with an empty grant; interactive tool questions and
  MCP elicitation require another compatible client on the computer.

Prototype machine/project preferences remain untouched in their original
preference file. They are not reinterpreted as app-server credentials. Old
transport/setup classes are retained as dormant source for migration work,
but their activities and monitor service have no manifest entry and are not
reachable from this alpha's runtime.

## Compatibility boundary

Source reconnaissance used Litter revision
`94b1b04fd27bfc1a996120bc3742b66230dcc866` (2026-09-08), its
[architecture](https://github.com/0xSero/litter/blob/94b1b04fd27bfc1a996120bc3742b66230dcc866/docs/ARCHITECTURE.md),
and the [official app-server contract](https://learn.chatgpt.com/docs/app-server).
Litter itself uses a shared Rust/UniFFI core, with native mobile UI. This plugin
implements the documented direct-server protocol independently in Kotlin; it
does not vendor or link Litter's GPLv3 code. Its only network runtime dependency
is the existing OkHttp library.

This alpha does not embed Litter's Alpine/proot runtime, terminal, SSH manager,
Kittylitter pairing, Local Studio transports or non-Codex agent bridges. It does
not claim compatibility with every Litter server option. Lists are bounded to
200 sessions (50 per page); the current conversation retains 60 messages with
16,000 characters per item. Older history stays on the server.

## Verification

From the configured Nexus root checkout:

```powershell
./gradlew :plugin-agents:testDebugUnitTest :plugin-agents:assembleDebug -PskipCxrGlobal=true
```

`LitterClientTest` uses a real local MockWebServer WebSocket and a deterministic
app-server peer to cover initialization, session hydration, whitespace deltas,
prompt/steer requests, scoped approvals and disconnect/reconnect. It calls no
model, requires no credentials, and touches no Android device.
`LitterSecurityTest` covers endpoint policy, request identity, expiry, replay,
missing previews and connection/thread/turn binding.

Device acceptance still needs the actual phone and glasses: Keystore restart,
visible settings lifecycle, TLS/auth failure, selecting a session, streaming,
one follow-up, one approval/denial, closing the plugin, and confirming the
WebSocket disconnects. Run these through the owning task's exclusive device
slot. No build or device success is implied by these instructions.
