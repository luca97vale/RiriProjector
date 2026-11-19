#!/usr/bin/env bash
# Build + install RiriProjector on the Fire TV (receiver) and/or Galaxy Tab (sender).
# Usage: scripts/deploy.sh [firetv|tablet|both] [--release] [--no-build] [--logs]
# Env: FIRETV_HOST (required for firetv), FIRETV_PORT (default 5555),
#      TABLET_SERIAL (optional), APP_ID (default dev.ririprojector)
set -euo pipefail

TARGET="both"; VARIANT="debug"; BUILD=1; LOGS=0
for arg in "$@"; do
  case "$arg" in
    firetv|tablet|both) TARGET="$arg" ;;
    --release) VARIANT="release" ;;
    --no-build) BUILD=0 ;;
    --logs) LOGS=1 ;;
    *) echo "Unknown argument: $arg" >&2; exit 64 ;;
  esac
done

APP_ID="${APP_ID:-dev.ririprojector}"
[[ "$VARIANT" == "debug" ]] && APP_ID="${APP_ID}.debug"
ACTIVITY="dev.ririprojector.MainActivity"
FIRETV_PORT="${FIRETV_PORT:-5555}"
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
  "$ADB" -s "$serial" shell am start -n "$APP_ID/$ACTIVITY" --es mode "$mode" >/dev/null
  if [[ $LOGS -eq 1 ]]; then
    sleep 5
    "$ADB" -s "$serial" logcat -d -t 300 -s 'Riri/Stats:*' 'Riri/Sender:*' 'Riri/Receiver:*' 'Riri/Net:*' '*:E'
  fi
}

if [[ "$TARGET" == "firetv" || "$TARGET" == "both" ]]; then
  [[ -z "${FIRETV_HOST:-}" ]] && { echo "Set FIRETV_HOST to the Fire TV IP address" >&2; exit 1; }
  FIRETV_SERIAL="$FIRETV_HOST:$FIRETV_PORT"
  "$ADB" connect "$FIRETV_SERIAL" | grep -Eq 'connected' || {
    echo "Connection to $FIRETV_SERIAL failed: check ADB debugging and accept the popup on the TV" >&2; exit 1; }
  install_and_start "$FIRETV_SERIAL" receiver
fi

if [[ "$TARGET" == "tablet" || "$TARGET" == "both" ]]; then
  SERIAL="${TABLET_SERIAL:-}"
  if [[ -z "$SERIAL" ]]; then
    mapfile -t devs < <("$ADB" devices | awk 'NR>1 && $2=="device" {print $1}' | grep -v "${FIRETV_HOST:-^$}" || true)
    [[ ${#devs[@]} -ne 1 ]] && { echo "Found ${#devs[@]} non-TV devices: set TABLET_SERIAL (${devs[*]:-none})" >&2; exit 1; }
    SERIAL="${devs[0]}"
  fi
  install_and_start "$SERIAL" sender
fi
