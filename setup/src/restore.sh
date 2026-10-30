# ---------------------------------------------------------------- Glass-Restore (Mac)
clear 2>/dev/null; printf '%sGlass TV restore%s\n' "$B" "$N"
say "This puts your TV back the way it was before Glass-Setup: the apps it switched off come back,"
say "Glass's settings are removed, the TV's own Home comes back. You can also uninstall Glass."

get_adb
connect_tv
check_tv
step "4. Finding your backup"
ALL=$(backups_for_model)
[ -n "$ALL" ] || stop "No backup for this TV ($SAFE_MODEL) in the backups folder. Use the folder you ran Glass-Setup from."
FIRST=$(printf '%s\n' "$ALL" | head -n 1); NEWEST=$(printf '%s\n' "$ALL" | tail -n 1)
LIST=""; while IFS= read -r d; do [ -f "$d/disabled.txt" ] && { LIST="$d/disabled.txt"; break; }; done < <(printf '%s\n' "$ALL" | sort -r)   # read line by line: the folder path can contain spaces
ok "Settings from backups/$(basename "$FIRST"), app list from ${LIST:+backups/$(basename "$(dirname "$LIST")")}"
orig() { grep -E "^$1=" "$FIRST/settings-secure.txt" 2>/dev/null | head -n 1 | cut -d= -f2-; }

step "5. Turning the apps back on"
if [ -n "$LIST" ]; then
  while IFS= read -r p; do [ -z "$p" ] && continue
    sh_ pm enable "$p" | grep -q "enabled" && ok "On: $p" || warn "$p isn't on this TV any more"
  done < "$LIST"
else say "Nothing was switched off."; fi

step "6. Removing Glass's settings"
cur=$(sh_ settings get secure enabled_accessibility_services)
new=$(printf '%s' "$cur" | tr ':' '\n' | grep -v "^$PKG/" | grep -v '^null$' | paste -sd: -)
if [ -n "$new" ]; then sh_ settings put secure enabled_accessibility_services "$new"
else sh_ settings delete secure enabled_accessibility_services >/dev/null; sh_ settings put secure accessibility_enabled "$(orig accessibility_enabled || echo 0)"; fi
ok "Accessibility: Glass removed (other services kept)"
sh_ cmd notification disallow_listener $PKG/$PKG.MediaListener >/dev/null
nl=$(sh_ settings get secure enabled_notification_listeners | tr ':' '\n' | grep -v "^$PKG/" | grep -v '^null$' | paste -sd: -)
[ -n "$nl" ] && sh_ settings put secure enabled_notification_listeners "$nl" || sh_ settings delete secure enabled_notification_listeners >/dev/null
ok "Notification access: Glass removed"
ss=$(orig screensaver_components)
if [ -n "$ss" ] && [ "$ss" != null ]; then sh_ settings put secure screensaver_components "$ss"; else sh_ settings delete secure screensaver_components >/dev/null; fi
ok "Screensaver: back to ${ss:-the default}"

step "7. Giving Home back to the TV"
H=$(cat "$FIRST/home.txt" 2>/dev/null)
case "$H" in */*) sh_ pm enable "${H%%/*}" >/dev/null; sh_ cmd package set-home-activity --user 0 "$H" >/dev/null 2>&1 || sh_ cmd package set-home-activity "$H" >/dev/null 2>&1 ;; esac
sh_ am force-stop $PKG; sh_ input keyevent KEYCODE_HOME; sleep 2
ok "Home: $(sh_ cmd package resolve-activity --brief -a android.intent.action.MAIN -c android.intent.category.HOME | tail -n 1)"

if confirm "Also uninstall Glass?"; then a uninstall $PKG | grep -q Success && ok "Glass uninstalled" || warn "Couldn't uninstall Glass"; else say "Glass stays installed (it's just an app now; Home no longer goes to it)."; fi
close_adb
step "All done: your TV is back to how it was."
done_pause
