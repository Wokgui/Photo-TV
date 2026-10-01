#!/usr/bin/env bash
set -euo pipefail

APK="${1:-Photo-TV.apk}"
OUT="${2:-marathon-results}"
PKG="fr.wokgui.phototv"
ACT="${PKG}/.MainActivity"

mkdir -p "$OUT"
adb install -r "$APK"
adb logcat -c

launch_slideshow() {
  adb shell am force-stop "$PKG"
  adb shell am start -n "$ACT" --ez phototv_test_mode true --ei phototv_test_page 0 \
    --ei phototv_test_library_count 5000 --ez phototv_test_slideshow true >/dev/null
  sleep 1
}

launch_settings() {
  adb shell am force-stop "$PKG"
  adb shell am start -n "$ACT" --ez phototv_test_mode true --ei phototv_test_page 3 \
    --ei phototv_test_library_count 5000 >/dev/null
  sleep 1
  for _ in $(seq 1 7); do adb shell input keyevent 20; done
  adb shell input keyevent 22
}

launch_photos() {
  adb shell am force-stop "$PKG"
  adb shell am start -n "$ACT" --ez phototv_test_mode true --ei phototv_test_page 1 \
    --ei phototv_test_library_count 5000 >/dev/null
  sleep 1
}

launch_slideshow
: > "$OUT/memory.txt"

for i in $(seq 1 3000); do
  phase=$((i % 750))
  if (( phase == 250 )); then
    launch_settings
  elif (( phase == 400 )); then
    for _ in $(seq 1 7); do adb shell input keyevent 22; done
  elif (( phase == 500 )); then
    launch_photos
  elif (( phase == 620 )); then
    launch_slideshow
  fi

  case $((i % 10)) in
    0|1|2) adb shell input keyevent 22 ;;
    3|4) adb shell input keyevent 21 ;;
    5) adb shell input keyevent 23 ;;
    6) adb shell input keyevent 85 ;;
    7) adb shell input keyevent 20 ;;
    8) adb shell input keyevent 19 ;;
    9) adb shell input keyevent 23 ;;
  esac

  if (( i % 500 == 0 )); then
    echo "=== step $i ===" >> "$OUT/memory.txt"
    adb shell dumpsys meminfo "$PKG" |
      grep -E "TOTAL PSS|TOTAL RSS|Java Heap|Native Heap" >> "$OUT/memory.txt" || true
    adb shell am send-trim-memory "$PKG" RUNNING_LOW >/dev/null 2>&1 || true
  fi

  if (( i % 1000 == 0 )); then
    adb shell am force-stop "$PKG"
    launch_slideshow
  fi
done

adb shell dumpsys activity activities | grep -q "$PKG"
adb logcat -d > "$OUT/logcat.txt"
if grep -E "FATAL EXCEPTION|OutOfMemoryError" "$OUT/logcat.txt" | grep -q "$PKG"; then
  echo "Crash or OOM detected" >&2
  exit 1
fi
