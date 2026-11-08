param(
    [Parameter(Mandatory=$true)][string]$Device,
    [string]$Adb = 'adb',
    [string]$Query = 'поиск zzzqqqxx',
    [int]$SearchWaitSec = 15
)
# adb пишет предупреждения в stderr; они не должны останавливать проверку.
$ErrorActionPreference = 'Continue'
$adbPath = (Get-Command $Adb -ErrorAction Stop).Source
if ((& $adbPath -s $Device get-state) -ne 'device') { throw 'Device must already be connected and authorized' }
function Invoke-Target([string]$Command) {
    $output = & $adbPath -s $Device shell $Command
    if ($LASTEXITCODE -ne 0) { Write-Host "  adb: $Command -> exit $LASTEXITCODE" }
    return ($output | Out-String).Trim()
}
$failures = 0
function Report([string]$name, [bool]$ok, [string]$detail) {
    $mark = if ($ok) { 'OK  ' } else { 'FAIL' }
    Write-Host ("[{0}] {1} :: {2}" -f $mark, $name, $detail)
    if (-not $ok) { $script:failures++ }
}
$package = 'ru.pda.tvhome'
$assistant = 'ru.pda.tvhome/ru.pda.tvhome.LauncherAssistantService'

'=== 1. Состояние, которое должно остаться неизменным ==='
$versionText = ((Invoke-Target 'dumpsys package ru.pda.tvhome') -split "`n") | Select-String -Pattern 'versionName' | Select-Object -First 1
Report 'версия' ($versionText -ne $null) ($versionText.ToString().Trim())
$homeActivities = Invoke-Target 'cmd package query-activities --brief -a android.intent.action.MAIN -c android.intent.category.HOME'
Report 'главный экран' ($homeActivities -match 'ru\.pda\.tvhome/\.MainActivity') (($homeActivities -split "`n" | Select-String -Pattern '/\.' | Select-Object -First 4) -join ' | ')
Report 'assistant' ((Invoke-Target 'settings get secure assistant') -eq $assistant) (Invoke-Target 'settings get secure assistant')
$vis = Invoke-Target 'settings get secure voice_interaction_service'
Report 'voice_interaction_service' ($vis -eq $assistant) ("значение: '$vis'; пусто значит, что привязка идёт только через assistant — прогрев сработает лишь когда служба ассистента реально привязана")
$inputState = Invoke-Target 'dumpsys input'
Report 'авто-повтор громкости (нет input filter)' ($inputState -match 'InputFilterEnabled:\s*false') ((($inputState -split "`n" | Select-String -Pattern 'InputFilterEnabled') | Select-Object -First 1)).ToString().Trim()
Report 'службы доступности пустые' ((Invoke-Target 'dumpsys accessibility') -match 'Enabled services:\{\}') ((Invoke-Target 'dumpsys accessibility') -split "`n" | Select-String -Pattern 'Enabled services' | Select-Object -First 1)
Report 'мост шестерёнки жив' ((Invoke-Target 'pidof pqtv-bridge') -match '^\d+') (Invoke-Target 'pidof pqtv-bridge')
$disabled = Invoke-Target 'pm list packages -d'
foreach ($pkg in @('com.google.android.inputmethod.latin','com.google.android.webview','com.android.tv.settings','com.mediatek.wwtv.tvcenter','com.mediatek.tvinput','com.google.android.tv.remote.service','com.android.statementservice','com.google.android.tts')) {
    Report "критичный пакет включён: $pkg" (-not ($disabled -match [regex]::Escape($pkg))) 'enabled'
}

