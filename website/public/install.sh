#!/usr/bin/env bash
# canvasOS installer for macOS and Linux. Follows AGENTS.md (https://canvasos.pages.dev/agent.md).
#
#   curl -fsSL https://canvasos.pages.dev/install.sh | bash                          # finds the TV, asks which one
#   curl -fsSL https://canvasos.pages.dev/install.sh | TV=192.168.x.x bash             # a given TV
#   curl -fsSL https://canvasos.pages.dev/install.sh | bash -s -- restore               # give the original home back
#   curl -fsSL https://canvasos.pages.dev/install.sh | bash -s -- restore --uninstall
#
# Environment:
#   TV        optional: the TV's IPv4 address (port 5555 by default), or an adb serial listed by
#             `adb devices`. Unset, the script looks for Android TVs on the local network (adb
#             devices, mDNS, port 5555 on the local /24) and asks which one to use.
#   APK       install this local APK file instead of downloading the latest Release
#   DRY_RUN   1 = print every command that would change the TV, change nothing
#
# What it never does (AGENTS.md, "Règles absolues"): power off, sleep or reboot the TV, uninstall
# a system app, or disable a launcher (never the Xiaomi / Redmi one, com.mitv.tvhome: the TV
# would boot to a black screen). It uninstalls canvasOS only with `restore --uninstall`.
# Discovery only reads: it never runs `adb connect` on a TV you did not choose.
# Before changing anything it saves the TV's original state under ~/.canvasos/<TV>/.
# Requires bash 3.2+, adb and curl (dns-sd or avahi-browse and nc help discovery, all optional).

set -euo pipefail
# fd 3: dry-run lines, unaffected by the >/dev/null and 2>/dev/null on individual commands.
exec 3>&1

main() {
  PKG=com.getisolated.canvasos
  RELEASES=https://github.com/getisolated/canvasOS/releases/latest/download

  MODE=install
  UNINSTALL=0
  while [ $# -gt 0 ]; do
    case "$1" in
      install|restore) MODE=$1 ;;
      --uninstall) UNINSTALL=1 ;;
      -h|--help) sed -n '2,22p' "${BASH_SOURCE[0]:-/dev/null}" 2>/dev/null || true; exit 0 ;;
      *) fail "unknown argument: $1 (use install, restore or restore --uninstall)" ;;
    esac
    shift
  done
  [ "$MODE" = restore ] || [ "$UNINSTALL" = 0 ] || fail "--uninstall only goes with restore"

  DRY=${DRY_RUN:-0}
  [ "$DRY" = 0 ] || [ "$DRY" = 1 ] || fail "DRY_RUN must be 0 or 1"
  [ "$DRY" = 1 ] && say "DRY_RUN=1: nothing on the TV will change; commands that would are printed with +"

  if ! command -v adb >/dev/null 2>&1 && [ "$DRY" = 0 ]; then
    fail "adb is missing. macOS: brew install android-platform-tools · Debian/Ubuntu: sudo apt install adb"
  fi
  [ -n "${TV:-}" ] || discover
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

  if ! command -v adb >/dev/null 2>&1; then
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
# Anything that changes the TV goes through run, which refuses what this script must never do.
run() {
  case " $* " in
    *com.mitv.tvhome*" disable"*|*" disable"*com.mitv.tvhome*|*com.mitv.tvhome*" hide "*|*" uninstall "*com.mitv.tvhome*)
      fail "refusing 'adb $*': never disable the Xiaomi / Redmi stock launcher (com.mitv.tvhome), the TV would boot to a black screen" ;;
    *" disable "*|*" disable-user "*|*" disable-until-used "*|*" hide "*)
      fail "refusing 'adb $*': this script never disables or hides an app" ;;
    *" uninstall "*)
      case " $* " in *" uninstall $PKG "*) ;; *) fail "refusing 'adb $*': this script only ever uninstalls canvasOS" ;; esac ;;
  esac
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

