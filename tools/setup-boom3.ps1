<#
  RetroConsole — Doom 3 (boom3 libretro core) setup.

  Installs the official boom3 core and creates the roms/doom3/base folder.
  Game data itself is NOT downloaded here: either copy the pk4s from your own
  Doom 3 install (base\pak000.pk4 ... pak008.pk4) or the free demo00.pk4.

  Usage:
    powershell -ExecutionPolicy Bypass -File tools\setup-boom3.ps1
    powershell -ExecutionPolicy Bypass -File tools\setup-boom3.ps1 -GameDir "C:\path\to\.minecraft"
#>
[CmdletBinding()]
param(
    [string[]] $GameDir = @()
)

$ErrorActionPreference = 'Stop'

$repo    = Split-Path -Parent $PSScriptRoot
$coreUrl = 'https://buildbot.libretro.com/nightly/windows/x86_64/latest/boom3_libretro.dll.zip'

if (-not $GameDir) {
    # every dev run dir the mod may start from, plus the repo-level config copy
    $GameDir = @((Join-Path $repo 'run\forge'), $repo) |
        Where-Object { $_ -eq $repo -or (Test-Path $_) }
    foreach ($extra in @('run\client', 'run\server')) {
        $p = Join-Path $repo $extra
        if (Test-Path $p) { $GameDir += $p }
    }
}

$work = Join-Path $env:TEMP ("boom3-" + [Guid]::NewGuid().ToString('N'))
New-Item -ItemType Directory -Force -Path $work | Out-Null
$zip = Join-Path $work 'boom3_libretro.zip'

Write-Host "Downloading $coreUrl"
Invoke-WebRequest -Uri $coreUrl -OutFile $zip
Expand-Archive -Path $zip -DestinationPath $work -Force

$dll = Join-Path $work 'boom3_libretro.dll'
if (-not (Test-Path $dll)) { throw "boom3_libretro.dll not found inside $zip" }
Write-Host ("boom3_libretro.dll  {0:N0} KB" -f ((Get-Item $dll).Length / 1KB))

foreach ($dir in $GameDir) {
    $cores = Join-Path $dir 'config\retroconsole\cores'
    $base  = Join-Path $dir 'config\retroconsole\roms\doom3\base'
    $d3xp  = Join-Path $dir 'config\retroconsole\roms\doom3\d3xp'
    New-Item -ItemType Directory -Force -Path $cores, $base, $d3xp | Out-Null
    Copy-Item $dll $cores -Force
    Write-Host "core  -> $cores"
    Write-Host "data  -> $base"
}

Write-Host @"

Next: drop Doom 3 game data into roms\doom3\base\
  retail  : pak000.pk4 ... pak008.pk4  (copy from your Doom 3 install)
  free    : demo00.pk4                (Doom 3 demo installer -> demo\demo00.pk4)
Optional: roms\doom3\d3xp\pak000.pk4 ... for Resurrection of Evil.

Then launch: the game shows up under the "Doom 3" tab as "Doom 3".
"@
