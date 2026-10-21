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
VERSION="2026-10-21.3"
CAPTURE_DIR="/sdcard/DCIM/Camera"
SCRIPT_PATH="$BASE/$NAME.sh"
PACKAGE="com.anezium.rokidbus.glasses"
ASSET_PATH="assets/rokid-nexus-cmd-bridge.sh"
# Split so the literal never appears twice in this file: the app renders the script by replacing
# the one secret slot and refuses an asset that carries more than a single occurrence.
PLACEHOLDER="__ROKID_NEXUS_BRIDGE_""SECRET_HEX__"
SELF_UPDATE_INTERVAL=300
SELF_UPDATE_SETTLE=3
SECRET="__ROKID_NEXUS_BRIDGE_SECRET_HEX__"
MAINTENANCE_INTERVAL=30
REQUEST_SCAN_INTERVAL=1
FALLBACK_POLL_INTERVAL=1
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

discard_foreign_channel() {
  # The channel must belong to the app: under FUSE the app cannot write into, nor delete, a
  # directory this uid created inside its own data dir, so a shell-owned channel silently kills
  # every command forever. Only this uid can clear it, and the app recreates it on its next
  # request.
  [ -d "$CHANNEL" ] || return 0
  channel_owner="$(stat -c %u "$CHANNEL" 2>/dev/null)"
  files_owner="$(stat -c %u "$CHANNEL/.." 2>/dev/null)"
  [ -n "$channel_owner" ] && [ -n "$files_owner" ] || return 0
  [ "$channel_owner" = "$files_owner" ] && return 0
  log_line "channel owned by $channel_owner not $files_owner; clearing for the app to recreate"
  rm -rf "$CHANNEL" 2>/dev/null
  return 0
}

prepare_channel() {
  discard_foreign_channel
  [ -d "$CHANNEL" ] || return 1
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

matching_forget_network_ids() {
  forget_networks="$(cmd wifi list-networks 2>/dev/null)" || return 1
  # API 32 uses fixed-width ID/SSID columns. Compare the whole SSID column, never a prefix,
  # glob or whitespace-separated word; an unfamiliar listing must fail closed.
  printf '%s\n' "$forget_networks" | NEXUS_FORGET_SSID="$ssid" awk '
    NR == 1 {
      if ($0 == "No networks") { empty = 1; next }
      if ($0 != "Network Id      SSID                         Security type") exit 1
      header = 1
      next
    }
    {
      if (!header || length($0) < 47 || substr($0, 1, 12) !~ /^[0-9]+ *$/ ||
          substr($0, 13, 1) != " " || substr($0, 46, 1) != " ") exit 1
      if (substr($0, 14, 32) == sprintf("%-32s", ENVIRON["NEXUS_FORGET_SSID"])) {
        id = substr($0, 1, 12)
        sub(/ +$/, "", id)
        print id
      }
    }
    END { if (!header && !empty) exit 1 }
  '
}

wifi_not_on_forget_network() {
  forget_status="$(cmd wifi status 2>/dev/null)" || return 1
  if printf '%s\n' "$forget_status" | grep -F -x 'Wifi is disabled' >/dev/null 2>&1 ||
    printf '%s\n' "$forget_status" | grep -F -x 'Wifi is not connected' >/dev/null 2>&1; then
    return 0
  fi
  forget_current_id="$(printf '%s\n' "$forget_status" |
    sed -n 's/.*Net ID: \([0-9][0-9]*\).*/\1/p')"
  case "$forget_current_id" in
    ""|*[!0-9]*) return 1 ;;
  esac
  for forget_saved_id in $forget_ids; do
    [ "$forget_saved_id" != "$forget_current_id" ] || return 1
  done
  return 0
}

