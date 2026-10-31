# 构建 + 安装 + 推书，一条命令走完
#
#   .\tools\deploy.ps1                              # 只重装当前 APK
#   .\tools\deploy.ps1 -Build                       # 先构建（含单测）
#   .\tools\deploy.ps1 -Build -Books book1.zip,book2.zip
#
# 依赖：adb 在 PATH；JDK 17；Android SDK（ANDROID_HOME 指向它）
param(
    [switch]$Build,
    [string[]]$Books = @()
)

$ErrorActionPreference = "Stop"

$androidDir = Split-Path -Parent $PSScriptRoot     # .../android
$repoDir = Split-Path -Parent $androidDir          # 仓库根
$pkg = "moe.mgt.zipshelf"
$apk = Join-Path $androidDir "app\build\outputs\apk\debug\app-debug.apk"

if (-not $env:ANDROID_HOME) {
    $guess = Join-Path $env:LOCALAPPDATA "Android\Sdk"
    if (Test-Path $guess) { $env:ANDROID_HOME = $guess }
}

# ~/.gradle 不可写时（沙箱 / CI）把 Gradle 家目录放到仓库内
if (-not $env:GRADLE_USER_HOME) {
    $env:GRADLE_USER_HOME = Join-Path $repoDir ".gradle-home"
}
if (-not $env:GRADLE_RO_DEP_CACHE -and (Test-Path (Join-Path $env:USERPROFILE ".gradle\caches"))) {
    $env:GRADLE_RO_DEP_CACHE = Join-Path $env:USERPROFILE ".gradle\caches"
}

if ($Build) {
    Push-Location $androidDir
    & ".\gradlew.bat" :app:testDebugUnitTest :app:assembleDebug --console=plain
    $code = $LASTEXITCODE
    Pop-Location
    if ($code -ne 0) { throw "构建失败" }
}

Write-Host "安装 $apk"
adb install -r $apk | Select-Object -Last 1

if ($Books.Count -gt 0) {
    adb shell run-as $pkg mkdir -p files/inbox | Out-Null
    foreach ($b in $Books) {
        $full = (Resolve-Path $b).Path
        $name = Split-Path -Leaf $full
        Write-Host "推书 $name"
        adb push $full "/data/local/tmp/$name" | Select-Object -Last 1
        adb shell run-as $pkg cp "/data/local/tmp/$name" files/inbox/ | Out-Null
    }
}

adb shell am start -n "$pkg/.ShelfActivity" | Select-Object -Last 1
Write-Host ""
Write-Host "完成。健康检查："
Write-Host "  adb forward tcp:8899 tcp:8787"
Write-Host "  curl http://127.0.0.1:8899/healthz"
