#!/bin/bash
# Registers the Nexus agent daemon to start at login on macOS, the counterpart
# to run-hidden.vbs plus schtasks on Windows. The daemon refuses to
# double-start (port 8791 already bound), so re-running this is harmless.
#
#   ./scripts/install-launchagent.sh            # install and start
#   ./scripts/install-launchagent.sh --uninstall # stop and remove
set -euo pipefail

LABEL="com.anezium.nexus-agentd"
PLIST="$HOME/Library/LaunchAgents/$LABEL.plist"
AGENT_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"

if [[ "${1:-}" == "--uninstall" ]]; then
  launchctl bootout "gui/$(id -u)/$LABEL" 2>/dev/null || true
  rm -f "$PLIST"
  echo "Removed $LABEL"
  exit 0
fi

# The LaunchAgent environment is not a login shell, so node is resolved here
# rather than relying on a PATH the daemon will not inherit.
NODE_BIN="$(command -v node || true)"
if [[ -z "$NODE_BIN" ]]; then
  echo "node was not found on PATH; install Node.js 20 or newer first" >&2
  exit 1
fi

# launchd hands the job PATH=/usr/bin:/bin:/usr/sbin:/sbin, which is where the
# daemon then looks for the Claude CLI when a wearer answers a session from
# their glasses. Homebrew and npm both install it outside those four
# directories, so without this the reply comes back "Claude Code CLI not found
# on this computer" while it sits happily on the shell's own PATH.
AGENT_PATH="/usr/bin:/bin:/usr/sbin:/sbin"
CLAUDE_BIN="$(command -v claude || true)"
for extra in "$(dirname "$NODE_BIN")" "${CLAUDE_BIN:+$(dirname "$CLAUDE_BIN")}"; do
  case ":$AGENT_PATH:" in
    *":$extra:"*) ;;
    *) [[ -n "$extra" ]] && AGENT_PATH="$extra:$AGENT_PATH" ;;
  esac
done
if [[ -z "$CLAUDE_BIN" ]]; then
  echo "note: the claude CLI is not on this shell's PATH, so answering a session" >&2
  echo "      from the glasses will fail until it is installed." >&2
fi

if [[ ! -f "$AGENT_ROOT/dist/cli.js" ]]; then
  echo "dist/cli.js is missing; run 'npm install && npm run build' first" >&2
  exit 1
fi

