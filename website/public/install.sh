#!/usr/bin/env bash
# canvasOS installer for macOS and Linux. Follows AGENTS.md (https://canvasos.pages.dev/agent.md).
#
#   curl -fsSL https://canvasos.pages.dev/install.sh | TV=192.168.x.x bash
#   curl -fsSL https://canvasos.pages.dev/install.sh | TV=192.168.x.x KEEP_ADB=1 bash      # Xiaomi: keep ADB on after standby
#   curl -fsSL https://canvasos.pages.dev/install.sh | TV=192.168.x.x bash -s -- restore   # give the original home back
#   curl -fsSL https://canvasos.pages.dev/install.sh | TV=192.168.x.x bash -s -- restore --uninstall
#
# Environment:
#   TV        the TV's IPv4 address (port 5555 by default), or an adb serial already listed by `adb devices`
#   KEEP_ADB  1 = keep ADB debugging on after standby (Xiaomi only; ignored elsewhere)
#   FLAVOR    standard | guard (default: guard on TCL / FFalcon, standard everywhere else)
#   APK       install this local APK file instead of downloading the latest Release
#   DRY_RUN   1 = print every command that would change the TV, change nothing
#
# What it never does (AGENTS.md, "Règles absolues"): power off, sleep or reboot the TV, uninstall
# a system app, or disable a launcher. It uninstalls canvasOS only with `restore --uninstall`.
# Before changing anything it saves the TV's original state under ~/.canvasos/<TV>/.
# Requires bash 3.2+, adb and curl (Python 3 as well for the TCL multi-screen push).

set -euo pipefail
# fd 3: dry-run lines, unaffected by the >/dev/null and 2>/dev/null on individual commands.
exec 3>&1

main() {
  PKG=com.getisolated.canvasos
  RELEASES=https://github.com/getisolated/canvasOS/releases/latest/download
  TCAST=https://raw.githubusercontent.com/getisolated/canvasOS/main/tools/tcast-push.py

  MODE=install
  UNINSTALL=0
  while [ $# -gt 0 ]; do
    case "$1" in
      install|restore) MODE=$1 ;;
      --uninstall) UNINSTALL=1 ;;
      -h|--help) sed -n '2,20p' "${BASH_SOURCE[0]:-/dev/null}" 2>/dev/null || true; exit 0 ;;
      *) fail "unknown argument: $1 (use install, restore or restore --uninstall)" ;;
    esac
    shift
  done
  [ "$MODE" = restore ] || [ "$UNINSTALL" = 0 ] || fail "--uninstall only goes with restore"

  DRY=${DRY_RUN:-0}
  [ "$DRY" = 0 ] || [ "$DRY" = 1 ] || fail "DRY_RUN must be 0 or 1"
  KEEP_ADB=${KEEP_ADB:-0}
  [ "$KEEP_ADB" = 0 ] || [ "$KEEP_ADB" = 1 ] || fail "KEEP_ADB must be 0 or 1"

  [ -n "${TV:-}" ] || fail "set TV to your TV's IP address, for example: curl -fsSL https://canvasos.pages.dev/install.sh | TV=192.168.1.20 bash"
  if [[ "$TV" =~ ^[0-9]{1,3}(\.[0-9]{1,3}){3}(:[0-9]{1,5})?$ ]]; then
    SERIAL=$TV
    case "$SERIAL" in *:*) ;; *) SERIAL="$SERIAL:5555" ;; esac
    NETWORK=1
  elif [[ "$TV" =~ ^[A-Za-z0-9._:-]+$ ]]; then
    SERIAL=$TV   # an adb serial such as emulator-5554 or a USB device
    NETWORK=0
  else
    fail "TV must be an IPv4 address (optionally :port) or an adb serial"
  fi

  STATE="$HOME/.canvasos/${SERIAL//:/_}"
  [ "$DRY" = 1 ] && say "DRY_RUN=1: nothing on the TV will change; commands that would are printed with +"

  if ! command -v adb >/dev/null 2>&1; then
    [ "$DRY" = 1 ] || fail "adb is missing. macOS: brew install android-platform-tools · Debian/Ubuntu: sudo apt install adb"
    warn "adb is missing (dry run continues without a TV)"
    ONLINE=0
  else
    connect
  fi

  if [ "$MODE" = restore ]; then restore; else install; fi
}

