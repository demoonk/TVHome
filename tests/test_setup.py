import base64
import hashlib
import json
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
if ($a -match 'get-state') {
 if ($env:SETUP_FAIL -eq 'offline') { 'unauthorized'; exit 1 }
 if ($env:SETUP_FAIL -eq 'retry' -and -not (Test-Path "$env:SETUP_LOG.retry")) { Set-Content "$env:SETUP_LOG.retry" '1'; 'unauthorized'; exit 1 }
 'device'; exit 0
}
if ($a -match 'getevent -lp') {
 if ($env:SETUP_FAIL -in @('bridge', 'bridge-sound-missing', 'home-recovery')) { "add device 1: /dev/input/event7`n  name:     `"TVC0GWBT Consumer Control`"`n KEY_UNKNOWN MSC_SCAN" }
 exit 0
}
if ($a -match 'dumpsys input') { 'InputFilterEnabled: false'; exit 0 }
if ($a -match 'pidof pqtv-bridge') { exit 0 }
if ($a -match 'cat /data/local/tmp/tvhome-remote-2.log') {
 if ($env:SETUP_FAIL -eq 'bridge-sound-missing') { 'PQ_REMOTE_READY device=/dev/input/event7 size=24 long_ms=700 picture=available sound=unavailable'; exit 0 }
 'PQ_REMOTE_READY device=/dev/input/event7 size=24 long_ms=700 picture=available sound=available'; exit 0
}
if ($a -match 'getprop ro.product.cpu.abi') { 'arm64-v8a'; exit 0 }
if ($a -match 'settings get global device_provisioned') { if ($env:SETUP_FAIL -eq 'not-provisioned') { '0' } else { '1' }; exit 0 }
if ($a -match 'pm path com.mediatek.wwtv.setupwizard') { if ($env:SETUP_FAIL -eq 'freeze-not-installed') { exit 0 }; 'package:/system/app/SetupWizard/base.apk'; exit 0 }
if ($a -match 'pm list packages -d ') { $pkg = $Arguments[-1]; if ((Test-Path "$env:SETUP_LOG.disabled") -and (@(Get-Content "$env:SETUP_LOG.disabled") -contains $pkg)) { "package:$pkg" }; exit 0 }
if ($a -match 'pm disable-user --user 0 (?<pkg>[A-Za-z0-9._]+)') {
 $pkg=$Matches['pkg']; Add-Content "$env:SETUP_LOG.disabled" $pkg
 if ($pkg -eq 'com.google.android.apps.tv.launcherx') { Set-Content "$env:SETUP_LOG.disabledhome" '1' }
 exit 0
}
if ($a -match 'dumpsys package io.github.sumyabazar1.weather') { 'android.permission.INTERNET: granted=true'; exit 0 }
if ($a -match 'am broadcast') { 'Broadcast receiver: io.github.sumyabazar1.weather/.RefreshReceiver'; exit 0 }
if ($a -match 'getprop') { '34'; exit 0 }
if ($a -match 'role get-role-holders') {
 if ($env:SETUP_FAIL -in @('home-role', 'home-recovery')) { 'ru.pda.tvhome' }
 exit 0
}
if ($a -match 'dumpsys voiceinteraction') { 'mComponent=ru.pda.tvhome/.LauncherAssistantService'; 'mBound=true'; exit 0 }
if ($a -match 'pm disable-user --user 0 com.google.android.apps.tv.launcherx') { Set-Content "$env:SETUP_LOG.disabledhome" '1'; exit 0 }
if ($a -match 'pm enable --user 0 com.google.android.apps.tv.launcherx') { Set-Content "$env:SETUP_LOG.restoredhome" '1'; exit 0 }
if ($a -match 'resolve-activity') {
 if ($env:SETUP_FAIL -in @('home-role', 'home-unverified', 'home-recovery')) { 'com.google.android.apps.tv.launcherx/.home.HomeActivity'; exit 0 }

 if (Test-Path "$env:SETUP_LOG.home") { 'ru.pda.tvhome/ru.pda.tvhome.MainActivity' } else { 'stock/.Home' }; exit 0
}
if ($a -match 'set-home-activity') { Set-Content "$env:SETUP_LOG.home" 'set'; 'Success'; exit 0 }
if ($a -match 'pm path com.google') { 'package:/google.apk'; exit 0 }
if ($a -match 'pm path') {
 if ($env:SETUP_FAIL -eq 'backup-progress') { 'package:/data/app/installed/base.apk' }
 exit 0
}
if ($a -match ' pull ') {
 Write-Error '1 file pulled, 0 skipped. 26.3 MB/s' -ErrorAction Continue
 Set-Content $Arguments[-1] 'previous APK'
 exit 0
}
if ($a -match 'install -r') { if ($env:SETUP_FAIL -eq 'install') { 'Failure [INSTALL_FAILED_UPDATE_INCOMPATIBLE]' } else { 'Success' }; exit 0 }
if ($a -match 'appops get') { $op=$Arguments[-1]; if ($env:SETUP_FAIL -eq 'permission') { "${op}: default" } else { "${op}: allow" }; exit 0 }
if ($a -match 'dumpsys package') { 'android.permission.RECORD_AUDIO: granted=true'; 'android.permission.WRITE_SECURE_SETTINGS: granted=true'; exit 0 }
if ($a -match 'settings put secure ru_pda_tvhome_assistant_request') {
 if ($env:SETUP_FAIL -eq 'guard-dead' -and $Arguments[-1] -like 'launcher:*') { exit 0 }
 Set-Content "$env:SETUP_LOG.ack" $Arguments[-1]; exit 0
}
if ($a -match 'cat /data/local/tmp/assistant-package-guard.pid') { if ($env:SETUP_FAIL -eq 'guard-no-pid') { 'No such file'; exit 0 } '12345'; exit 0 }
# Process table: the installer proves the guard is the process behind the pid in the file, not just that
# the pid exists. A stale pid belongs to another program, so the identity check must fail.
if ($a -match 'cat /proc/(?<pid>[0-9]+)/cmdline') {
 $procPid = $Matches['pid']
 if ($procPid -eq '12345') {
  if ($env:SETUP_FAIL -eq 'guard-stale-pid') { 'app_process /system/bin' }
  elseif ($env:SETUP_FAIL -eq 'guard-cmdline-unreadable') { exit 1 }
  else { '/system/bin/sh /data/local/tmp/assistant-package-guard.sh' }
  exit 0
 }
 'app_process /system/bin --nice-name=pqtv-bridge PqRemoteProof /dev/input/event7 24 0'; exit 0
}
if ($a -match ' kill [0-9]+$') { exit 0 }
if ($a -match 'ls -d /proc/') { 'ok'; exit 0 }
if ($a -match 'settings get secure ru_pda_tvhome_assistant_ack') { Get-Content "$env:SETUP_LOG.ack"; exit 0 }
if ($a -match 'settings put secure (assistant|voice_interaction_service|voice_recognition_service) ') {
 Set-Content ("$env:SETUP_LOG.setting." + $Arguments[-2]) $Arguments[-1]; exit 0
}
if ($a -match 'settings get secure voice_interaction_service' -and (Test-Path "$env:SETUP_LOG.setting.voice_interaction_service")) {
 if ($env:SETUP_FAIL -eq 'voice-race' -and -not (Test-Path "$env:SETUP_LOG.race")) {
  Set-Content "$env:SETUP_LOG.race" '1'
  Set-Content "$env:SETUP_LOG.setting.voice_interaction_service" 'stock/.Assistant'
 }
 Get-Content "$env:SETUP_LOG.setting.voice_interaction_service"; exit 0
}
if ($a -match 'settings put secure') { exit 0 }
if ($a -match 'dumpsys activity') {
 if ($env:SETUP_FAIL -eq 'home-recovery') { 'topResumedActivity= com.google.android.tungsten.setupwraith/.RecoveryActivity'; exit 0 }
 if ($env:SETUP_FAIL -eq 'home-transition') {
  $count=0; if(Test-Path "$env:SETUP_LOG.polls") { $count=[int](Get-Content "$env:SETUP_LOG.polls") }
  Set-Content "$env:SETUP_LOG.polls" ($count+1)
  if($count -lt 3) { 'topResumedActivity= com.google.android.tungsten.setupwraith/.RecoveryActivity'; exit 0 }
 }
 'topResumedActivity= ru.pda.tvhome/.MainActivity'; exit 0
}
if ($a -match 'am start' -and $a -notmatch ' -n ') {
 if ($env:SETUP_FAIL -eq 'home-no-activity') { 'Status: ok'; exit 0 }
 if ($env:SETUP_FAIL -in @('home-role', 'home-unverified') -and -not (Test-Path "$env:SETUP_LOG.disabledhome")) { 'Activity: com.google.android.apps.tv.launcherx/.home.HomeActivity' } else { 'Activity: ru.pda.tvhome/.MainActivity' }; exit 0
}
if ($a -match 'settings get') { 'stock/.Assistant'; exit 0 }
if ($a -match 'am start') {
 if ($env:SETUP_FAIL -eq 'launch') { 'Error: Activity not started'; exit 1 }
 'Status: ok'; exit 0
}
if ($a -match 'connect|appops set|pm grant|push|nohup|setsid') { exit 0 }
throw "Unexpected ADB call: $a"
'''

HARNESS = r'''
function Read-Host { param($Prompt) if ($Prompt -like 'IP ТВ*') { "$env:SETUP_ADDRESS" } else { "$env:SETUP_ANSWER" } }
function Invoke-RestMethod {
 [pscustomobject]@{assets=@([pscustomobject]@{name='update.json';browser_download_url='https://fixture/metadata'},[pscustomobject]@{name='TVHome-2.0.3.apk';browser_download_url='https://fixture/apk'},[pscustomobject]@{name='TVHome-weather.apk';browser_download_url='https://fixture/weather'},[pscustomobject]@{name='TVHome-remote-2.0.3.dex';browser_download_url='https://fixture/dex'},[pscustomobject]@{name='SHA256SUMS.txt';browser_download_url='https://fixture/sums'})}
}
function Invoke-WebRequest {
 param($Uri,$OutFile,[switch]$UseBasicParsing,$TimeoutSec)
 Add-Content "$env:SETUP_LOG.downloads" $Uri
 if ($env:SETUP_FAIL -eq 'network' -and $Uri -like '*metadata' -and -not (Test-Path "$env:SETUP_LOG.network")) {
  Set-Content "$env:SETUP_LOG.network" 'failed once'
  throw '504 Gateway Time-out'
 }
 if ($Uri -like '*metadata') {
  $hash=(Get-FileHash $env:SETUP_APK -Algorithm SHA256).Hash
  if ($env:SETUP_FAIL -eq 'hash') { $hash='0'*64 }
  @{apkName='TVHome-2.0.3.apk';sha256=$hash} | ConvertTo-Json | Set-Content $OutFile
 } elseif ($Uri -like '*fixture/sums') { $sums = @(((Get-FileHash $env:SETUP_APK -Algorithm SHA256).Hash + '  TVHome-remote-2.0.3.dex')); if ($env:SETUP_FAIL -ne 'weather-no-sums') { $sums += ((Get-FileHash $env:SETUP_APK -Algorithm SHA256).Hash + '  TVHome-weather.apk') }; $sums | Set-Content $OutFile
 } elseif ($Uri -like '*fixture/weather') { Copy-Item $env:SETUP_APK $OutFile
 } elseif ($Uri -like '*fixture/dex') { Copy-Item $env:SETUP_APK $OutFile
 } elseif ($Uri -like '*fixture/apk') { Copy-Item $env:SETUP_APK $OutFile }
 else { Set-Content $OutFile 'fake zip' }
}
function Expand-Archive {
 param($LiteralPath,$DestinationPath,[switch]$Force)
 New-Item -ItemType Directory -Force (Join-Path $DestinationPath 'platform-tools') | Out-Null
 Copy-Item $env:SETUP_ADB (Join-Path $DestinationPath 'platform-tools/adb.ps1') -Force
 Set-Content (Join-Path $DestinationPath 'platform-tools/AdbWinApi.dll') 'stub'
 Set-Content (Join-Path $DestinationPath 'platform-tools/AdbWinUsbApi.dll') 'stub'
}
$named = @{}
if ($env:SETUP_ARGS) {
 foreach ($p in ($env:SETUP_ARGS | ConvertFrom-Json).PSObject.Properties) { $named[$p.Name] = $p.Value }
}
. $env:SETUP_SCRIPT @named
'''

# Offline run must not contact GitHub at all: any network call fails the test.
HARNESS_OFFLINE = r'''
param([switch]$Offline)
function Read-Host { param($Prompt) "$env:SETUP_ADDRESS" }
function Invoke-RestMethod { throw 'GitHub was contacted in offline mode' }
function Invoke-WebRequest { throw 'GitHub was contacted in offline mode' }
. $env:SETUP_SCRIPT -Offline
'''


@unittest.skipUnless(PS, 'PowerShell unavailable')
class SetupTest(unittest.TestCase):
    def scenario(self, fail='', address='192.168.1.50:5555', answer='', runs=1, extra_args=None, timeout=60, prepare=None, harness_text=None, named=None):
        with tempfile.TemporaryDirectory(prefix='setup-test-') as folder:
            work = Path(folder)
            source = (ROOT / 'Setup-TVHome.ps1').read_text(encoding='utf-8-sig')
            source = source.replace('(Read-EditableAddress).Trim()', "(Read-Host 'IP ТВ').Trim()")
            # Redirect filesystem and native ADB boundaries; keep installer logic intact.
            source = source.replace("([Environment]::GetFolderPath('LocalApplicationData'))", '$env:SETUP_WORK')
            source = source.replace("'platform-tools/adb.exe'", "'platform-tools/adb.ps1'")
            for name, content in [('setup.ps1', source), ('adb.ps1', FAKE), ('harness.ps1', harness_text or HARNESS)]:
                (work / name).write_text(content, encoding='utf-8-sig')
            (work / 'app.apk').write_bytes(b'fixture APK')
            env = dict(os.environ, SETUP_LOG=str(work / 'adb.log'), SETUP_FAIL=fail, SETUP_ADDRESS=address, SETUP_ANSWER=answer,
                       SETUP_APK=str(work / 'app.apk'), SETUP_ADB=str(work / 'adb.ps1'),
                       SETUP_WORK=str(work), SETUP_SCRIPT=str(work / 'setup.ps1'),
                       SETUP_ARGS=json.dumps(named) if named else '')
            args = [PS, '-NoProfile', '-NonInteractive', '-ExecutionPolicy', 'Bypass', '-File', str(work / 'harness.ps1')]
            if extra_args:
                args += extra_args
            if prepare:
                prepare(work, env)
            result = subprocess.run(args, env=env, capture_output=True, text=True, timeout=timeout)
            if runs > 1:
                first_downloads = (work / 'adb.log.downloads').read_bytes()
                result = subprocess.run(args, env=env, capture_output=True, text=True, timeout=timeout)
                self.assertEqual(first_downloads, (work / 'adb.log.downloads').read_bytes(), 'Second run downloaded files again')
            log = (work / 'adb.log').read_text(encoding='utf-8-sig') if (work / 'adb.log').exists() else ''
            return result, log

    def test_success(self):
        result, calls = self.scenario()
        self.assertEqual(result.returncode, 0, result.stdout + result.stderr)
        for expected in ['install -r', 'appops set ru.pda.tvhome SYSTEM_ALERT_WINDOW allow',
                         'GET_USAGE_STATS allow', 'REQUEST_INSTALL_PACKAGES allow',
                         'pm grant ru.pda.tvhome android.permission.RECORD_AUDIO',
                         'pm grant ru.pda.tvhome android.permission.WRITE_SECURE_SETTINGS',
                         'set-home-activity ru.pda.tvhome/ru.pda.tvhome.MainActivity',
                         'assistant-package-guard.sh', 'am start']:
            self.assertIn(expected, calls)
        self.assertLess(calls.index('install -r'), calls.index('pm grant'))
        self.assertLess(calls.index('set-home-activity'), calls.index('am start'))
        self.assertLess(calls.index('pm disable-user --user 0 com.google.android.katniss'),
                        calls.index('settings put secure voice_interaction_service'))
        self.assertGreaterEqual(calls.count('dumpsys voiceinteraction'), 3)
        self.assertIn('settings put secure ru_pda_tvhome_assistant_request launcher:', calls)
        self.assertNotIn('settings put secure enabled_accessibility_services', calls)
        self.assertIn('pm disable-user --user 0 com.google.android.apps.tv.launcherx', calls)
        self.assertNotIn('uninstall', calls)

    def test_weather_widget_installed_and_permission_checked(self):
        result, calls = self.scenario()
        self.assertEqual(result.returncode, 0, result.stdout + result.stderr)
        self.assertEqual(calls.count('install -r '), 2, 'expected the launcher and the widget installs')
        self.assertIn('dumpsys package io.github.sumyabazar1.weather', calls)
        self.assertIn('am broadcast -a io.github.sumyabazar1.weather.action.REFRESH -n io.github.sumyabazar1.weather/.RefreshReceiver', calls)
        self.assertIn('Виджет погоды установлен: TVHome-weather.apk', result.stdout + result.stderr)

    def test_no_weather_key_skips_widget(self):
        result, calls = self.scenario(named={'NoWeather': True})
        self.assertEqual(result.returncode, 0, result.stdout + result.stderr)
        self.assertEqual(calls.count('install -r '), 1)
        self.assertNotIn('io.github.sumyabazar1.weather', calls)

    def test_weather_without_checksum_is_not_installed(self):
        result, calls = self.scenario('weather-no-sums')
        self.assertEqual(result.returncode, 0, result.stdout + result.stderr)
        self.assertEqual(calls.count('install -r '), 1)
        self.assertNotIn('io.github.sumyabazar1.weather', calls)
        self.assertIn('Нет контрольной суммы', result.stdout + result.stderr)

    def test_freeze_list_applied_after_install(self):
        result, calls = self.scenario()
        self.assertEqual(result.returncode, 0, result.stdout + result.stderr)
        self.assertIn('pm disable-user --user 0 com.mediatek.wwtv.setupwizard', calls)
        self.assertLess(calls.index('install -r'), calls.index('pm disable-user --user 0 com.mediatek.wwtv.setupwizard'))
        self.assertIn('Заморожено: com.mediatek.wwtv.setupwizard', result.stdout + result.stderr)
        # The recovery stub has to be frozen before Home is checked, otherwise the Home key stays with it.
        self.assertIn('pm disable-user --user 0 com.google.android.tungsten.setupwraith', calls)
        self.assertLess(calls.index('set-home-activity'),
                        calls.index('pm disable-user --user 0 com.google.android.tungsten.setupwraith'))

    def test_freeze_refused_for_protected_package(self):
        result, calls = self.scenario(named={'Freeze': ['com.android.systemui']})
        self.assertEqual(result.returncode, 0, result.stdout + result.stderr)
        self.assertNotIn('pm disable-user --user 0 com.android.systemui', calls)
        self.assertIn('Заморозка com.android.systemui запрещена', result.stdout + result.stderr)

    def test_freeze_skips_package_not_installed(self):
        result, calls = self.scenario('freeze-not-installed')
        self.assertEqual(result.returncode, 0, result.stdout + result.stderr)
        self.assertNotIn('pm disable-user --user 0 com.mediatek.wwtv.setupwizard', calls)
        self.assertIn('не установлен', result.stdout + result.stderr)

    def test_freeze_waits_for_finished_initial_setup(self):
        result, calls = self.scenario('not-provisioned')
        self.assertEqual(result.returncode, 0, result.stdout + result.stderr)
        self.assertNotIn('pm disable-user --user 0 com.mediatek.wwtv.setupwizard', calls)
        # The recovery stub keeps HOME only while the setup wizard can still run.
        self.assertNotIn('pm disable-user --user 0 com.google.android.tungsten.setupwraith', calls)
        self.assertIn('Первоначальная настройка прошивки не завершена', result.stdout + result.stderr)

    def test_dead_guard_fails_the_voice_choice(self):
        # The launcher's own path: settings call, then the acknowledgement. Without a live guard the choice
        # is rolled back, so the run must not report success.
        result, calls = self.scenario('guard-dead')
        self.assertNotEqual(result.returncode, 0, result.stdout + result.stderr)
        self.assertIn('settings put secure ru_pda_tvhome_assistant_request launcher:', calls)
        self.assertIn('не ответил на запрос launcher', result.stdout + result.stderr)
        self.assertGreaterEqual(calls.count('settings get secure ru_pda_tvhome_assistant_ack'), 2)

    def test_guard_without_pid_file_is_reported(self):
        result, calls = self.scenario('guard-no-pid')
        self.assertNotEqual(result.returncode, 0, result.stdout + result.stderr)
        self.assertIn('не записал pid-файл', result.stdout + result.stderr)

    def test_guard_pid_of_another_process_is_not_accepted(self):
        # setsid forks, so the pid written to the file can belong to a different program. The installer reads
        # the cmdline of that pid instead of trusting the exit code of ls, so a foreign process must fail.
        result, calls = self.scenario('guard-stale-pid')
        self.assertNotEqual(result.returncode, 0, result.stdout + result.stderr)
        self.assertIn('Помощник не запущен (pid 12345)', result.stdout + result.stderr)
        self.assertGreaterEqual(calls.count('cat /proc/12345/cmdline 2>/dev/null'), 9)
        self.assertNotIn('settings put secure ru_pda_tvhome_assistant_request', calls)

    def test_guard_pid_with_unreadable_cmdline_is_not_accepted(self):
        # A dead pid: cat fails, and the failure must not be mistaken for a running guard.
        result, calls = self.scenario('guard-cmdline-unreadable')
        self.assertNotEqual(result.returncode, 0, result.stdout + result.stderr)
        self.assertIn('Помощник не запущен (pid 12345)', result.stdout + result.stderr)
        self.assertNotIn('settings put secure ru_pda_tvhome_assistant_request', calls)

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

    def test_home_role_accepts_stock_resolver(self):
        result, calls = self.scenario('home-role')
        self.assertEqual(result.returncode, 0, result.stdout + result.stderr)
        self.assertIn('role get-role-holders', calls)
        self.assertIn('assistant-package-guard.sh', calls)
        self.assertIn('am start', calls)

    def test_unverified_home_warns_and_continues(self):
        result, calls = self.scenario('home-unverified')
        self.assertEqual(result.returncode, 0, result.stdout + result.stderr)
        self.assertIn('am start', calls)
        self.assertNotIn('settings put secure enabled_accessibility_services', calls)
        self.assertNotIn('settings put secure enabled_accessibility_services', calls)
        self.assertIn('pm disable-user --user 0 com.google.android.apps.tv.launcherx', calls)

    def test_compatible_remote_starts_bridge_without_input_filter(self):
        result, calls = self.scenario('bridge')
        self.assertEqual(result.returncode, 0, result.stdout + result.stderr)
        self.assertIn('PqRemoteProof /dev/input/event7 24 0', calls)
        self.assertIn('tvhome-remote-2.dex', calls)
        self.assertNotIn('settings put secure enabled_accessibility_services', calls)

    def test_bridge_reports_short_and_long_press_without_extra_argument(self):
        result, calls = self.scenario('bridge')
        self.assertEqual(result.returncode, 0, result.stdout + result.stderr)
        # The published DEX asset reads three arguments; the threshold is its default.
        self.assertIn('PqRemoteProof /dev/input/event7 24 0', calls)
        self.assertNotIn('PqRemoteProof /dev/input/event7 24 0 700', calls)
        output = result.stdout + result.stderr
        self.assertIn('настройки экрана', output)
        self.assertIn('настройки звука', output)

    def test_missing_sound_activity_warns_and_keeps_the_bridge(self):
        result, calls = self.scenario('bridge-sound-missing')
        self.assertEqual(result.returncode, 0, result.stdout + result.stderr)
        self.assertIn('PqRemoteProof /dev/input/event7 24 0', calls)
        output = result.stdout + result.stderr
        self.assertIn('настройки звука на этом ТВ не найдены', output)
        self.assertIn('настройки экрана', output)

    def test_voice_reasserts_selection_after_android_resets_it(self):
        result, calls = self.scenario('voice-race')
        self.assertEqual(result.returncode, 0, result.stdout + result.stderr)
        self.assertGreaterEqual(calls.count('settings put secure voice_interaction_service'), 2)
        self.assertGreaterEqual(calls.count('dumpsys voiceinteraction'), 3)

    def test_home_waits_for_recovery_without_relaunch_loop(self):
        result, calls = self.scenario('home-transition')
        self.assertEqual(result.returncode, 0, result.stdout + result.stderr)
        self.assertEqual(calls.count('dumpsys activity activities'), 4)
        self.assertEqual(calls.count('shell am start -W -a android.intent.action.MAIN -c android.intent.category.HOME\n'), 1)

    def test_recovery_activity_keeps_the_role_and_warns_without_rollback(self):
        # Role assigned, proшивка keeps HOME for its own setup activity: warn, do not
        # roll back Google Home, and continue with the rest of the configuration.
        result, calls = self.scenario('home-recovery', timeout=120)
        self.assertEqual(result.returncode, 0, result.stdout + result.stderr)
        output = result.stdout + result.stderr
        self.assertIn('Роль главного экрана назначена TVHome, но телевизор показал не TVHome', output)
        self.assertNotIn('pm enable --user 0 com.google.android.apps.tv.launcherx', calls)
        self.assertIn('assistant-package-guard.sh', calls)
        self.assertIn('PqRemoteProof /dev/input/event7 24 0', calls)

    def test_offline_clean_cache_never_contacts_github(self):
        def prepare(work, env):
            cache = Path(env['SETUP_WORK']) / 'TVHome' / 'Installer' / 'cache'
            (cache / 'platform-tools').mkdir(parents=True, exist_ok=True)
            shutil.copyfile(str(work / 'adb.ps1'), str(cache / 'platform-tools' / 'adb.ps1'))
            for name in ('AdbWinApi.dll', 'AdbWinUsbApi.dll'):
                (cache / 'platform-tools' / name).write_bytes(b'stub')
            shutil.copyfile(str(work / 'app.apk'), str(cache / 'TVHome-2.0.3.apk'))
            (cache / 'TVHome-remote-2.0.3-longgear-test.dex').write_bytes(b'fixture DEX')
            metadata = {'versionName': '2.0.3', 'versionCode': 86, 'apkName': 'TVHome-2.0.3.apk',
                        'sha256': hashlib.sha256(b'fixture APK').hexdigest()}
            (cache / 'update-test.json').write_text(json.dumps(metadata), encoding='utf-8')
            (cache / 'SHA256SUMS-test.txt').write_text(
                hashlib.sha256(b'fixture DEX').hexdigest() + '  TVHome-remote-2.0.3-longgear-test.dex\n', encoding='utf-8')

        result, calls = self.scenario('bridge', harness_text=HARNESS_OFFLINE, extra_args=['-Offline'],
                                      prepare=prepare, timeout=120)
        self.assertEqual(result.returncode, 0, result.stdout + result.stderr)
        output = result.stdout + result.stderr
        self.assertIn('Использую уже скачанный ADB', output)
        self.assertIn('Данные выпуска: кэш', output)
        self.assertIn('TVHome-remote-2.0.3-longgear-test.dex', calls)
        self.assertIn('PqRemoteProof /dev/input/event7 24 0', calls)
        # The recovery stub is now part of the freeze list, so the Home key reaches TVHome.
        self.assertIn('pm disable-user --user 0 com.google.android.tungsten.setupwraith', calls)

    def test_home_uses_foreground_when_start_omits_activity(self):
        result, calls = self.scenario('home-no-activity')
        self.assertEqual(result.returncode, 0, result.stdout + result.stderr)
        self.assertIn('dumpsys activity activities', calls)
        self.assertNotIn('pm enable --user 0 com.google.android.apps.tv.launcherx', calls)

    def test_failed_replacement_launch_keeps_google_home(self):
        result, calls = self.scenario('launch')
        self.assertNotEqual(result.returncode, 0)
        self.assertNotIn('pm disable-user', calls)

    def test_successful_backup_stderr_is_not_failure(self):
        result, calls = self.scenario('backup-progress')
        self.assertEqual(result.returncode, 0, result.stdout + result.stderr)
        self.assertIn('pull /data/app/installed/base.apk', calls)
        self.assertIn('install -r', calls)
        self.assertIn('am start', calls)

    def test_temporary_download_error_retries(self):
        result, calls = self.scenario('network')
        self.assertEqual(result.returncode, 0, result.stdout + result.stderr)
        self.assertIn('2/5', result.stdout)
        self.assertIn('install -r', calls)

    def test_second_run_reuses_downloads(self):
        result, _ = self.scenario(runs=2)
        self.assertEqual(result.returncode, 0, result.stdout + result.stderr)

    def test_default_port(self):
        result, calls = self.scenario(address='10.0.0.7')
        self.assertEqual(result.returncode, 0, result.stdout + result.stderr)
        self.assertIn('connect 10.0.0.7:5555', calls)
        self.assertNotIn(' pair ', calls)

    def test_invalid_address_stops_before_connection(self):
        result, calls = self.scenario(address='192.168.1.')
        self.assertNotEqual(result.returncode, 0)
        self.assertEqual(calls, '')

    def test_enter_retries_connection(self):
        result, calls = self.scenario('retry')
        self.assertEqual(result.returncode, 0, result.stdout + result.stderr)
        self.assertEqual(calls.count('connect 192.168.1.50:5555'), 2)

    def test_zero_cancels_connection(self):
        result, calls = self.scenario('offline', answer='0')
        self.assertNotEqual(result.returncode, 0)
        self.assertEqual(calls.count('connect 192.168.1.50:5555'), 1)
        self.assertNotIn('install -r', calls)

    def test_embedded_helper_matches_source(self):
        source = (ROOT / 'Setup-TVHome.ps1').read_text(encoding='utf-8-sig')
        encoded = re.search(r"\$guardBase64 = '([^']+)'", source)[1]
        self.assertEqual(base64.b64decode(encoded).decode(),
                         (ROOT / 'tools/assistant-package-guard.sh').read_text())


if __name__ == '__main__':
    unittest.main()
