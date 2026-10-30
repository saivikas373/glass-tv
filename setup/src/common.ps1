# ---------------------------------------------------------------- shared by Glass-Setup and Glass-Restore (Windows)
param([string]$Root)
$ErrorActionPreference = 'Continue'
$ProgressPreference = 'SilentlyContinue'
[Net.ServicePointManager]::SecurityProtocol = [Net.SecurityProtocolType]::Tls12
if (-not $Root) { $Root = Split-Path -Parent (Split-Path -Parent $PSCommandPath) }
$Root = (Resolve-Path -LiteralPath $Root).Path       # works from folders with spaces in their names
Set-Location -LiteralPath $Root
$PKG = 'tv.glass.launcher'; $REPO = 'saivikas373/glass-tv'
$TOOLS = Join-Path $Root 'tools'; $ADB = Join-Path $TOOLS 'platform-tools\adb.exe'
$BACKUPS = Join-Path $Root 'backups'
New-Item -ItemType Directory -Force -Path $TOOLS, $BACKUPS | Out-Null
$env:ANDROID_ADB_SERVER_PORT = '5038'                # its own ADB server, so nothing else on this PC is touched
$script:SERIAL = ''; $script:TVIP = ''
$LOG = Join-Path $BACKUPS ("log-" + (Get-Date -Format 'yyyyMMdd-HHmmss') + ".txt")
try { Start-Transcript -LiteralPath $LOG -Append | Out-Null } catch {}

function Step($t) { Write-Host ""; Write-Host "== $t" -ForegroundColor White }
function Say($t)  { Write-Host "   $t" }
function Ok($t)   { Write-Host "   OK  $t" -ForegroundColor Green }
function Warn($t) { Write-Host "   !   $t" -ForegroundColor Yellow }
function Ask($t)  { return (Read-Host "   $t") }
function Confirm-Yes($t) { return ((Ask "$t (y/n)") -match '^(y|yes)$') }
function Stop-Glass($t) { Write-Host ""; Write-Host $t -ForegroundColor Red; Close-AdbServer; Restore-UserKey; try { Stop-Transcript | Out-Null } catch {}; exit 1 }

# ADB keeps its key in %USERPROFILE%\.android. This script uses a fresh key of its own and deletes it when it
# closes ADB, so the TV's "Allow" for it can't be reused. Any key that was already there is put back.
$ANDROID_DIR = Join-Path $env:USERPROFILE '.android'
$KEY = Join-Path $ANDROID_DIR 'adbkey'; $KEYBAK = Join-Path $ANDROID_DIR 'adbkey.glass-backup'
function Use-OwnKey {
  New-Item -ItemType Directory -Force -Path $ANDROID_DIR | Out-Null
  if ((Test-Path -LiteralPath $KEY) -and -not (Test-Path -LiteralPath $KEYBAK)) {
    Move-Item -LiteralPath $KEY $KEYBAK; if (Test-Path -LiteralPath "$KEY.pub") { Move-Item -LiteralPath "$KEY.pub" "$KEYBAK.pub" }
  }
}
function Restore-UserKey {
  Remove-Item -LiteralPath $KEY, "$KEY.pub" -Force -ErrorAction SilentlyContinue
  if (Test-Path -LiteralPath $KEYBAK) { Move-Item -LiteralPath $KEYBAK $KEY; if (Test-Path -LiteralPath "$KEYBAK.pub") { Move-Item -LiteralPath "$KEYBAK.pub" "$KEY.pub" } }
}
function Adb { param([Parameter(ValueFromRemainingArguments = $true)][string[]]$A) $o = & $ADB @A 2>&1 | Out-String; return $o.Replace("`r", "") }
function A   { param([Parameter(ValueFromRemainingArguments = $true)][string[]]$A) return (Adb (@('-s', $script:SERIAL) + $A)) }
function Sh  { param([Parameter(ValueFromRemainingArguments = $true)][string[]]$A) return (A (@('shell') + $A)).Trim() }
function Close-AdbServer { if (Test-Path -LiteralPath $ADB) { & $ADB kill-server 2>&1 | Out-Null } }

function Get-Adb {
  Step "1. Getting ADB (Google's official tool, saved in the tools folder)"
  if (Test-Path -LiteralPath $ADB) { Ok "Already here" } else {
    Say "Downloading platform-tools from dl.google.com ..."
    $zip = Join-Path $TOOLS 'platform-tools.zip'
    try { Invoke-WebRequest -UseBasicParsing -Uri 'https://dl.google.com/android/repository/platform-tools-latest-windows.zip' -OutFile $zip }
    catch { Stop-Glass "Couldn't download ADB. Check this PC's internet connection and try again." }
    Expand-Archive -LiteralPath $zip -DestinationPath $TOOLS -Force; Remove-Item -LiteralPath $zip -Force
    if (-not (Test-Path -LiteralPath $ADB)) { Stop-Glass "ADB didn't unpack." }
    Ok "ADB is ready"
  }
  Close-AdbServer; Use-OwnKey
}

