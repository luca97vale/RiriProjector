#!/usr/bin/env bash
# Runs the second-screen helper's self-test (ADR-014) through adb, which already runs as shell:
# no Wireless-debugging pairing needed. Usage: scripts/helper-selftest.sh [serial]
set -euo pipefail
ADB="${ADB:-adb}"
SERIAL="${1:-${SENDER_SERIAL:-}}"
PKG="${APP_ID:-dev.ririprojector.debug}"
args=()
[[ -n "$SERIAL" ]] && args=(-s "$SERIAL")
apk="$("$ADB" "${args[@]}" shell pm path "$PKG" | head -1 | tr -d '\r' | sed 's/^package://')"
[[ -z "$apk" ]] && { echo "$PKG is not installed" >&2; exit 1; }
"$ADB" "${args[@]}" shell "CLASSPATH='$apk' app_process /system/bin dev.ririprojector.helper.HelperMain --selftest"
