[CmdletBinding()]
param(
    [Parameter(Mandatory)]
    [ValidatePattern('^emulator-\d+$')]
    [string]$Serial,
    [Parameter(Mandatory)]
    [ValidateSet('phone-usage', 'phone-layout', 'phone-widget', 'wear-direct', 'wear-phone', 'wear-quotas', 'wear-trend', 'wear-devices', 'wear-tile')]
    [string]$Scenario,
    [string[]]$Expected,
    [switch]$LiveHub,
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
$fixtures = @{
    'phone-usage' = @('总览','用量与成本','1,264,020')
    'phone-layout' = @('编辑总览','保存布局')
    'phone-widget' = @('Token Monitor','Tokens')
    'wear-direct' = @('总览','独立直连')
    'wear-phone' = @('总览','手机推送')
    'wear-quotas' = @('额度','短时','周期','余额')
    'wear-trend' = @('趋势','已观测')
    'wear-devices' = @('设备')
    'wear-tile' = @('今天','本月','打开看板')
}
if (!$Expected) {
    $Expected = $fixtures[$Scenario]
    if ($LiveHub -and $Scenario -eq 'phone-usage') { $Expected = @('总览','用量与成本','Tokens') }
}

$androidDump = '/sdcard/tmcomp-acceptance.xml'
Invoke-Adb shell uiautomator dump $androidDump | Out-Null
[xml]$tree = (Invoke-Adb shell cat $androidDump) -join ''
$hosted = $Scenario -in 'phone-widget', 'wear-tile'
$xpath = if ($hosted) { '//node/@text | //node/@content-desc' } else {
    '//node[@package="io.github.jerryskywalker.tmcomp"]/@text | //node[@package="io.github.jerryskywalker.tmcomp"]/@content-desc'
}
$labels = $tree.SelectNodes($xpath) | ForEach-Object Value | Where-Object { $_ }
foreach ($phrase in $Expected) {
    if (!($labels | Where-Object { $_.Contains($phrase) })) { throw "Expected UI label: $phrase" }
}

New-Item -ItemType Directory -Path $Directory -Force | Out-Null
$androidShot = '/sdcard/tmcomp-acceptance.png'
$prefix = Join-Path $Directory ("$stamp-$Scenario")
Invoke-Adb shell screencap -p $androidShot | Out-Null
Invoke-Adb pull $androidShot "$prefix.png" | Out-Null
[pscustomobject]@{ scenario = $Scenario; serial = $Serial; image = $make; api = $version; liveHub = $LiveHub.IsPresent; assertions = $Expected } |
    ConvertTo-Json -Depth 4 | Set-Content "$prefix.json" -Encoding utf8
Write-Output "PASS $Scenario ($Serial, API $version) $prefix.png"