# ---------- discovery (TV unset) ----------
# Read-only: adb's own list of network devices, an mDNS browse and a probe of port 5555 on the
# local /24. It never runs `adb connect`: no TV shows an authorisation prompt before you choose it.
DISC=""
discover() {
  DISC=$(mktemp -d "${TMPDIR:-/tmp}/canvasos-scan.XXXXXX")
  trap 'disc_cleanup' EXIT
  trap 'disc_cleanup; exit 130' INT TERM
  : >"$DISC/rec"
  NC=bash
  if command -v nc >/dev/null 2>&1; then
    if [ "$(uname -s)" = Darwin ]; then NC=mac
    elif nc -h 2>&1 | grep -q -- '-z'; then NC=nc; fi
  fi
  say "TV is not set: looking for Android TVs on the local network (a few seconds)"
  disc_scan </dev/null >/dev/null 2>&1 &
  local scan=$!
  disc_adb
  disc_mdns
  wait "$scan" 2>/dev/null || true
  disc_probe_others
  disc_choose
  disc_cleanup
  trap - EXIT INT TERM
}

disc_cleanup() {
  [ -n "$DISC" ] || return 0
  # shellcheck disable=SC2046
  disc_kill $(jobs -p)
  rm -rf "$DISC"
  DISC=""
}

# Stop background jobs without the shell's "Terminated" notices.
disc_kill() {
  local p
  for p in "$@"; do kill "$p" 2>/dev/null || true; done
  for p in "$@"; do wait "$p" 2>/dev/null || true; done
}

# The computer's IPv4 address on the LAN (not a VPN tunnel).
disc_local_ip() {
  local ip="" i
  if [ "$(uname -s)" = Darwin ]; then
    for i in $(route -n get default 2>/dev/null | awk '/interface:/ {print $2; exit}') en0 en1; do
      case "$i" in utun*|ipsec*|ppp*|lo*) continue ;; esac
      ip=$(ipconfig getifaddr "$i" 2>/dev/null || true)
      [ -z "$ip" ] || break
    done
  else
    ip=$(ip route get 1.1.1.1 2>/dev/null | sed -n 's/.* src \([0-9.]*\).*/\1/p' | head -n 1)
    [ -n "$ip" ] || ip=$(hostname -I 2>/dev/null | awk '{print $1}')
  fi
  printf '%s' "$ip"
}

# Exit 0 if $1 accepts a TCP connection on 5555 within about a second.
disc_probe() {
  case "$NC" in
    mac) nc -z -G 1 -w 1 "$1" 5555 </dev/null >/dev/null 2>&1 ;;
    nc) nc -z -w 1 "$1" 5555 </dev/null >/dev/null 2>&1 ;;
    *)
      (exec 9<>"/dev/tcp/$1/5555") </dev/null >/dev/null 2>&1 &
      local p=$! k
      (sleep 1; kill "$p") </dev/null >/dev/null 2>&1 &
      k=$!
      if wait "$p" 2>/dev/null; then disc_kill "$k"; return 0; fi
      disc_kill "$k"
      return 1 ;;
  esac
}

disc_scan() {
  local me prefix mine i=1
  me=$(disc_local_ip)
  [[ "$me" =~ ^([0-9]+\.[0-9]+\.[0-9]+)\.([0-9]+)$ ]] || return 0
  prefix=${BASH_REMATCH[1]}
  mine=${BASH_REMATCH[2]}
  printf '%s\n' "$prefix" >"$DISC/prefix"
  while [ "$i" -le 254 ]; do
    if [ "$i" != "$mine" ]; then
      { if disc_probe "$prefix.$i"; then printf '%s\topen\t1\n' "$prefix.$i" >>"$DISC/rec"; fi; } &
    fi
    [ $((i % 128)) != 0 ] || wait
    i=$((i + 1))
  done
  wait
}

disc_adb() {
  command -v adb >/dev/null 2>&1 || return 0
  local serial state _
  while read -r serial state _; do
    [[ "$serial" =~ ^([0-9]{1,3}(\.[0-9]{1,3}){3}):[0-9]+$ ]] || continue
    printf '%s\tserial\t%s\n%s\tadb\t%s\n' "${BASH_REMATCH[1]}" "$serial" "${BASH_REMATCH[1]}" "$state" >>"$DISC/rec"
  done < <(adb devices </dev/null 2>/dev/null | tr -d '\r')
}

