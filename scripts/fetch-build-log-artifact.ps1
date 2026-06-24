$ErrorActionPreference = 'Stop'
$cfg = @{}
$configFile = Join-Path (Split-Path $PSScriptRoot -Parent) 'github.local.properties'
Get-Content $configFile -Encoding UTF8 | ForEach-Object {
    $line = $_.Trim()
    if ($line -eq '' -or $line.StartsWith('#')) { return }
    $parts = $line -split '=', 2
    if ($parts.Length -eq 2) { $cfg[$parts[0].Trim()] = $parts[1].Trim() }
}
$h = @{ Authorization = "Bearer $($cfg['github.token'])"; Accept = 'application/vnd.github+json' }
$zip = Join-Path $env:TEMP 'build-log-artifact.zip'
Invoke-WebRequest -Uri 'https://api.github.com/repos/DaydayuPeng/floatscan/actions/artifacts/7845169330/zip' -Headers $h -OutFile $zip
Add-Type -AssemblyName System.IO.Compression.FileSystem
$out = Join-Path $env:TEMP 'build-log-artifact'
if (Test-Path $out) { Remove-Item $out -Recurse -Force }
[System.IO.Compression.ZipFile]::ExtractToDirectory($zip, $out)
Get-ChildItem $out -Recurse -File | ForEach-Object { Get-Content $_.FullName -Raw }
