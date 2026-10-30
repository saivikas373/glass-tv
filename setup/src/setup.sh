# ---------------------------------------------------------------- Glass-Setup (Mac)
clear 2>/dev/null; printf '%sGlass TV setup%s\n' "$B" "$N"
say "This sets up Glass on your TV over your Wi-Fi: it backs up the TV, switches off ad and tracking apps"
say "(nothing is deleted), installs Glass, turns it on, and closes the debugging door at the end."
say "Running it again after a TV update is safe: that's how you repair Glass."

# Never touched, whatever a list says: settings, setup, system UI, Play, inputs/Live TV, keyboard, remote, audio, updates, launchers.
NEVER='(setupwraith|\.gms$|\.gsf$|vending|systemui|\.settings|launcher|tvlauncher|inputmethod|\.ime|keyboard|bluetooth|tv\.remote|remote\.service|katniss|tvinput|\.tv$|livetv|dashboard|ttvs|update|(^|\.)ota|firmware|audio|\.hdmi|packageinstaller|permissioncontroller|webview|^android$|providers\.)'

get_glass() {
  step "Getting Glass.apk"
  if [ -f "$DIR/Glass.apk" ]; then APK="$DIR/Glass.apk"; ok "Using the Glass.apk next to this script"; return; fi
  APK="$TOOLS/Glass.apk"
  curl -fL --progress-bar -o "$APK.part" "https://github.com/$REPO/releases/latest/download/Glass.apk" \
    || { [ -f "$APK" ] && { warn "Couldn't download a new Glass.apk; using the one from last time."; return; }; stop "Couldn't download Glass.apk. Check the internet connection, or put Glass.apk next to this script."; }
  if curl -fsL -o "$TOOLS/SHA256SUMS.txt" "https://github.com/$REPO/releases/latest/download/SHA256SUMS.txt"; then
    local want got; want=$(grep ' Glass.apk$' "$TOOLS/SHA256SUMS.txt" | awk '{print $1}'); got=$(shasum -a 256 "$APK.part" | awk '{print $1}')
    [ -n "$want" ] && [ "$want" != "$got" ] && { rm -f "$APK.part"; stop "The downloaded Glass.apk doesn't match the release checksum. Try again later."; }
  fi
  mv "$APK.part" "$APK"; ok "Downloaded Glass.apk"
}

backup_tv() {
  step "4. Backing up the TV's settings"
  BK="$DIR/backups/$SAFE_MODEL-$(date +%Y%m%d-%H%M%S)"; mkdir -p "$BK"
  for f in s 3 d e; do sh_ pm list packages -$f > "$BK/packages-$f.txt"; done
  sh_ pm list packages -f > "$BK/packages-f.txt"
  sh_ cmd package resolve-activity --brief -a android.intent.action.MAIN -c android.intent.category.HOME | tail -n 1 > "$BK/home.txt"
  for ns in global secure system; do sh_ settings list $ns > "$BK/settings-$ns.txt"; done
  sh_ getprop > "$BK/getprop.txt"
  [ -s "$BK/settings-secure.txt" ] || stop "Couldn't read the TV's settings, so nothing was changed."
  ok "Saved in backups/$(basename "$BK")  (home: $(cat "$BK/home.txt"))"
}

# The embedded list: "[all]" applies everywhere, "[tcl]" etc. only when the brand matches.
debloat() {
  step "5. Switching off ad and tracking apps"
  local brand section on p n=0 list=() batch
  brand=$(printf '%s %s' "$BRAND" "$MAKER" | tr 'A-Z' 'a-z')
  sh_ pm list packages -e | sed 's/^package://' > "$BK/.enabled"
  while IFS= read -r line; do
    line=${line%%#*}; line=$(printf '%s' "$line" | tr -d ' \t\r')
    [ -z "$line" ] && continue
    case "$line" in \[*\]) section=$(printf '%s' "$line" | tr -d '[]'); on=0
        [ "$section" = all ] && on=1
        for k in $section; do case "$brand" in *"$k"*) on=1;; esac; done; continue ;; esac
    [ "${on:-0}" = 1 ] || continue
    printf '%s\n' "$line" | grep -Eqi "$NEVER" && { warn "Skipped $line (protected)"; continue; }
    grep -qx "$line" "$BK/.enabled" && list+=("$line")
  done <<'GLASS_LIST'
