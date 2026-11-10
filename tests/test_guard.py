import os
import shutil
import subprocess
import tempfile
import time
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
SCRIPT = ROOT / "tools" / "assistant-package-guard.sh"
BASH = str(Path(os.environ.get("ProgramFiles", "C:/Program Files")) / "Git/bin/bash.exe") if os.name == "nt" else shutil.which("bash")
if BASH and not Path(BASH).exists():
    BASH = None

# Stub commands for a fake TV: the guard's own parsing, protected list and answers are what is tested.
STUBS = {
    "settings": r"""#!/bin/sh
action=$1; domain=$2; key=$3
case "$action" in
    get) [ -f "$FAKE_STORE/$domain-$key" ] && cat "$FAKE_STORE/$domain-$key" || echo null ;;
    put) printf '%s\n' "$4" > "$FAKE_STORE/$domain-$key" ;;
    delete) rm -f "$FAKE_STORE/$domain-$key" ;;
esac
""",
    "pm": r"""#!/bin/sh
case "$1" in
    path) c=0
        if [ -f "$FAKE_STORE/pathfailcount" ]; then c=$(cat "$FAKE_STORE/pathfailcount"); fi
        if [ "$FAKE_PATH_FAIL" = 1 ] && [ "$c" -lt "${FAKE_FAILS:-3}" ]; then echo $((c+1)) > "$FAKE_STORE/pathfailcount"; exit 1; fi
        exit 0 ;;
    grant) [ "$FAKE_OK" = 1 ] && printf 'grant %s %s\n' "$2" "$3" >> "$FAKE_RUN_LOG" && exit 0; exit 1 ;;
    disable-user) case " $FAKE_DISABLED " in *" $4 "*) exit 1 ;; esac
        printf 'disable-user %s\n' "$4" >> "$FAKE_RUN_LOG"; [ "$FAKE_OK" = 1 ] && exit 0; exit 1 ;;
    enable) [ "$FAKE_OK" = 1 ] && printf 'enable %s\n' "$4" >> "$FAKE_RUN_LOG" && exit 0; exit 1 ;;
    list) if [ "$3" = -d ]; then for pkg in $FAKE_DISABLED; do printf '%s\n' "$pkg"; done; fi ;;
esac
""",
    "appops": r"""#!/bin/sh
printf 'appops %s %s %s\n' "$2" "$3" "$4" >> "$FAKE_RUN_LOG"
exit 0
""",
}

# Extra stubs for the part of the loop that keeps the gear bridge running.
BRIDGE_STUBS = {
    "pidof": r"""#!/bin/sh
[ "$FAKE_BRIDGE_RUNNING" = 1 ] && exit 0
exit 1
""",
    "getevent": r"""#!/bin/sh
printf '%s\n' "$FAKE_INVENTORY"
""",
    "getprop": r"""#!/bin/sh
printf '%s\n' "$FAKE_ABI"
""",
    "setsid": r"""#!/bin/sh
printf '%s\n' "$*" >> "$FAKE_RUN_LOG"
exit 0
""",
}
INVENTORY = 'add device 1: /dev/input/event7\n  name:     "Keyboard"\nadd device 2: /dev/input/event5\n  name:     "TVC0GWBT Consumer Control"'
NO_REMOTE_INVENTORY = 'add device 1: /dev/input/event7\n  name:     "Keyboard"'

REQUEST_KEY = "secure-ru_pda_tvhome_assistant_request"
ACK_KEY = "secure-ru_pda_tvhome_assistant_ack"
TRACE_NAME = "assistant-package-guard.trace"
DEX_NAME = "tvhome-remote-2.dex"


