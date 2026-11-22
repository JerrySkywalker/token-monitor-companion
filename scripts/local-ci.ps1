[CmdletBinding()]
param(
    [switch]$GatewayOnly,
    [switch]$Release
)
$ErrorActionPreference = 'Stop'
$root = Split-Path -Parent $PSScriptRoot

function Invoke-Checked {
    param([string]$Executable, [string[]]$Arguments)
    & $Executable @Arguments
    if ($LASTEXITCODE -ne 0) { throw "$Executable failed with exit code $LASTEXITCODE" }
}

if ($GatewayOnly) {
    Push-Location (Join-Path $root 'gateway')
    try {
        Invoke-Checked npm.cmd @('ci', '--no-audit', '--no-fund')
        Invoke-Checked npm.cmd @('test')
    } finally {
        Pop-Location
    }
    return
}

if (!$env:ANDROID_HOME) {
    $defaultSdk = Join-Path $env:LOCALAPPDATA 'Android/Sdk'
    if (Test-Path $defaultSdk) { $env:ANDROID_HOME = $defaultSdk }
}
Push-Location $root
try {
    $tasks = @(':core:testDebugUnitTest', ':mobile:testDebugUnitTest', ':mobile:lintDebug', ':wear:lintDebug', ':mobile:assembleDebug', ':wear:assembleDebug')
    if ($Release) { $tasks += @(':mobile:assembleRelease', ':wear:assembleRelease') }
    Invoke-Checked (Join-Path $root 'gradlew.bat') (@('--console=plain', '--no-daemon') + $tasks)
} finally {
    Pop-Location
}
