import base64
import os
import re
import shutil
import subprocess
import tempfile
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
PS = shutil.which('pwsh') or shutil.which('powershell')

FAKE = r'''param([Parameter(ValueFromRemainingArguments=$true)][string[]]$Arguments)
$a = $Arguments -join ' '
Add-Content $env:SETUP_LOG $a
if ($a -match 'get-state') { 'device'; exit 0 }
if ($a -match 'getprop') { '34'; exit 0 }
if ($a -match 'resolve-activity') {
 if (Test-Path "$env:SETUP_LOG.home") { 'ru.pda.tvhome/ru.pda.tvhome.MainActivity' } else { 'stock/.Home' }; exit 0
}
if ($a -match 'set-home-activity') { Set-Content "$env:SETUP_LOG.home" 'set'; 'Success'; exit 0 }
if ($a -match 'pm path com.google') { 'package:/google.apk'; exit 0 }
if ($a -match 'pm path') { exit 0 }
if ($a -match 'install -r') { if ($env:SETUP_FAIL -eq 'install') { 'Failure [INSTALL_FAILED_UPDATE_INCOMPATIBLE]' } else { 'Success' }; exit 0 }
if ($a -match 'appops get') { $op=$Arguments[-1]; if ($env:SETUP_FAIL -eq 'permission') { "${op}: default" } else { "${op}: allow" }; exit 0 }
if ($a -match 'dumpsys package') { 'android.permission.RECORD_AUDIO: granted=true'; 'android.permission.WRITE_SECURE_SETTINGS: granted=true'; exit 0 }
if ($a -match 'settings put secure ru_pda_tvhome_assistant_request') { Set-Content "$env:SETUP_LOG.ack" $Arguments[-1]; exit 0 }
if ($a -match 'settings get secure ru_pda_tvhome_assistant_ack') { Get-Content "$env:SETUP_LOG.ack"; exit 0 }
if ($a -match 'settings get') { 'stock/.Assistant'; exit 0 }
if ($a -match 'connect|appops set|pm grant|push|nohup|am start') { exit 0 }
throw "Unexpected ADB call: $a"
'''

HARNESS = r'''
function Read-Host { param($Prompt) if ($Prompt -like 'IP ТВ*') { '192.168.1.50:5555' } else { '' } }
function Invoke-RestMethod {
 [pscustomobject]@{assets=@([pscustomobject]@{name='update.json';browser_download_url='https://fixture/metadata'},[pscustomobject]@{name='TVHome-2.0.3.apk';browser_download_url='https://fixture/apk'})}
}
function Invoke-WebRequest {
 param($Uri,$OutFile,[switch]$UseBasicParsing)
 if ($Uri -like '*metadata') {
  $hash=(Get-FileHash $env:SETUP_APK -Algorithm SHA256).Hash
  if ($env:SETUP_FAIL -eq 'hash') { $hash='0'*64 }
  @{apkName='TVHome-2.0.3.apk';sha256=$hash} | ConvertTo-Json | Set-Content $OutFile
 } elseif ($Uri -like '*fixture/apk') { Copy-Item $env:SETUP_APK $OutFile }
 else { Set-Content $OutFile 'fake zip' }
}
function Expand-Archive {
 param($LiteralPath,$DestinationPath)
 New-Item -ItemType Directory (Join-Path $DestinationPath 'platform-tools') | Out-Null
 Copy-Item $env:SETUP_ADB (Join-Path $DestinationPath 'platform-tools/adb.ps1')
}
. $env:SETUP_SCRIPT
'''


@unittest.skipUnless(PS, 'PowerShell unavailable')
class SetupTest(unittest.TestCase):
    def scenario(self, fail=''):
        with tempfile.TemporaryDirectory(prefix='setup-test-') as folder:
            work = Path(folder)
            source = (ROOT / 'Setup-TVHome.ps1').read_text(encoding='utf-8-sig')
            # Redirect filesystem and native ADB boundaries; keep installer logic intact.
            source = source.replace("([Environment]::GetFolderPath('LocalApplicationData'))", '$env:SETUP_WORK')
            source = source.replace("'platform-tools/adb.exe'", "'platform-tools/adb.ps1'")
            for name, content in [('setup.ps1', source), ('adb.ps1', FAKE), ('harness.ps1', HARNESS)]:
                (work / name).write_text(content, encoding='utf-8-sig')
            (work / 'app.apk').write_bytes(b'fixture APK')
            env = dict(os.environ, SETUP_LOG=str(work / 'adb.log'), SETUP_FAIL=fail,
                       SETUP_APK=str(work / 'app.apk'), SETUP_ADB=str(work / 'adb.ps1'),
                       SETUP_WORK=str(work), SETUP_SCRIPT=str(work / 'setup.ps1'))
            result = subprocess.run([PS, '-NoProfile', '-NonInteractive', '-ExecutionPolicy', 'Bypass',
                                     '-File', str(work / 'harness.ps1')], env=env,
                                    capture_output=True, text=True, timeout=60)
            log = (work / 'adb.log').read_text(encoding='utf-8-sig')
            return result, log

    def test_success(self):
        result, calls = self.scenario()
        self.assertEqual(result.returncode, 0, result.stdout + result.stderr)
        for expected in ['install -r', 'appops set ru.pda.tvhome SYSTEM_ALERT_WINDOW allow',
                         'GET_USAGE_STATS allow', 'pm grant ru.pda.tvhome android.permission.RECORD_AUDIO',
                         'pm grant ru.pda.tvhome android.permission.WRITE_SECURE_SETTINGS',
                         'set-home-activity ru.pda.tvhome/ru.pda.tvhome.MainActivity',
                         'assistant-package-guard.sh', 'am start']:
            self.assertIn(expected, calls)
        self.assertLess(calls.index('install -r'), calls.index('pm grant'))
        self.assertLess(calls.index('set-home-activity'), calls.index('am start'))
        self.assertNotIn('disable-user', calls)
        self.assertNotIn('uninstall', calls)

    def test_hash_failure_stops_install(self):
        result, calls = self.scenario('hash')
        self.assertNotEqual(result.returncode, 0)
        self.assertNotIn('install -r', calls)

    def test_install_failure_stops_configuration(self):
        result, calls = self.scenario('install')
        self.assertNotEqual(result.returncode, 0)
        self.assertNotIn('pm grant', calls)
        self.assertNotIn('set-home-activity', calls)

    def test_permission_failure_stops_home_change(self):
        result, calls = self.scenario('permission')
        self.assertNotEqual(result.returncode, 0)
        self.assertNotIn('set-home-activity', calls)

    def test_embedded_helper_matches_source(self):
        source = (ROOT / 'Setup-TVHome.ps1').read_text(encoding='utf-8-sig')
        encoded = re.search(r"\$guardBase64 = '([^']+)'", source)[1]
        self.assertEqual(base64.b64decode(encoded).decode(),
                         (ROOT / 'tools/assistant-package-guard.sh').read_text())


if __name__ == '__main__':
    unittest.main()