# ---------- output ----------
say()  { printf '\033[1m==>\033[0m %s\n' "$*"; }
warn() { printf '\033[33mwarning:\033[0m %s\n' "$*" >&2; }
fail() { printf '\033[31merror:\033[0m %s\n' "$*" >&2; exit 1; }

# ---------- adb ----------
# stdin is always /dev/null: with `curl | bash` the script itself is on stdin and adb would eat it.
ad() { adb -s "$SERIAL" "$@" </dev/null; }
# Read-only query: runs even in a dry run when the TV is reachable; prints nothing otherwise.
q() { if [ "${ONLINE:-0}" = 1 ]; then { ad shell "$@" 2>/dev/null | tr -d '\r'; } || true; fi; }
# Anything that changes the TV goes through run.
run() {
  if [ "$DRY" = 1 ]; then printf '  + adb -s %s %s\n' "$SERIAL" "$*" >&3; return 0; fi
  ad "$@"
}

connect() {
  ONLINE=0
  if [ "$NETWORK" = 1 ]; then adb connect "$SERIAL" </dev/null >/dev/null 2>&1 || true; fi
  local st
  st=$(adb -s "$SERIAL" get-state </dev/null 2>/dev/null || true)
  if [ "$st" = device ]; then ONLINE=1; say "Connected to $SERIAL"; return; fi
  local why="the TV did not answer on $SERIAL: turn on ADB debugging (see https://canvasos.pages.dev/#install) and check the IP"
  if adb devices </dev/null 2>/dev/null | grep -q "^${SERIAL}[[:space:]]*unauthorized"; then
    why="the TV has not authorised this computer: on the TV, tick \"Always allow from this computer\" and choose Allow, then run this again"
  fi
  [ "$DRY" = 1 ] || fail "$why"
  warn "$why (dry run continues without a TV)"
}

home_activity() {
  # Last line of resolve-activity --brief is package/activity; older systems print a full dump.
  local out
  out=$(q cmd package resolve-activity --brief -a android.intent.action.MAIN -c android.intent.category.HOME | tail -n 1)
  case "$out" in */*) printf '%s' "$out" ;; *) printf '' ;; esac
}

resumed() { q dumpsys activity activities | grep -m1 ResumedActivity || true; }

press_home_and_check() {
  run shell input keyevent KEYCODE_HOME >/dev/null
  if [ "$DRY" = 1 ]; then printf '  + check: dumpsys activity activities | grep -m1 ResumedActivity\n' >&3; return 0; fi
  # The home guard can take a moment to bring canvasOS back over the stock launcher.
  local r
  for _ in 1 2 3 4 5 6; do
    sleep 1
    r=$(resumed)
    case "$r" in *"$PKG/"*) break ;; esac
  done
  printf '  %s\n' "$(printf '%s' "${r:-ResumedActivity: (not reported)}" | sed 's/^ *//')"
  case "$r" in *"$PKG/"*) return 0 ;; *) return 1 ;; esac
}

start_canvasos() {
  # Open canvasOS once so the guard has something to bring back, then let it settle.
  run shell am start -n "$PKG/.HomeActivity" >/dev/null || true
  [ "$DRY" = 1 ] || sleep 3
}

config() {
  # The app's CONFIG broadcast (AGENTS.md step 4); prints its data="…" answer.
  local out
  if [ "$DRY" = 1 ]; then run shell am broadcast -a "$PKG.CONFIG" -n "$PKG/.ConfigReceiver" "$@"; return 0; fi
  out=$(ad shell am broadcast -a "$PKG.CONFIG" -n "$PKG/.ConfigReceiver" "$@" 2>&1 | tr -d '\r' || true)
  printf '  %s\n' "$(printf '%s\n' "$out" | grep -o 'data=.*' || printf '%s' "$out" | tail -n 1)"
}

installed() { q pm path "$PKG" | grep -q '^package:'; }

