#!/bin/bash
set -e

OUTPUT_DIR="/Users/nikos/Documents/New project/mylibrepods/test-output"
TIMESTAMP=$(date +%Y%m%d_%H%M%S)
LOG_FILE="$OUTPUT_DIR/${TIMESTAMP}_broken_logcat.txt"
ADB="/Users/nikos/Library/Android/sdk/platform-tools/adb"
PKG="me.kavishdevar.librepods"

echo "======================================"
echo " LibrePods 'Volume' → Volume Control Test"
echo "======================================"
mkdir -p "$OUTPUT_DIR"

# Check if device is locked and unlock if needed
echo "[CHECK] Checking device lock state..."
LOCK_STATE=$($ADB shell dumpsys window | grep "mDreamingLockscreen" | head -1 | grep -c "true" || echo "0")
if [ "$LOCK_STATE" -gt 0 ]; then
    echo "[CHECK] Device is locked. Unlocking..."
    $ADB shell input keyevent 82
    sleep 1
    $ADB shell input swipe 540 2000 540 500 300
    sleep 2
    LOCK_STATE=$($ADB shell dumpsys window | grep "mDreamingLockscreen" | head -1 | grep -c "true" || echo "0")
    if [ "$LOCK_STATE" -gt 0 ]; then
        echo "[ERROR] Failed to unlock device. Please unlock manually."
        exit 1
    fi
    echo "[CHECK] Device unlocked successfully."
else
    echo "[CHECK] Device is already unlocked."
fi

# Start logcat capture in background
echo "[0/6] Starting logcat capture..."
$ADB logcat -c 2>/dev/null || true
$ADB logcat LibrePods:E *:S > "$LOG_FILE" &
LOGCAT_PID=$!
sleep 1

echo "[1/6] Force-stopping and restarting LibrePods..."
$ADB shell am force-stop $PKG
sleep 1
$ADB shell monkey -p $PKG -c android.intent.category.LAUNCHER 1
sleep 5
$ADB exec-out screencap -p > "$OUTPUT_DIR/${TIMESTAMP}_broken_01_app_open.png"

echo "[2/6] Tapping search..."
$ADB shell input tap 1000 240
sleep 2
$ADB exec-out screencap -p > "$OUTPUT_DIR/${TIMESTAMP}_broken_02_search.png"

echo "[3/6] Typing volume..."
$ADB shell input text "volume"
sleep 2
$ADB exec-out screencap -p > "$OUTPUT_DIR/${TIMESTAMP}_broken_03_typed.png"

echo "[4/6] Tapping 'Volume Control' result..."
$ADB shell input tap 540 500
sleep 1
$ADB exec-out screencap -p > "$OUTPUT_DIR/${TIMESTAMP}_broken_04_tapped.png"

echo "[5/6] Waiting for scroll + highlight..."
sleep 4
$ADB exec-out screencap -p > "$OUTPUT_DIR/${TIMESTAMP}_broken_05_after.png"

# Stop logcat
echo "[6/6] Stopping logcat..."
kill $LOGCAT_PID 2>/dev/null || true
sleep 1

echo ""
echo "======================================"
echo " TEST COMPLETE"
echo "======================================"
ls -lh "$OUTPUT_DIR/${TIMESTAMP}"_broken_*.png
echo ""
echo "Log lines: $(wc -l < "$LOG_FILE" || echo 0)"
echo ""
echo "Key events:"
grep "LibrePods" "$LOG_FILE" | grep -E "search tap|SCROLL START|targetY|scrollTarget|scrollState|highlight|sectionTrack" || echo "No matching events found"
