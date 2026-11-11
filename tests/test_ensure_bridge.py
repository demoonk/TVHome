import hashlib
import os
import shutil
import subprocess
import tempfile
import time
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
SCRIPT = ROOT / "tools" / "tvhome-bridge-ensure.sh"
BASH = str(Path(os.environ.get("ProgramFiles", "C:/Program Files")) / "Git/bin/bash.exe") if os.name == "nt" else shutil.which("bash")
if BASH and not Path(BASH).exists():
    BASH = None

# Stub commands for a fake TV: the script's own logic is what is tested, nothing is read from a device.
STUBS = {
    "setsid": r"""#!/bin/sh
printf '%s\n' "$*" >> "$FAKE_RUN_LOG"
exit 0
""",
}

GUARD_BODY = b"#!/system/bin/sh\n# fake guard used by the ensure-script tests\necho guard\n"
STALE_GUARD_BODY = b"#!/system/bin/sh\n# older guard without the launcher mode\necho old guard\n"


class EnsureBridgeTest(unittest.TestCase):
    def scenario(self, guard_cmdline_for=None, guard_hash="match", pid="987654", env_extra=None):
        if BASH is None:
            self.skipTest("bash is not available")
        with tempfile.TemporaryDirectory(prefix="ensure-test-", dir=ROOT / "tests") as temp:
            work = Path(temp).resolve()
            assert work.is_relative_to(ROOT)
            stubs = work / "stubs"
            stubs.mkdir()
            for name, body in STUBS.items():
                stub = stubs / name
                stub.write_text(body, encoding="utf-8", newline="\n")
                stub.chmod(0o755)
            run_log = work / "runs.log"
            run_log.write_text("", encoding="utf-8")
            guard = work / "assistant-package-guard.sh"
            guard.write_bytes(GUARD_BODY)
            # Paths are passed in the slash form: grep treats a backslash in the guard path as an escape.
            posix = work.as_posix()
            env = dict(os.environ, TVHOME_TMP=posix, TVHOME_PROC=posix + "/proc", FAKE_RUN_LOG=str(run_log))
            env["PATH"] = str(stubs) + os.pathsep + env["PATH"]
            if env_extra:
                env.update(env_extra)
            if guard_cmdline_for is not None:
                pid_dir = work / "proc" / pid
                pid_dir.mkdir(parents=True)
                (pid_dir / "cmdline").write_bytes(guard_cmdline_for(posix).replace(" ", "\0").encode("utf-8"))
                (work / "assistant-package-guard.pid").write_text(pid, encoding="utf-8")
                recorded = hashlib.md5(STALE_GUARD_BODY if guard_hash == "stale" else GUARD_BODY).hexdigest()
                (work / "assistant-package-guard.hash").write_text(recorded, encoding="utf-8")
            result = subprocess.run([BASH, str(SCRIPT)], env=env, capture_output=True, text=True)
            time.sleep(1)  # the script backgrounds what it starts
            calls = [line for line in run_log.read_text(encoding="utf-8").splitlines() if line]
            log = (work / "tvhome-bridge-ensure.log").read_text(encoding="utf-8").splitlines()
            return result.returncode, log, calls

    def test_starts_the_guard_when_it_is_not_running(self):
        code, log, calls = self.scenario(None)
        self.assertEqual(code, 0)
        self.assertIn("guard started", log)
        self.assertEqual(len(calls), 1)
        self.assertIn("assistant-package-guard.sh", calls[0])

    def test_skips_a_guard_that_already_runs(self):
        code, log, calls = self.scenario(lambda work: "sh " + work + "/assistant-package-guard.sh")
        self.assertEqual(code, 0)
        self.assertEqual(calls, [])
        self.assertIn("guard already running", log)

    def test_replaces_a_guard_started_from_an_older_script(self):
        # The bedroom TV on 2026-11-11: the guard ran since 2026-11-05, so it kept the old script in memory and
        # answered "check" but ignored the newer "launcher" request. The deployed hash says it must be replaced.
        code, log, calls = self.scenario(lambda work: "sh " + work + "/assistant-package-guard.sh", guard_hash="stale")
        self.assertEqual(code, 0)
        self.assertIn("guard restarting: script changed", log)
        self.assertIn("guard started", log)
        self.assertEqual(len(calls), 1)
        self.assertNotIn("guard already running", log)

    def test_force_replaces_a_running_guard(self):
        code, log, calls = self.scenario(lambda work: "sh " + work + "/assistant-package-guard.sh",
                                         env_extra={"TVHOME_FORCE_GUARD": "1"})
        self.assertEqual(code, 0)
        self.assertIn("guard restarting: forced", log)
        self.assertIn("guard started", log)
        self.assertEqual(len(calls), 1)

    def test_stale_pid_file_starts_the_guard_again(self):
        code, log, calls = self.scenario(lambda work: "sh " + work + "/something-else.sh")
        self.assertEqual(code, 0)
        self.assertIn("guard started", log)
        self.assertEqual(len(calls), 1)
        self.assertIn("assistant-package-guard.sh", calls[0])

    def test_pid_pointing_at_another_process_is_not_trusted(self):
        code, log, calls = self.scenario(lambda work: "cat " + work + "/assistant-package-guard.pid")
        self.assertEqual(code, 0)
        self.assertIn("guard started", log)
        self.assertEqual(len(calls), 1)

    def test_the_bridge_is_not_started_here(self):
        # The guard owns the bridge: a bridge started by this script dies with the adbd session that called it,
        # so the ensure script must not start one, otherwise two places would fight over the process.
        code, log, calls = self.scenario(None)
        self.assertEqual(code, 0)
        self.assertNotIn("bridge started", log)
        self.assertFalse([line for line in calls if "app_process" in line])


if __name__ == "__main__":
    unittest.main()
