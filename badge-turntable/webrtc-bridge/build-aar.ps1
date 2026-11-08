# Builds app/libs/webrtc-bridge.aar, the WebRTC transport for the app, with gomobile.
# Needs Go (see go.mod), gobind and gomobile on the PATH, a JDK, and the Android NDK. See README.md.
param(
  [string]$Sdk = "$env:LOCALAPPDATA\Android\Sdk",
  [string]$Ndk = ''
)
$ErrorActionPreference = 'Stop'
if (-not $Ndk) {
  $Ndk = Get-ChildItem "$Sdk\ndk" -Directory -ErrorAction SilentlyContinue | Sort-Object { [version]$_.Name } | Select-Object -Last 1 -ExpandProperty FullName
}
if (-not $Ndk -or -not (Test-Path $Ndk)) { throw "No Android NDK under $Sdk\ndk. Install one with Android Studio's SDK Manager (SDK Tools, NDK (Side by side))." }
$env:ANDROID_HOME = $Sdk
$env:ANDROID_NDK_HOME = $Ndk
$out = Join-Path $PSScriptRoot '..\app\libs\webrtc-bridge.aar'
New-Item -ItemType Directory -Force (Split-Path $out) | Out-Null
Push-Location $PSScriptRoot
try {
  # arm64 only keeps the size down; on other ABIs (an x86 emulator) the app runs without WebRTC.
  # -checklinkname=0: github.com/wlynxg/anet, which Pion uses to list network interfaces on
  # Android 11+, reaches into net with //go:linkname, which Go 1.23+ refuses without it.
  gomobile bind -target=android/arm64 -androidapi 31 -javapkg org.snurkle.webrtc -trimpath -ldflags='-s -w -checklinkname=0' -o $out ./bridge
  if ($LASTEXITCODE -ne 0) { throw "gomobile bind failed ($LASTEXITCODE)" }
  Remove-Item (Join-Path (Split-Path $out) 'webrtc-bridge-sources.jar') -ErrorAction SilentlyContinue
  "Built $((Resolve-Path $out).Path), $([math]::Round((Get-Item $out).Length / 1MB, 1)) MB, with NDK $(Split-Path $Ndk -Leaf)"
} finally {
  Pop-Location
}
