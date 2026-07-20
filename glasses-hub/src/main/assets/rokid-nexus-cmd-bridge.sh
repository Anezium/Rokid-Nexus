#!/system/bin/sh

NAME="rokid-nexus-cmd-bridge"
BASE="/data/local/tmp"
PIDFILE="$BASE/$NAME.pid"
LOGFILE="$BASE/$NAME.log"
HEARTBEAT="$BASE/$NAME.heartbeat"
VERSIONFILE="$BASE/$NAME.version"
SEENFILE="$BASE/$NAME.seen"
PENDING_DISABLE="$BASE/$NAME.pending-disable"
CHANNEL="/sdcard/Android/data/com.anezium.rokidbus.glasses/files/cmd_bridge"
DOORBELL="$CHANNEL/doorbell"
VERSION="2026-07-21.1"
SECRET="__ROKID_NEXUS_BRIDGE_SECRET_HEX__"
POLL_INTERVAL=1
DISABLE_DELAY=2
MAX_REQUEST_BYTES=512

rotate_log_if_needed() {
  if [ ! -f "$LOGFILE" ]; then
    return
  fi
  log_size="$(wc -c < "$LOGFILE" 2>/dev/null | tr -d '[:space:]')"
  case "$log_size" in
    ""|*[!0-9]*) log_size=0 ;;
  esac
  if [ "$log_size" -gt 65536 ]; then
    log_tmp="$LOGFILE.tmp.$$"
    if tail -n 50 "$LOGFILE" > "$log_tmp" 2>/dev/null; then
      mv "$log_tmp" "$LOGFILE"
    fi
    rm -f "$log_tmp"
  fi
}

log_line() {
  rotate_log_if_needed
  echo "$(date '+%Y-%m-%dT%H:%M:%S%z') $*" >> "$LOGFILE"
}

pid_cmdline() {
  tr '\000' ' ' < "/proc/$1/cmdline" 2>/dev/null
}

is_bridge_pid() {
  pid="$1"
  cmdline="$(pid_cmdline "$pid")"
  case "$cmdline" in
    *"$NAME.sh run"*|*"$(basename "$0") run"*|*"$0 run"*) return 0 ;;
    *) return 1 ;;
  esac
}

is_bridge_running() {
  if [ ! -f "$PIDFILE" ]; then
    return 1
  fi
  pid="$(cat "$PIDFILE" 2>/dev/null)"
  if [ -z "$pid" ]; then
    return 1
  fi
  kill -0 "$pid" 2>/dev/null && is_bridge_pid "$pid"
}

is_lower_hex() {
  value="$1"
  expected_length="$2"
  if [ "${#value}" -ne "$expected_length" ]; then
    return 1
  fi
  case "$value" in
    *[!0-9a-f]*) return 1 ;;
    *) return 0 ;;
  esac
}

prepare_channel() {
  if [ ! -d "$CHANNEL" ]; then
    mkdir -p "$CHANNEL" 2>/dev/null || return 1
  fi
  if [ -e "$DOORBELL" ] && [ ! -p "$DOORBELL" ]; then
    rm -f "$DOORBELL" 2>/dev/null || return 1
  fi
  if [ ! -p "$DOORBELL" ]; then
    mkfifo "$DOORBELL" 2>/dev/null || return 1
  fi
  return 0
}

write_response() {
  response_nonce="$1"
  response_status="$2"
  response_code="$3"
  response_file="$CHANNEL/$response_nonce.response"
  response_tmp="$CHANNEL/.$response_nonce.response.$$"
  if [ "$response_status" = "ok" ]; then
    response_line="$response_nonce:ok"
  else
    response_line="$response_nonce:error:$response_code"
  fi
  if printf '%s\n' "$response_line" > "$response_tmp" 2>/dev/null; then
    mv "$response_tmp" "$response_file" 2>/dev/null || rm -f "$response_tmp"
  else
    rm -f "$response_tmp"
  fi
}

