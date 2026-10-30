# ---------------------------------------------------------------- Glass-Restore (Windows)
Clear-Host
Write-Host "Glass TV restore" -ForegroundColor White
Say "This puts your TV back the way it was before Glass-Setup: the apps it switched off come back,"
Say "Glass's settings are removed, the TV's own Home comes back. You can also uninstall Glass."

Get-Adb
Connect-Tv
Test-Tv
Step "4. Finding your backup"
$all = Get-ModelBackups
if ($all.Count -eq 0) { Stop-Glass "No backup for this TV ($script:SAFE_MODEL) in the backups folder. Use the folder you ran Glass-Setup from." }
$first = $all[0].FullName
$list = $null; foreach ($d in ($all | Sort-Object Name -Descending)) { $f = Join-Path $d.FullName 'disabled.txt'; if (Test-Path -LiteralPath $f) { $list = $f; break } }
Ok "Settings from backups\$(Split-Path -Leaf $first)"
function Orig($k) { $l = Get-Content -LiteralPath (Join-Path $first 'settings-secure.txt') -ErrorAction SilentlyContinue | Where-Object { $_ -like "$k=*" } | Select-Object -First 1; if ($l) { return $l.Substring($k.Length + 1) } else { return '' } }

Step "5. Turning the apps back on"
if ($list) { foreach ($p in (Get-Content -LiteralPath $list)) { if (-not $p) { continue }; if ((Sh pm enable $p) -match 'enabled') { Ok "On: $p" } else { Warn "$p isn't on this TV any more" } } }
else { Say "Nothing was switched off." }

Step "6. Removing Glass's settings"
$keep = ((Sh settings get secure enabled_accessibility_services) -split ':' | Where-Object { $_ -and $_ -ne 'null' -and -not $_.StartsWith("$PKG/") }) -join ':'
if ($keep) { Sh settings put secure enabled_accessibility_services $keep | Out-Null }
else { Sh settings delete secure enabled_accessibility_services | Out-Null; $ae = Orig 'accessibility_enabled'; if (-not $ae) { $ae = '0' }; Sh settings put secure accessibility_enabled $ae | Out-Null }
Ok "Accessibility: Glass removed (other services kept)"
Sh cmd notification disallow_listener "$PKG/$PKG.MediaListener" | Out-Null
$nl = ((Sh settings get secure enabled_notification_listeners) -split ':' | Where-Object { $_ -and $_ -ne 'null' -and -not $_.StartsWith("$PKG/") }) -join ':'
if ($nl) { Sh settings put secure enabled_notification_listeners $nl | Out-Null } else { Sh settings delete secure enabled_notification_listeners | Out-Null }
Ok "Notification access: Glass removed"
$ss = Orig 'screensaver_components'
if ($ss -and $ss -ne 'null') { Sh settings put secure screensaver_components $ss | Out-Null } else { Sh settings delete secure screensaver_components | Out-Null }
Ok "Screensaver: back to the TV's own"

Step "7. Giving Home back to the TV"
$h = (Get-Content -LiteralPath (Join-Path $first 'home.txt') -ErrorAction SilentlyContinue | Select-Object -First 1)
if ($h -match '/') { Sh pm enable ($h -split '/')[0] | Out-Null; $r = Sh cmd package set-home-activity --user 0 $h; if ($r -match 'Error|Unknown') { Sh cmd package set-home-activity $h | Out-Null } }
Sh am force-stop $PKG | Out-Null; Sh input keyevent KEYCODE_HOME | Out-Null; Start-Sleep -Seconds 2
Ok ("Home: " + ((Sh cmd package resolve-activity --brief -a android.intent.action.MAIN -c android.intent.category.HOME) -split "`n")[-1])

if (Confirm-Yes "Also uninstall Glass?") { if ((A uninstall $PKG) -match 'Success') { Ok "Glass uninstalled" } else { Warn "Couldn't uninstall Glass" } }
else { Say "Glass stays installed (it's just an app now; Home no longer goes to it)." }
Close-Adb
Step "All done: your TV is back to how it was."
try { Stop-Transcript | Out-Null } catch {}