# ---------- install ----------
install() {
  local sdk maker model brand
  sdk=$(q getprop ro.build.version.sdk)
  maker=$(q getprop ro.product.manufacturer)
  model=$(q getprop ro.product.model)
  brand=$(printf '%s %s' "$maker" "$(q getprop ro.product.brand)" | tr '[:upper:]' '[:lower:]')
  if [ "${ONLINE:-0}" = 1 ]; then
    [[ "$sdk" =~ ^[0-9]+$ ]] || fail "could not read the Android version"
    [ "$sdk" -ge 28 ] || fail "canvasOS needs Android 9 (API 28) or later; this TV reports API $sdk"
    say "TV: $maker $model, API $sdk"
  fi

  local is_tcl=0 is_xiaomi=0
  case "$brand" in *tcl*|*ffalcon*) is_tcl=1 ;; esac
  case "$brand" in *xiaomi*|*redmi*) is_xiaomi=1 ;; esac
  local flavor=${FLAVOR:-}
  [ -n "$flavor" ] || { [ "$is_tcl" = 1 ] && flavor=guard || flavor=standard; }
  [ "$flavor" = standard ] || [ "$flavor" = guard ] || fail "FLAVOR must be standard or guard"
  if [ "$KEEP_ADB" = 1 ] && [ "$is_xiaomi" = 0 ] && [ "${ONLINE:-0}" = 1 ]; then
    warn "KEEP_ADB=1 only matters on Xiaomi / Redmi; ignored on this TV"
    KEEP_ADB=0
  fi

  snapshot "$maker" "$model" "$sdk"

  # Download (or take APK=…).
  local apk_name apk
  [ "$flavor" = guard ] && apk_name=canvasOS-guard.apk || apk_name=canvasOS.apk
  TMPD=$(mktemp -d "${TMPDIR:-/tmp}/canvasos.XXXXXX")
  trap 'rm -rf "$TMPD"' EXIT
  if [ -n "${APK:-}" ]; then
    [ -f "$APK" ] || fail "APK file not found: $APK"
    apk=$APK
    say "Using $apk"
  else
    apk="$TMPD/$apk_name"
    say "Downloading $RELEASES/$apk_name"
    if [ "$DRY" = 1 ]; then
      printf '  + curl -fL -o %s %s/%s\n' "$apk" "$RELEASES" "$apk_name" >&3
    else
      command -v curl >/dev/null 2>&1 || fail "curl is missing"
      curl -fL --retry 2 --progress-bar -o "$apk" "$RELEASES/$apk_name" </dev/null || fail "download failed"
    fi
  fi
  if [ "$DRY" = 0 ]; then
    [ "$(head -c 2 "$apk")" = PK ] && [ "$(wc -c <"$apk")" -gt 1000000 ] || fail "$apk is not an APK"
  fi

  # Install, never uninstalling anything.
  say "Installing canvasOS ($flavor)"
  local out=""
  if [ "$DRY" = 1 ]; then
    run install -r "$apk"
  else
    out=$(ad install -r "$apk" 2>&1 | tr -d '\r' || true)
    printf '  %s\n' "$(printf '%s\n' "$out" | tail -n 1)"
  fi
  case "$out" in
    *Success*|"") ;;
    *INSTALL_FAILED_UPDATE_INCOMPATIBLE*)
      fail "the canvasOS already on the TV is signed with another key (a local build or an old Release).
       Updating it needs one uninstall first, which erases its settings and permissions.
       Nothing was changed. If you accept that, run:
         curl -fsSL https://canvasos.pages.dev/install.sh | TV=$TV bash -s -- restore --uninstall
       then this install again." ;;
    *INSTALL_FAILED_VERIFICATION_FAILURE*)
      if [ "$is_tcl" = 1 ] || [ "$flavor" = guard ]; then tcast_push "$TMPD"
      else fail "the TV's verifier refused the install (INSTALL_FAILED_VERIFICATION_FAILURE). See AGENTS.md step 3."; fi ;;
    *) fail "adb install failed: $(printf '%s\n' "$out" | tail -n 1)" ;;
  esac
  if [ "$DRY" = 0 ]; then installed || fail "canvasOS is not installed after all (pm path is empty)"; fi

  # Home screen and the grants only adb can give (AGENTS.md step 4).
  if [ "$flavor" = standard ]; then
    say "Making canvasOS the home screen"
    run shell cmd package set-home-activity "$PKG/.HomeActivity" >/dev/null || warn "set-home-activity refused"
  fi
  say "Granting permissions"
  run shell pm grant "$PKG" android.permission.WRITE_SECURE_SETTINGS || warn "WRITE_SECURE_SETTINGS not granted"
  if [ -z "$sdk" ] || [ "$sdk" -ge 31 ]; then
    run shell pm grant "$PKG" android.permission.BLUETOOTH_CONNECT 2>/dev/null || true
  fi
  run shell cmd notification allow_listener "$PKG/$PKG.RevivalListener" || warn "notification listener not allowed"
  run shell appops set "$PKG" SYSTEM_ALERT_WINDOW allow || true
  if [ "$is_tcl" = 1 ]; then
    run shell appops set "$PKG" APP_AUTO_START allow 2>/dev/null || true
    run shell appops set "$PKG" APP_ASSOC_START allow 2>/dev/null || true
  fi
  run shell pm grant "$PKG" android.permission.READ_TV_LISTINGS 2>/dev/null || true

  # Xiaomi / TCL (and the guard build) need the home guard; elsewhere only if Home still opens the stock launcher.
  local guard=0
  if [ "$flavor" = guard ] || [ "$is_tcl" = 1 ] || [ "$is_xiaomi" = 1 ]; then guard=1; fi
  if [ "$guard" = 1 ]; then
    say "Turning on the home guard"
    if [ "$KEEP_ADB" = 1 ]; then config --ez guard true --ez keep_adb true; else config --ez guard true; fi
    start_canvasos
  elif [ "$KEEP_ADB" = 1 ]; then
    config --ez keep_adb true
  fi

  say "Pressing Home and checking what is in front"
  if [ "$DRY" = 1 ]; then
    press_home_and_check
    say "(dry run) if the stock launcher came back: set-home-activity again, then the home guard; never disable a launcher"
  elif press_home_and_check; then
    say "canvasOS is the home screen."
  elif [ "$guard" = 0 ]; then
    say "The stock launcher came back; trying again, then the home guard (AGENTS.md step 5)"
    run shell cmd package set-home-activity "$PKG/.HomeActivity" >/dev/null || true
    if press_home_and_check; then
      say "canvasOS is the home screen."
    else
      config --ez guard true
      start_canvasos
      if press_home_and_check; then
        say "canvasOS is the home screen, kept in front by the home guard."
      else
        warn "Home still opens the stock launcher. canvasOS stays installed and opens as an app.
         No launcher was disabled. See AGENTS.md section 10, or ask an AI agent to follow it."
      fi
    fi
  else
    warn "Home did not bring canvasOS to the front yet. The guard may need a few seconds after first launch:
         press Home on the remote, then check again. See AGENTS.md section 10."
  fi

  if [ "$DRY" = 1 ]; then say "Dry run finished: nothing on the TV was changed."; return 0; fi
  cat <<EOF

