# ---------------------------------------------------------------- shared by Glass-Setup and Glass-Restore (Mac)
set -u
DIR="$(cd "$(dirname "$0")" && pwd)"          # works from folders with spaces in their names
cd "$DIR"
PKG=tv.glass.launcher
REPO=saivikas373/glass-tv
TOOLS="$DIR/tools"; ADB="$TOOLS/platform-tools/adb"
ADBHOME="$TOOLS/adb-key"                      # this script's own ADB key, deleted when it closes ADB
export ANDROID_ADB_SERVER_PORT=5038           # its own ADB server, so nothing else on this computer is touched
SERIAL=""
mkdir -p "$TOOLS" "$DIR/backups"
LOG="$DIR/backups/log-$(date +%Y%m%d-%H%M%S).txt"
exec > >(tee -a "$LOG") 2>&1

B=$'\033[1m'; G=$'\033[32m'; Y=$'\033[33m'; R=$'\033[31m'; N=$'\033[0m'
step() { printf '\n%s== %s%s\n' "$B" "$*" "$N"; }
say()  { printf '   %s\n' "$*"; }
ok()   { printf '   %s✓ %s%s\n' "$G" "$*" "$N"; }
warn() { printf '   %s! %s%s\n' "$Y" "$*" "$N"; }
done_pause() { echo; read -r -p "Press Return to close this window. " _ || true; }
stop() { printf '\n%s%s%s\n' "$R" "$*" "$N"; cleanup_adb; done_pause; exit 1; }
ask()  { local a; read -r -p "   $1 " a || true; printf '%s' "$a"; }
confirm() { case "$(ask "$1 (y/n)")" in y|Y|yes|Yes|YES) return 0;; *) return 1;; esac; }

adb_() { mkdir -p "$ADBHOME/.android"; HOME="$ADBHOME" "$ADB" "$@" </dev/null; }   # </dev/null: adb must not eat a read loop's input
a()    { adb_ -s "$SERIAL" "$@"; }
sh_()  { a shell "$@" 2>/dev/null | tr -d '\r'; }
cleanup_adb() { [ -x "$ADB" ] && adb_ kill-server >/dev/null 2>&1; true; }

get_adb() {
  step "1. Getting ADB (Google's official tool, saved in the tools folder)"
  if [ -x "$ADB" ] && "$ADB" version >/dev/null 2>&1; then ok "Already here"; return; fi
  say "Downloading platform-tools from dl.google.com ..."
  curl -fL --progress-bar -o "$TOOLS/platform-tools.zip" "https://dl.google.com/android/repository/platform-tools-latest-darwin.zip" \
    || stop "Couldn't download ADB. Check this computer's internet connection and try again."
  rm -rf "$TOOLS/platform-tools"; unzip -q -o "$TOOLS/platform-tools.zip" -d "$TOOLS" && rm -f "$TOOLS/platform-tools.zip"
  xattr -dr com.apple.quarantine "$TOOLS/platform-tools" 2>/dev/null || true
  "$ADB" version >/dev/null 2>&1 || stop "ADB didn't start on this Mac."
  ok "ADB is ready"
}

# Wait until the TV says "device". Shows the Allow hint once. Returns 0 when connected.
wait_ready() {
  local target=$1 secs=${2:-60} hint=0 st t=0
  while [ $t -lt "$secs" ]; do
    adb_ connect "$target" >/dev/null 2>&1
    st=$(adb_ -s "$target" get-state 2>&1 | tr -d '\r')
    case "$st" in
      device) SERIAL=$target; return 0 ;;
      *unauthorized*|*authorizing*) NEED_ALLOW=1; [ $hint = 0 ] && { say "${B}Press Allow on your TV (tick Always allow).${N}"; hint=1; } ;;
    esac
    sleep 3; t=$((t + 3))
  done
  return 1
}

