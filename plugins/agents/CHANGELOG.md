# Changelog

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
- Pair a kittylitter node by scanning `kittylitter pair --qr` (or pasting the
  same JSON). The bearer token lives in Keystore-backed encrypted storage,
  is never shown again, never logged, and never sent to the glasses. Auth
  failure from `kittylitter rotate` asks for a re-pair instead of retrying.
- Talk to Alleycat over Iroh (`computer.iroh:iroh-android`, ALPN `alleycat/1`,
  u32+JSON framing) and reuse the Codex app-server session layer after
  `connect`. Direct `ws(s)://` remains a supported Add computer option.
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
