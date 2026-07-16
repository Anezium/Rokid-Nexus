#!/system/bin/sh

PKG="com.anezium.rokidbus.glasses"
SVC="com.anezium.rokidbus.glasses/com.anezium.rokidbus.glasses.RokidBusAccessibilityService"
SVC_SHORT="com.anezium.rokidbus.glasses/.RokidBusAccessibilityService"
NAME="rokid-nexus-a11y-watchdog"
BASE="/data/local/tmp"
PIDFILE="$BASE/$NAME.pid"
LOGFILE="$BASE/$NAME.log"
HEARTBEAT="$BASE/$NAME.heartbeat"
VERSIONFILE="$BASE/$NAME.version"
RECOVERYFILE="$BASE/$NAME.recovery"
VERSION="2026-07-16.1"
HEALTHY_INTERVAL="${INTERVAL:-180}"
RECOVERY_INTERVAL="${RECOVERY_INTERVAL:-30}"
RECOVERY_CYCLES="${RECOVERY_CYCLES:-3}"

case "$HEALTHY_INTERVAL" in
  ""|*[!0-9]*) HEALTHY_INTERVAL=180 ;;
esac
if [ "$HEALTHY_INTERVAL" -lt 180 ]; then
  HEALTHY_INTERVAL=180
fi
case "$RECOVERY_INTERVAL" in
  ""|*[!0-9]*) RECOVERY_INTERVAL=30 ;;
esac
if [ "$RECOVERY_INTERVAL" -lt 30 ]; then
  RECOVERY_INTERVAL=30
fi
case "$RECOVERY_CYCLES" in
  ""|*[!0-9]*) RECOVERY_CYCLES=3 ;;
esac
if [ "$RECOVERY_CYCLES" -lt 1 ]; then
  RECOVERY_CYCLES=3
fi

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

app_pid() {
  pidof "$PKG" 2>/dev/null | tr -d '\r'
}

pid_cmdline() {
  tr '\000' ' ' < "/proc/$1/cmdline" 2>/dev/null
}

is_watchdog_pid() {
  pid="$1"
  cmdline="$(pid_cmdline "$pid")"
  case "$cmdline" in
    *"$NAME.sh run"*|*"$(basename "$0") run"*|*"$0 run"*) return 0 ;;
    *) return 1 ;;
  esac
}

is_watchdog_running() {
  if [ ! -f "$PIDFILE" ]; then
    return 1
  fi
  pid="$(cat "$PIDFILE" 2>/dev/null)"
  if [ -z "$pid" ]; then
    return 1
  fi
  kill -0 "$pid" 2>/dev/null && is_watchdog_pid "$pid"
}

service_present() {
  enabled="$1"
  case ":$enabled:" in
    *":$SVC:"*|*":$SVC_SHORT:"*) return 0 ;;
    *) return 1 ;;
  esac
}

services_without_nexus() {
  enabled="$1"
  without_nexus=""
  old_ifs="$IFS"
  IFS=':'
  for service in $enabled; do
    if [ -n "$service" ] && [ "$service" != "$SVC" ] && [ "$service" != "$SVC_SHORT" ]; then
      if [ -z "$without_nexus" ]; then
        without_nexus="$service"
      else
        without_nexus="$without_nexus:$service"
      fi
    fi
  done
  IFS="$old_ifs"
  echo "$without_nexus"
}

mark_recovery_cycles() {
  if [ ! -f "$RECOVERYFILE" ]; then
    echo "$RECOVERY_CYCLES" > "$RECOVERYFILE"
  fi
}

next_interval() {
  remaining="$(cat "$RECOVERYFILE" 2>/dev/null)"
  case "$remaining" in
    ""|*[!0-9]*) remaining=0 ;;
  esac
  if [ "$remaining" -gt 0 ]; then
    remaining=$((remaining - 1))
    echo "$remaining" > "$RECOVERYFILE"
    echo "$RECOVERY_INTERVAL"
  else
    echo "$HEALTHY_INTERVAL"
  fi
}

clear_exhausted_recovery_if_healthy() {
  remaining="$(cat "$RECOVERYFILE" 2>/dev/null)"
  if [ "$remaining" = "0" ]; then
    rm -f "$RECOVERYFILE"
  fi
}

