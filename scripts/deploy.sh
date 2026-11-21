#!/usr/bin/env bash
# Build + install RiriProjector on the receiver and/or the sender (any Android devices).
# Usage: scripts/deploy.sh [receiver|sender|both] [--release] [--no-build] [--logs]
# Env: RECEIVER_SERIAL (USB or already-connected receiver) or RECEIVER_HOST (receiver IP, adb connect),
#      RECEIVER_PORT (default 5555), SENDER_SERIAL (optional), APP_ID (default dev.ririprojector).
# Legacy names still work: targets firetv/tablet, FIRETV_HOST, FIRETV_PORT, TABLET_SERIAL.
set -euo pipefail

TARGET="both"; VARIANT="debug"; BUILD=1; LOGS=0
for arg in "$@"; do
  case "$arg" in
    receiver|sender|both) TARGET="$arg" ;;
    firetv) TARGET="receiver" ;; # legacy name
    tablet) TARGET="sender" ;;   # legacy name
    --release) VARIANT="release" ;;
    --no-build) BUILD=0 ;;
    --logs) LOGS=1 ;;
    *) echo "Unknown argument: $arg" >&2; exit 64 ;;
  esac
done

APP_ID="${APP_ID:-dev.ririprojector}"
[[ "$VARIANT" == "debug" ]] && APP_ID="${APP_ID}.debug"
ACTIVITY="dev.ririprojector.MainActivity"
RECEIVER_HOST="${RECEIVER_HOST:-${FIRETV_HOST:-}}"
RECEIVER_PORT="${RECEIVER_PORT:-${FIRETV_PORT:-5555}}"
RECEIVER_SERIAL="${RECEIVER_SERIAL:-}"
SENDER_SERIAL="${SENDER_SERIAL:-${TABLET_SERIAL:-}}"
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"

# --- adb ---------------------------------------------------------------------
ADB="$(command -v adb || true)"
if [[ -z "$ADB" ]]; then
  for sdk in "${ANDROID_HOME:-}" "${ANDROID_SDK_ROOT:-}" "${LOCALAPPDATA:-}/Android/Sdk" "$HOME/Android/Sdk" "$HOME/Library/Android/sdk"; do
    for exe in adb adb.exe; do
      [[ -n "$sdk" && -x "$sdk/platform-tools/$exe" ]] && ADB="$sdk/platform-tools/$exe" && break 2
    done
  done
fi
[[ -z "$ADB" ]] && { echo "adb not found: install platform-tools or set ANDROID_HOME" >&2; exit 1; }

# --- build -------------------------------------------------------------------
cap="$(tr '[:lower:]' '[:upper:]' <<< "${VARIANT:0:1}")${VARIANT:1}"
if [[ $BUILD -eq 1 ]]; then
  (cd "$ROOT" && ./gradlew ":app:assemble${cap}")
fi
APK="$(ls -t "$ROOT"/app/build/outputs/apk/"$VARIANT"/*.apk 2>/dev/null | head -n1 || true)"
[[ -z "$APK" ]] && { echo "APK not found in app/build/outputs/apk/$VARIANT" >&2; exit 1; }
echo "APK: $APK"

install_and_start() { # serial mode
  local serial="$1" mode="$2"
  echo "==> $mode on $serial"
  "$ADB" -s "$serial" install -r -d "$APK"
  "$ADB" -s "$serial" shell am start -S -n "$APP_ID/$ACTIVITY" --es mode "$mode" >/dev/null
  if [[ $LOGS -eq 1 ]]; then
    sleep 5
    "$ADB" -s "$serial" logcat -d -t 300 -s 'Riri/Stats:*' 'Riri/Sender:*' 'Riri/Receiver:*' 'Riri/Net:*' '*:E'
  fi
}

# Network receivers are reached with adb connect; a USB receiver is named by RECEIVER_SERIAL.
if [[ -z "$RECEIVER_SERIAL" && -n "$RECEIVER_HOST" ]]; then
  RECEIVER_SERIAL="$RECEIVER_HOST:$RECEIVER_PORT"
  if [[ "$TARGET" == "receiver" || "$TARGET" == "both" ]]; then
    "$ADB" connect "$RECEIVER_SERIAL" | grep -Eq 'connected' || {
      echo "Connection to $RECEIVER_SERIAL failed: check ADB debugging and accept the popup on the receiver" >&2; exit 1; }
  fi
fi

if [[ "$TARGET" == "receiver" || "$TARGET" == "both" ]]; then
  [[ -z "$RECEIVER_SERIAL" ]] && {
    echo "Set RECEIVER_HOST to the receiver IP address, or RECEIVER_SERIAL for a USB-attached receiver" >&2; exit 1; }
  install_and_start "$RECEIVER_SERIAL" receiver
fi

if [[ "$TARGET" == "sender" || "$TARGET" == "both" ]]; then
  SERIAL="$SENDER_SERIAL"
  if [[ -z "$SERIAL" ]]; then
    # The only attached device that is not the receiver.
    mapfile -t devs < <("$ADB" devices | awk -v rx="$RECEIVER_SERIAL" 'NR>1 && $2=="device" && $1!=rx {print $1}' || true)
    [[ ${#devs[@]} -ne 1 ]] && {
      echo "Found ${#devs[@]} devices besides the receiver: set SENDER_SERIAL (${devs[*]:-none})" >&2; exit 1; }
    SERIAL="${devs[0]}"
  fi
  install_and_start "$SERIAL" sender
fi
