param(
    [string] $GoRoot = "$env:USERPROFILE\sdk\go1.25.11",
    [string] $AndroidSdk = "D:\Program Files\android-tools\sdk",
    [string] $JavaHome = "C:\Program Files\Microsoft\jdk-17.0.19.10-hotspot"
)

$ErrorActionPreference = "Stop"
$projectDir = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot ".."))
$sourceDir = Join-Path $projectDir "build\official-libbox\sing-box-25a600db24f7680ad9806ce5427bd0ab8afe1114"
$go = Join-Path $GoRoot "bin\go.exe"
$ndk = Join-Path $AndroidSdk "ndk\28.0.13004108"
$builder = Join-Path $sourceDir "cmd\internal\build_libbox\main.go"

if (!(Test-Path $go -PathType Leaf)) { throw "Go 1.25.11 was not found at $go" }
if (!(Test-Path $sourceDir -PathType Container)) { throw "sing-box source was not found at $sourceDir" }

$builderText = Get-Content -LiteralPath $builder -Raw -Encoding UTF8
$originalTags = 'sharedTags = append(sharedTags, "with_gvisor", "with_quic", "with_wireguard", "with_utls", "with_naive_outbound", "with_clash_api", "badlinkname", "tfogo_checklinkname0")'
$previousCustomTags = 'sharedTags = append(sharedTags, "with_gvisor", "with_utls", "badlinkname", "tfogo_checklinkname0")'
$customTags = 'sharedTags = append(sharedTags, "with_gvisor", "with_utls", "with_clash_api", "badlinkname", "tfogo_checklinkname0")'
if (!$builderText.Contains($originalTags) -and !$builderText.Contains($previousCustomTags) -and !$builderText.Contains($customTags)) {
    throw "Unexpected sing-box build tag definition"
}
Set-Content -LiteralPath $builder -Value $builderText.Replace($originalTags, $customTags).Replace($previousCustomTags, $customTags).Replace('sharedTags = append(sharedTags, "with_tailscale", "ts_omit_logtail", "ts_omit_ssh", "ts_omit_drive", "ts_omit_taildrop", "ts_omit_webclient", "ts_omit_doctor", "ts_omit_capture", "ts_omit_kube", "ts_omit_aws", "ts_omit_synology", "ts_omit_bird")', '') -Encoding UTF8

$env:GOROOT = $GoRoot
$env:PATH = "$GoRoot\bin;$env:USERPROFILE\go\bin;$env:PATH"
$env:GOTOOLCHAIN = "local"
$env:ANDROID_HOME = $AndroidSdk
$env:ANDROID_NDK_HOME = $ndk
$env:NDK = $ndk
$env:JAVA_HOME = $JavaHome
$env:SING_BOX_VERSION = "1.13.14-hjply"

Push-Location $sourceDir
try {
    & $go run ./cmd/internal/build_libbox -target android
    if ($LASTEXITCODE -ne 0) { throw "custom libbox build failed" }
} finally {
    Pop-Location
}

Copy-Item -LiteralPath (Join-Path $sourceDir "libbox.aar") -Destination (Join-Path $projectDir "app\libs\libbox-hjply.aar") -Force