function Wait-Ready([string]$target, [int]$secs = 60) {
  $hint = $false; $t = 0
  while ($t -lt $secs) {
    Adb connect $target | Out-Null
    $st = (Adb -s $target get-state).Trim()
    if ($st -eq 'device') { $script:SERIAL = $target; return $true }
    if ($st -match 'unauthorized|authorizing') { $script:NEED_ALLOW = $true; if (-not $hint) { Write-Host "   Press Allow on your TV (tick Always allow)." -ForegroundColor White; $hint = $true } }
    Start-Sleep -Seconds 3; $t += 3
  }
  return $false
}

function Connect-Tv {
  Step "2. Connecting to your TV"
  $lastFile = Join-Path $TOOLS 'last-ip.txt'; $last = ''
  if (Test-Path -LiteralPath $lastFile) { $last = (Get-Content -LiteralPath $lastFile -Raw).Trim() }
  while ($true) {
    $sfx = ''; if ($last) { $sfx = " [$last]" }
    $ip = (Ask "Type your TV's IP address (Settings -> Network & Internet -> your Wi-Fi):$sfx") -replace '\s', ''
    if (-not $ip) { $ip = $last }
    if ($ip -match '^(\d{1,3}\.){3}\d{1,3}(:\d{1,5})?$') { break }
    Warn "That doesn't look like an IP address. It looks like 192.168.1.23"
  }
  $script:TVIP = ($ip -split ':')[0]; Set-Content -LiteralPath $lastFile -Value $script:TVIP
  if ($ip -notmatch ':') { $ip = "${ip}:5555" }
  Say "Connecting to $ip ..."
  $script:NEED_ALLOW = $false
  if (Wait-Ready $ip 60) { Ok "Connected to the TV"; return }
  # The TV answered but nobody pressed Allow: debugging is on, so don't send them looking for it
  if ($script:NEED_ALLOW) {
    Warn "The TV is still waiting for you to press Allow. If you missed the popup, it comes back now."
    Adb disconnect $ip | Out-Null
    if (Wait-Ready $ip 120) { Ok "Connected to the TV"; return }
  }
  Warn "The TV didn't answer."
  Say "Most likely USB debugging is off. On the TV:"
  Say "  1. Settings -> System -> About -> press OK on 'Android TV OS build' 7 times ('You are now a developer')."
  Say "  2. Settings -> System -> Developer options -> turn on USB debugging."
  Say "Some TVs use Wireless debugging with a pairing code instead (Developer options -> Wireless debugging)."
  while ($true) {
    switch (Ask "Type 1 = try again, 2 = pair with a code (Wireless debugging), 3 = quit:") {
      '1' { if (Wait-Ready $ip 60) { Ok "Connected to the TV"; return }; Warn "Still no answer." }
      '2' {
        Say "On the TV: Developer options -> Wireless debugging -> Pair device with pairing code."
        $pp = (Ask "Type the IP address & port shown under the code (like 192.168.1.23:37115):") -replace '\s', ''
        $code = (Ask "Type the 6-digit pairing code:") -replace '\s', ''
        $r = Adb pair $pp $code
        if ($r -notmatch 'Successfully') { Warn "Pairing failed. Check the code and try again."; continue }
        $cp = (Ask "Now type the IP address & Port shown on the Wireless debugging screen (like 192.168.1.23:41235):") -replace '\s', ''
        if (Wait-Ready $cp 30) { Ok "Connected to the TV"; return }; Warn "Couldn't connect with that address."
      }
      default { Stop-Glass "Stopped. Nothing on the TV was changed." }
    }
  }
}

function Test-Tv {
  Step "3. Checking the TV"
  $script:SDK = [int]("0" + (Sh getprop ro.build.version.sdk)); $script:MODEL = Sh getprop ro.product.model
  $script:BRAND = Sh getprop ro.product.brand; $script:MAKER = Sh getprop ro.product.manufacturer; $rel = Sh getprop ro.build.version.release
  Say "$script:MAKER $script:MODEL - Android $rel"
  if ("$script:MAKER $script:BRAND" -match 'amazon') { Stop-Glass "This is a Fire TV (Fire OS). Glass needs Android TV or Google TV, sorry. Nothing was changed." }
  if ((Sh pm list features) -notmatch 'android\.software\.leanback') { Stop-Glass "This doesn't look like an Android TV or Google TV. Nothing was changed." }
  if ($script:SDK -lt 28) { Stop-Glass "Glass needs Android 9 or newer; this TV has Android $rel. Nothing was changed." }
  $script:SAFE_MODEL = (("$script:MAKER-$script:MODEL") -replace '[^A-Za-z0-9._-]', '_').TrimEnd('_')
  Ok "Android TV / Google TV, Android ${rel}: supported"
}

function Get-ModelBackups { return @(Get-ChildItem -LiteralPath $BACKUPS -Directory -Filter "$script:SAFE_MODEL-*" -ErrorAction SilentlyContinue | Sort-Object Name) }

function Close-Adb {
  Step "Closing ADB (the TV's remote-debugging door)"
  Sh settings put global adb_wifi_enabled 0 | Out-Null
  A shell settings put global adb_enabled 0 | Out-Null
  Adb disconnect $script:SERIAL | Out-Null
  Close-AdbServer; Restore-UserKey   # this script's key is deleted, so the TV's old "Allow" can't be used by anyone
  Ok "USB debugging is off and this computer's key was deleted"
  Say "(Next time, the TV asks you to press Allow again. That's on purpose.)"
}
