# Changelog

## Unreleased — Litter-compatible alpha

- Replace the active custom-daemon setup with explicit Codex app-server endpoints.
- Encrypt endpoint credentials in Android Keystore and connect only while an
  Agents phone screen or Nexus session is open.
- Add native session history, streamed progress, new prompts, follow-ups and
  request-scoped approvals on the phone and existing glasses surfaces.
- Preserve prototype project metadata without running its old monitor service.
- Add deterministic WebSocket and endpoint/approval security tests.

## 1.0.0

- Add Claude Code, Codex, and OpenClaw session monitoring.
- Add a unified HUD mission-control board and a conversation view that reads
  like prose: whole messages, Codex history included, scrolled chunk by chunk
  with a role badge marking where each message starts.
- List every linked computer with its state, and forget them one at a time.
- Gather the three ways to link a computer — automatic on the home Wi-Fi,
  Tailscale from anywhere, a pasted pairing line — on one Add a computer
  screen, with a link window that shows its countdown and can be cancelled.
  The OpenClaw gateway is configured there too.
- Give each computer its own screen, and let the wearer walk its folders over
  the link to anchor project folders — the ground the glasses will start
  sessions from.
- Start a new session from a project: one prompt, Claude Code or Codex, and
  the daemon launches it inside the project folder. It appears on the board
  like any other session.
- Walk computer, project, and the project's threads on the glasses, and start
  an empty Codex thread from the ring: the board's last row is the door, one
  tap up from the top.
- Alert on the glasses with an interactive notice; the phone stays silent and
  the plugin holds no notification permission.
- Let a computer link itself over the LAN, and refuse the ones that were not
  invited: unknown machines need an armed two-minute window, and a known
  machine presenting the wrong token is rejected without losing its token.
- Add phone configuration for both providers.
