#!/bin/sh
# Run with bash/sh and pass the watchdog asset path. Android services are mocked.
set -eu
SCRIPT="$1"
TEST_DIR="$(mktemp -d)"
trap 'rm -rf "$TEST_DIR"' EXIT
export TEST_DIR
printf '100.0 0.0\n' > "$TEST_DIR/uptime"
echo 1 > "$TEST_DIR/armed"
installed=1
metadata=2000:755
settings() {
  case "$*" in
    'get global boot_count') echo 7 ;;
    'get global r08_access_bridge_armed') cat "$TEST_DIR/armed" ;;
    'put global rokid_nexus_r08_watchdog '*) echo "$4" > "$TEST_DIR/marker" ;;
    *) echo "Unexpected settings mutation: $*" >&2; exit 1 ;;
  esac
}
pm() {
  [ "$*" = 'list packages -e com.anezium.r08accessbridge' ]
  [ "$installed" = 0 ] || echo 'package:com.anezium.r08accessbridge'
}
stat() { echo "$metadata"; }
log_line() { echo "$*" >> "$TEST_DIR/log"; }
sed -n '/^recover_r08()/,/^)/p' "$SCRIPT" |
  sed "s#/data/local/tmp/#$TEST_DIR/#g; s#/proc/uptime#$TEST_DIR/uptime#g" > "$TEST_DIR/function.sh"
. "$TEST_DIR/function.sh"
for name in r08-shortcut-bridge r08-a11y-watchdog; do
  cat > "$TEST_DIR/$name.sh" <<'HELPER'
#!/bin/sh
[ "$1" = start ] || exit 1
name="${0##*/}"
if [ -f "$TEST_DIR/$name.running" ]; then
  echo 'already running pid=123'
else
  touch "$TEST_DIR/$name.running"
  echo "$name" >> "$TEST_DIR/starts"
  echo 'started pid=123'
fi
HELPER
done
recover_r08
[ "$(cat "$TEST_DIR/marker")" = '1:7:100' ]
[ "$(wc -l < "$TEST_DIR/starts" | tr -d ' ')" = 2 ]
recover_r08
[ "$(wc -l < "$TEST_DIR/starts" | tr -d ' ')" = 2 ]
echo 'PASS: both helpers restored, repeated recovery preserves running helpers'

rm "$TEST_DIR/starts" "$TEST_DIR/"*.running
echo 0 > "$TEST_DIR/armed"
recover_r08
[ ! -f "$TEST_DIR/starts" ]
echo 1 > "$TEST_DIR/armed"
installed=0
recover_r08
[ ! -f "$TEST_DIR/starts" ]
installed=1
mv "$TEST_DIR/r08-a11y-watchdog.sh" "$TEST_DIR/saved.sh"
recover_r08
[ ! -f "$TEST_DIR/starts" ]
mv "$TEST_DIR/saved.sh" "$TEST_DIR/r08-a11y-watchdog.sh"
echo 'PASS: disarmed, uninstalled or incomplete setup never starts helpers'

for metadata in 10001:755 2000:777 2000:775 ''; do
  recover_r08
  [ ! -f "$TEST_DIR/starts" ]
done
metadata=2000:755
mv "$TEST_DIR/r08-a11y-watchdog.sh" "$TEST_DIR/saved.sh"
ln -s "$TEST_DIR/saved.sh" "$TEST_DIR/r08-a11y-watchdog.sh"
recover_r08
[ ! -f "$TEST_DIR/starts" ]
echo 'PASS: foreign-owned, writable and symlink scripts rejected'

# Any additional settings mutation fails above. The function never invokes ADB or Wi-Fi.
echo 'PASS: only the capability heartbeat is published'
