#!/usr/bin/env bash
set -euo pipefail

APK="${1:-Photo-TV.apk}"
OUT="${2:-marathon-results}"

mkdir -p "$OUT"
adb install -r "$APK"
adb shell am force-stop fr.wokgui.phototv
adb shell am start -n fr.wokgui.phototv/.MainActivity --ez phototv_test_mode true --ei phototv_test_page 0
sleep 2

: > "$OUT/memory.txt"
for i in $(seq 1 2500); do
  case $((i % 8)) in
    0) adb shell input keyevent 22 ;;
    1) adb shell input keyevent 21 ;;
    2) adb shell input keyevent 20 ;;
    3) adb shell input keyevent 19 ;;
    4) adb shell input keyevent 23 ;;
    5) adb shell input keyevent 4 ;;
    6) adb shell input keyevent 22 ;;
    7) adb shell input keyevent 23 ;;
  esac

  if (( i % 500 == 0 )); then
    echo "=== step $i ===" >> "$OUT/memory.txt"
    adb shell dumpsys meminfo fr.wokgui.phototv |
      grep -E "TOTAL PSS|TOTAL RSS|Java Heap|Native Heap" >> "$OUT/memory.txt" || true
  fi
done

adb shell dumpsys activity activities | grep -q "fr.wokgui.phototv"
adb logcat -d > "$OUT/logcat.txt"
if grep -E "FATAL EXCEPTION|OutOfMemoryError" "$OUT/logcat.txt" | grep -q "fr.wokgui.phototv"; then
  echo "Crash or OOM detected" >&2
  exit 1
fi