# Wait up to $1 tenths of a second until every file in the rest of the arguments contains $2.
disc_wait_for() {
  local n=$1 pat=$2 f all
  shift 2
  while [ "$n" -gt 0 ]; do
    all=1
    for f in "$@"; do grep -q -- "$pat" "$f" 2>/dev/null || { all=0; break; }; done
    [ "$all" = 0 ] || return 0
    sleep 0.1
    n=$((n - 1))
  done
}

# A TXT value from a `dns-sd -L` answer: $1 key, $2 file. "\ " escapes are undone.
disc_txt() {
  local line
  line=" $(grep -m 1 '^ ' "$2" 2>/dev/null | sed 's/^ *//')"
  case "$line" in *" $1="*) ;; *) return 0 ;; esac
  line=${line#*" $1="}
  printf '%s' "$line" | sed -E 's/(^|[^\\]) .*/\1/; s/\\(.)/\1/g'
}

disc_mdns() {
  if command -v dns-sd >/dev/null 2>&1; then disc_mdns_dnssd
  elif command -v avahi-browse >/dev/null 2>&1; then disc_mdns_avahi; fi
  return 0
}

# _androidtvremote2 marks an Android TV / Google TV; _googlecast adds its name (fn) and model (md).
disc_mdns_dnssd() {
  local t n=0 pids="" files="" name f host ip i
  for t in _androidtvremote2._tcp _googlecast._tcp; do
    dns-sd -B "$t" local. </dev/null >"$DISC/browse$t" 2>/dev/null &
    pids="$pids $!"
  done
  sleep 3   # dns-sd never exits on its own
  # shellcheck disable=SC2086
  disc_kill $pids
  pids=""
  for t in _androidtvremote2._tcp _googlecast._tcp; do
    sed -nE 's/^[0-9:.]+ +Add +([^ ]+ +){4}//p' "$DISC/browse$t" | sort -u >"$DISC/names$t"
    while IFS= read -r name; do
      [ -n "$name" ] || continue
      n=$((n + 1))
      printf '%s\n%s\n' "$t" "$name" >"$DISC/svc$n"
      dns-sd -L "$name" "$t" local. </dev/null >"$DISC/res$n" 2>/dev/null &
      pids="$pids $!"
      files="$files $DISC/res$n"
    done <"$DISC/names$t"
  done
  [ "$n" -gt 0 ] || return 0
  # shellcheck disable=SC2086
  disc_wait_for 20 'can be reached at' $files
  # shellcheck disable=SC2086
  disc_kill $pids
  pids=""
  files=""
  i=1
  while [ "$i" -le "$n" ]; do
    host=$(sed -nE 's/.* can be reached at ([^ ]+):[0-9]+.*/\1/p' "$DISC/res$i" | head -n 1)
    if [ -n "$host" ]; then
      dns-sd -G v4 "$host" </dev/null >"$DISC/addr$i" 2>/dev/null &
      pids="$pids $!"
      files="$files $DISC/addr$i"
    fi
    i=$((i + 1))
  done
  [ -n "$files" ] || return 0
  # shellcheck disable=SC2086
  disc_wait_for 15 ' Add ' $files
  # shellcheck disable=SC2086
  disc_kill $pids
  i=1
  while [ "$i" -le "$n" ]; do
    f="$DISC/addr$i"
    ip=$(grep ' Add ' "$f" 2>/dev/null | grep -Eo '([0-9]{1,3}\.){3}[0-9]{1,3}' | head -n 1 || true)
    if [ -n "$ip" ]; then
      t=$(sed -n 1p "$DISC/svc$i")
      name=$(sed -n 2p "$DISC/svc$i")
      case "$t" in
        _androidtvremote2._tcp) printf '%s\tatv\t1\n%s\tlabel\t%s\n' "$ip" "$ip" "$name" ;;
        *) printf '%s\tname\t%s\n%s\tmodel\t%s\n' "$ip" "$(disc_txt fn "$DISC/res$i")" "$ip" "$(disc_txt md "$DISC/res$i")" ;;
      esac >>"$DISC/rec"
    fi
    i=$((i + 1))
  done
}