forget_wifi_network() {
  [ "$(getprop ro.build.version.sdk 2>/dev/null)" = "32" ] || return 1
  ssid="$(printf '%s' "$ssid_encoded" | base64 -d 2>/dev/null)" || return 1
  forget_ssid_bytes="$(printf '%s' "$ssid" | wc -c | tr -d '[:space:]')"
  [ -n "$ssid" ] && [ "$forget_ssid_bytes" -le 32 ] || return 1
  # Round-tripping rejects embedded NULs and stripped newlines as well as malformed base64.
  [ "$(printf '%s' "$ssid" | base64 | tr -d '\n')" = "$ssid_encoded" ] || return 1
  [ "$(printf '%s' "$ssid" | LC_ALL=C tr -d '[:cntrl:]')" = "$ssid" ] || return 1
  forget_ids="$(matching_forget_network_ids)" || return 1
  for forget_id in $forget_ids; do
    case "$forget_id" in ""|*[!0-9]*) return 1 ;; esac
    forget_ids="$(matching_forget_network_ids)" || return 1
    printf '%s\n' "$forget_ids" | grep -F -x "$forget_id" >/dev/null 2>&1 || return 1
    wifi_not_on_forget_network || return 1
    cmd wifi forget-network "$forget_id" >/dev/null 2>&1 || return 1
  done
  # The API-32 command can return zero even when its asynchronous action failed.
  forget_ids="$(matching_forget_network_ids)" || return 1
  [ -z "$forget_ids" ]
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
    wifi_enable|wifi_disable|adb_wifi_enable|adb_wifi_disable)
      token="$field3"
      if [ -n "$field4" ] || [ -n "$field5" ] || [ -n "$field6" ] ||
        [ "$request_line" != "$command:$nonce:$token" ]; then
        reject_request "$request_file" "$file_nonce" format
        return
      fi
      token_input="${SECRET}:${command}:${nonce}"
      ;;
    wifi_connect|wifi_connect_files)
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
    wifi_forget)
      ssid_encoded="$field3"
      token="$field4"
      case "$ssid_encoded" in
        ""|*[!A-Za-z0-9+/=]*)
          reject_request "$request_file" "$file_nonce" format
          return
          ;;
      esac
      if [ "${#ssid_encoded}" -gt 44 ] || [ -n "$field5" ] || [ -n "$field6" ] ||
        [ -z "$token" ] || [ "$request_line" != "$command:$nonce:$ssid_encoded:$token" ]; then
        reject_request "$request_file" "$file_nonce" format
        return
      fi
      token_input="${SECRET}:${command}:${nonce}:${ssid_encoded}"
      ;;
    delete_capture)
      name_encoded="$field3"
      token="$field4"
      case "$name_encoded" in
        ""|*[!A-Za-z0-9+/=]*)
          reject_request "$request_file" "$file_nonce" format
          return
          ;;
      esac
      if [ -n "$field5" ] || [ -n "$field6" ] || [ -z "$token" ] ||
        [ "$request_line" != "$command:$nonce:$name_encoded:$token" ]; then
        reject_request "$request_file" "$file_nonce" format
        return
      fi
      token_input="${SECRET}:${command}:${nonce}:${name_encoded}"
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
  # exact fixed command shapes, only literal commands are allowed, and an attacker
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
    wifi_connect_files)
      connect_result=error
      if [ "$(getprop ro.build.version.sdk 2>/dev/null)" = "32" ] &&
        ssid="$(printf '%s' "$ssid_encoded" | base64 -d 2>/dev/null)" &&
        passphrase="$(printf '%s' "$passphrase_encoded" | base64 -d 2>/dev/null)" &&
        [ -n "$ssid" ] && [ "${#ssid}" -le 32 ] &&
        ! printf '%s%s' "$ssid" "$passphrase" | LC_ALL=C grep -q '[[:cntrl:]]'; then
        case "$security" in
          open) [ -z "$passphrase" ] && connect_valid=1 || connect_valid=0 ;;
          wpa2|wpa3) [ "${#passphrase}" -ge 8 ] && [ "${#passphrase}" -le 128 ] && connect_valid=1 || connect_valid=0 ;;
          *) connect_valid=0 ;;
        esac
        if [ "$connect_valid" = "1" ]; then
          rm -f "$PENDING_DISABLE"
          cmd wifi start-scan >/dev/null 2>&1 || true
          # Connecting cancels an active scan on this ROM. Wait for a recent target result
          # before selecting it; never let an expired app request initiate a late join.
          connect_scan=0
          connect_scanned=0
          while [ "$connect_scan" -lt 15 ] && [ -f "$CHANNEL/$nonce.connect-result" ]; do
            if cmd wifi list-scan-results 2>/dev/null | TARGET_SSID="$ssid" awk '
              NR > 1 && $4 + 0 >= 0 && $4 + 0 < 3 && index($0, ENVIRON["TARGET_SSID"]) { found = 1 }
              END { exit !found }'; then
              connect_scanned=1
              break
            fi
            connect_scan=$((connect_scan + 1))
            sleep 0.2
          done
          if [ "$connect_scanned" = "1" ] && [ -f "$CHANNEL/$nonce.connect-result" ] &&
            { { [ "$security" = "open" ] && cmd wifi connect-network "$ssid" open >/dev/null 2>&1; } ||
              { [ "$security" != "open" ] && cmd wifi connect-network "$ssid" "$security" "$passphrase" >/dev/null 2>&1; }; }; then
            connect_poll=0
            while [ "$connect_poll" -lt 40 ] && [ -f "$CHANNEL/$nonce.connect-result" ]; do
              connect_status="$(cmd wifi status 2>/dev/null)"
              if printf '%s\n' "$connect_status" | grep -Fxq "Wifi is connected to \"$ssid\"" &&
                printf '%s\n' "$connect_status" | grep -q 'Supplicant state: COMPLETED' &&
                ip -4 addr show wlan0 2>/dev/null | grep -q 'inet '; then
                connect_result=ok
                break
              fi
              connect_poll=$((connect_poll + 1))
              sleep 0.2
            done
          fi
        fi
      fi
      connect_receipt="$CHANNEL/$nonce.connect-result"
      if [ -f "$connect_receipt" ] && [ ! -L "$connect_receipt" ]; then
        printf '%s\n' "$connect_result" > "$connect_receipt" 2>/dev/null
      fi
      write_response "$nonce" "$connect_result" ""
      log_line "command completed command=wifi_connect_files nonce=$nonce result=$connect_result"
      unset ssid passphrase ssid_encoded passphrase_encoded connect_status connect_valid connect_poll connect_receipt connect_scan connect_scanned
      ;;
    wifi_forget)
      if forget_wifi_network; then
        forget_result=ok
        write_response "$nonce" ok ""
        log_line "command completed command=wifi_forget nonce=$nonce"
      else
        forget_result=error
        write_response "$nonce" error not_forgotten
        log_line "command failed command=wifi_forget nonce=$nonce"
      fi
      # The app creates this inode before the request, avoiding the shell-created response's
      # negative FUSE dentry. Never create it ourselves or follow a substituted symlink.
      forget_receipt="$CHANNEL/$nonce.forget-result"
      if [ -f "$forget_receipt" ] && [ ! -L "$forget_receipt" ]; then
        printf '%s\n' "$forget_result" > "$forget_receipt" 2>/dev/null
      fi
      unset ssid ssid_encoded forget_networks forget_status forget_ids
      ;;
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
    adb_wifi_enable)
      # The Android confirmation dialog ultimately calls IAdbManager with the current BSSID.
      # This bridge is already shell uid, so it can make that same narrow call without driving
      # Settings through accessibility. Never log the BSSID: it is a device identifier.
      adb_api_level="$(getprop ro.build.version.sdk 2>/dev/null)"
      adb_bssid="$(cmd wifi status 2>/dev/null | sed -n \
        -e 's/.*BSSID: \([0-9A-Fa-f:]*\).*/\1/p' \
        -e 's/.*BSSID[[:space:]]*=[[:space:]]*\([0-9A-Fa-f:]*\).*/\1/p' | head -n 1)"
      case "$adb_bssid" in
        *[!0-9A-Fa-f:]*) adb_bssid="" ;;
      esac
      adb_colons="$(printf '%s' "$adb_bssid" | tr -cd ':' | wc -c | tr -d '[:space:]')"
      if [ "$adb_api_level" != "32" ]; then
        write_response "$nonce" error unsupported_android_version
        log_line "command failed command=adb_wifi_enable nonce=$nonce reason=unsupported_android_version"
      elif [ "${#adb_bssid}" -ne 17 ] || [ "$adb_colons" != "5" ]; then
        write_response "$nonce" error no_wifi_network
        log_line "command failed command=adb_wifi_enable nonce=$nonce reason=no_wifi_network"
      elif service call adb 4 i32 1 s16 "$adb_bssid" >/dev/null 2>&1; then
        # IAdbManager both records the trusted network and enables its Wi-Fi transport. The app
        # verifies the live TLS port as the effect, so a successful Binder transaction alone is
        # never treated as success.
        write_response "$nonce" ok ""
        log_line "command completed command=adb_wifi_enable nonce=$nonce"
      else
        write_response "$nonce" error command_failed
        log_line "command failed command=adb_wifi_enable nonce=$nonce"
      fi
      ;;
    adb_wifi_disable)
      # Transaction 11 stops any outstanding pairing server before the transport itself is
      # disabled. It is intentionally best-effort because no active pairing server is normal.
      adb_api_level="$(getprop ro.build.version.sdk 2>/dev/null)"
      if [ "$adb_api_level" != "32" ]; then
        write_response "$nonce" error unsupported_android_version
        log_line "command failed command=adb_wifi_disable nonce=$nonce reason=unsupported_android_version"
      else
        service call adb 11 >/dev/null 2>&1 || true
        if settings put global adb_wifi_enabled 0 >/dev/null 2>&1; then
          write_response "$nonce" ok ""
          log_line "command completed command=adb_wifi_disable nonce=$nonce"
        else
          write_response "$nonce" error command_failed
          log_line "command failed command=adb_wifi_disable nonce=$nonce"
        fi
      fi
      ;;
    delete_capture)
      # The wearer's captures belong to the camera app, so scoped storage refuses the hub's own
      # delete. Shell may remove them - but only ever one of the camera's own capture files:
      # the name is re-validated here rather than trusted, and anything with a path separator, a
      # leading dot or an unexpected character is refused outright, so this command can never be
      # steered outside the capture directory.
      if ! capture_name="$(printf '%s' "$name_encoded" | base64 -d 2>/dev/null)"; then
        write_response "$nonce" error decode_failed
        log_line "command failed command=delete_capture nonce=$nonce reason=decode"
      else
        case "$capture_name" in
          ""|.*|*/*|*[!A-Za-z0-9._+-]*)
            write_response "$nonce" error name_invalid
            log_line "command failed command=delete_capture nonce=$nonce reason=name"
            ;;
          *)
            if [ "${#capture_name}" -gt 255 ]; then
              write_response "$nonce" error name_invalid
              log_line "command failed command=delete_capture nonce=$nonce reason=length"
            elif [ ! -f "$CAPTURE_DIR/$capture_name" ]; then
              write_response "$nonce" ok ""
              log_line "command completed command=delete_capture nonce=$nonce reason=absent"
            elif rm -f "$CAPTURE_DIR/$capture_name" >/dev/null 2>&1 &&
              [ ! -f "$CAPTURE_DIR/$capture_name" ]; then
              write_response "$nonce" ok ""
              log_line "command completed command=delete_capture nonce=$nonce"
            else
              write_response "$nonce" error command_failed
              log_line "command failed command=delete_capture nonce=$nonce"
            fi
            ;;
        esac
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

write_heartbeat() {
  echo "$(date '+%s')" > "$HEARTBEAT"
}

run_maintenance_if_due() {
  maintenance_now="$SECONDS"
  if [ "$((maintenance_now - last_heartbeat))" -ge "$MAINTENANCE_INTERVAL" ]; then
    write_heartbeat
    last_heartbeat="$maintenance_now"
  fi
  if [ "$((maintenance_now - last_update_check))" -ge "$SELF_UPDATE_INTERVAL" ]; then
    last_update_check="$maintenance_now"
    self_update_if_needed
  fi
}

serve_channel() {
  # fd 3 is opened read/write by the function call so opening the FIFO cannot block waiting for
  # an external writer. Keeping its read side open makes every non-blocking app doorbell succeed.
  process_requests
  while [ -d "$CHANNEL" ] && [ -p "$DOORBELL" ]; do
    wait_timeout="$((MAINTENANCE_INTERVAL - (SECONDS - last_heartbeat)))"
    if [ "$wait_timeout" -le 0 ]; then
      run_maintenance_if_due
      continue
    fi
    # The app rings the doorbell through FUSE and that write never reaches a pipe the shell
    # holds open, so a ring may simply not arrive. Slice the blocking read to one second: a
    # queued request is still picked up within the client's response window, and the slices
    # are pure builtins - no process is forked between maintenance wakes.
    if [ "$wait_timeout" -gt "$REQUEST_SCAN_INTERVAL" ]; then
      wait_timeout="$REQUEST_SCAN_INTERVAL"
    fi
    if IFS= read -r -t "$wait_timeout" ignored <&3; then
      process_requests
      continue
    fi

    process_requests
    if [ ! -d "$CHANNEL" ] || [ ! -p "$DOORBELL" ]; then
      return 1
    fi
    run_maintenance_if_due
  done
  return 1
}

cleanup_loop() {
  rm -f "$PIDFILE" "$DOORBELL"
  log_line "bridge loop stopped"
}

# The bridge is spawned by the self-arm ADB session, and on a device whose ADB key has gone stale
# that session never opens again - so a bridge started months ago would keep running old code and
# reject every command added since, with no way to replace it short of re-onboarding.
#
# It therefore updates itself, but only ever from the installed APK: the script is read straight
# out of the signed package, so what runs as shell always comes from a build we signed, never from
# a blob handed over at runtime. That distinction is the whole point - the fixed command whitelist
# stays meaningful.
self_update_if_needed() {
  apk="$(pm path "$PACKAGE" 2>/dev/null | sed -n 's/^package://p' | head -n 1)"
  if [ -z "$apk" ] || [ ! -f "$apk" ]; then
    return 0
  fi
  candidate_version="$(unzip -p "$apk" "$ASSET_PATH" 2>/dev/null |
    sed -n 's/^VERSION="\(.*\)"$/\1/p' | head -n 1)"
  if [ -z "$candidate_version" ] || [ "$candidate_version" = "$VERSION" ]; then
    return 0
  fi

  candidate="$BASE/$NAME.candidate.$$"
  rendered="$BASE/$NAME.rendered.$$"
  rm -f "$candidate" "$rendered"
  if ! unzip -p "$apk" "$ASSET_PATH" > "$candidate" 2>/dev/null; then
    rm -f "$candidate"
    return 0
  fi
  # Refuse anything that is not recognisably this script with exactly one secret slot, then refuse
  # anything the shell itself cannot parse: a botched hand-over would leave no bridge at all, and
  # no ADB session to spawn a new one.
  if [ "$(head -n 1 "$candidate")" != "#!/system/bin/sh" ] ||
    [ "$(grep -c "$PLACEHOLDER" "$candidate")" != "1" ]; then
    log_line "self-update rejected version=$candidate_version reason=shape"
    rm -f "$candidate"
    return 0
  fi
  if ! sed "s|$PLACEHOLDER|$SECRET|" "$candidate" > "$rendered" || ! sh -n "$rendered" 2>/dev/null; then
    log_line "self-update rejected version=$candidate_version reason=syntax"
    rm -f "$candidate" "$rendered"
    return 0
  fi
  rm -f "$candidate"

  cp "$SCRIPT_PATH" "$SCRIPT_PATH.bak" 2>/dev/null
  if ! cp "$rendered" "$SCRIPT_PATH" 2>/dev/null; then
    log_line "self-update failed version=$candidate_version reason=install"
    rm -f "$rendered"
    return 0
  fi
  chmod 700 "$SCRIPT_PATH" 2>/dev/null
  rm -f "$rendered"

  nohup sh "$SCRIPT_PATH" run >/dev/null 2>&1 &
  successor="$!"
  sleep "$SELF_UPDATE_SETTLE"
  if is_bridge_pid "$successor"; then
    log_line "self-update handover version=$VERSION->$candidate_version pid=$successor"
    # The successor owns the pidfile, the doorbell and the channel now; leaving through the normal
    # cleanup would delete them underneath it.
    trap - INT TERM EXIT
    exit 0
  fi
  log_line "self-update aborted version=$candidate_version reason=successor_died"
  cp "$SCRIPT_PATH.bak" "$SCRIPT_PATH" 2>/dev/null
  chmod 700 "$SCRIPT_PATH" 2>/dev/null
  echo "$$" > "$PIDFILE"
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
  log_line "bridge loop started pid=$$ waitTimeout=$MAINTENANCE_INTERVAL fallbackPollInterval=$FALLBACK_POLL_INTERVAL"
  trap 'cleanup_loop; exit 0' INT TERM EXIT
  last_heartbeat="$SECONDS"
  last_update_check="$SECONDS"
  write_heartbeat
  while true; do
    if prepare_channel; then
      # The redirection persists for the whole healthy serving loop and closes automatically when
      # a channel/FIFO error returns us to the degraded retry path.
      serve_channel 3<> "$DOORBELL"
    fi

    # A missing or unopenable FIFO is the only polling mode. Requests are still authoritative,
    # and opening the permanent reader on the next pass closes the scan-to-wait race.
    process_requests
    sleep "$FALLBACK_POLL_INTERVAL"
    run_maintenance_if_due
  done
}

start_bridge() {
  if is_bridge_running; then
    running_version="$(cat "$VERSIONFILE" 2>/dev/null)"
    if [ "$running_version" = "$VERSION" ]; then
      echo "running pid=$(cat "$PIDFILE" 2>/dev/null) version=$VERSION"
      exit 0
    fi
    # An app update rewrites this script, but the loop already running is the old one and would
    # keep rejecting commands it has never heard of. A version change is the signal to hand over.
    log_line "bridge version changed old=${running_version:-unknown} new=$VERSION; restarting"
    stop_bridge >/dev/null 2>&1
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
