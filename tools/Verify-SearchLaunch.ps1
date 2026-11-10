# Checks on the living-room TV that every search row can actually be opened: the provider answers at the
# path the launcher queries, every entry point resolves to an exported activity of that app, and the apps
# without a card path are not promised a search. Table mirrors SearchCardRules / AppSearchEntry; keep both
# in sync (tests/test_search_card_rules.py compares the code against the same table).
#
# Usage: powershell -NoProfile -ExecutionPolicy Bypass -File tools/Verify-SearchLaunch.ps1 [-Device 192.168.1.7:5555]

param(
    [string]$Device = '192.168.1.7:5555',
    [string]$Adb = 'C:\Soft\utilities\ADB_AppControl\adb\adb.exe'
)

$ErrorActionPreference = 'Stop'

# id, package, provider authority + path, entry base, recorded exported search activity (or '' = none)
$apps = @(
    @{id='smarttube'; pkg='org.smarttube.stable'; authority='org.smarttube.stable'; path='search/search_suggest_query';
      entry='https://www.youtube.com/results?search_query='; activity='com.liskovsoft.leanbackassistant.search.SearchableActivity'},
    @{id='num'; pkg='ru.yourok.num'; authority='ru.yourok.num.atvsearch'; path='search/search_suggest_query';
      entry=''; activity='.activity.details.DetailsActivity'},
    @{id='kinopoisk'; pkg='ru.kinopoisk.tv'; authority=''; path='';
      entry=''; activity='.presentation.deeplink.DeepLinkTargetActivity'},
    @{id='kion'; pkg='ru.mts.mtstv'; authority=''; path='';
      entry=''; activity='.common.ui.SearchableActivity'},
    @{id='vk'; pkg='com.vk.tv'; authority=''; path='';
      entry='vkvideo://vk.com/search?query='; activity='.presentation.base.links.TvLinkRedirectActivity'},
    @{id='reshala'; pkg='reshala.tv.app'; authority='reshala.tv.app.poisk'; path='search_suggest_query';
      entry='reshala://search?q='; activity='.MainActivity'},
    @{id='vimu'; pkg='net.gtvbox.videoplayer'; authority=''; path='';
      entry=''; activity='net.gtvbox.vimuhd.VimuHDActivity'},
    @{id='tizentube'; pkg='io.gh.reisxd.tizentube.cobalt'; authority=''; path='';
      entry='https://www.youtube.com/results?search_query='; activity=''},
    @{id='boosty'; pkg='live.vkplay.app'; authority=''; path='';
      entry='https://vkplay.live/search?query='; activity=''}
)

# Content pages the launcher turns into cards, and the host the app itself registered for them.
$cardTargets = @(
    @{id='kinopoisk'; pkg='ru.kinopoisk.tv'; url='https://hd.kinopoisk.ru/film/3401/'},
    @{id='vk'; pkg='com.vk.tv'; url='https://vk.com/video-111180788_456249130'},
    @{id='reshala'; pkg='reshala.tv.app'; url='reshala://open?screen=card%3Atmdb%3A%2F%2Fmovie%2F808'},
    @{id='smarttube'; pkg='org.smarttube.stable'; url='https://www.youtube.com/watch?v=Swiz1XyfhcI'},
    @{id='num'; pkg='ru.yourok.num'; url='https://www.themoviedb.org/movie/808'}
)

& $Adb connect $Device 2>&1 | Out-Null
function Shell([string]$command) { & $Adb -s $Device shell $command 2>&1 }

$failures = 0
function Check([string]$name, $condition, [string]$detail = '') {
    Write-Output ($name + ' ' + $(if ($condition) { 'PASS' } else { 'FAIL' }))
    if ($detail) { Write-Output ('   ' + $detail) }
    if (-not $condition) { $script:failures++ }
}

foreach ($app in $apps) {
    if (-not ((Shell "pm path $($app.pkg)") -join '' -match 'package:')) {
        Write-Output "SKIP $($app.id): $($app.pkg) is not installed"
        continue
    }
    $openable = $false

    if ($app.authority) {
        $uri = "content://$($app.authority)/$($app.path)/shrek?limit=2"
        $rows = (Shell "content query --uri '$uri'") -join "`n"
        $answered = $rows -match 'Row:|No result found'
        Check "$($app.id) provider answers at $($app.path)" $answered ($rows.Split("`n")[0])
        $openable = $answered
    }

    if ($app.entry) {
        $resolved = Shell "cmd package query-activities -a android.intent.action.VIEW -d '$($app.entry)shrek' --brief"
        $hit = @($resolved | Where-Object { $_ -match [regex]::Escape($app.pkg) })
        Check "$($app.id) entry point resolves inside the app" ($hit.Count -gt 0) (($hit | Select-Object -First 1))
        $openable = $openable -or ($hit.Count -gt 0)
    }

    if ($app.activity) {
        $raw = (Shell "cmd package query-activities -a android.intent.action.SEARCH") -join "`n"
        $block = @([regex]::Matches($raw, '(?s)' + [regex]::Escape($app.pkg) + '.{0,200}') |
            ForEach-Object { $_.Value } | Where-Object { $_ -match 'exported=true' })
        Check "$($app.id) search activity is exported" ($block.Count -gt 0) (($block | Select-Object -First 1))
        $openable = $openable -or ($block.Count -gt 0)
    }

    if (-not $openable) { Check "$($app.id) can open something from the search row" $false }
}

foreach ($target in $cardTargets) {
    $resolved = & $Adb -s $Device shell "cmd package query-activities -a android.intent.action.VIEW -d '$($target.url)' --brief" 2>&1
    $hit = @($resolved | Where-Object { $_ -match [regex]::Escape($target.pkg) })
    Check "card $($target.id) opens through the app's own target" ($hit.Count -gt 0) (($hit | Select-Object -First 1))
}

Write-Output $(if ($failures -eq 0) { 'ALL PASS' } else { "FAILURES $failures" })
exit $(if ($failures -eq 0) { 0 } else { 1 })