connect_tv() {
  step "2. Connecting to your TV"
  local last="" ip
  [ -f "$TOOLS/last-ip.txt" ] && last=$(cat "$TOOLS/last-ip.txt")
  while :; do
    ip=$(ask "Type your TV's IP address (Settings → Network & Internet → your Wi-Fi):${last:+ [$last]}")
    ip=${ip:-$last}; ip=${ip// /}
    [[ "$ip" =~ ^([0-9]{1,3}\.){3}[0-9]{1,3}(:[0-9]{1,5})?$ ]] && break
    warn "That doesn't look like an IP address. It looks like 192.168.1.23"
  done
  echo "${ip%%:*}" > "$TOOLS/last-ip.txt"; TVIP=${ip%%:*}
  case "$ip" in *:*) ;; *) ip="$ip:5555" ;; esac
  say "Connecting to $ip ..."
  NEED_ALLOW=0
  if wait_ready "$ip" 60; then ok "Connected to the TV"; return; fi
  # The TV answered but nobody pressed Allow: debugging is on, so don't send them looking for it
  if [ "$NEED_ALLOW" = 1 ]; then
    warn "The TV is still waiting for you to press Allow. If you missed the popup, it comes back now."
    adb_ disconnect "$ip" >/dev/null 2>&1
    wait_ready "$ip" 120 && { ok "Connected to the TV"; return; }
  fi
  warn "The TV didn't answer."
  say "Most likely ${B}USB debugging is off${N}. On the TV:"
  say "  1. Settings → System → About → press OK on \"Android TV OS build\" 7 times (\"You are now a developer\")."
  say "  2. Settings → System → Developer options → turn on USB debugging."
  say "Some TVs use ${B}Wireless debugging${N} with a pairing code instead (Developer options → Wireless debugging)."
  while :; do
    case "$(ask "Type 1 = try again, 2 = pair with a code (Wireless debugging), 3 = quit:")" in
      1) wait_ready "$ip" 60 && { ok "Connected to the TV"; return; }; warn "Still no answer." ;;
      2) local pp code cp
         say "On the TV: Developer options → Wireless debugging → Pair device with pairing code."
         pp=$(ask "Type the IP address & port shown under the code (like 192.168.1.23:37115):")
         code=$(ask "Type the 6-digit pairing code:")
         adb_ pair "${pp// /}" "${code// /}" || { warn "Pairing failed. Check the code and try again."; continue; }
         cp=$(ask "Now type the IP address & Port shown on the Wireless debugging screen (like 192.168.1.23:41235):")
         wait_ready "${cp// /}" 30 && { ok "Connected to the TV"; return; }; warn "Couldn't connect with that address." ;;
      *) stop "Stopped. Nothing on the TV was changed." ;;
    esac
  done
}

check_tv() {
  step "3. Checking the TV"
  SDK=$(sh_ getprop ro.build.version.sdk); MODEL=$(sh_ getprop ro.product.model); BRAND=$(sh_ getprop ro.product.brand)
  MAKER=$(sh_ getprop ro.product.manufacturer); REL=$(sh_ getprop ro.build.version.release)
  say "$MAKER $MODEL · Android $REL"
  case "$MAKER $BRAND" in *[Aa]mazon*) stop "This is a Fire TV (Fire OS). Glass needs Android TV or Google TV, sorry. Nothing was changed." ;; esac
  sh_ pm list features | grep -q "android.software.leanback" || stop "This doesn't look like an Android TV or Google TV (it's a phone or tablet build). Nothing was changed."
  [ "${SDK:-0}" -ge 28 ] 2>/dev/null || stop "Glass needs Android 9 or newer; this TV has Android $REL. Nothing was changed."
  SAFE_MODEL=$(printf '%s' "$MAKER-$MODEL" | tr -c 'A-Za-z0-9._-' '_' | sed 's/_*$//')
  ok "Android TV / Google TV, Android $REL: supported"
}

# Backup folders for this TV, oldest first
backups_for_model() { ls -1d "$DIR/backups/$SAFE_MODEL-"* 2>/dev/null | sort; }

close_adb() {
  step "Closing ADB (the TV's remote-debugging door)"
  sh_ settings put global adb_wifi_enabled 0 >/dev/null
  a shell settings put global adb_enabled 0 >/dev/null 2>&1
  adb_ disconnect "$SERIAL" >/dev/null 2>&1
  cleanup_adb
  rm -rf "$ADBHOME"   # this computer's key is gone, so the TV's old "Allow" can't be used by anyone
  ok "USB debugging is off and this computer's key was deleted"
  say "(Next time, the TV asks you to press Allow again. That's on purpose.)"
}
