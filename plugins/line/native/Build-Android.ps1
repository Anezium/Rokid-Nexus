#requires -Version 5.1
[CmdletBinding()]
param([string]$GoPath = 'go')
$ErrorActionPreference = 'Stop'
$compiler = (Get-Command $GoPath -ErrorAction Stop).Source
if (-not $env:ANDROID_HOME -or -not (Test-Path -LiteralPath $env:ANDROID_HOME)) {
    throw 'ANDROID_HOME must refer to the existing Android SDK; no toolchains are installed by this script.'
}
$previousPath = $env:PATH
$previousToolchain = $env:GOTOOLCHAIN
Push-Location $PSScriptRoot
try {
    $env:GOTOOLCHAIN = 'local'
    $env:PATH = (Split-Path -Parent $compiler) + [IO.Path]::PathSeparator + $previousPath
    $requiredVersion = ((Get-Content -LiteralPath 'go.mod' | Where-Object { $_ -match '^toolchain ' }) -split '\s+')[1]
    $actualVersion = & $compiler env GOVERSION
    if ($LASTEXITCODE -ne 0 -or $actualVersion.Trim() -ne $requiredVersion) { throw "This build requires $requiredVersion." }
    $bindingTool = & $compiler tool -n gobind
    if ($LASTEXITCODE -ne 0) { throw 'The pinned gobind tool is unavailable.' }
    $env:PATH = (Split-Path -Parent $bindingTool.Trim()) + [IO.Path]::PathSeparator + $env:PATH
    New-Item -ItemType Directory -Force -Path '../libs' | Out-Null
    & $compiler tool gomobile bind '-trimpath' '-target=android/arm64' '-androidapi=30' '-ldflags=-s -w -extldflags=-Wl,-z,max-page-size=16384' '-javapkg=com.anezium.rokidbus.plugin.line.nativebridge' -o '../libs/line-engine.aar' './mobile'
    if ($LASTEXITCODE -ne 0) { throw 'Android native engine compilation failed.' }
} finally {
    Pop-Location
    $env:PATH = $previousPath
    $env:GOTOOLCHAIN = $previousToolchain
}