disc_mdns_avahi() {
  local t p n
  for t in _androidtvremote2._tcp _googlecast._tcp; do
    avahi-browse -rpt "$t" </dev/null >"$DISC/avahi$t" 2>/dev/null &
    p=$!
    n=50
    while [ "$n" -gt 0 ] && kill -0 "$p" 2>/dev/null; do sleep 0.1; n=$((n - 1)); done
    disc_kill "$p"
    awk -F';' -v t="$t" '$1 == "=" && $3 == "IPv4" {
        name = $4; gsub(/\\032/, " ", name); gsub(/\\\\/, "\\", name)
        if (t == "_androidtvremote2._tcp") { print $8 "\tatv\t1"; print $8 "\tlabel\t" name; next }
        fn = $10; md = $10
        if (sub(/.*"fn=/, "", fn)) { sub(/".*/, "", fn); print $8 "\tname\t" fn }
        if (sub(/.*"md=/, "", md)) { sub(/".*/, "", md); print $8 "\tmodel\t" md }
      }' "$DISC/avahi$t" >>"$DISC/rec"
  done
}

# Android TVs seen by mDNS outside the scanned /24 still get their port 5555 checked.
disc_probe_others() {
  local prefix ip
  prefix=$(cat "$DISC/prefix" 2>/dev/null || true)
  while read -r ip; do
    if [ -n "$prefix" ] && [[ "$ip" == "$prefix".* ]]; then continue; fi
    { if disc_probe "$ip"; then printf '%s\topen\t1\n' "$ip" >>"$DISC/rec"; fi; } </dev/null >/dev/null 2>&1 &
  done < <(awk -F'\t' '$2 == "atv" {print $1}' "$DISC/rec" | sort -u)
  wait
}

disc_adb_help() {
  cat <<EOF
ADB debugging is not on for $1 (port 5555 is closed). Turn it on with the remote, then run this again:
  Android TV / Google TV (boxes, Thomson, Philips, Hisense…):
    Paramètres (Settings) → Préférences de l'appareil (Device Preferences) → À propos (About):
    press Build 7 times, then Options pour les développeurs (Developer options) →
    Débogage USB (USB debugging), or Débogage réseau / sans fil if it is there.
  Sony BRAVIA:
    Settings → System → About: press OK on Build 7 times,
    then Settings → System → Developer options → USB debugging.
The first connection then asks "Allow USB debugging?": tick "Always allow" and choose Allow.
More: https://canvasos.pages.dev/#install
EOF
}

# One line per Android TV: ip, adb target, adb open (1/0), label.
disc_choose() {
  local list="$DISC/list" n open i line ip target ok label first="" ans
  awk -F'\t' '
    !($1 in seen) { seen[$1] = 1; order[++n] = $1 }
    $2 == "atv" { atv[$1] = 1 }
    $2 == "open" { open[$1] = 1 }
    $2 == "serial" { serial[$1] = $3 }
    $2 == "adb" { known[$1] = 1; state[$1] = $3; if ($3 != "offline") open[$1] = 1 }   # adb already reaches it
    $2 == "label" && $3 != "" && label[$1] == "" { label[$1] = $3 }
    $2 == "name" && $3 != "" && name[$1] == "" { name[$1] = $3 }
    $2 == "model" && $3 != "" && model[$1] == "" { model[$1] = $3 }
    END {
      for (i = 1; i <= n; i++) {
        ip = order[i]
        if (!atv[ip] && !open[ip] && !known[ip]) continue   # a Cast speaker or a Chromecast without ADB
        l = name[ip] != "" ? name[ip] : label[ip]
        if (model[ip] != "" && model[ip] != l) l = (l != "" ? l " (" model[ip] ")" : model[ip])
        if (state[ip] != "" && state[ip] != "device") l = l (l != "" ? ", " : "") "adb: " state[ip]
        print ip "\t" (serial[ip] != "" ? serial[ip] : ip) "\t" (open[ip] ? 1 : 0) "\t" l
      }
    }' "$DISC/rec" | sort -t. -k1,1n -k2,2n -k3,3n -k4,4n >"$list"
  n=$(wc -l <"$list" | tr -d ' ')
  open=$(awk -F'\t' '$3 == 1' "$list" | wc -l | tr -d ' ')
  if [ "$n" = 0 ]; then
    fail "no Android TV found on this network. Check that the TV is on, on the same network as this computer,
       and that ADB debugging is on (https://canvasos.pages.dev/#install), or give its address:
         curl -fsSL https://canvasos.pages.dev/install.sh | TV=192.168.1.20 bash"
  fi
  say "Android TVs on this network:"
  i=0
  while IFS="$(printf '\t')" read -r ip target ok label; do
    i=$((i + 1))
    [ "$ok" = 1 ] && ok=yes || ok=no
    [ -n "$first" ] || { [ "$ok" = no ] || first=$ip; }
    printf '  %d) %-15s  ADB open: %-3s  %s\n' "$i" "$ip" "$ok" "${label:-(no name)}"
  done <"$list"
  if [ "$open" = 0 ]; then
    disc_adb_help "$(awk -F'\t' 'NR == 1 {print $1}' "$list")"
    exit 1
  fi
  if ! { : </dev/tty; } 2>/dev/null; then
    fail "no terminal to ask which TV to use: run this again with its address, for example
         curl -fsSL https://canvasos.pages.dev/install.sh | TV=$first bash"
  fi
  if [ "$n" = 1 ]; then
    printf 'Use this TV? [Y/n] '
    read -r ans </dev/tty || ans=n
    case "$ans" in ""|[Yy]*) i=1 ;; *) fail "cancelled: nothing was changed" ;; esac
  else
    while :; do
      printf 'Which TV? [1-%d, q to quit] ' "$n"
      read -r ans </dev/tty || ans=q
      case "$ans" in [Qq]*) fail "cancelled: nothing was changed" ;; esac
      if [[ "$ans" =~ ^[0-9]+$ ]] && [ "$ans" -ge 1 ] && [ "$ans" -le "$n" ]; then i=$ans; break; fi
    done
  fi
  line=$(sed -n "${i}p" "$list")
  IFS="$(printf '\t')" read -r ip target ok label <<EOF