'=== 2. Прогрев движка (новая сборка: процесс :search поднимается без речевого запроса) ==='
Invoke-Target 'am force-stop com.google.android.tts' | Out-Null
Invoke-Target "am force-stop $package" | Out-Null
& $adbPath -s $Device shell 'input keyevent KEYCODE_WAKEUP' | Out-Null
Invoke-Target "am start -n $package/.MainActivity" | Out-Null
Start-Sleep -Seconds 15
$warmed = Invoke-Target 'ps -A'
Report 'прогрев движка при готовности ассистента' ($warmed -match 'com\.google\.android\.tts:search') (($warmed -split "`n" | Select-String -Pattern 'tts' | Select-Object -First 2) -join ' | ')

'=== 3. Холодный старт и повторное нажатие во время прослушивания ==='
& $adbPath -s $Device shell 'input keyevent KEYCODE_WAKEUP' | Out-Null
Invoke-Target 'logcat -c' | Out-Null
Invoke-Target "am start -W -n $package/.VoiceLaunchActivity --ez remote_microphone true" | Out-Null
Start-Sleep -Seconds 4
Invoke-Target "am start -n $package/.VoiceLaunchActivity --ez remote_microphone true" | Out-Null
Start-Sleep -Seconds 14
$log = Invoke-Target 'logcat -d'
$listening = @($log -split "`n" | Select-String -Pattern 'Online recognizer - start listening')
$starts = @($log -split "`n" | Select-String -Pattern 'GoogleTTSActivity' | Select-String -Pattern 'START ')
Report 'первое прослушивание началось' ($listening.Count -ge 1) ("start listening: " + $listening.Count)
Report 'повторное обращение возобновляет распознавание' ($listening.Count -ge 2) ("start listening: " + $listening.Count + ", speech UI start: " + $starts.Count)

'=== 4. Кнопка для строк, которые не дают карточки ==='
Invoke-Target "am force-stop $package" | Out-Null
& $adbPath -s $Device shell 'input keyevent KEYCODE_WAKEUP' | Out-Null
Invoke-Target "am start -W -n $package/.VoiceLaunchActivity --es command '$Query'" | Out-Null
Start-Sleep -Seconds $SearchWaitSec
Invoke-Target 'uiautomator dump /sdcard/v.json' | Out-Null
$dump = Invoke-Target 'cat /sdcard/v.json'
$rows = New-Object Collections.ArrayList
foreach ($node in ($dump -split '<node ')) {
    $text = [regex]::Match($node, 'text="([^"]+)"').Groups[1].Value
    $desc = [regex]::Match($node, 'content-desc="([^"]+)"').Groups[1].Value
    if ($text -or $desc) { [void]$rows.Add([pscustomobject]@{ Desc=$desc; Text=$text }) }
}
$statuses = @('Ничего не найдено','Поиск не ответил. Повторите запрос позже','Поиск сейчас недоступен','Результаты доступны внутри приложения')
$rowsWithoutCards = 0
for ($i = 0; $i -lt $rows.Count; $i++) {
    if ($statuses -contains $rows[$i].Text) {
        $rowsWithoutCards++
        # Имя строки — предыдущий текстовый узел; кнопка принадлежит этой строке,
        # пока не началась следующая.
        $name = if ($i -gt 0) { $rows[$i - 1].Text } else { '' }
        $button = $null
        for ($j = $i + 1; $j -lt $rows.Count; $j++) {
            if ($statuses -contains $rows[$j].Text) { break }
            if ($rows[$j].Desc -match '^(Искать в|Открыть) ') { $button = $rows[$j].Desc; break }
        }
        Report ("строка '$name' без карточек имеет кнопку") ($button -ne $null) ("статус: $($rows[$i].Text) | кнопка: $button")
    }
}
Report 'есть строки без карточек' ($rowsWithoutCards -ge 1) ("строк: $rowsWithoutCards")
Write-Host ("Итог: отказов {0}" -f $failures)
Write-Host 'На 2.0.3 пункты 2, 3 (вторая запись) и кнопка в строках без карточек ожидаемо FAIL: этих изменений там нет.'
Invoke-Target "am start -n $package/.MainActivity" | Out-Null
'=== Конец ==='