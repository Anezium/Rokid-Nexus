"""Exercise the real bridge parser with pm, app_process and process identity mocked."""
import hashlib
import unittest
import files_cleanup_test as fixture


class AudioCaptureTest(unittest.TestCase):
    def setUp(self):
        self.helper = fixture.FilesCleanupTest("runTest")
        self.helper.setUp()
        self.addCleanup(self.helper.doCleanups)

    def run_request(self, command, arguments=(), nonce="1" * 32, mode="term", cmdline="rokid-nexus-audio "):
        token = hashlib.sha256(":".join([fixture.SECRET, command, nonce, *arguments]).encode()).hexdigest()
        self.helper.write(f"{nonce}.request", ":".join([command, nonce, *arguments, token]) + "\n")
        self.helper.write("cmdline", cmdline)
        self.helper.write("mode", mode)
        if (self.helper.directory / "audio.pid").exists():
            self.helper.write("alive", "1")
        return self.helper.run_bridge(r'''
AUDIO_PIDFILE="$CHANNEL/audio.pid"
pid_cmdline() { cat "$CHANNEL/cmdline"; }
audio_capture_pid_exists() { [ -f "$CHANNEL/alive" ]; }
sleep() { echo tick >> "$CHANNEL/sleeps"; }
kill() {
  printf '%s\n' "$*" >> "$CHANNEL/killed"
  if [ "$(cat "$CHANNEL/mode")" = term ] ||
    { [ "$1" = -9 ] && [ "$(cat "$CHANNEL/mode")" != immortal ]; }; then
    rm -f "$CHANNEL/alive"
  fi
}
pm() { printf 'package:/installed/test.apk\n'; }
nohup() { "$@"; }
app_process() {
  printf '%s\n' "$CLASSPATH" "$NEXUS_AUDIO_TOKEN" "$@" > "$CHANNEL/spawn"
}
''' + f'process_request "$CHANNEL/{nonce}.request"\nwait\ncat "$CHANNEL/{nonce}.response"\n')

    def test_start_kills_stale_pid_and_spawns_expected_arguments_and_environment(self):
        self.helper.write("audio.pid", "123\n")
        self.assertTrue(self.run_request("audio_capture_start", ["47000"]).endswith(":ok"))
        self.assertEqual("123\n", (self.helper.directory / "killed").read_text(encoding="utf-8"))
        spawn = (self.helper.directory / "spawn").read_text(encoding="utf-8").splitlines()
        expected_token = hashlib.sha256(f'{fixture.SECRET}:audio-session:{"1" * 32}'.encode()).hexdigest()
        self.assertEqual(["/installed/test.apk", expected_token, "/system/bin", "--nice-name=rokid-nexus-audio",
                          "com.anezium.rokidbus.glasses.audio.GlassesAudioCaptureMain", "47000", "1" * 32], spawn)

    def test_stop_is_idempotent_and_replay_and_invalid_ports_are_rejected(self):
        self.helper.write("audio.pid", "123\n")
        self.assertTrue(self.run_request("audio_capture_stop").endswith(":ok"))
        self.assertTrue(self.run_request("audio_capture_stop", nonce="2" * 32).endswith(":ok"))
        self.assertTrue(self.run_request("audio_capture_stop").endswith(":error:replay"))
        for index, port in enumerate(["0", "70000", "nope", "1023"], start=3):
            self.assertTrue(self.run_request("audio_capture_start", [port], nonce=f"{index:032x}").endswith(":error:format"))
        self.assertFalse((self.helper.directory / "spawn").exists())

    def test_stop_does_not_kill_a_reused_pid(self):
        self.helper.write("audio.pid", "999\n")
        self.assertIn(":error:", self.run_request("audio_capture_start", ["47000"], cmdline="rokid-nexus-audio unrelated"))
        self.assertFalse((self.helper.directory / "killed").exists())
        self.assertFalse((self.helper.directory / "spawn").exists())
        self.assertEqual("999\n", (self.helper.directory / "audio.pid").read_text(encoding="utf-8"))

    def test_term_ignored_capture_is_killed_before_pidfile_is_removed(self):
        self.helper.write("audio.pid", "123\n")
        self.assertTrue(self.run_request("audio_capture_stop", mode="ignore-term", cmdline="rokid-nexus-audio     ").endswith(":ok"))
        self.assertEqual(["123", "-9 123"], (self.helper.directory / "killed").read_text(encoding="utf-8").splitlines())
        self.assertEqual(20, len((self.helper.directory / "sleeps").read_text(encoding="utf-8").splitlines()))
        self.assertFalse((self.helper.directory / "audio.pid").exists())
        self.assertFalse((self.helper.directory / "alive").exists())

    def test_process_that_survives_kill_prevents_pidfile_overwrite_and_replacement(self):
        self.helper.write("audio.pid", "123\n")
        self.assertIn(":error:", self.run_request("audio_capture_start", ["47000"], mode="immortal"))
        self.assertEqual("123\n", (self.helper.directory / "audio.pid").read_text(encoding="utf-8"))
        self.assertFalse((self.helper.directory / "spawn").exists())


if __name__ == "__main__":
    unittest.main()
