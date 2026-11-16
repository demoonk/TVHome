#!/usr/bin/env python3
"""Offline smoke test of the user-facing installer scripts.

Runs install.ps1 / install.sh against a fake adb that records every call and
answers like a healthy TV, so the documented commands are proven to work
without touching any real device.
"""
import os
import shutil
import subprocess
import sys
import tempfile

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
CALLS = []

FAKE_ADB_PY = r'''
import sys
args = sys.argv[1:]
line = " ".join(args)
open(r"{log}", "a", encoding="utf-8").write(line + "\n")
if args[:1] == ["connect"]:
    print("connected to " + args[1]); sys.exit(0)
if args[:1] == ["devices"]:
    print("List of devices attached"); print(sorted({{"192.168.50.7:5555"}})[0] + "\tdevice"); sys.exit(0)
if "-s" in args:
    rest = args[args.index("-s") + 2:]
    if rest[:1] == ["get-state"]:
        print("device"); sys.exit(0)
    if rest[:1] == ["shell"] and "ro.build.version.sdk" in rest:
        print("34"); sys.exit(0)
    if rest[:1] == ["shell"] and "appops" in rest and "get" in rest:
        name = [token for token in rest if token in ("SYSTEM_ALERT_WINDOW", "GET_USAGE_STATS")]
        print((name[0] if name else "SYSTEM_ALERT_WINDOW") + ": allow"); sys.exit(0)
    if rest[:1] == ["shell"] and "am" in rest and "start" in rest:
        print("Starting: Intent {{ cmp=ru.pda.tvhome/.MainActivity }}"); sys.exit(0)
    if rest[:1] == ["install"]:
        print("Performing Streamed Install"); print("Success"); sys.exit(0)
    if rest[:1] == ["pull"]:
        print("1 file pulled"); sys.exit(0)
    if rest[:1] == ["shell"]:
        sys.exit(0)
sys.exit(0)
'''


def make_fake_adb(directory):
    log = os.path.join(directory, "calls.log")
    script = os.path.join(directory, "fake_adb.py")
    with open(script, "w", encoding="utf-8") as handle:
        handle.write(FAKE_ADB_PY.replace("{log}", log.replace("\\", "\\\\")))
    if os.name == "nt":
        launcher = os.path.join(directory, "fake_adb.cmd")
        with open(launcher, "w", encoding="utf-8") as handle:
            handle.write('@echo off\r\npython "%s" %%*\r\n' % script)
        return launcher, log
    wrapper = os.path.join(directory, "adb")
    with open(wrapper, "w", encoding="utf-8") as handle:
        handle.write('#!/bin/sh\nexec python3 "%s" "$@"\n' % script)
    os.chmod(wrapper, 0o755)
    return wrapper, log


def run(label, command, workdir):
    print("==", label)
    result = subprocess.run(command, cwd=workdir, capture_output=True, text=True)
    output = (result.stdout or "") + (result.stderr or "")
    print(output.strip()[:1500])
    print("exit:", result.returncode)
    return result.returncode, output


def main():
    work = tempfile.mkdtemp(prefix="tvhome-smoke-")
    try:
        apk = os.path.join(work, "TVHome-2.1.0.apk")
        source_apk = os.path.join(ROOT, "..", "build-output", "TVHome-2.1.0.apk")
        shutil.copyfile(source_apk, apk)
        adb, log = make_fake_adb(work)
        failures = []

        if os.name == "nt":
            code, out = run(
                "install.ps1",
                ["powershell", "-NoProfile", "-ExecutionPolicy", "Bypass", "-File",
                 os.path.join(ROOT, "install.ps1"), "-Device", "192.168.50.7:5555",
                 "-Apk", apk, "-Adb", adb],
                work)
        else:
            code, out = run(
                "install.sh",
                ["sh", os.path.join(ROOT, "install.sh"), "192.168.50.7:5555", apk, adb],
                work)
        if code != 0:
            failures.append("installer exited %s" % code)
        if "Success" not in out and "установлен" not in out.lower():
            failures.append("installer did not report success")

        calls = open(log, encoding="utf-8").read() if os.path.exists(log) else ""
        for expected in ("connect", "install -r", "am start"):
            if expected.split()[0] not in calls:
                failures.append("adb call %r never happened" % expected)
        if "install -r" not in calls:
            failures.append("APK was not installed in place")

        print("\nrecorded adb calls:")
        for line in calls.strip().splitlines():
            print("  adb " + line)

        if failures:
            print("\nFAILURES:")
            for item in failures:
                print("  - " + item)
            return 1
        print("\nOK: documented installer command runs end to end against a fake TV")
        return 0
    finally:
        shutil.rmtree(work, ignore_errors=True)


if __name__ == "__main__":
    sys.exit(main())