nonce_seen() {
  [ -f "$SEENFILE" ] && grep -F -x "$1" "$SEENFILE" >/dev/null 2>&1
}

remember_nonce() {
  printf '%s\n' "$1" >> "$SEENFILE" 2>/dev/null || return 1
  return 0
}

schedule_wifi_disable() {
  disable_nonce="$1"
  printf '%s\n' "$disable_nonce" > "$PENDING_DISABLE" 2>/dev/null || return 1
  (
    sleep "$DISABLE_DELAY"
    if [ "$(cat "$PENDING_DISABLE" 2>/dev/null)" = "$disable_nonce" ]; then
      if svc wifi disable >/dev/null 2>&1; then
        log_line "command completed command=wifi_disable nonce=$disable_nonce"
      else
        log_line "command failed command=wifi_disable nonce=$disable_nonce"
      fi
      rm -f "$PENDING_DISABLE"
    fi
  ) >/dev/null 2>&1 &
  return 0
}

reject_request() {
  rejected_file="$1"
  rejected_nonce="$2"
  rejected_reason="$3"
  if is_lower_hex "$rejected_nonce" 32; then
    write_response "$rejected_nonce" error "$rejected_reason"
  fi
  log_line "request rejected reason=$rejected_reason nonce=${rejected_nonce:-unknown}"
  rm -f "$rejected_file"
}

process_request() {
  request_file="$1"
  request_name="${request_file##*/}"
  file_nonce="${request_name%.request}"

  if ! is_lower_hex "$file_nonce" 32; then
    reject_request "$request_file" "" filename
    return
  fi
  if [ -L "$request_file" ] || [ ! -f "$request_file" ]; then
    reject_request "$request_file" "$file_nonce" file_type
    return
  fi
  request_size="$(wc -c < "$request_file" 2>/dev/null | tr -d '[:space:]')"
  case "$request_size" in
    ""|*[!0-9]*) request_size=0 ;;
  esac
  if [ "$request_size" -lt 1 ] || [ "$request_size" -gt "$MAX_REQUEST_BYTES" ]; then
    reject_request "$request_file" "$file_nonce" size
    return
  fi
  safe_size="$(LC_ALL=C tr -cd '[:alnum:]_:+/=\n-' < "$request_file" 2>/dev/null | wc -c | tr -d '[:space:]')"
  newline_count="$(tr -cd '\n' < "$request_file" 2>/dev/null | wc -c | tr -d '[:space:]')"
  if [ "$safe_size" != "$request_size" ] || [ "$newline_count" != "1" ]; then
    reject_request "$request_file" "$file_nonce" characters
    return
  fi

  request_line="$(cat "$request_file" 2>/dev/null)"
  old_ifs="$IFS"
  IFS=':' read -r command nonce field3 field4 field5 field6 extra <<EOF
