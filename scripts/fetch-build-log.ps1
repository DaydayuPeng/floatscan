$ErrorActionPreference = 'Stop'
$cfg = @{}
$configFile = Join-Path (Split-Path $PSScriptRoot -Parent) 'github.local.properties'
Get-Content $configFile -Encoding UTF8 | ForEach-Object {
    $line = $_.Trim()
    if ($line -eq '' -or $line.StartsWith('#')) { return }
    $parts = $line -split '=', 2
    if ($parts.Length -eq 2) { $cfg[$parts[0].Trim()] = $parts[1].Trim() }
}
$token = $cfg['github.token']
$headers = @{ Authorization = "Bearer $token"; Accept = 'application/vnd.github+json' }
$runs = Invoke-RestMethod -Uri 'https://api.github.com/repos/DaydayuPeng/floatscan/actions/runs?per_page=1' -Headers $headers
$runId = $runs.workflow_runs[0].id
$jobs = Invoke-RestMethod -Uri "https://api.github.com/repos/DaydayuPeng/floatscan/actions/runs/$runId/jobs" -Headers $headers
$jobId = $jobs.jobs[0].id
$zipPath = Join-Path $env:TEMP 'gh-actions-log.zip'
$outDir = Join-Path $env:TEMP 'gh-actions-log'
Invoke-WebRequest -Uri "https://api.github.com/repos/DaydayuPeng/floatscan/actions/jobs/$jobId/logs" -Headers $headers -OutFile $zipPath
$bytes = [System.IO.File]::ReadAllBytes($zipPath)
Write-Host "Downloaded bytes: $($bytes.Length)"
if ($bytes.Length -lt 4) { throw 'Log download too small' }
Add-Type -AssemblyName System.IO.Compression.FileSystem
if (Test-Path $outDir) { Remove-Item $outDir -Recurse -Force }
[System.IO.Compression.ZipFile]::ExtractToDirectory($zipPath, $outDir)
Get-ChildItem $outDir -Recurse -File | ForEach-Object { Get-Content $_.FullName -Encoding UTF8 } |
    Select-String -Pattern 'error:|Error|FAILURE|FAILED|Exception|What went wrong|> Task' |
    Select-Object -Last 60
