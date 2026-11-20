#!/usr/bin/env bash
# Pull EveryPods handover traces from a phone over adb into ~/Everypods-logs.
#
# The phone's trace folder holds its own trace (android-<date>.log) plus the
# Windows client's traces in windows/ (sent from the tray: "Send traces to
# phone"). "Full handover trace" must be on in the phone's Debug screen.
#
# Usage: tools/fetch-traces.sh [-s <adb serial>] [--bugreport] [--out <dir>]
#   --bugreport  also save an adb bugreport (includes the Bluetooth HCI snoop
#                log when it is enabled in Developer options). Takes ~1-3 min.
set -eo pipefail

PACKAGE="io.automated.ventures.everypods"
REMOTE="/sdcard/Android/data/$PACKAGE/files/handover-trace"
SERIAL=()
BUGREPORT=0
OUT="$HOME/Everypods-logs/$(date +%Y%m%d-%H%M%S)"

while [[ $# -gt 0 ]]; do
  case "$1" in
    -s) SERIAL=(-s "$2"); shift 2 ;;
    --bugreport) BUGREPORT=1; shift ;;
    --out) OUT="$2"; shift 2 ;;
    -h|--help) sed -n '2,12p' "$0"; exit 0 ;;
    *) echo "Unknown option: $1" >&2; exit 2 ;;
  esac
done

adb "${SERIAL[@]}" get-state >/dev/null 2>&1 || {
  echo "No adb device. Connect USB, or pair Wireless debugging: adb pair <ip:port>, then adb connect <ip:port>." >&2
  exit 1
}
if ! adb "${SERIAL[@]}" shell "[ -d '$REMOTE' ]"; then
  echo "No trace folder on the phone yet ($REMOTE). Turn on Full handover trace in the Debug screen." >&2
  exit 1
fi

mkdir -p "$OUT"
model=$(adb "${SERIAL[@]}" shell getprop ro.product.model | tr -d '\r' | tr ' ' '_')
adb "${SERIAL[@]}" pull "$REMOTE" "$OUT/$model" >/dev/null
if [[ $BUGREPORT -eq 1 ]]; then
  echo "Collecting bugreport..."
  adb "${SERIAL[@]}" bugreport "$OUT/$model/bugreport.zip" >/dev/null
fi

echo "Saved to $OUT/$model"
find "$OUT/$model" -type f -exec ls -lh {} \; | awk '{print "  " $5 "\t" $9}'