$line
EOF
  if [ "$ok" != 1 ]; then disc_adb_help "$ip"; exit 1; fi
  TV=$target
  say "Using $TV${label:+ ($label)}"
}

# ---------- install ----------
install() {
  local sdk maker model
  sdk=$(q getprop ro.build.version.sdk)
  maker=$(q getprop ro.product.manufacturer)
  model=$(q getprop ro.product.model)
  if [ "${ONLINE:-0}" = 1 ]; then
    [[ "$sdk" =~ ^[0-9]+$ ]] || fail "could not read the Android version"
    [ "$sdk" -ge 28 ] || fail "canvasOS needs Android 9 (API 28) or later; this TV reports API $sdk"
    say "TV: $maker $model, API $sdk"
  fi

  snapshot "$maker" "$model" "$sdk"

  # Download (or take APK=…).
  local apk_name=canvasOS.apk apk
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
  say "Installing canvasOS"
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
    *) fail "adb install failed: $(printf '%s\n' "$out" | tail -n 1)" ;;
  esac
  if [ "$DRY" = 0 ]; then installed || fail "canvasOS is not installed after all (pm path is empty)"; fi

  # Home screen and the grants only adb can give (AGENTS.md step 4).
  say "Making canvasOS the home screen"
  run shell cmd package set-home-activity "$PKG/.HomeActivity" >/dev/null || warn "set-home-activity refused"
  say "Granting permissions"
  run shell pm grant "$PKG" android.permission.WRITE_SECURE_SETTINGS || warn "WRITE_SECURE_SETTINGS not granted"
  if [ -z "$sdk" ] || [ "$sdk" -ge 31 ]; then
    run shell pm grant "$PKG" android.permission.BLUETOOTH_CONNECT 2>/dev/null || true
  fi
  run shell cmd notification allow_listener "$PKG/$PKG.RevivalListener" || warn "notification listener not allowed"
  run shell appops set "$PKG" SYSTEM_ALERT_WINDOW allow || true
  run shell pm grant "$PKG" android.permission.READ_TV_LISTINGS 2>/dev/null || true

  # The home guard only where Home still opens the stock launcher (TVs whose system keeps the Home key).
  say "Pressing Home and checking what is in front"
  if [ "$DRY" = 1 ]; then
    press_home_and_check
    say "(dry run) if the stock launcher came back: set-home-activity again, then the home guard; never disable a launcher"
  elif press_home_and_check; then
    say "canvasOS is the home screen."
  else
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
    say "Turning off the home guard"
    config --ez guard false
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