# macOS privacy (TCC) gates Documents, Desktop, Downloads and iCloud Drive per
# application. A terminal inherits the consent its own app was granted, but a
# LaunchAgent has no app to attribute the access to and no way to raise the
# prompt: node then blocks on the first read under such a folder and the daemon
# hangs before it binds a port, with nothing written to either log. Measured on
# macOS 15 with Homebrew node — a plain /bin/bash agent reads the same path
# fine, so this is specific to the third-party interpreter.
case "$AGENT_ROOT/" in
  "$HOME"/Documents/*|"$HOME"/Desktop/*|"$HOME"/Downloads/*|"$HOME"/Library/Mobile\ Documents/*)
    # Probing beats assuming: Full Disk Access on the interpreter lifts the
    # restriction, and refusing outright would then block an install that works.
    probe_label="com.anezium.nexus-agentd.tccprobe"
    # /tmp and a literal .plist suffix: launchctl answers "Bootstrap failed: 5"
    # both for a plist in the per-user /var/folders temp directory and for one
    # whose filename does not end in .plist.
    probe_plist="$(mktemp /tmp/nexus-agentd-tccprobe.XXXXXX)"
    mv "$probe_plist" "$probe_plist.plist"
    probe_plist="$probe_plist.plist"
    probe_out="$(mktemp /tmp/nexus-agentd-tccout.XXXXXX)"
    cat > "$probe_plist" <<PROBE_EOF
<?xml version="1.0" encoding="UTF-8"?>
<!DOCTYPE plist PUBLIC "-//Apple//DTD PLIST 1.0//EN" "http://www.apple.com/DTDs/PropertyList-1.0.dtd">
<plist version="1.0">
<dict>
  <key>Label</key><string>$probe_label</string>
  <key>ProgramArguments</key>
  <array>
    <string>$NODE_BIN</string>
    <string>-e</string>
    <string>require('fs').readFileSync(process.argv[1]);console.log('READABLE')</string>
    <string>$AGENT_ROOT/dist/cli.js</string>
  </array>
  <key>RunAtLoad</key><true/>
  <key>StandardOutPath</key><string>$probe_out</string>
</dict>
</plist>
PROBE_EOF
    launchctl bootout "gui/$(id -u)/$probe_label" 2>/dev/null || true
    launchctl bootstrap "gui/$(id -u)" "$probe_plist" 2>/dev/null || true
    for _ in 1 2 3 4 5 6 7 8 9 10; do
      grep -q READABLE "$probe_out" 2>/dev/null && break
      sleep 1
    done
    launchctl bootout "gui/$(id -u)/$probe_label" 2>/dev/null || true
    if ! grep -q READABLE "$probe_out" 2>/dev/null; then
      rm -f "$probe_plist" "$probe_out"
      cat >&2 <<TCC_EOF
$AGENT_ROOT is inside a macOS privacy-protected folder that this LaunchAgent
cannot read: the daemon would start and hang instead of listening, writing
nothing to either log.

Pick one:
  1. Grant Full Disk Access to $NODE_BIN in
     System Settings > Privacy & Security > Full Disk Access, then re-run this.
  2. Move or clone agentd somewhere outside Documents, Desktop, Downloads and
     iCloud Drive (for example ~/nexus-agentd), rebuild there, and re-run this.

Nothing was installed.
TCC_EOF
      exit 1
    fi
    rm -f "$probe_plist" "$probe_out"
    ;;
esac

mkdir -p "$HOME/Library/LaunchAgents"
cat > "$PLIST" <<PLIST_EOF
<?xml version="1.0" encoding="UTF-8"?>
<!DOCTYPE plist PUBLIC "-//Apple//DTD PLIST 1.0//EN" "http://www.apple.com/DTDs/PropertyList-1.0.dtd">
<plist version="1.0">
<dict>
  <key>Label</key>
  <string>$LABEL</string>
  <key>ProgramArguments</key>
  <array>
    <string>$NODE_BIN</string>
    <string>$AGENT_ROOT/dist/cli.js</string>
    <string>run</string>
  </array>
  <key>WorkingDirectory</key>
  <string>$AGENT_ROOT</string>
  <key>EnvironmentVariables</key>
  <dict>
    <key>PATH</key>
    <string>$AGENT_PATH</string>
  </dict>
  <key>RunAtLoad</key>
  <true/>
  <!-- No KeepAlive, matching the Windows "schtasks /sc onlogon" behaviour: a
       second instance exits 1 on EADDRINUSE, which launchd would otherwise
       respawn in a tight loop for as long as the first one holds the port. -->
  <key>ProcessType</key>
  <string>Background</string>
  <key>StandardOutPath</key>
  <string>$HOME/.nexus-agentd/launchagent.out.log</string>
  <key>StandardErrorPath</key>
  <string>$HOME/.nexus-agentd/launchagent.err.log</string>
</dict>
</plist>
PLIST_EOF

mkdir -p "$HOME/.nexus-agentd"
launchctl bootout "gui/$(id -u)/$LABEL" 2>/dev/null || true
# bootout returns before launchd has finished releasing the label, and
# bootstrapping into the gap fails with "Bootstrap failed: 5: Input/output
# error". Wait for the old job to actually go away.
for _ in 1 2 3 4 5 6 7 8 9 10; do
  launchctl print "gui/$(id -u)/$LABEL" >/dev/null 2>&1 || break
  sleep 1
done
launchctl bootstrap "gui/$(id -u)" "$PLIST"
echo "Installed $LABEL ($PLIST)"
echo "Check it with: node dist/cli.js status"
cat <<'NET_EOF'

If the daemon runs but never links the phone — the log fills with
discovery_send_failed or phone_link_error and approvals fall back to the
computer — macOS is withholding local network access from it. Enable the
interpreter under System Settings > Privacy & Security > Local Network. A
terminal has that consent already, which is why a manual run can work while
this one does not.
NET_EOF