Done. Next, on the TV:
  - canvasOS offers to turn on power off, sleep and full memory cleanup: press "Commencer", then tick
    "Always allow" and choose "Allow" when the TV asks "Allow debugging?".
  - Updates: Home settings (Réglages de l'accueil) -> Système -> Mise à jour.
Original state saved in $STATE (keep it: restore uses it).
Restore later with:
  curl -fsSL https://canvasos.pages.dev/install.sh | TV=$TV bash -s -- restore
EOF
}

snapshot() {
  # Saved once, before the first change; an update never overwrites it.
  if [ -f "$STATE/home" ]; then say "Original state already saved in $STATE"; return; fi
  if [ "${ONLINE:-0}" = 0 ]; then printf '  + save home, screensaver and accessibility state to %s\n' "$STATE" >&3; return; fi
  local home
  home=$(home_activity)
  case "$home" in "$PKG"/*) warn "canvasOS is already the home screen; the saved original home will be empty" ; home="" ;; esac
  if [ "$DRY" = 1 ]; then
    printf '  + save to %s: home=%s screensaver=%s\n' "$STATE" "${home:-?}" "$(q settings get secure screensaver_components)" >&3
    return
  fi
  mkdir -p "$STATE"
  chmod 700 "$STATE"
  printf '%s\n' "$home" >"$STATE/home"
  q settings get secure screensaver_components >"$STATE/screensaver"
  q settings get secure enabled_accessibility_services >"$STATE/accessibility"
  q settings get secure enabled_notification_listeners >"$STATE/listeners"
  printf 'manufacturer=%s\nmodel=%s\nsdk=%s\ndate=%s\n' "$1" "$2" "$3" "$(date -u +%Y-%m-%dT%H:%M:%SZ)" >"$STATE/device"
  say "Original state saved in $STATE (home: ${home:-unknown})"
}

tcast_push() {
  # TCL refuses every adb install: push the guard APK through its multi-screen interaction (T-Cast).
  local dir=$1 guard_apk script ip
  [ "$NETWORK" = 1 ] || fail "the TCL push needs the TV's IP address in TV="
  ip=${SERIAL%%:*}
  command -v python3 >/dev/null 2>&1 || fail "python3 is needed for the TCL push"
  guard_apk="$dir/canvasOS-guard.apk"
  script="$dir/tcast-push.py"
  if [ -n "${APK:-}" ]; then cp "$APK" "$guard_apk"; else curl -fL -o "$guard_apk" "$RELEASES/canvasOS-guard.apk" </dev/null; fi
  curl -fsSL -o "$script" "$TCAST" </dev/null
  say "TCL refuses adb installs: sending canvasOS-guard.apk through multi-screen interaction (port 6553)"
  say "On the TV, choose 同意 (Accept) when the push prompt appears; allow port 8765 if the firewall asks"
  python3 "$script" "$ip" "$guard_apk" </dev/null || fail "the multi-screen push did not finish. Without it, copy the APK to /sdcard/Download/ and install it with a file manager on the TV (AGENTS.md step 3)."
}

# ---------- restore ----------
restore() {
  local home="" saver_saved="" current_saver
  if [ -f "$STATE/home" ]; then
    home=$(cat "$STATE/home")
    saver_saved=$(cat "$STATE/screensaver" 2>/dev/null || true)
    say "Using the state saved in $STATE"
  else
    [ "$UNINSTALL" = 1 ] || fail "no saved state in $STATE: run restore from the computer that installed canvasOS, or follow AGENTS.md (\"Désinstaller\")"
    warn "no saved state in $STATE; uninstalling lets the TV fall back to its own launcher"
  fi
  if [ -n "$home" ] && ! [[ "$home" =~ ^[A-Za-z0-9._]+/[A-Za-z0-9._$]+$ ]]; then fail "the saved home ($home) looks invalid"; fi

  if [ "${ONLINE:-0}" = 1 ] && ! installed; then
    warn "canvasOS is not installed on this TV"
  else
    say "Turning off the home guard and keep-ADB"
    config --ez guard false --ez keep_adb false
  fi

  # The guard's accessibility service can stay listed (the "capture TV keys" option uses it too):
  # take only canvasOS's own entry out, so the list goes back to what it was.
  local acc rest
  [ "$DRY" = 1 ] || sleep 1
  acc=$(q settings get secure enabled_accessibility_services)
  if [ "${ONLINE:-0}" = 1 ] && [[ "$acc" == *"$PKG/"* ]]; then
    rest=$(printf '%s' "$acc" | tr ':' '\n' | grep -v "^$PKG/" | paste -sd: - || true)
    say "Removing the canvasOS accessibility service from the enabled list"
    if [ -n "$rest" ]; then run shell settings put secure enabled_accessibility_services "$rest"
    else run shell settings delete secure enabled_accessibility_services >/dev/null; fi
  fi

  current_saver=$(q settings get secure screensaver_components)
  if [ "${ONLINE:-0}" = 0 ] || [[ "$current_saver" == *"$PKG"* ]]; then
    if [ -n "$saver_saved" ] && [ "$saver_saved" != null ] && [[ "$saver_saved" != *"$PKG"* ]]; then
      say "Restoring the original screensaver"
      run shell settings put secure screensaver_components "$saver_saved"
    else
      say "Removing the canvasOS screensaver (no original one was saved)"
      run shell settings delete secure screensaver_components >/dev/null
    fi
  fi

  if [ "$UNINSTALL" = 0 ]; then
    # canvasOS stays installed: stop it from coming back at boot, then hand Home back.
    local listeners
    listeners=$(cat "$STATE/listeners" 2>/dev/null || true)
    case "$listeners" in *"$PKG"*) ;; *) run shell cmd notification disallow_listener "$PKG/$PKG.RevivalListener" 2>/dev/null || true ;; esac
    if [ -n "$home" ]; then
      say "Giving Home back to ${home}"
      run shell cmd package set-home-activity "$home" >/dev/null || warn "set-home-activity refused; choose the launcher in the TV's settings"
    fi
    run shell am force-stop "$PKG"
  else
    say "Uninstalling canvasOS"
    run uninstall "$PKG"
  fi

  say "Pressing Home"
  run shell input keyevent KEYCODE_HOME >/dev/null
  if [ "$DRY" = 0 ]; then
    sleep 2
    local r
    r=$(resumed)
    printf '  %s\n' "${r:-ResumedActivity: (not reported)}"
    case "$r" in
      *"$PKG/"*) warn "canvasOS is still in front; choose your launcher in the TV's settings" ;;
      *) say "The original home screen is back." ;;
    esac
  fi
  [ "$UNINSTALL" = 1 ] || say "canvasOS is still installed; add --uninstall to remove it."
}

main "$@"
