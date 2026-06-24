#Requires -Version 5.1
<#
.SYNOPSIS
  Read github.local.properties, push project to GitHub, trigger APK build.
.USAGE
  powershell -ExecutionPolicy Bypass -File scripts\deploy-to-github.ps1
#>

$ErrorActionPreference = "Stop"
$ProjectRoot = Split-Path -Parent $PSScriptRoot
$ConfigFile = Join-Path $ProjectRoot "github.local.properties"

function Read-Config {
    if (-not (Test-Path $ConfigFile)) {
        throw "Config file not found: $ConfigFile"
    }
    $config = @{}
    $raw = Get-Content $ConfigFile -Encoding UTF8 -Raw
    $raw = $raw.TrimStart([char]0xFEFF)
    $raw -split "`r?`n" | ForEach-Object {
        $line = $_.Trim()
        if ($line -eq "" -or $line.StartsWith("#")) { return }
        $parts = $line -split "=", 2
        if ($parts.Length -eq 2) {
            $key = $parts[0].Trim().TrimStart([char]0xFEFF)
            $config[$key] = $parts[1].Trim()
        }
    }
    return $config
}

function Invoke-GitHubApi {
    param(
        [string]$Method,
        [string]$Url,
        [string]$Token,
        [object]$Body = $null
    )
    $headers = @{
        Authorization        = "Bearer $Token"
        Accept               = "application/vnd.github+json"
        "X-GitHub-Api-Version" = "2022-11-28"
    }
    $params = @{
        Method  = $Method
        Uri     = $Url
        Headers = $headers
    }
    if ($null -ne $Body) {
        $params.Body = ($Body | ConvertTo-Json -Depth 8)
        $params.ContentType = "application/json"
    }
    return Invoke-RestMethod @params
}

