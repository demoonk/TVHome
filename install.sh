#!/bin/sh
set -eu
if [ "$#" -lt 2 ] || [ "$#" -gt 3 ]; then
    echo 'Usage: ./install.sh TV_ADDRESS:PORT ./TVHome-VERSION.apk [path/to/adb]' >&2
    exit 2
fi
device=$1
apk=$2
adb=${3:-adb}
case "$apk" in *.apk) ;; *) echo 'Expected an .apk file' >&2; exit 1;; esac
[ -f "$apk" ] || { echo 'APK file not found' >&2; exit 1; }
command -v "$adb" >/dev/null 2>&1 || { echo 'ADB not found' >&2; exit 1; }
case "$device" in *:*) "$adb" connect "$device";; esac
[ "$("$adb" -s "$device" get-state | tr -d '\r\n')" = device ] || { echo 'TV is not authorized or connected' >&2; exit 1; }
sdk=$("$adb" -s "$device" shell getprop ro.build.version.sdk | tr -d '\r\n')
case "$sdk" in ''|*[!0-9]*) echo 'Could not read Android API level' >&2; exit 1;; esac
[ "$sdk" -ge 26 ] || { echo 'Android API 26 or newer is required' >&2; exit 1; }
script_dir=$(CDPATH= cd -P "$(dirname "$0")" && pwd)
paths=$("$adb" -s "$device" shell pm path ru.pda.tvhome 2>/dev/null || true)
case "$paths" in *package:*)
    backup="$script_dir/backups/$(date -u +%Y%m%d-%H%M%S)-$$"
    mkdir -p "$backup"
    printf '%s\n' "$paths" | tr -d '\r' | while IFS= read -r entry; do
        case "$entry" in package:*) remote=${entry#package:}; "$adb" -s "$device" pull "$remote" "$backup/$(basename "$remote")";; esac
    done
    echo "Previous APK backup: $backup"
esac
installed=$("$adb" -s "$device" install -r "$apk")
printf '%s\n' "$installed"
printf '%s\n' "$installed" | tr -d '\r' | grep -q '^Success$' || { echo 'Installation did not report Success; launch aborted' >&2; exit 1; }
started=$("$adb" -s "$device" shell am start -W -a android.intent.action.MAIN -c android.intent.category.HOME -n ru.pda.tvhome/ru.pda.tvhome.MainActivity)
printf '%s\n' "$started"
if printf '%s\n' "$started" | grep -Ei '^Error:|Exception' >/dev/null; then echo 'APK installed but TVHome could not start' >&2; exit 1; fi
echo 'TVHome installed. Select the default Home in system Settings if needed. App data was preserved by install -r.'
