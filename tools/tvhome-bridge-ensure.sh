#!/system/bin/sh
# Ensures the guard is running, and starts nothing that already runs. The guard is the process that keeps the
# gear bridge alive, so this script only brings the guard up: a bridge started from the session that calls this
# script dies when that session closes, while the guard survives it.
# Runs as shell (adb), never from the launcher: only the shell domain reads /dev/input and runs app_process.
# Safe to call after every restart or reboot; the files are not re-pushed here. Usage:
#   adb -s <tv ip>:5555 shell sh /data/local/tmp/tvhome-bridge-ensure.sh
# The launcher's on-device helper calls this same script once, after Home has drawn its first frame.
# TVHOME_TMP and TVHOME_PROC let tests run this same logic against fake devices.
TMP=${TVHOME_TMP:-/data/local/tmp}
PROC=${TVHOME_PROC:-/proc}
GUARD=$TMP/assistant-package-guard.sh
PIDFILE=$TMP/assistant-package-guard.pid
ENSURE_LOG=$TMP/tvhome-bridge-ensure.log

# A recorded pid can be reused by another process, so the command line is checked before trusting it.
guard_alive=0
if [ -f "$PIDFILE" ]; then
  pid=$(cat "$PIDFILE" 2>/dev/null)
  if [ -n "$pid" ] && cat "$PROC/$pid/cmdline" 2>/dev/null | tr '\0' ' ' | grep -q "sh $GUARD"; then
    guard_alive=1
  fi
fi
if [ "$guard_alive" = 1 ]; then
  echo "guard already running" >>"$ENSURE_LOG"
else
  setsid sh "$GUARD" >>$TMP/assistant-package-guard.log 2>&1 </dev/null &
  # adbd tears this shell down, and its process group with it, as soon as the command returns. setsid
  # needs this shell to stay alive for a moment to leave that group: when the helper closes the
  # connection right after CLSE (the 1.7 client reads it; the earlier build waited for a read timeout)
  # the guard was killed before it wrote even its pid (seen on 2026-11-11). One second is enough.
  sleep 1
  echo "guard started" >>"$ENSURE_LOG"
fi
exit 0
