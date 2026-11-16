import os, shutil, subprocess, time
from pathlib import Path

ROOT = Path(r"C:/Users/demOOn/Documents/TV/TVHome")
BASH = r"C:/Program Files/Git/bin/bash.exe"
SCRIPT = ROOT / "tools" / "assistant-package-guard.sh"

work = ROOT / "tests" / "probe-run"
shutil.rmtree(work, ignore_errors=True)
(work / "stubs").mkdir(parents=True)
(work / "store").mkdir(parents=True)
stubs = {
    "settings": """#!/bin/sh
action=$1; domain=$2; key=$3
case "$action" in
    get) [ -f "$FAKE_STORE/$domain-$key" ] && cat "$FAKE_STORE/$domain-$key" || echo null ;;
    put) printf '%s\n' "$4" > "$FAKE_STORE/$domain-$key" ;;
    delete) rm -f "$FAKE_STORE/$domain-$key" ;;
esac
""",
    "pm": """#!/bin/sh
case "$1" in
    path) exit 0 ;;
    disable-user) printf 'disable-user %s\n' "$4" >> "$FAKE_RUN_LOG"; exit 0 ;;
esac
""",
    "pidof": """#!/bin/sh
exit 1
""",
    "getevent": """#!/bin/sh
printf '%s\n' "$FAKE_INVENTORY"
""",
    "getprop": """#!/bin/sh
printf '%s\n' "$FAKE_ABI"
""",
    "setsid": """#!/bin/sh
printf '%s\n' "$*" >> "$FAKE_RUN_LOG"
exit 0
""",
    "awk": """#!/bin/sh
printf 'awk stub called: %s\n' "$*" >> "$FAKE_RUN_LOG"
exit 0
""",
}
for name, body in stubs.items():
    p = work / "stubs" / name
    p.write_text(body, encoding="utf-8", newline="\n")
    p.chmod(0o755)
(work / "store" / "secure-ru_pda_tvhome_assistant_request").write_text("check:uuid-8\n", encoding="utf-8")
(work / "tvhome-remote-2.dex").write_bytes(b"dex")
(work / "runs.log").write_text("", encoding="utf-8")
env = dict(os.environ, TVHOME_TMP=str(work), FAKE_STORE=str(work / "store"), FAKE_RUN_LOG=str(work / "runs.log"),
          FAKE_BRIDGE_RUNNING="0",
          FAKE_INVENTORY='add device 2: /dev/input/event5\n  name:     "TVC0GWBT Consumer Control"',
          FAKE_ABI="armeabi-v7a")
env["PATH"] = str(work / "stubs") + os.pathsep + env["PATH"]
err_file = open(work / "stderr.txt", "w", encoding="utf-8")
proc = subprocess.Popen([BASH, str(SCRIPT)], env=env, stdout=subprocess.DEVNULL,
                        stderr=err_file, text=True)
time.sleep(10)
proc.terminate()
proc.wait(timeout=10)
err_file.close()
err = (work / "stderr.txt").read_text(encoding="utf-8", errors="replace")
print("STDERR:", err[:1500])
print("TRACE:", (work / "assistant-package-guard.trace").read_text(encoding="utf-8"))
print("RUNS:", (work / "runs.log").read_text(encoding="utf-8"))
ack = work / "store" / "secure-ru_pda_tvhome_assistant_ack"
print("ACK:", ack.read_text(encoding="utf-8") if ack.exists() else "(none)")