$request_line
EOF
  IFS="$old_ifs"
  if [ -n "$extra" ]; then
    reject_request "$request_file" "$file_nonce" format
    return
  fi
  case "$command" in
    wifi_enable|wifi_disable)
      token="$field3"
      if [ -n "$field4" ] || [ -n "$field5" ] || [ -n "$field6" ] ||
        [ "$request_line" != "$command:$nonce:$token" ]; then
        reject_request "$request_file" "$file_nonce" format
        return
      fi
      token_input="${SECRET}:${command}:${nonce}"
      ;;
    wifi_connect)
      ssid_encoded="$field3"
      passphrase_encoded="$field4"
      security="$field5"
      token="$field6"
      case "$ssid_encoded" in
        ""|*[!A-Za-z0-9+/=]*)
          reject_request "$request_file" "$file_nonce" format
          return
          ;;
      esac
      case "$passphrase_encoded" in
        *[!A-Za-z0-9+/=]*)
          reject_request "$request_file" "$file_nonce" format
          return
          ;;
      esac
      case "$security" in
        open|wpa2|wpa3) ;;
        *)
          reject_request "$request_file" "$file_nonce" format
          return
          ;;
      esac
      if [ -z "$token" ] ||
        [ "$request_line" != "$command:$nonce:$ssid_encoded:$passphrase_encoded:$security:$token" ]; then
        reject_request "$request_file" "$file_nonce" format
        return
      fi
      token_input="${SECRET}:${command}:${nonce}:${ssid_encoded}:${passphrase_encoded}:${security}"
      ;;
    *)
      reject_request "$request_file" "$file_nonce" command
      return
      ;;
  esac
  if [ "$nonce" != "$file_nonce" ] || ! is_lower_hex "$nonce" 32 || ! is_lower_hex "$token" 64; then
    reject_request "$request_file" "$file_nonce" format
    return
  fi
  if nonce_seen "$nonce"; then
    reject_request "$request_file" "$nonce" replay
    return
  fi

  # Android 11+ scoped storage already prevents other apps from writing this app-specific
  # channel. This prefix-keyed digest is defense-in-depth against a forged request file.
  # SHA-256 length extension cannot produce an accepted request because parsing requires the
  # exact fixed command:nonce form, only two literal commands are allowed, and an attacker
  # cannot write the channel directory in the first place.
  expected_token="$(printf '%s' "$token_input" | sha256sum | cut -d' ' -f1)"
  if [ -z "$expected_token" ] || [ "$expected_token" != "$token" ]; then
    reject_request "$request_file" "$nonce" auth
    return
  fi
  if ! remember_nonce "$nonce"; then
    reject_request "$request_file" "$nonce" replay_state
    return
  fi
  rm -f "$request_file"

  case "$command" in
    wifi_enable)
      rm -f "$PENDING_DISABLE"
      if svc wifi enable >/dev/null 2>&1; then
        write_response "$nonce" ok ""
        log_line "command completed command=wifi_enable nonce=$nonce"
      else
        write_response "$nonce" error command_failed
        log_line "command failed command=wifi_enable nonce=$nonce"
      fi
      ;;
    wifi_disable)
      if schedule_wifi_disable "$nonce"; then
        write_response "$nonce" ok ""
        log_line "command scheduled command=wifi_disable nonce=$nonce delay=$DISABLE_DELAY"
      else
        write_response "$nonce" error schedule_failed
        log_line "command failed command=wifi_disable nonce=$nonce reason=schedule"
      fi
      ;;
    wifi_connect)
      rm -f "$PENDING_DISABLE"
      if ! ssid="$(printf '%s' "$ssid_encoded" | base64 -d 2>/dev/null)" ||
        ! passphrase="$(printf '%s' "$passphrase_encoded" | base64 -d 2>/dev/null)"; then
        write_response "$nonce" error decode_failed
        log_line "command failed command=wifi_connect nonce=$nonce reason=decode"
      elif [ -z "$ssid" ] || [ "${#ssid}" -gt 128 ]; then
        write_response "$nonce" error credentials_invalid
        log_line "command failed command=wifi_connect nonce=$nonce reason=credentials"
      else
        case "$security" in
          open)
            if [ -n "$passphrase" ]; then
              write_response "$nonce" error credentials_invalid
              log_line "command failed command=wifi_connect nonce=$nonce reason=credentials"
            elif cmd wifi connect-network "$ssid" open >/dev/null 2>&1; then
              write_response "$nonce" ok ""
              log_line "command completed command=wifi_connect nonce=$nonce security=$security"
            else
              write_response "$nonce" error command_failed
              log_line "command failed command=wifi_connect nonce=$nonce security=$security"
            fi
            ;;
          wpa2|wpa3)
            if [ "${#passphrase}" -lt 8 ] || [ "${#passphrase}" -gt 128 ]; then
              write_response "$nonce" error credentials_invalid
              log_line "command failed command=wifi_connect nonce=$nonce reason=credentials"
            elif cmd wifi connect-network "$ssid" "$security" "$passphrase" >/dev/null 2>&1; then
              write_response "$nonce" ok ""
              log_line "command completed command=wifi_connect nonce=$nonce security=$security"
            else
              write_response "$nonce" error command_failed
              log_line "command failed command=wifi_connect nonce=$nonce security=$security"
            fi
            ;;
        esac
      fi
      ;;
  esac
}

