[CmdletBinding()]
param(
    [Parameter(Mandatory)]
    [ValidatePattern('^emulator-\d+$')]
    [string]$Serial,
    [Parameter(Mandatory)]
    [ValidateSet('phone-usage', 'wear-direct', 'wear-phone')]
    [string]$Scenario,
    [string]$Directory = (Join-Path (Split-Path -Parent $PSScriptRoot) '.local/evidence')
)
$ErrorActionPreference = 'Stop'
if (!$env:ANDROID_HOME) { throw 'Set ANDROID_HOME to your Android SDK.' }
$adb = Join-Path $env:ANDROID_HOME 'platform-tools/adb.exe'

function Invoke-Adb {
    & $adb -s $Serial @args
    if ($LASTEXITCODE -ne 0) { throw "adb failed on $Serial" }
}

$make = Invoke-Adb shell getprop ro.product.name
$version = Invoke-Adb shell getprop ro.build.version.sdk
if ($Scenario.StartsWith('wear-') -ne ($make -match 'wear')) {
    throw "The $Scenario scenario requires an appropriate emulator image."
}

$stamp = Get-Date -Format 'yyyyMMdd-HHmmss'
$phoneExpected = @('Token Monitor', '今日 1,264,020 tokens', 'Codex', '剩余 62%')
$modeLabel = if ($Scenario -eq 'wear-direct') { '独立直连' } else { '手机同步' }
$expected = if ($Scenario -eq 'phone-usage') {
    $phoneExpected
} else {
    @('Token Monitor', $modeLabel, 'Codex', '剩余 41%', 'OpenRouter', '余额 12.34 USD')
}

$androidDump = '/sdcard/tmcomp-acceptance.xml'
Invoke-Adb shell uiautomator dump $androidDump | Out-Null
[xml]$tree = (Invoke-Adb shell cat $androidDump) -join ''
$labels = $tree.SelectNodes('//node[@package="io.github.jerryskywalker.tmcomp"]/@text') |
    ForEach-Object Value | Where-Object { $_ }
foreach ($phrase in $expected) {
    if (!($labels | Where-Object { $_.Contains($phrase) })) { throw "Expected UI label: $phrase" }
}

New-Item -ItemType Directory -Path $Directory -Force | Out-Null
$androidShot = '/sdcard/tmcomp-acceptance.png'
$prefix = Join-Path $Directory ("$stamp-$Scenario")
Invoke-Adb shell screencap -p $androidShot | Out-Null
Invoke-Adb pull $androidShot "$prefix.png" | Out-Null
[pscustomobject]@{ scenario = $Scenario; serial = $Serial; image = $make; api = $version; labels = @($labels) } |
    ConvertTo-Json -Depth 4 | Set-Content "$prefix.json" -Encoding utf8
Write-Output "PASS $Scenario ($Serial, API $version) $prefix.png"
