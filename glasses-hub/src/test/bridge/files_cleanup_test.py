"""Run the real bridge with mocked Android commands; never uses ADB or a device."""
import base64
import hashlib
import os
from pathlib import Path
import subprocess
import tempfile
import unittest

ROOT = Path(__file__).resolve().parents[4]
ASSET = ROOT / "glasses-hub/src/main/assets/rokid-nexus-cmd-bridge.sh"
BASH = r"C:\Program Files\Git\bin\bash.exe" if os.name == "nt" else "/bin/bash"
SECRET = "0" * 64
SOURCE = ASSET.read_text(encoding="utf-8").rsplit('case "$1" in', 1)[0].replace(
    "__ROKID_NEXUS_BRIDGE_SECRET_HEX__", SECRET)
HEADER = "Network Id      SSID                         Security type\n"


def row(network_id, ssid):
    return f"{network_id:<12} {ssid:<32} WPA2\n"


class FilesCleanupTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory(prefix="nexus-files-bridge-")
        self.directory = Path(self.temp.name).resolve()
        self.addCleanup(self.temp.cleanup)
        self.counter = 0
        self.write("networks", HEADER + row(1, "AndroidShare_camera") + row(2, "HomeTest"))
        self.write("status", "Wifi is not connected\n")
        self.write("now", "1000")
        self.write("api", "32")

    def write(self, name, value):
        (self.directory / name).write_text(value, encoding="utf-8", newline="\n")

    def run_bridge(self, body):
        script = SOURCE + r'''
CHANNEL='__DIR__'
FILES_NETWORKS="$CHANNEL/files-networks"
SEENFILE="$CHANNEL/seen"
LOGFILE="$CHANNEL/log"
PENDING_DISABLE="$CHANNEL/disable"
getprop() { cat "$CHANNEL/api"; }
date() {
  if [ "$1" = +%s ]; then cat "$CHANNEL/now"; else command date "$@"; fi
}
sleep() { :; }
ip() { printf '    inet 192.0.2.2/24\n'; }
svc() { return 99; }
cmd() {
  printf '%s\n' "$2" >> "$CHANNEL/calls"
  case "$2" in
    list-networks) cat "$CHANNEL/networks" ;;
    status) cat "$CHANNEL/status" ;;
    start-scan) : ;;
    list-scan-results)
      printf 'BSSID Frequency RSSI Age(sec) SSID Flags\n'
      printf 'synthetic 5180 -40 0.100 BatchTest [ESS]\n' ;;
    connect-network)
      if ! awk '$1 == 7 { found=1 } END { exit !found }' "$CHANNEL/networks"; then
        printf '%-12d %-32s WPA2\n' 7 BatchTest >> "$CHANNEL/networks"
      fi
      printf 'Wifi is connected to "BatchTest"\nWifiInfo: Net ID: 7, Supplicant state: COMPLETED\n' > "$CHANNEL/status"
      [ ! -f "$CHANNEL/fail-connect" ] ;;
    forget-network)
      printf '%s\n' "$3" >> "$CHANNEL/forgotten"
      if [ ! -f "$CHANNEL/fail-forget" ]; then
        awk -v target="$3" 'NR == 1 || $1 != target' "$CHANNEL/networks" > "$CHANNEL/rows.tmp"
        mv "$CHANNEL/rows.tmp" "$CHANNEL/networks"
      fi ;;
    *) return 99 ;;
  esac
}
'''.replace("__DIR__", self.directory.as_posix()) + body
        result = subprocess.run([BASH, "--noprofile", "--norc"], input=script, text=True,
                                encoding="utf-8", capture_output=True, timeout=15)
        self.assertEqual(0, result.returncode, result.stderr + result.stdout)
        return result.stdout.strip()

    def request(self, command, arguments=()):
        self.counter += 1
        nonce = f"{self.counter:032x}"
        token = hashlib.sha256(":".join([SECRET, command, nonce, *arguments]).encode()).hexdigest()
        self.write(f"{nonce}.request", ":".join([command, nonce, *arguments, token]) + "\n")
        suffix = "connect-result" if command == "wifi_connect_files" else "files-result"
        self.write(f"{nonce}.{suffix}", "pending\n")
        return self.run_bridge(f'process_request "$CHANNEL/{nonce}.request"\ncat "$CHANNEL/{nonce}.{suffix}"\n')

    def connect(self):
        return self.request("wifi_connect_files", [base64.b64encode(b"BatchTest").decode(),
                                                  base64.b64encode(b"only-test-value").decode(), "wpa2"])

    def forgotten(self):
        path = self.directory / "forgotten"
        return path.read_text(encoding="utf-8").splitlines() if path.exists() else []

    def test_process_restart_recovers_only_created_numeric_ids(self):
        self.assertEqual("ok", self.connect())
        self.assertEqual("7 1000\n", (self.directory / "files-networks").read_text(encoding="utf-8"))
        self.write("status", "WifiInfo: Net ID: 2, Supplicant state: COMPLETED\n")
        # A fresh shell has no app or previous request state, only the numeric record.
        self.assertEqual("ok", self.request("wifi_cleanup_files"))
        self.assertEqual(["7"], self.forgotten())
        self.assertFalse((self.directory / "files-networks").exists())
        self.assertIn("AndroidShare_camera", (self.directory / "networks").read_text(encoding="utf-8"))
        self.assertEqual("ok", self.request("wifi_cleanup_files"))
        self.assertEqual(["7"], self.forgotten())

    def test_current_network_is_retained_until_disconnected(self):
        self.assertEqual("ok", self.connect())
        self.assertEqual("error", self.request("wifi_cleanup_files"))
        self.assertEqual([], self.forgotten())
        self.write("status", "Wifi is not connected\n")
        self.assertEqual("ok", self.request("wifi_cleanup_files"))
        self.assertEqual(["7"], self.forgotten())

    def test_failed_association_still_records_the_saved_network(self):
        self.write("fail-connect", "1")
        self.assertEqual("error", self.connect())
        self.assertEqual("7 1000\n", (self.directory / "files-networks").read_text(encoding="utf-8"))
        self.write("status", "Wifi is disabled\n")
        self.assertEqual("ok", self.request("wifi_cleanup_files"))

    def test_existing_camera_or_user_ssid_is_not_adopted(self):
        self.write("networks", HEADER + row(5, "BatchTest"))
        self.assertEqual("error", self.connect())
        self.assertEqual("ok", self.request("wifi_cleanup_files"))
        self.assertEqual([], self.forgotten())
        self.assertNotIn("connect-network", (self.directory / "calls").read_text(encoding="utf-8"))

    def test_expired_files_lease_releases_its_own_current_id(self):
        self.assertEqual("ok", self.connect())
        self.write("now", "87400")
        self.assertEqual("ok", self.request("wifi_cleanup_files"))
        self.assertEqual(["7"], self.forgotten())

    def test_unknown_status_and_listing_fail_closed(self):
        self.assertEqual("ok", self.connect())
        self.write("status", "unknown\n")
        self.assertEqual("error", self.request("wifi_cleanup_files"))
        self.write("networks", "unknown\n")
        self.assertEqual("error", self.request("wifi_cleanup_files"))
        self.assertEqual([], self.forgotten())
        self.assertTrue((self.directory / "files-networks").exists())

    def test_failed_forget_retains_obligation_for_next_restart(self):
        self.assertEqual("ok", self.connect())
        self.write("status", "Wifi is not connected\n")
        self.write("fail-forget", "1")
        self.assertEqual("error", self.request("wifi_cleanup_files"))
        self.assertTrue((self.directory / "files-networks").exists())
        (self.directory / "fail-forget").unlink()
        self.assertEqual("ok", self.request("wifi_cleanup_files"))
        self.assertFalse((self.directory / "files-networks").exists())

    def test_other_api_and_extra_argument_are_rejected(self):
        self.write("api", "33")
        self.assertEqual("error", self.request("wifi_cleanup_files"))
        self.assertEqual("error", self.connect())
        self.assertFalse((self.directory / "calls").exists())
        self.assertEqual("pending", self.request("wifi_cleanup_files", ["7"]))

    def test_record_count_is_bounded_and_invalid_records_fail_closed(self):
        self.write("networks", HEADER + "".join(row(i, f"Test{i}") for i in range(8)))
        self.write("files-networks", "".join(f"{i} 1000\n" for i in range(8)))
        self.write("status", "unknown\n")
        self.assertEqual("error", self.connect())
        self.assertNotIn("connect-network", (self.directory / "calls").read_text(encoding="utf-8"))
        self.write("files-networks", "not numeric\n")
        self.assertEqual("error", self.request("wifi_cleanup_files"))
        self.assertEqual([], self.forgotten())

    def test_logs_and_record_contain_no_wifi_credentials(self):
        self.assertEqual("ok", self.connect())
        for name in ["files-networks", "log"]:
            value = (self.directory / name).read_text(encoding="utf-8")
            self.assertNotIn("BatchTest", value)
            self.assertNotIn("only-test-value", value)

    def test_camera_execution_and_update_flow_are_unchanged(self):
        previous = subprocess.check_output(["git", "show", "139df523:glasses-hub/src/main/assets/rokid-nexus-cmd-bridge.sh"], cwd=ROOT).decode("utf-8")
        current = ASSET.read_text(encoding="utf-8")
        camera = lambda text: text[text.rindex("    wifi_connect)"):text.index("\nprocess_requests()")]
        update = lambda text: text[text.index("self_update_if_needed() {"):text.index("\nloop_forever()")]
        self.assertEqual(camera(previous), camera(current))
        self.assertEqual(update(previous), update(current))
        syntax = subprocess.run([BASH, "-n"], input=current, text=True, encoding="utf-8", capture_output=True)
        self.assertEqual(0, syntax.returncode, syntax.stderr)


if __name__ == "__main__":
    unittest.main(verbosity=2)
