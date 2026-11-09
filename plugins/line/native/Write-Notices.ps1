#requires -Version 5.1
[CmdletBinding()]
param([string]$GoPath = 'go')
$ErrorActionPreference = 'Stop'
$compiler = (Get-Command $GoPath -ErrorAction Stop).Source
$oldOS = $env:GOOS
$oldArch = $env:GOARCH
Push-Location $PSScriptRoot
try {
    $env:GOOS = 'android'
    $env:GOARCH = 'arm64'
    $linked = @(& $compiler list -deps -f '{{if .Module}}{{.Module.Path}}{{end}}' ./mobile)
    if ($LASTEXITCODE -ne 0) { throw 'Could not list Android dependencies.' }
    $paths = @($linked + 'golang.org/x/mobile' | Where-Object { $_ -and $_ -notlike 'github.com/Anezium/*' } | Sort-Object -Unique)
    $notice = [Collections.Generic.List[string]]::new()
    $notice.Add('LINE Messages: Rokid Nexus code is licensed under Apache License 2.0. Third-party code follows the licenses below.')
    $notice.Add([IO.File]::ReadAllText((Resolve-Path '../../../LICENSE')))
    $goRoot = (& $compiler env GOROOT).Trim()
    $notice.Add("`nGo standard library`n" + [IO.File]::ReadAllText((Join-Path $goRoot 'LICENSE')))
    foreach ($modulePath in $paths) {
        $metadata = (& $compiler list -m -json $modulePath | Out-String | ConvertFrom-Json)
        if ($LASTEXITCODE -ne 0) { throw "Could not locate $modulePath." }
        $licenseFiles = @(Get-ChildItem -LiteralPath $metadata.Dir -File | Where-Object { $_.Name -match '^(LICENSE|COPYING|NOTICE|PATENTS)(\..*)?$' })
        if (-not $licenseFiles) { throw "Missing license for $modulePath." }
        $notice.Add("`n$modulePath $($metadata.Version)")
        foreach ($licenseFile in $licenseFiles) { $notice.Add([IO.File]::ReadAllText($licenseFile.FullName)) }
    }
    $outputDir = Join-Path $PSScriptRoot '../src/main/assets/licenses'
    New-Item -ItemType Directory -Force -Path $outputDir | Out-Null
    [IO.File]::WriteAllText((Join-Path $outputDir 'NOTICE.txt'), ($notice -join "`n`n"), [Text.UTF8Encoding]::new($false))
} finally {
    Pop-Location
    $env:GOOS = $oldOS
    $env:GOARCH = $oldArch
}
