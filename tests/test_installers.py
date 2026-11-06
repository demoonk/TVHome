import os
import shutil
import subprocess
import tempfile
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
PS = shutil.which("pwsh") or shutil.which("powershell")
BASH = str(Path(os.environ.get("ProgramFiles", "C:/Program Files")) / "Git/bin/bash.exe") if os.name == "nt" else shutil.which("bash")
if BASH and not Path(BASH).exists():
    BASH = None

FAKE_PS = r"""param([Parameter(ValueFromRemainingArguments=$true)][string[]]$Arguments)
Add-Content -LiteralPath $env:FAKE_ADB_LOG -Value ($Arguments -join ' ')
if($Arguments[0] -eq 'connect'){Write-Output 'connected';exit 0}
if($Arguments -contains 'get-state'){Write-Output 'device';exit 0}
if($Arguments -contains 'getprop'){Write-Output $env:FAKE_SDK;exit 0}
if($Arguments -contains 'path'){exit 0}
if($Arguments -contains 'install'){
 if($env:FAKE_FAIL -eq '1'){Write-Output 'Failure [INSTALL_FAILED_UPDATE_INCOMPATIBLE]';exit 0}
 Write-Output 'Success';exit 0
}
if($Arguments -contains 'start'){Write-Output 'Status: ok';exit 0}
exit 1
"""
FAKE_SH = r"""#!/bin/sh
printf '%s\n' "$*" >> "$FAKE_ADB_LOG"
case " $* " in
 *' connect '*) echo connected;;
 *' get-state '*) echo device;;
 *' getprop '*) echo "$FAKE_SDK";;
 *' pm path '*) exit 0;;
 *' install '*) if [ "$FAKE_FAIL" = 1 ]; then echo 'Failure [INSTALL_FAILED_UPDATE_INCOMPATIBLE]'; else echo Success; fi;;
 *' am start '*) echo 'Status: ok';;
 *) exit 1;;
esac
"""

class InstallersTest(unittest.TestCase):
    def scenario(self, kind, sdk="34", fail="0"):
        with tempfile.TemporaryDirectory(prefix="installer-test-", dir=ROOT / "tests") as temp:
            work = Path(temp).resolve()
            assert work.is_relative_to(ROOT)
            log = work / "adb.log"
            apk = work / "update with spaces.apk"
            apk.write_bytes(b"fake APK handled by stub only")
            env = dict(os.environ, FAKE_ADB_LOG=str(log), FAKE_SDK=sdk, FAKE_FAIL=fail)
            if kind == "ps":
                fake = work / "fake-adb.ps1"
                fake.write_text(FAKE_PS, encoding="utf-8")
                cmd = [PS, "-NoProfile", "-NonInteractive", "-ExecutionPolicy", "Bypass", "-File", str(ROOT / "install.ps1"), "-Device", "127.0.0.1:5555", "-Apk", str(apk), "-Adb", str(fake)]
            else:
                fake = work / "fake-adb.sh"
                fake.write_text(FAKE_SH, encoding="utf-8", newline="\n")
                fake.chmod(0o755)
                cmd = [BASH, str(ROOT / "install.sh"), "127.0.0.1:5555", str(apk), str(fake)]
            result = subprocess.run(cmd, env=env, capture_output=True, text=True, timeout=30)
            calls = log.read_text(encoding="utf-8-sig") if log.exists() else ""
            return result, calls

    def check_success(self, kind):
        result, calls = self.scenario(kind)
        self.assertEqual(0, result.returncode, result.stdout + result.stderr)
        self.assertIn("install -r ", calls)
        self.assertIn("ru.pda.tvhome/ru.pda.tvhome.MainActivity", calls)
        for forbidden in ["uninstall", "disable-user", "pm clear", "set-home-activity", "force-stop"]:
            self.assertNotIn(forbidden, calls)

    def check_failed_install(self, kind):
        result, calls = self.scenario(kind, fail="1")
        self.assertNotEqual(0, result.returncode)
        self.assertNotIn("am start", calls)

    def check_old_android(self, kind):
        result, calls = self.scenario(kind, sdk="25")
        self.assertNotEqual(0, result.returncode)
        self.assertNotIn("install -r", calls)
        self.assertNotIn("am start", calls)

    @unittest.skipUnless(PS, "PowerShell unavailable")
    def test_powershell_success(self): self.check_success("ps")
    @unittest.skipUnless(PS, "PowerShell unavailable")
    def test_powershell_install_failure(self): self.check_failed_install("ps")
    @unittest.skipUnless(PS, "PowerShell unavailable")
    def test_powershell_old_android(self): self.check_old_android("ps")
    @unittest.skipUnless(BASH, "Bash unavailable")
    def test_shell_success(self): self.check_success("sh")
    @unittest.skipUnless(BASH, "Bash unavailable")
    def test_shell_install_failure(self): self.check_failed_install("sh")
    @unittest.skipUnless(BASH, "Bash unavailable")
    def test_shell_old_android(self): self.check_old_android("sh")

if __name__ == "__main__": unittest.main()
