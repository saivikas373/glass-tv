# ---------------------------------------------------------------- Glass-Setup (Windows)
Clear-Host
Write-Host "Glass TV setup" -ForegroundColor White
Say "This sets up Glass on your TV over your Wi-Fi: it backs up the TV, switches off ad and tracking apps"
Say "(nothing is deleted), installs Glass, turns it on, and closes the debugging door at the end."
Say "Running it again after a TV update is safe: that's how you repair Glass."

# Never touched, whatever a list says: settings, setup, system UI, Play, inputs/Live TV, keyboard, remote, audio, updates, launchers.
$NEVER = '(?i)(setupwraith|\.gms$|\.gsf$|vending|systemui|\.settings|launcher|tvlauncher|inputmethod|\.ime|keyboard|bluetooth|tv\.remote|remote\.service|katniss|tvinput|\.tv$|livetv|dashboard|ttvs|update|(^|\.)ota|firmware|audio|\.hdmi|packageinstaller|permissioncontroller|webview|^android$|providers\.)'
$GLASS_LIST = @'
@@LIST@@
'@

function Get-Glass {
  Step "Getting Glass.apk"
  $local = Join-Path $Root 'Glass.apk'
  if (Test-Path -LiteralPath $local) { $script:APK = $local; Ok "Using the Glass.apk next to this script"; return }
  $script:APK = Join-Path $TOOLS 'Glass.apk'; $part = "$script:APK.part"
  try { Invoke-WebRequest -UseBasicParsing -Uri "https://github.com/$REPO/releases/latest/download/Glass.apk" -OutFile $part }
  catch { if (Test-Path -LiteralPath $script:APK) { Warn "Couldn't download a new Glass.apk; using the one from last time."; return }
          Stop-Glass "Couldn't download Glass.apk. Check the internet connection, or put Glass.apk next to this script." }
  try {
    $sums = (Invoke-WebRequest -UseBasicParsing -Uri "https://github.com/$REPO/releases/latest/download/SHA256SUMS.txt").Content
    if ($sums -is [byte[]]) { $sums = [Text.Encoding]::UTF8.GetString($sums) }
    $want = ($sums -split "`n" | Where-Object { $_ -match ' Glass\.apk\s*$' } | ForEach-Object { ($_ -split '\s+')[0] }) | Select-Object -First 1
    $got = (Get-FileHash -LiteralPath $part -Algorithm SHA256).Hash.ToLower()
    if ($want -and ($want.ToLower() -ne $got)) { Remove-Item -LiteralPath $part -Force; Stop-Glass "The downloaded Glass.apk doesn't match the release checksum. Try again later." }
  } catch {}
  Move-Item -LiteralPath $part $script:APK -Force; Ok "Downloaded Glass.apk"
}

function Backup-Tv {
  Step "4. Backing up the TV's settings"
  $script:BK = Join-Path $BACKUPS ("$script:SAFE_MODEL-" + (Get-Date -Format 'yyyyMMdd-HHmmss')); New-Item -ItemType Directory -Force -Path $script:BK | Out-Null
  foreach ($f in 's', '3', 'd', 'e', 'f') { Set-Content -LiteralPath (Join-Path $script:BK "packages-$f.txt") -Value (Sh pm list packages "-$f") }
  $h = ((Sh cmd package resolve-activity --brief -a android.intent.action.MAIN -c android.intent.category.HOME) -split "`n")[-1].Trim()
  Set-Content -LiteralPath (Join-Path $script:BK 'home.txt') -Value $h
  foreach ($ns in 'global', 'secure', 'system') { Set-Content -LiteralPath (Join-Path $script:BK "settings-$ns.txt") -Value (Sh settings list $ns) }
  Set-Content -LiteralPath (Join-Path $script:BK 'getprop.txt') -Value (Sh getprop)
  if ((Get-Item -LiteralPath (Join-Path $script:BK 'settings-secure.txt')).Length -lt 10) { Stop-Glass "Couldn't read the TV's settings, so nothing was changed." }
  Ok "Saved in backups\$(Split-Path -Leaf $script:BK)  (home: $h)"
}

