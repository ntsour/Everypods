#!/bin/bash
set -e

# Pixel 10 Search Navigation Test Automation
# This script automates: open app → tap search → type "volume" → select "Volume Control" → capture screenshots + logs

OUTPUT_DIR="/Users/nikos/Documents/New project/mylibrepods/test-output"
TIMESTAMP=$(date +%Y%m%d_%H%M%S)
LOG_FILE="$OUTPUT_DIR/${TIMESTAMP}_logcat.txt"
ADB="/Users/nikos/Library/Android/sdk/platform-tools/adb"
PKG="me.kavishdevar.librepods"

echo "======================================"
echo " LibrePods Search Navigation Test"
echo "======================================"
echo "Output dir: $OUTPUT_DIR"
echo "Log file: $LOG_FILE"
echo ""

# Ensure output directory exists
mkdir -p "$OUTPUT_DIR"

# Step 0: Clear previous state
echo "[0/7] Clearing logcat and forcing app stop..."
$ADB logcat -c 2>/dev/null || true
$ADB shell am force-stop $PKG 2>/dev/null || true
sleep 1

# Step 1: Open app (tap LibrePods icon)
echo "[1/7] Opening LibrePods app..."
$ADB shell input tap 668 1160
sleep 4
$ADB exec-out screencap -p > "$OUTPUT_DIR/${TIMESTAMP}_01_app_open.png"
echo "      Screenshot saved: ${TIMESTAMP}_01_app_open.png"

# Step 2: Tap search icon
echo "[2/7] Tapping search icon..."
$ADB shell input tap 1000 240
sleep 2
$ADB exec-out screencap -p > "$OUTPUT_DIR/${TIMESTAMP}_02_search_tapped.png"
echo "      Screenshot saved: ${TIMESTAMP}_02_search_tapped.png"

# Step 3: Type "volume"
echo "[3/7] Typing 'volume'..."
$ADB shell input text "volume"
sleep 2
$ADB exec-out screencap -p > "$OUTPUT_DIR/${TIMESTAMP}_03_typed_volume.png"
echo "      Screenshot saved: ${TIMESTAMP}_03_typed_volume.png"

# Step 4: Tap "Volume Control" result (first result in list)
echo "[4/7] Tapping 'Volume Control' result..."
$ADB shell input tap 540 500
sleep 1
$ADB exec-out screencap -p > "$OUTPUT_DIR/${TIMESTAMP}_04_result_tapped.png"
echo "      Screenshot saved: ${TIMESTAMP}_04_result_tapped.png"

# Step 5: Wait for navigation + scroll + highlight
echo "[5/7] Waiting 3s for navigation + scroll + highlight..."
sleep 3
$ADB exec-out screencap -p > "$OUTPUT_DIR/${TIMESTAMP}_05_after_scroll.png"
echo "      Screenshot saved: ${TIMESTAMP}_05_after_scroll.png"

# Step 6: Wait for highlight to finish
echo "[6/7] Waiting 3s for highlight to finish..."
sleep 3
$ADB exec-out screencap -p > "$OUTPUT_DIR/${TIMESTAMP}_06_highlight_done.png"
echo "      Screenshot saved: ${TIMESTAMP}_06_highlight_done.png"

# Step 7: Capture logcat
echo "[7/7] Capturing logcat..."
$ADB logcat -d | grep "LibrePods" > "$LOG_FILE"
echo "      Log saved: ${TIMESTAMP}_logcat.txt"

echo ""
echo "======================================"
echo " TEST COMPLETE"
echo "======================================"
echo "Screenshots:"
ls -lh "$OUTPUT_DIR/${TIMESTAMP}"_*.png
echo ""
echo "Log summary:"
grep -c "LibrePods" "$LOG_FILE" || echo "0 LibrePods log lines"
echo ""
echo "Key events:"
grep "LibrePods" "$LOG_FILE" | grep -E "search tap|SCROLL START|targetY|scrollTarget|scrollState|highlight|sectionTrack" || echo "No matching events found"
