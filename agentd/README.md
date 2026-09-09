# nexus-agentd

`nexus-agentd` is the Windows PC-side monitor for Nexus Agents mission control. It receives
Claude Code lifecycle hooks on loopback, tails active Claude transcript JSONL files, and
publishes authenticated session snapshots and ordered deltas to the Nexus Agents Android
plugin.

The daemon can hold Claude Code `PreToolUse` hooks while the linked phone asks the wearer
to allow or deny a tool call. It does not auto-approve requests, accept voice commands,
provide TLS, or install itself as a Windows service.

## Requirements and setup

- Windows 11 or macOS
- Node.js 20 or newer
- A Tailscale connection between the PC and the Android device, or both on the
  same Wi-Fi

```powershell
cd agentd
npm install
npm run build
node dist/cli.js install-hooks
node dist/cli.js run
```

On macOS the same four commands run from any shell:

```bash
cd agentd
npm install && npm run build
node dist/cli.js install-hooks
node dist/cli.js run
```

`run` is the default command, so `node dist/cli.js` is equivalent. If installed through
`npm link` or as a package, use `agentd` in place of `node dist/cli.js`.

Claude Code hooks post only to `127.0.0.1:8791`; this listener never accepts remote
connections. Plugin WebSockets listen on `0.0.0.0:8792` and require the pairing token.
Allow inbound TCP 8792 through Windows Firewall:

```powershell
netsh advfirewall firewall add rule name="nexus-agentd" dir=in action=allow protocol=TCP localport=8792
```

macOS needs no equivalent rule while its application firewall is off, which is the
default. With it on, allow incoming connections for `node` the first time macOS
asks, or add it under System Settings > Network > Firewall > Options.

Port 8792 carries an authenticated but unencrypted WebSocket in Phase 1. Expose it only
over the user's trusted Tailscale tailnet, not through router port forwarding or a public
interface.

## Pair the plugin

```powershell
node dist/cli.js pair
```

The command prints a one-line JSON pairing payload and a terminal QR code. It prefers the
first IPv4 address on an interface whose name contains `Tailscale`. The Android plugin can
scan the QR code or accept the printed JSON pasted directly.

The token, stable machine ID, ports, and machine name live in
`~/.nexus-agentd/config.json`. Treat this file and pairing payload as credentials.
Runtime logs are appended to `~/.nexus-agentd/agentd.log`; at 5 MB the daemon rotates the
file to `agentd.log.old`.

## Codex monitoring

Codex monitoring is disabled by default. Enable it in the existing
`~/.nexus-agentd/config.json`:

```json
"codex": {
  "enabled": true,
  "port": 8390
}
```

The daemon first tries to attach to `codex app-server` on that port. If nothing is
listening, it starts and owns an app-server process. Both paths are fixed to
`127.0.0.1`; the unauthenticated app-server WebSocket is never exposed to the network.
Codex thread snapshots, live status changes, approval requests, and phone-requested
thread starts use the already authenticated phone link. A start request creates a thread
in an existing local project directory and can begin its first turn. Threads started by
other Codex clients keep the same monitoring-only behavior as before.

The authenticated phone can also start Claude Code in an existing local project
directory. The prompt is written to the detached CLI process over stdin; user content is
never placed in its command line. Existing user-wide Claude hooks surface the new session
through the normal monitoring pipeline.

If no phone decision is available, agentd sends no approval response to Codex. The
app-server request remains pending for another subscribed Codex UI to answer and is
replayed when a client resumes the thread.

## Typing a reply from the phone

Off by default. A phone that can already watch a session should not also be able to
type into it without the owner turning that on:

```json
"allowTerminalInput": true
```

With it on, the daemon only reaches a session that is running inside `screen` or
`tmux` — it types into the multiplexer the way a person would (`screen -X stuff` /
`tmux send-keys`) rather than owning the session's process, which is also why a
session `claude --resume` would fork instead of continuing cannot be reached this
way. A session mid-turn refuses the phone's text outright: typing into a turn in
progress would land mid-thought rather than at a prompt.

## Away from home (Tailscale)

Install Tailscale on the PC and phone. At startup and every 60 seconds, the daemon
checks the local tailnet for online Android peers and tries their tailnet addresses
on the phone listener port. If automatic discovery is unavailable, read the phone's
tailnet IP from the Tailscale app and add it as a direct target:

