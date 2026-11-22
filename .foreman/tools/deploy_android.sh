#!/usr/bin/env bash
# Idempotent Android build, reverse port tunnel, and installation script for ClipSync.
set -euo pipefail

REPO_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
cd "$REPO_DIR"

echo "=== 1. Checking ADB Device Connectivity ==="
DEVICE=$(adb devices | grep -w "device" | head -n 1 | awk '{print $1}')
if [ -z "$DEVICE" ]; then
    echo "[ERROR] No authorized Android device detected via adb devices."
    exit 1
fi
echo "[OK] Found target device: $DEVICE"

echo "=== 2. Configuring ADB Reverse Port ==="
adb -s "$DEVICE" reverse tcp:8765 tcp:8765
echo "[OK] Port 8765 reversed to PC"

echo "=== 3. Compiling Debug APK ==="
./gradlew assembleDebug

echo "=== 4. Streaming APK to Device ==="
adb -s "$DEVICE" install -r app/build/outputs/apk/debug/app-debug.apk
echo "[OK] ClipSync successfully deployed and updated on device."
