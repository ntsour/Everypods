#!/bin/bash
set -e

OUTPUT_DIR="/Users/nikos/Documents/New project/mylibrepods/test-output"
TIMESTAMP=$(date +%Y%m%d_%H%M%S)
LOG_FILE="$OUTPUT_DIR/${TIMESTAMP}_pause_logcat.txt"
ADB="/Users/nikos/Library/Android/sdk/platform-tools/adb"
PKG="me.kavishdevar.librepods"

echo "======================================"
echo " LibrePods 'Pause' → Conversation Awareness Test"
echo "======================================"

mkdir -p "$OUTPUT_DIR"

echo "[0/5] Clearing logcat and forcing app stop..."
$ADB logcat -c 2>/dev/null || true
$ADB shell am force-stop $PKG 2>/dev/null || true
sleep 1

echo "[1/5] Opening LibrePods app..."
$ADB shell input tap 668 1160
sleep 4
$ADB exec-out screencap -p > "$OUTPUT_DIR/${TIMESTAMP}_pause_01_app_open.png"

echo "[2/5] Tapping search icon..."
$ADB shell input tap 1000 240
sleep 2
$ADB exec-out screencap -p > "$OUTPUT_DIR/${TIMESTAMP}_pause_02_search_tapped.png"

echo "[3/5] Typing 'pause'..."
$ADB shell input text "pause"
sleep 2
$ADB exec-out screencap -p > "$OUTPUT_DIR/${TIMESTAMP}_pause_03_typed_pause.png"

echo "[4/5] Tapping 'Conversation Awareness' result..."
$ADB shell input tap 540 500
sleep 1
$ADB exec-out screencap -p > "$OUTPUT_DIR/${TIMESTAMP}_pause_04_result_tapped.png"

echo "[5/5] Waiting 4s for navigation + scroll + highlight..."
sleep 4
$ADB exec-out screencap -p > "$OUTPUT_DIR/${TIMESTAMP}_pause_05_after_scroll.png"

echo "[6/5] Capturing logcat..."
$ADB logcat -d | grep "LibrePods" > "$LOG_FILE"

echo ""
echo "======================================"
echo " TEST COMPLETE"
echo "======================================"
ls -lh "$OUTPUT_DIR/${TIMESTAMP}"_pause_*.png
echo ""
echo "Log lines: $(grep -c "LibrePods" "$LOG_FILE" || echo 0)"
echo ""
echo "Key events:"
grep "LibrePods" "$LOG_FILE" | grep -E "search tap|SCROLL START|targetY|scrollTarget|scrollState|highlight|sectionTrack" || echo "No matching events found"