```powershell
agentd link-phone 100.x.y.z
```

The running daemon picks up direct-target changes on its next 60-second refresh, with no
restart required. It then dials the phone directly whenever LAN broadcast discovery
cannot find it. A phone already linked at home accepts the tailnet dial without
re-pairing because the daemon uses the same machine identity. Set
`"tailnetDiscovery": false` in `config.json` to disable the Tailscale scan without
disabling direct targets or their hot reload.

## Running at logon

`scripts/run-hidden.vbs` starts the daemon with no console window. Register it once
from an elevated prompt in the `agentd` directory:

```powershell
schtasks /create /tn NexusAgentd /tr "wscript.exe //B \"$PWD\scripts\run-hidden.vbs\"" /sc onlogon /f
schtasks /run /tn NexusAgentd
```

Without elevation, dropping a shortcut to the script into `shell:startup` does the
same job. The daemon exits immediately if another instance already holds its port, so
either launcher can fire at any time to make sure one is up.

On macOS, `scripts/install-launchagent.sh` writes and loads a per-user LaunchAgent
that does the same job:

```bash
./scripts/install-launchagent.sh              # install and start
./scripts/install-launchagent.sh --uninstall  # stop and remove
```

It resolves `node` at install time, because a LaunchAgent does not inherit a login
shell's `PATH`, and deliberately sets no `KeepAlive`: a second instance exits 1 on
`EADDRINUSE`, which launchd would otherwise respawn in a tight loop for as long as
the first one holds the port. Its output goes to `~/.nexus-agentd/launchagent.out.log`
and `launchagent.err.log` alongside the daemon's own log.

**macOS privacy gates the daemon in two separate ways, and a LaunchAgent needs
both lifted.** A terminal inherits the consent its own app was granted, which is
why a manual `node dist/cli.js run` can work while the LaunchAgent does not.

*Files.* Documents, Desktop, Downloads and iCloud Drive are gated per
application, and a LaunchAgent has no app to attribute the access to nor any way
to raise the prompt: node blocks on the first read under such a folder, so the
daemon starts and hangs before binding a port, writing nothing to either log.
The install script probes for this and refuses rather than installing something
that would hang. Either grant Full Disk Access to the `node` binary in System
Settings > Privacy & Security > Full Disk Access, or keep agentd somewhere else
such as `~/nexus-agentd`.

*Local network.* Reaching the phone is gated too. Without it the daemon runs and
serves hooks, but every dial fails — the log fills with `discovery_send_failed`
and `phone_link_error`, connections to the phone return `EHOSTUNREACH`, and
approvals fall back to the computer as if no phone were paired. Enable the
interpreter under System Settings > Privacy & Security > Local Network. macOS
usually lists it only after a first attempt has been refused, so install the
agent, let it fail once, then look.

## Hook management

```powershell
node dist/cli.js install-hooks
node dist/cli.js uninstall-hooks
```

Both commands merge `~/.claude/settings.json` without replacing unrelated settings or
hooks. Before changing an existing settings file, they create a timestamped
`settings.json.agentd-backup-*` copy. Reinstalling is idempotent. Malformed JSON aborts
without writing.

The generated hook forwarder always exits successfully. Ordinary lifecycle hooks return
immediately. A `PreToolUse` hook waits for the linked phone for up to 120 seconds; set
`NEXUS_AGENTD_APPROVAL_TIMEOUT_MS` to a positive millisecond value to change that timeout.
If the daemon or phone is unavailable, the hook returns no decision so Claude Code uses
its own local permission prompt.

## Operations and verification

```powershell
node dist/cli.js status
npm test
npm run smoke
```

`status` checks the loopback health endpoint and prints whether the daemon is running plus
its session count. `npm test` uses Node's built-in test runner. `npm run smoke` starts an
isolated daemon, sends `SessionStart` and `UserPromptSubmit` with `curl`, authenticates a
WebSocket client, verifies `hello_ack`, `snapshot`, and ordered `session_upsert` frames,
prints those frames, and shuts the daemon down.

On startup, recent transcripts under `~/.claude/projects/*/*.jsonl` are shown as stale
idle sessions. They are not tailed until a hook references them. Completed sessions remain
available for 30 minutes.