class GuardTest(unittest.TestCase):
    """
    Runs the guard against the stubs and stops as soon as the state the test waits for is there, instead of
    sleeping a fixed time. A pass costs a fork per command, which on this host can take seconds, so a fixed
    sleep either reads too early or lets the guard make more passes than the test wants to allow.
    """

    def run_guard(self, request, until, ok=True, disabled="com.mediatek.wwtv.tvcenter com.homwee.factory",
                  path_fail=False, fails=3, dex=False, bridge_running=False, inventory=INVENTORY,
                  abi="armeabi-v7a", settings_values=None, timeout=60):
        if BASH is None:
            self.skipTest("bash is not available")
        with tempfile.TemporaryDirectory(prefix="guard-test-", dir=ROOT / "tests") as temp:
            work = Path(temp).resolve()
            assert work.is_relative_to(ROOT)
            stubs = work / "stubs"
            stubs.mkdir()
            for name, body in dict(STUBS, **BRIDGE_STUBS).items():
                stub = stubs / name
                stub.write_text(body, encoding="utf-8", newline="\n")
                stub.chmod(0o755)
            store = work / "store"
            store.mkdir()
            run_log = work / "runs.log"
            run_log.write_text("", encoding="utf-8")
            if request:
                (store / REQUEST_KEY).write_text(request + "\n", encoding="utf-8")
            for name, value in (settings_values or {}).items():
                (store / name).write_text(value + "\n", encoding="utf-8")
            if dex:
                (work / DEX_NAME).write_bytes(b"dex")
            env = dict(os.environ, TVHOME_TMP=str(work), FAKE_STORE=str(store), FAKE_RUN_LOG=str(run_log),
                       FAKE_OK="1" if ok else "0", FAKE_DISABLED=disabled,
                       FAKE_PATH_FAIL="1" if path_fail else "0", FAKE_FAILS=str(fails),
                       FAKE_BRIDGE_RUNNING="1" if bridge_running else "0",
                       FAKE_INVENTORY=inventory, FAKE_ABI=abi)
            env["PATH"] = str(stubs) + os.pathsep + env["PATH"]
            proc = subprocess.Popen([BASH, str(SCRIPT)], env=env,
                                    stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
            ack_file = store / ACK_KEY
            trace_file = work / TRACE_NAME
            def lines(path):
                return [line for line in path.read_text(encoding="utf-8").splitlines() if line] if path.exists() else []
            deadline = time.time() + timeout
            ack, calls, trace = "", [], []
            while time.time() < deadline:
                ack = lines(ack_file)[0] if ack_file.exists() else ""
                calls = lines(run_log)
                trace = lines(trace_file)
                if until(ack, calls, trace):
                    break
                time.sleep(0.5)
            proc.terminate()
            proc.wait(timeout=5)
            return ack, calls, trace

    def bridge(self, bridge_running, inventory=INVENTORY, abi="armeabi-v7a", dex=True):
        # The bridge part runs at the end of a pass, so waiting only for the start freeze would read the state
        # before the guard has looked at the bridge. The command is started in the background, so the pass is
        # over when its line is in the run log, not when the guard has written its own trace line. Without a
        # bridge to start, two passes prove the check ran and found nothing to do.
        ack, calls, trace = self.run_guard(None, lambda ack, calls, trace: any(
            "app_process" in line for line in calls)
            or any(line in ("no dex", "no remote") for line in trace)
            or trace.count("freeze done") >= 2,
            bridge_running=bridge_running, inventory=inventory, abi=abi, dex=dex)
        return trace, calls

    def test_bridge_is_started_when_it_is_not_running(self):
        trace, calls = self.bridge(False)
        bridge_call = [line for line in calls if "app_process" in line]
        self.assertEqual(len(bridge_call), 1)
        self.assertIn("app_process /system/bin --nice-name=pqtv-bridge PqRemoteProof /dev/input/event5 16 0",
                      bridge_call[0])
        self.assertIn("bridge started on /dev/input/event5 size 16", trace)

    def test_a_bridge_that_already_runs_is_left_alone(self):
        trace, calls = self.bridge(True)
        self.assertFalse([line for line in calls if "app_process" in line])
        self.assertFalse([line for line in trace if "bridge" in line])

    def test_missing_remote_is_reported_and_nothing_is_started(self):
        # At boot the remote is not enumerated yet: the guard reports it and searches again later,
        # instead of exiting the way the old ensure script did.
        trace, calls = self.bridge(False, inventory=NO_REMOTE_INVENTORY)
        self.assertIn("no remote", trace)
        self.assertFalse([line for line in calls if "app_process" in line])

    def test_remote_is_found_by_name_not_by_number(self):
        trace, calls = self.bridge(False)
        self.assertIn("/dev/input/event5", [line for line in calls if "app_process" in line][0])

    def test_64_bit_abi_uses_24_byte_events(self):
        trace, calls = self.bridge(False, abi="arm64-v8a")
        self.assertIn("bridge started on /dev/input/event5 size 24", trace)

    def test_missing_dex_is_reported_and_nothing_is_started(self):
        trace, calls = self.bridge(False, dex=False)
        self.assertIn("no dex", trace)
        self.assertFalse([line for line in calls if "app_process" in line])

    def test_safe_package_freeze_is_applied_and_answered(self):
        ack, calls, _ = self.run_guard("freeze:com.homwee.help:uuid-1", lambda ack, calls, trace: ack)
        self.assertEqual(ack, "freeze:com.homwee.help:uuid-1")
        self.assertIn("disable-user com.homwee.help", calls)

    def test_protected_package_is_never_touched_and_never_answered(self):
        # With the start freeze packages already off, the protected check must not add anything.
        ack, calls, trace = self.run_guard("freeze:com.google.android.gms:uuid-2",
                                           lambda ack, calls, trace: "freeze done" in trace,
                                           disabled="com.mediatek.wwtv.tvcenter com.homwee.factory com.google.android.apps.tv.launcherx")
        self.assertEqual(ack, "")
        self.assertEqual(calls, [])

    def test_already_frozen_safe_package_answers_as_the_same_state(self):
        ack, calls, _ = self.run_guard("freeze:com.homwee.help:uuid-3", lambda ack, calls, trace: ack, ok=False,
                                       disabled="com.homwee.help com.mediatek.wwtv.tvcenter")
        self.assertEqual(ack, "freeze:com.homwee.help:uuid-3")
        self.assertNotIn("disable-user com.homwee.help", calls)

    def test_boot_freezes_the_stock_shell_before_the_loop(self):
        # The remote gear opens the shell's turnkey menu next to the launcher settings on this firmware,
        # and the physical gear reaches the factory receiver that shows system settings. The Google Home
        # handler shows the same system menu when it is enabled, so Google Home is frozen at start too;
        # it stays out of the protected mirror because its row freezes it by owner decision 2026-11-10.
        # The recovery stub is not frozen here because this fake TV has no finished initial setup.
        ack, calls, _ = self.run_guard("check:uuid-8", lambda ack, calls, trace: ack, disabled="")
        self.assertEqual(ack, "check:uuid-8")
        self.assertEqual(calls, ["disable-user com.mediatek.wwtv.tvcenter",
                                 "disable-user com.homwee.factory",
                                 "disable-user com.google.android.apps.tv.launcherx"])

    def test_the_recovery_stub_is_frozen_at_boot_once_the_setup_is_finished(self):
        # Verified on the living-room TV on 2026-11-11: with the stub enabled, Home from the settings menu
        # started its RecoveryActivity and nothing came to the screen. With the initial setup finished the
        # guard freezes it in the same start pass, so Home resolves to TVHome again after a reboot or a
        # reinstall, which is what put the package back on this TV.
        ack, calls, trace = self.run_guard("check:uuid-11", lambda ack, calls, trace: ack, disabled="",
                                           settings_values={"global-device_provisioned": "1"})
        self.assertEqual(ack, "check:uuid-11")
        self.assertEqual(calls, ["disable-user com.mediatek.wwtv.tvcenter",
                                 "disable-user com.homwee.factory",
                                 "disable-user com.google.android.apps.tv.launcherx",
                                 "disable-user com.google.android.tungsten.setupwraith"])
        self.assertIn("home stub frozen", trace)

    def test_the_recovery_stub_is_left_on_before_the_initial_setup(self):
        # The setup wizard has to be able to run, so on a TV that has not finished the initial setup only the
        # packages that never belong to that step are frozen, and the trace records why this one was skipped.
        ack, calls, trace = self.run_guard("check:uuid-12", lambda ack, calls, trace: ack, disabled="")
        self.assertEqual(ack, "check:uuid-12")
        self.assertNotIn("disable-user com.google.android.tungsten.setupwraith", calls)
        self.assertIn("home stub left on: initial setup not finished", trace)

    def test_the_stock_shell_can_be_requested_and_answered(self):
        # It left the protected mirror, so a menu row for it is executed: boot pass plus request.
        ack, calls, _ = self.run_guard("freeze:com.mediatek.wwtv.tvcenter:uuid-9", lambda ack, calls, trace: ack,
                                       disabled="")
        self.assertEqual(ack, "freeze:com.mediatek.wwtv.tvcenter:uuid-9")
        self.assertEqual(calls, ["disable-user com.mediatek.wwtv.tvcenter",
                                 "disable-user com.homwee.factory",
                                 "disable-user com.google.android.apps.tv.launcherx",
                                 "disable-user com.mediatek.wwtv.tvcenter"])

    def test_the_factory_receiver_left_the_protected_mirror_by_owner_decision(self):
        # Verified with a physical gear press on the living-room TV on 2026-11-10: FactoryKeyReciver
        # answers there, so the row freezes the package instead of being refused.
        ack, calls, _ = self.run_guard("freeze:com.homwee.factory:uuid-10", lambda ack, calls, trace: ack,
                                       disabled="")
        self.assertEqual(ack, "freeze:com.homwee.factory:uuid-10")
        self.assertEqual(calls, ["disable-user com.mediatek.wwtv.tvcenter",
                                 "disable-user com.homwee.factory",
                                 "disable-user com.google.android.apps.tv.launcherx",
                                 "disable-user com.homwee.factory"])

    def test_grant_applies_the_declared_permissions_then_answers(self):
        ack, calls, _ = self.run_guard("grant:uuid-4", lambda ack, calls, trace: ack)
        self.assertEqual(ack, "grant:uuid-4")
        self.assertIn("grant ru.pda.tvhome android.permission.RECORD_AUDIO", calls)
        self.assertIn("appops ru.pda.tvhome RECORD_AUDIO allow", calls)
        self.assertIn("appops ru.pda.tvhome SYSTEM_ALERT_WINDOW allow", calls)
        self.assertIn("appops ru.pda.tvhome GET_USAGE_STATS allow", calls)

    def test_a_refused_grant_answers_nothing(self):
        ack, _, trace = self.run_guard("grant:uuid-5", lambda ack, calls, trace: "freeze done" in trace, ok=False)
        self.assertEqual(ack, "")

    def test_unfreeze_reopens_the_package_and_answers(self):
        ack, calls, _ = self.run_guard("unfreeze:com.homwee.help:uuid-6", lambda ack, calls, trace: ack)
        self.assertEqual(ack, "unfreeze:com.homwee.help:uuid-6")
        self.assertIn("enable com.homwee.help", calls)
        self.assertNotIn("enable com.google.android.gms", calls)

    def test_guard_survives_pm_answers_nothing_early_at_boot(self):
        # On the real device pm answered nothing before the user was unlocked right after a reboot,
        # and the first check killed the old guard before its start freeze. The guard now waits:
        # the first three passes fail, the fourth freezes the three start packages; the check is
        # answered as soon as settings work. The answer therefore comes before the freeze, so the test waits
        # for the freeze itself instead of for the first answer.
        ack, calls, _ = self.run_guard("check:uuid-11", lambda ack, calls, trace: len(calls) >= 3, disabled="",
                                       path_fail=True, timeout=180)
        self.assertEqual(ack, "check:uuid-11")
        self.assertEqual(calls, ["disable-user com.mediatek.wwtv.tvcenter",
                                 "disable-user com.homwee.factory",
                                 "disable-user com.google.android.apps.tv.launcherx"])

    def test_no_freeze_while_pm_is_not_ready(self):
        # Before the user is unlocked the guard must not freeze anything. On the real device settings
        # also fail there, so the guard does nothing until unlock; in the test settings work, so the
        # check is answered but the freeze waits for the first working pm pass. The failure count is
        # high enough that the guard cannot reach the pass where pm would answer within the run.
        ack, calls, _ = self.run_guard("check:uuid-12", lambda ack, calls, trace: ack, disabled="",
                                       path_fail=True, fails=2000)
        self.assertEqual(ack, "check:uuid-12")
        self.assertEqual(calls, [])

    def test_old_modes_keep_working(self):
        # The check mode answers as before, without touching any package: only the start freeze
        # remains, and Google Home is now part of it by owner decision on 2026-11-10.
        ack, calls, _ = self.run_guard("check:uuid-7", lambda ack, calls, trace: ack, disabled="")
        self.assertEqual(ack, "check:uuid-7")
        self.assertEqual(calls, ["disable-user com.mediatek.wwtv.tvcenter",
                                 "disable-user com.homwee.factory",
                                 "disable-user com.google.android.apps.tv.launcherx"])


if __name__ == "__main__":
    unittest.main()