function Invoke-Debloat {
  Step "5. Switching off ad and tracking apps"
  $brand = "$script:BRAND $script:MAKER".ToLower()
  $enabled = @((Sh pm list packages -e) -split "`n" | ForEach-Object { $_.Trim() -replace '^package:', '' })
  $list = New-Object System.Collections.Generic.List[string]; $on = $false
  foreach ($raw in ($GLASS_LIST -split "`n")) {
    $line = (($raw -split '#')[0]).Trim(); if (-not $line) { continue }
    if ($line -match '^\[(.+)\]$') { $sec = $Matches[1]; $on = ($sec -eq 'all'); foreach ($k in ($sec -split '\s+')) { if ($brand.Contains($k)) { $on = $true } }; continue }
    if (-not $on) { continue }
    if ($line -match $NEVER) { Warn "Skipped $line (protected)"; continue }
    if ($enabled -contains $line) { $list.Add($line) }
  }
  # disabled.txt keeps everything Glass ever switched off on this TV, so Glass-Restore can undo all of it
  $out = Join-Path $script:BK 'disabled.txt'; $all = New-Object System.Collections.Generic.List[string]
  $prev = Get-ModelBackups | Where-Object { $_.FullName -ne $script:BK } | Select-Object -Last 1
  if ($prev -and (Test-Path -LiteralPath (Join-Path $prev.FullName 'disabled.txt'))) { Get-Content -LiteralPath (Join-Path $prev.FullName 'disabled.txt') | ForEach-Object { if ($_) { $all.Add($_) } } }
  if ($list.Count -eq 0) { Ok "Nothing to switch off (already done, or none of them are on this TV)" }
  for ($n = 0; $n -lt $list.Count; $n += 10) {          # 10 at a time, checking the TV is fine after each batch
    $batch = $list.GetRange($n, [Math]::Min(10, $list.Count - $n))
    foreach ($p in $batch) {
      $all.Add($p); Set-Content -LiteralPath $out -Value ($all | Sort-Object -Unique)
      if ((Sh pm disable-user --user 0 $p) -match 'disabled') { Ok "Off: $p" } else { Warn "Couldn't switch off $p (kept)" }
    }
    Start-Sleep -Seconds 2
    if ((Sh cmd package resolve-activity --brief -a android.intent.action.MAIN -c android.intent.category.HOME) -notmatch '/') {
      Warn "The TV's Home looked wrong after that batch, so it was switched back on."
      foreach ($p in $batch) { Sh pm enable $p | Out-Null }; break
    }
  }
  Set-Content -LiteralPath $out -Value ($all | Sort-Object -Unique)
  Ok "Saved the list in backups\$(Split-Path -Leaf $script:BK)\disabled.txt"
}

function Install-Glass {
  Step "6. Installing Glass"
  $o = A install -r $script:APK
  if ($o -match 'UPDATE_INCOMPATIBLE|SIGNATURES_DO_NOT_MATCH') {
    Warn "An older Glass is on this TV, signed differently (a test build)."
    if (-not (Confirm-Yes "Remove it and install the new one? Its settings and paired phones reset.")) { Stop-Glass "Stopped. Glass was not changed." }
    A uninstall $PKG | Out-Null; $o = A install -r $script:APK
  } elseif ($o -match 'VERSION_DOWNGRADE') { $o = A install -r -d $script:APK }
  if ($o -match 'Success') { Ok "Glass is installed" } else { Stop-Glass ("Install failed: " + (($o.Trim() -split "`n")[-1])) }
}

