param(
    [string] $GoRoot = "$env:USERPROFILE\sdk\go1.25.11",
    [string] $AndroidSdk = "D:\Program Files\android-tools\sdk",
    [string] $JavaHome = "C:\Program Files\Microsoft\jdk-17.0.19.10-hotspot"
)

$ErrorActionPreference = "Stop"
$ProgressPreference = "SilentlyContinue"

$sourceCommit = "25a600db24f7680ad9806ce5427bd0ab8afe1114"
$sourceArchiveSha256 = "87365C45EDD4C955E01425DD7AC34EA0456F3B9EF89FB068CED4BC3C64B167FD"
$gomobileVersion = "v0.1.12"
$ndkVersion = "28.0.13004108"

$projectDir = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot ".."))
$workDir = Join-Path $projectDir "build\official-libbox"
$archive = Join-Path $workDir "$sourceCommit.zip"
$sourceDir = Join-Path $workDir "sing-box-$sourceCommit"
$outputFile = Join-Path $projectDir "app\libs\libbox.aar"
$go = Join-Path $GoRoot "bin\go.exe"
$ndk = Join-Path $AndroidSdk "ndk\$ndkVersion"

if (!(Test-Path $go -PathType Leaf)) { throw "Go 1.25.11 was not found at $go" }
if ((& $go version) -notmatch "go1\.25\.11") { throw "The libbox build requires Go 1.25.11" }
if (!(Test-Path (Join-Path $ndk "source.properties") -PathType Leaf)) {
    throw "Android NDK $ndkVersion was not found under $AndroidSdk"
}

New-Item -ItemType Directory -Force $workDir | Out-Null
if (!(Test-Path $archive -PathType Leaf)) {
    Invoke-WebRequest -UseBasicParsing `
        -Uri "https://github.com/SagerNet/sing-box/archive/$sourceCommit.zip" `
        -OutFile $archive
}
if ((Get-FileHash $archive -Algorithm SHA256).Hash -ne $sourceArchiveSha256) {
    throw "sing-box source archive checksum mismatch"
}
if (Test-Path $sourceDir) {
    $resolvedSource = [IO.Path]::GetFullPath($sourceDir)
    if (!$resolvedSource.StartsWith([IO.Path]::GetFullPath($workDir), [StringComparison]::OrdinalIgnoreCase)) {
        throw "Refusing to remove source directory outside $workDir"
    }
    Remove-Item -LiteralPath $resolvedSource -Recurse -Force
}
tar -xf $archive -C $workDir
if ($LASTEXITCODE -ne 0) { throw "Unable to extract sing-box source" }

$env:GOROOT = $GoRoot
$env:PATH = "$GoRoot\bin;$env:USERPROFILE\go\bin;$env:PATH"
$env:GOTOOLCHAIN = "local"
$env:ANDROID_HOME = $AndroidSdk
$env:ANDROID_SDK_HOME = $AndroidSdk
$env:ANDROID_NDK_HOME = $ndk
$env:NDK = $ndk
$env:JAVA_HOME = $JavaHome
$env:SING_BOX_VERSION = "1.13.14"

& $go install "github.com/sagernet/gomobile/cmd/gomobile@$gomobileVersion"
if ($LASTEXITCODE -ne 0) { throw "Unable to install gomobile $gomobileVersion" }
& $go install "github.com/sagernet/gomobile/cmd/gobind@$gomobileVersion"
if ($LASTEXITCODE -ne 0) { throw "Unable to install gobind $gomobileVersion" }

Push-Location $sourceDir
try {
    & $go run ./cmd/internal/build_libbox -target android
    if ($LASTEXITCODE -ne 0) { throw "libbox build failed" }
} finally {
    Pop-Location
}

New-Item -ItemType Directory -Force (Split-Path $outputFile) | Out-Null
Copy-Item -LiteralPath (Join-Path $sourceDir "libbox.aar") -Destination $outputFile -Force
Write-Host "libbox.aar: $outputFile"
Write-Host "SHA256: $((Get-FileHash $outputFile -Algorithm SHA256).Hash)"
