#Requires -Version 5.1
<#
.SYNOPSIS
  读取 github.local.properties，将项目推送到 GitHub 并触发云端 APK 构建。

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
        Authorization = "Bearer $Token"
        Accept        = "application/vnd.github+json"
        "X-GitHub-Api-Version" = "2022-11-28"
    }
    $params = @{
        Method  = $Method
        Uri     = $Url
        Headers = $headers
    }
    if ($null -ne $Body) {
        $params.Body = ($Body | ConvertTo-Json)
        $params.ContentType = "application/json"
    }
    return Invoke-RestMethod @params
}

$config = Read-Config
$token = $config['github.token']
if (-not $token) { $token = $config.Get_Item('github.token') }
$owner = $config['github.owner']
if (-not $owner) { $owner = $config.Get_Item('github.owner') }
$repo = $config['github.repo']
if (-not $repo) { $repo = $config.Get_Item('github.repo') }

if (-not $token -or $token.Trim().Length -eq 0) {
    $keys = ($config.Keys | ForEach-Object { "'$_'" }) -join ', '
    throw "github.token is empty. Parsed keys: $keys"
}
if (-not $owner -or $owner.Trim().Length -eq 0) { throw "github.owner is empty in github.local.properties" }
if (-not $repo -or $repo.Trim().Length -eq 0) { throw "github.repo is empty in github.local.properties" }

Set-Location $ProjectRoot
Write-Host ">>> 项目目录: $ProjectRoot"

# 初始化 Git
if (-not (Test-Path ".git")) {
    Write-Host ">>> 初始化 Git 仓库..."
    git init | Out-Null
    git branch -M main 2>$null
}

# 确保有提交
git add -A
$status = git status --porcelain
if ($status) {
    Write-Host ">>> 创建提交..."
    git commit -m "Initial commit: FloatScan Android app" | Out-Null
} else {
    $commitCount = (git rev-list --count HEAD 2>$null)
    if (-not $commitCount -or [int]$commitCount -eq 0) {
        git commit --allow-empty -m "Initial commit: FloatScan Android app" | Out-Null
    }
}

# 检查远程仓库是否存在，不存在则创建
$repoUrl = "https://api.github.com/repos/$owner/$repo"
$repoExists = $true
try {
    Invoke-GitHubApi -Method GET -Url $repoUrl -Token $token | Out-Null
} catch {
    $repoExists = $false
}

if (-not $repoExists) {
    Write-Host ">>> 远程仓库不存在，正在创建 $owner/$repo ..."
    Invoke-GitHubApi -Method POST -Url "https://api.github.com/user/repos" -Token $token -Body @{
        name        = $repo
        description = "FloatScan - floating barcode scanner injector"
        private     = $false
        auto_init   = $false
    } | Out-Null
    Start-Sleep -Seconds 2
}

# 配置 remote 并推送
$remoteUrl = "https://x-access-token:${token}@github.com/${owner}/${repo}.git"
if (git remote | Select-String -Pattern "^origin$" -Quiet) {
    git remote set-url origin $remoteUrl
} else {
    git remote add origin $remoteUrl
}

Write-Host '>>> Pushing to GitHub...'
$pushResult = git push -u origin main 2>&1
if ($LASTEXITCODE -ne 0) {
    $pushText = $pushResult | Out-String
    if ($pushText -match 'workflow') {
        throw 'GitHub token missing workflow scope. Regenerate token with repo + workflow, update github.local.properties, then run again.'
    }
    throw "git push failed: $pushText"
}

# 触发 GitHub Actions 构建
Write-Host ">>> 触发云端 APK 构建..."
try {
    Invoke-GitHubApi -Method POST `
        -Url "https://api.github.com/repos/$owner/$repo/actions/workflows/build-apk.yml/dispatches" `
        -Token $token `
        -Body @{ ref = "main" } | Out-Null
    Write-Host ''
    Write-Host '=========================================='
    Write-Host '  Deploy success!'
    Write-Host "  Repo: https://github.com/$owner/$repo"
    Write-Host "  Build: https://github.com/$owner/$repo/actions"
    Write-Host ''
    Write-Host '  Wait 3-5 min, then download APK from Artifacts.'
    Write-Host '=========================================='
} catch {
    $actionsUrl = "https://github.com/$owner/$repo/actions"
    Write-Host '>>> Push done, but workflow trigger failed.'
    Write-Host ">>> Open manually: $actionsUrl"
    Write-Host '>>> Click Build APK, then Run workflow.'
}
