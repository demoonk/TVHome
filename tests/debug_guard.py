import os
import shutil
import subprocess
import sys
import time
from pathlib import Path

import test_guard

SCRIPT = Path(sys.argv[1]) if len(sys.argv) > 1 else test_guard.SCRIPT

work = Path(test_guard.ROOT) / "tests" / "debug-run"
shutil.rmtree(work, ignore_errors=True)
work.mkdir(parents=True, exist_ok=True)
stubs = work / "stubs"
stubs.mkdir()
for name, body in test_guard.STUBS.items():
    p = stubs / name
    p.write_text(body, encoding="utf-8", newline="\n")
    p.chmod(0o755)
store = work / "store"
store.mkdir()
run_log = work / "runs.log"
run_log.write_text("", encoding="utf-8")
(store / test_guard.REQUEST_KEY).write_text("check:uuid-8\n", encoding="utf-8")
env = dict(os.environ, TVHOME_TMP=str(work), FAKE_STORE=str(store), FAKE_RUN_LOG=str(run_log),
           FAKE_OK="1", FAKE_DISABLED="com.mediatek.wwtv.tvcenter com.homwee.factory",
           FAKE_PATH_FAIL="0", FAKE_FAILS="3")
env["PATH"] = str(stubs) + os.pathsep + env["PATH"]
err = open(work / "stderr.txt", "w", encoding="utf-8")
proc = subprocess.Popen([test_guard.BASH, str(SCRIPT)], env=env, stdout=subprocess.DEVNULL, stderr=err)
trace_file = work / "assistant-package-guard.trace"
for i in range(12):
    time.sleep(1)
    print(f"t={i+1}s:", trace_file.read_text(encoding="utf-8", errors="replace").splitlines() if trace_file.exists() else "(no trace)",
          [line for line in run_log.read_text(encoding="utf-8").splitlines() if line])
proc.terminate()
proc.wait(timeout=5)
err.close()
ack = store / test_guard.ACK_KEY
print("SCRIPT:", SCRIPT)
print("TRACE:", (work / "assistant-package-guard.trace").read_text(encoding="utf-8").splitlines())
print("CALLS:", [line for line in run_log.read_text(encoding="utf-8").splitlines() if line])
print("ACK:", ack.read_text(encoding="utf-8").strip() if ack.exists() else "(none)")
print("STDERR:", (work / "stderr.txt").read_text(encoding="utf-8", errors="replace")[:600])