@@LIST@@
GLASS_LIST
  # disabled.txt keeps everything Glass ever switched off on this TV, so Glass-Restore can undo all of it
  : > "$BK/disabled.txt"
  local prev; prev=$(backups_for_model | grep -v "$BK" | tail -n 1)
  [ -n "$prev" ] && [ -f "$prev/disabled.txt" ] && cat "$prev/disabled.txt" >> "$BK/disabled.txt"
  if [ ${#list[@]} = 0 ]; then ok "Nothing to switch off (already done, or none of them are on this TV)"; return; fi
  while [ $n -lt ${#list[@]} ]; do
    batch=("${list[@]:$n:10}"); n=$((n + 10))          # 10 at a time, checking the TV is fine after each batch
    for p in "${batch[@]}"; do
      printf '%s\n' "$p" >> "$BK/disabled.txt"
      if sh_ pm disable-user --user 0 "$p" | grep -q "disabled"; then ok "Off: $p"; else warn "Couldn't switch off $p (kept)"; fi
    done
    sleep 2
    if ! sh_ cmd package resolve-activity --brief -a android.intent.action.MAIN -c android.intent.category.HOME | grep -q /; then
      warn "The TV's Home looked wrong after that batch, so it was switched back on."
      for p in "${batch[@]}"; do sh_ pm enable "$p" >/dev/null; done; break
    fi
  done
  sort -u -o "$BK/disabled.txt" "$BK/disabled.txt"
  ok "Saved the list in backups/$(basename "$BK")/disabled.txt"
}

install_glass() {
  step "6. Installing Glass"
  local out; out=$(a install -r "$APK" 2>&1)
  case "$out" in
    *Success*) ;;
    *UPDATE_INCOMPATIBLE*|*SIGNATURES_DO_NOT_MATCH*)
      warn "An older Glass is on this TV, signed differently (a test build)."
      confirm "Remove it and install the new one? Its settings and paired phones reset." || stop "Stopped. Glass was not changed."
      a uninstall "$PKG" >/dev/null; out=$(a install -r "$APK" 2>&1) ;;
    *VERSION_DOWNGRADE*) out=$(a install -r -d "$APK" 2>&1) ;;
  esac
  case "$out" in *Success*) ok "Glass is installed" ;; *) stop "Install failed: $(printf '%s' "$out" | tail -n 1)" ;; esac
}

setup_glass() {
  step "7. Turning Glass on"
  [ "$SDK" -ge 33 ] && sh_ appops set $PKG ACCESS_RESTRICTED_SETTINGS allow >/dev/null
  sh_ pm grant $PKG android.permission.READ_CALENDAR >/dev/null
  [ "$SDK" -ge 31 ] && sh_ pm grant $PKG android.permission.BLUETOOTH_CONNECT >/dev/null
  # TCL blocks app services at boot without this (CleanYourTV); harmless elsewhere
  sh_ "cmd appops set $PKG APP_AUTO_START allow; cmd appops set $PKG AUTO_START allow; appops write-settings" >/dev/null
  # Now Playing needs notification access
  sh_ cmd notification allow_listener $PKG/$PKG.MediaListener >/dev/null
  local nl; nl=$(sh_ settings get secure enabled_notification_listeners)
  case "$nl" in *"$PKG"*) ;; null|"") sh_ settings put secure enabled_notification_listeners "$PKG/$PKG.MediaListener" ;; *) sh_ settings put secure enabled_notification_listeners "$nl:$PKG/$PKG.MediaListener" ;; esac
  ok "Permissions and Now Playing"
  sh_ settings put secure screensaver_components "$PKG/.AmbientDream"
  ok "Screensaver: Glass Ambient"
  sh_ cmd package compile -m speed -f $PKG >/dev/null
  sh_ am force-stop $PKG
  # Tell Glass it was set up from the computer BEFORE the Home override switches on, so its first-run screen never shows
  sh_ am start -W -n $PKG/.HomeActivity --ez from_setup true | grep -q "Status: ok" && ok "Glass is open" || warn "Glass didn't open by itself; open it from the apps list."
  # Accessibility (the Home button override). Added to any services already on, never replacing them.
  # Written fresh after the force stop, because a force stop takes an app's service off the list (CleanYourTV).
  local svc="$PKG/$PKG.KeyService" cur new
  cur=$(sh_ settings get secure enabled_accessibility_services)
  case "$cur" in null|"") new=$svc ;; *"$svc"*) new=$cur ;; *) new="$cur:$svc" ;; esac
  sh_ settings put secure enabled_accessibility_services "''"; sh_ settings put secure accessibility_enabled 0; sleep 1
  sh_ settings put secure enabled_accessibility_services "$new"; sh_ settings put secure accessibility_enabled 1
  sleep 2
  sh_ settings get secure enabled_accessibility_services | grep -q "$svc" && ok "Home button: goes to Glass (hold Home for the TV's own home)" \
    || warn "The TV didn't accept the accessibility setting. Glass's setup screen will help you turn it on by hand."
  sleep 3; sh_ input keyevent KEYCODE_HOME; sleep 3
  sh_ dumpsys activity activities | grep -E -m1 'mResumedActivity|topResumedActivity' | grep -q "$PKG" \
    && ok "Checked: pressing Home lands on Glass" || warn "Home didn't land on Glass yet. Turn Glass off and on in Settings → Accessibility."
}

get_adb
get_glass
connect_tv
check_tv
backup_tv
debloat
install_glass
setup_glass
close_adb
step "All done"
say "Done: backed up the TV, switched off the apps in backups/$(basename "$BK")/disabled.txt, installed and turned on Glass, closed ADB."
say "Phone remote: on your phone (same Wi-Fi) open  ${B}http://$TVIP:8642${N}  and type the PIN from Glass's Control Center."
say "${B}Keep this folder: Glass-Restore undoes everything.${N}"
say "After a TV update: turn USB debugging on and double-click Glass-Setup again."
done_pause