repair_once() {
  accessibility_enabled="$(settings get secure accessibility_enabled 2>/dev/null)"
  enabled_services="$(settings get secure enabled_accessibility_services 2>/dev/null)"
  pid_before="$(app_pid)"
  service_was_missing=0
  if ! service_present "$enabled_services"; then
    service_was_missing=1
  fi

  case "$enabled_services" in
    ""|"null")
      next_services="$SVC"
      ;;
    *)
      if [ "$service_was_missing" = "1" ]; then
        next_services="$enabled_services:$SVC"
      else
        next_services="$enabled_services"
      fi
      ;;
  esac

  if [ "$enabled_services" != "$next_services" ]; then
    rotate_log_if_needed
    settings put secure enabled_accessibility_services "$next_services" 2>>"$LOGFILE"
  fi
  if [ "$accessibility_enabled" != "1" ]; then
    rotate_log_if_needed
    settings put secure accessibility_enabled 1 2>>"$LOGFILE"
  fi

  if [ -z "$pid_before" ] && [ "$service_was_missing" = "1" ]; then
    without_nexus="$(services_without_nexus "$next_services")"
    rotate_log_if_needed
    settings put secure enabled_accessibility_services "$without_nexus" 2>>"$LOGFILE"
    rotate_log_if_needed
    settings put secure enabled_accessibility_services "$next_services" 2>>"$LOGFILE"
    log_line "rebind requested services=${next_services:-empty} appPid=$(app_pid)"
  fi

  now="$(date '+%s')"
  echo "$now" > "$HEARTBEAT"
  mark_recovery_cycles
  log_line "repair requested a11y=$accessibility_enabled services=${enabled_services:-empty} appPid=$(app_pid)"
}

cleanup_loop() {
  rm -f "$PIDFILE"
  log_line "watchdog loop stopped"
}

loop_forever() {
  echo "$$" > "$PIDFILE"
  echo "$VERSION" > "$VERSIONFILE"
  log_line "watchdog loop started pid=$$ healthyInterval=$HEALTHY_INTERVAL recoveryInterval=$RECOVERY_INTERVAL"
  trap 'cleanup_loop; exit 0' INT TERM EXIT
  while true; do
    accessibility_enabled="$(settings get secure accessibility_enabled 2>/dev/null)"
    enabled_services="$(settings get secure enabled_accessibility_services 2>/dev/null)"
    if [ "$accessibility_enabled" != "1" ] || ! service_present "$enabled_services"; then
      repair_once
    else
      echo "$(date '+%s')" > "$HEARTBEAT"
      clear_exhausted_recovery_if_healthy
    fi
    sleep "$(next_interval)"
  done
}

start_watchdog() {
  if is_watchdog_running; then
    echo "running pid=$(cat "$PIDFILE" 2>/dev/null) version=$VERSION"
    exit 0
  fi
  nohup sh "$0" run >/dev/null 2>&1 &
  echo "$!" > "$PIDFILE"
  echo "$VERSION" > "$VERSIONFILE"
  log_line "watchdog start requested pid=$!"
  echo "started pid=$! version=$VERSION"
}

stop_watchdog() {
  if is_watchdog_running; then
    pid="$(cat "$PIDFILE" 2>/dev/null)"
    kill "$pid" 2>/dev/null
    sleep 1
    if is_watchdog_pid "$pid"; then
      kill -9 "$pid" 2>/dev/null
    fi
    log_line "watchdog stop requested pid=$pid"
  elif [ -f "$PIDFILE" ]; then
    pid="$(cat "$PIDFILE" 2>/dev/null)"
    log_line "watchdog pidfile stale or foreign pid=$pid cmdline=$(pid_cmdline "$pid")"
  fi
  rm -f "$PIDFILE"
  echo "stopped version=$VERSION"
}

status_watchdog() {
  if is_watchdog_running; then
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
  echo "healthy_interval=$HEALTHY_INTERVAL"
  echo "recovery_interval=$RECOVERY_INTERVAL"
  echo "recovery_cycles_remaining=$(cat "$RECOVERYFILE" 2>/dev/null)"
  echo "appPid=$(app_pid)"
  echo "accessibility_enabled=$(settings get secure accessibility_enabled 2>/dev/null)"
  echo "enabled_accessibility_services=$(settings get secure enabled_accessibility_services 2>/dev/null)"
  echo "heartbeat=$(cat "$HEARTBEAT" 2>/dev/null)"
}

case "$1" in
  start|"")
    start_watchdog
    ;;
  stop)
    stop_watchdog
    ;;
  restart)
    stop_watchdog
    start_watchdog
    ;;
  status)
    status_watchdog
    ;;
  repair)
    repair_once
    ;;
  run)
    loop_forever
    ;;
  *)
    echo "usage: $0 {start|stop|restart|status|repair}"
    exit 2
    ;;
esac