function Get-ProjectFiles {
    param([string]$Root)
    $excludeDirs = @('.git', 'build', '.gradle', '.idea', 'out', 'captures', '.cxx')
    $excludeFiles = @('github.local.properties', 'local.properties')
    $files = @()
    foreach ($item in Get-ChildItem -Path $Root -Recurse -File -Force) {
        $rel = $item.FullName.Substring($Root.Length + 1).Replace('\', '/')
        if ($excludeFiles -contains (Split-Path $rel -Leaf)) { continue }
        $skip = $false
        foreach ($dir in $excludeDirs) {
            if ($rel -eq $dir -or $rel.StartsWith("$dir/")) { $skip = $true; break }
        }
        if (-not $skip) { $files += $item }
    }
    return $files
}

function Push-ViaGitHubApi {
    param(
        [string]$Owner,
        [string]$Repo,
        [string]$Token,
        [string]$Root
    )
    Write-Host '>>> Git push failed, trying GitHub API upload...'
    $files = Get-ProjectFiles -Root $Root
    Write-Host ">>> Uploading $($files.Count) files via API..."

    $treeItems = New-Object System.Collections.Generic.List[object]
    $index = 0
    foreach ($file in $files) {
        $index++
        $relPath = $file.FullName.Substring($Root.Length + 1).Replace('\', '/')
        if ($index % 10 -eq 0) { Write-Host ">>>   $index / $($files.Count)" }
        $bytes = [System.IO.File]::ReadAllBytes($file.FullName)
        $b64 = [Convert]::ToBase64String($bytes)
        $blob = Invoke-GitHubApi -Method POST `
            -Url "https://api.github.com/repos/$Owner/$Repo/git/blobs" `
            -Token $Token `
            -Body @{ content = $b64; encoding = 'base64' }
        $treeItems.Add(@{
            path = $relPath
            mode = '100644'
            type = 'blob'
            sha  = $blob.sha
        })
    }

    $parentSha = $null
    try {
        $ref = Invoke-GitHubApi -Method GET `
            -Url "https://api.github.com/repos/$Owner/$Repo/git/ref/heads/main" `
            -Token $Token
        $parentSha = $ref.object.sha
    } catch {
        $parentSha = $null
    }

    $tree = Invoke-GitHubApi -Method POST `
        -Url "https://api.github.com/repos/$Owner/$Repo/git/trees" `
        -Token $Token `
        -Body @{ tree = $treeItems.ToArray() }

    $commitBody = @{
        message = 'Deploy FloatScan Android app'
        tree    = $tree.sha
    }
    if ($parentSha) { $commitBody.parents = @($parentSha) }

    $commit = Invoke-GitHubApi -Method POST `
        -Url "https://api.github.com/repos/$Owner/$Repo/git/commits" `
        -Token $Token `
        -Body $commitBody

    if ($parentSha) {
        Invoke-GitHubApi -Method PATCH `
            -Url "https://api.github.com/repos/$Owner/$Repo/git/refs/heads/main" `
            -Token $Token `
            -Body @{ sha = $commit.sha } | Out-Null
    } else {
        Invoke-GitHubApi -Method POST `
            -Url "https://api.github.com/repos/$Owner/$Repo/git/refs" `
            -Token $Token `
            -Body @{ ref = 'refs/heads/main'; sha = $commit.sha } | Out-Null
    }
    Write-Host '>>> API upload complete.'
}

$config = Read-Config
$token = $config['github.token']
if (-not $token) { $token = $config.Get_Item('github.token') }
$owner = $config['github.owner']
if (-not $owner) { $owner = $config.Get_Item('github.owner') }
$repo = $config['github.repo']
if (-not $repo) { $repo = $config.Get_Item('github.repo') }

if (-not $token -or $token.Trim().Length -eq 0) {
    throw "github.token is empty in github.local.properties"
}
if (-not $owner -or $owner.Trim().Length -eq 0) {
    throw "github.owner is empty in github.local.properties"
}
if (-not $repo -or $repo.Trim().Length -eq 0) {
    throw "github.repo is empty in github.local.properties"
}

Set-Location $ProjectRoot
Write-Host ">>> Project: $ProjectRoot"

if (-not (Test-Path ".git")) {
    Write-Host ">>> Init git..."
    git init | Out-Null
    git branch -M main 2>$null
}

git add -A
$status = git status --porcelain
if ($status) {
    Write-Host ">>> Commit..."
    git commit -m "Deploy FloatScan Android app" | Out-Null
}

$repoUrl = "https://api.github.com/repos/$owner/$repo"
$repoExists = $true
try {
    Invoke-GitHubApi -Method GET -Url $repoUrl -Token $token | Out-Null
} catch {
    $repoExists = $false
}

if (-not $repoExists) {
    Write-Host ">>> Create repo $owner/$repo ..."
    Invoke-GitHubApi -Method POST -Url "https://api.github.com/user/repos" -Token $token -Body @{
        name        = $repo
        description = "FloatScan - floating barcode scanner injector"
        private     = $false
        auto_init   = $false
    } | Out-Null
    Start-Sleep -Seconds 2
}

$remoteUrl = "https://x-access-token:${token}@github.com/${owner}/${repo}.git"
if (git remote | Select-String -Pattern "^origin$" -Quiet) {
    git remote set-url origin $remoteUrl
} else {
    git remote add origin $remoteUrl
}

Write-Host '>>> Pushing to GitHub...'
$pushOk = $true
try {
    $pushResult = git push -u origin main 2>&1
    if ($LASTEXITCODE -ne 0) {
        $pushText = $pushResult | Out-String
        if ($pushText -match 'workflow') {
            throw 'GitHub token missing workflow scope. Add repo + workflow, update github.local.properties.'
        }
        $pushOk = $false
    }
} catch {
    $pushOk = $false
}

if (-not $pushOk) {
    Push-ViaGitHubApi -Owner $owner -Repo $repo -Token $token -Root $ProjectRoot
}

Write-Host '>>> Trigger cloud APK build...'
try {
    Invoke-GitHubApi -Method POST `
        -Url "https://api.github.com/repos/$owner/$repo/actions/workflows/build-apk.yml/dispatches" `
        -Token $token `
        -Body @{ ref = 'main' } | Out-Null
    Write-Host ''
    Write-Host '=========================================='
    Write-Host '  Deploy success!'
    Write-Host "  Repo:  https://github.com/$owner/$repo"
    Write-Host "  Build: https://github.com/$owner/$repo/actions"
    Write-Host '  Wait 3-5 min, download APK from Artifacts.'
    Write-Host '=========================================='
} catch {
    Write-Host '>>> Code uploaded, but workflow trigger failed.'
    Write-Host ">>> Open: https://github.com/$owner/$repo/actions"
    Write-Host '>>> Click Build APK, then Run workflow.'
}