process_requests() {
  for request_file in "$CHANNEL"/*.request; do
    [ -e "$request_file" ] || continue
    process_request "$request_file"
  done
}

wait_for_doorbell_or_poll() {
  if ! prepare_channel; then
    sleep "$POLL_INTERVAL"
    return
  fi
  (
    sleep "$POLL_INTERVAL"
    printf 'poll\n' > "$DOORBELL" 2>/dev/null
  ) &
  timer_pid="$!"
  IFS= read -r ignored < "$DOORBELL" 2>/dev/null || sleep "$POLL_INTERVAL"
  kill "$timer_pid" 2>/dev/null || true
  wait "$timer_pid" 2>/dev/null || true
}

cleanup_loop() {
  rm -f "$PIDFILE" "$DOORBELL"
  log_line "bridge loop stopped"
}

loop_forever() {
  case "$SECRET" in
    *[!0-9a-f]*) exit 3 ;;
  esac
  if [ "${#SECRET}" -ne 64 ]; then
    exit 3
  fi
  echo "$$" > "$PIDFILE"
  echo "$VERSION" > "$VERSIONFILE"
  log_line "bridge loop started pid=$$ pollInterval=$POLL_INTERVAL"
  trap 'cleanup_loop; exit 0' INT TERM EXIT
  while true; do
    echo "$(date '+%s')" > "$HEARTBEAT"
    process_requests
    wait_for_doorbell_or_poll
  done
}

start_bridge() {
  if is_bridge_running; then
    echo "running pid=$(cat "$PIDFILE" 2>/dev/null) version=$VERSION"
    exit 0
  fi
  prepare_channel >/dev/null 2>&1 || true
  nohup sh "$0" run >/dev/null 2>&1 &
  echo "$!" > "$PIDFILE"
  echo "$VERSION" > "$VERSIONFILE"
  log_line "bridge start requested pid=$!"
  echo "started pid=$! version=$VERSION"
}

stop_bridge() {
  if is_bridge_running; then
    pid="$(cat "$PIDFILE" 2>/dev/null)"
    kill "$pid" 2>/dev/null
    sleep 1
    if is_bridge_pid "$pid"; then
      kill -9 "$pid" 2>/dev/null
    fi
    log_line "bridge stop requested pid=$pid"
  elif [ -f "$PIDFILE" ]; then
    pid="$(cat "$PIDFILE" 2>/dev/null)"
    log_line "bridge pidfile stale or foreign pid=$pid cmdline=$(pid_cmdline "$pid")"
  fi
  rm -f "$PIDFILE" "$DOORBELL"
  echo "stopped version=$VERSION"
}

status_bridge() {
  if is_bridge_running; then
    running="yes"
    pid="$(cat "$PIDFILE" 2>/dev/null)"
  else
    running="no"
    pid=""
  fi
  echo "name=$NAME"
  echo "version=$VERSION"
  echo "running=$running"
  echo "pid=$pid"
  echo "heartbeat=$(cat "$HEARTBEAT" 2>/dev/null)"
}

case "$1" in
  start|"")
    start_bridge
    ;;
  stop)
    stop_bridge
    ;;
  restart)
    stop_bridge
    start_bridge
    ;;
  status)
    status_bridge
    ;;
  run)
    loop_forever
    ;;
  *)
    echo "usage: $0 {start|stop|restart|status}"
    exit 2
    ;;
esac
