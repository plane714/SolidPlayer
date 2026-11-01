# 用法：
#   pwsh -File scripts/build-release-and-publish.ps1
#
# 每次执行做三件事：
#   1. versionName 末尾 +0.0.1（0.5.2 → 0.5.3 …），versionCode 同步 +1；
#   2. 构建 release APK（离线、debug keystore 签名，可直接安装）；
#   3. 复制到安装包目录：E:\02code\0A 安装包\solidplayer-z\solidplayer-<版本>-release.apk
#
# 只想重新出当前版本、不递增版本号时加 -NoBump。
param(
    [switch]$NoBump,
    [string]$OutputDir = 'E:\02code\0A 安装包\solidplayer-z'
)

$ErrorActionPreference = 'Stop'

$repo = Split-Path -Parent $PSScriptRoot
$gradleFile = Join-Path $repo 'app\build.gradle.kts'
$text = [System.IO.File]::ReadAllText($gradleFile)

$nameMatch = [regex]::Match($text, 'versionName\s*=\s*"([^"]+)"')
$codeMatch = [regex]::Match($text, 'versionCode\s*=\s*(\d+)')
if (-not $nameMatch.Success -or -not $codeMatch.Success) {
    throw '无法从 app/build.gradle.kts 解析 versionCode / versionName'
}

$oldName = $nameMatch.Groups[1].Value
$oldCode = [int]$codeMatch.Groups[1].Value

function Get-NextVersion([string]$version) {
    $core = ($version -split '-')[0].TrimStart('v')
    $parts = [System.Collections.Generic.List[string]]($core.Split('.'))
    while ($parts.Count -lt 3) { $parts.Add('0') }
    $last = [int]$parts[$parts.Count - 1] + 1
    $parts[$parts.Count - 1] = [string]$last
    return ($parts -join '.')
}

if ($NoBump) {
    $newName = $oldName
    $newCode = $oldCode
} else {
    $newName = Get-NextVersion $oldName
    $newCode = $oldCode + 1
}

$updated = [regex]::Replace($text, 'versionCode\s*=\s*\d+', "versionCode = $newCode")
$updated = [regex]::Replace($updated, 'versionName\s*=\s*"[^"]+"', "versionName = `"$newName`"")
if ($updated -ne $text) {
    [System.IO.File]::WriteAllText($gradleFile, $updated, [System.Text.UTF8Encoding]::new($false))
}

$env:JAVA_HOME = 'E:\study\ANDROI~1\jbr'
$env:GRADLE_USER_HOME = 'E:\0files\gradle-home'

Push-Location $repo
try {
    & '.\gradlew.bat' ':app:assembleRelease' '--offline'
    if ($LASTEXITCODE -ne 0) { throw "Gradle 构建失败（exit $LASTEXITCODE）" }
} finally {
    Pop-Location
}

$apk = Join-Path $repo 'app\build\outputs\apk\release\app-release.apk'
if (-not (Test-Path -LiteralPath $apk)) { throw "未找到 release APK：$apk" }

New-Item -ItemType Directory -Path $OutputDir -Force | Out-Null
$dest = Join-Path $OutputDir "solidplayer-$newName-release.apk"
Copy-Item -LiteralPath $apk -Destination $dest -Force

Write-Host "已发布：$dest"
Write-Host "versionCode=$newCode  versionName=$newName"