function Set-Glass {
  Step "7. Turning Glass on"
  if ($script:SDK -ge 33) { Sh appops set $PKG ACCESS_RESTRICTED_SETTINGS allow | Out-Null }
  Sh pm grant $PKG android.permission.READ_CALENDAR | Out-Null
  if ($script:SDK -ge 31) { Sh pm grant $PKG android.permission.BLUETOOTH_CONNECT | Out-Null }
  # TCL blocks app services at boot without this (CleanYourTV); harmless elsewhere
  Sh "cmd appops set $PKG APP_AUTO_START allow; cmd appops set $PKG AUTO_START allow; appops write-settings" | Out-Null
  Sh cmd notification allow_listener "$PKG/$PKG.MediaListener" | Out-Null
  $nl = Sh settings get secure enabled_notification_listeners
  if ($nl -notmatch [regex]::Escape($PKG)) { if ($nl -eq 'null' -or -not $nl) { $nl = "$PKG/$PKG.MediaListener" } else { $nl = "${nl}:$PKG/$PKG.MediaListener" }; Sh settings put secure enabled_notification_listeners $nl | Out-Null }
  Ok "Permissions and Now Playing"
  Sh settings put secure screensaver_components "$PKG/.AmbientDream" | Out-Null; Ok "Screensaver: Glass Ambient"
  Sh cmd package compile -m speed -f $PKG | Out-Null
  Sh am force-stop $PKG | Out-Null
  # Tell Glass it was set up from the computer BEFORE the Home override switches on, so its first-run screen never shows
  if ((Sh am start -W -n "$PKG/.HomeActivity" --ez from_setup true) -match 'Status: ok') { Ok "Glass is open" } else { Warn "Glass didn't open by itself; open it from the apps list." }
  # Accessibility (the Home button override). Added to any services already on, never replacing them.
  # Written fresh after the force stop, because a force stop takes an app's service off the list (CleanYourTV).
  $svc = "$PKG/$PKG.KeyService"; $cur = Sh settings get secure enabled_accessibility_services
  if ($cur -eq 'null' -or -not $cur) { $new = $svc } elseif ($cur.Contains($svc)) { $new = $cur } else { $new = "${cur}:$svc" }
  Sh settings put secure enabled_accessibility_services "''" | Out-Null; Sh settings put secure accessibility_enabled 0 | Out-Null; Start-Sleep -Seconds 1
  Sh settings put secure enabled_accessibility_services $new | Out-Null; Sh settings put secure accessibility_enabled 1 | Out-Null
  Start-Sleep -Seconds 2
  if ((Sh settings get secure enabled_accessibility_services).Contains($svc)) { Ok "Home button: goes to Glass (hold Home for the TV's own home)" }
  else { Warn "The TV didn't accept the accessibility setting. Glass's setup screen will help you turn it on by hand." }
  Start-Sleep -Seconds 3; Sh input keyevent KEYCODE_HOME | Out-Null; Start-Sleep -Seconds 3
  $top = (Sh dumpsys activity activities) -split "`n" | Where-Object { $_ -match 'mResumedActivity|topResumedActivity' } | Select-Object -First 1
  if ($top -match [regex]::Escape($PKG)) { Ok "Checked: pressing Home lands on Glass" } else { Warn "Home didn't land on Glass yet. Turn Glass off and on in Settings -> Accessibility." }
}

Get-Adb
Get-Glass
Connect-Tv
Test-Tv
Backup-Tv
Invoke-Debloat
Install-Glass
Set-Glass
Close-Adb
Step "All done"
Say "Done: backed up the TV, switched off the apps in backups\$(Split-Path -Leaf $script:BK)\disabled.txt, installed and turned on Glass, closed ADB."
Write-Host "   Phone remote: on your phone (same Wi-Fi) open  http://$($script:TVIP):8642  and type the PIN from Glass's Control Center." -ForegroundColor White
Write-Host "   Keep this folder: Glass-Restore undoes everything." -ForegroundColor White
Say "After a TV update: turn USB debugging on and double-click Glass-Setup again."
try { Stop-Transcript | Out-Null } catch {}
