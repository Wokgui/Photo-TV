#!/usr/bin/env bash
set -euo pipefail

APK="${1:-Photo-TV.apk}"
OUT="${2:-android-visual-results}"
PACKAGE="fr.wokgui.phototv"
ACTIVITY=".MainActivity"

mkdir -p "$OUT"
adb wait-for-device
adb shell settings put global window_animation_scale 0 || true
adb shell settings put global transition_animation_scale 0 || true
adb shell settings put global animator_duration_scale 0 || true
adb shell wm size 1280x720 || true
adb shell wm density 160 || true
adb logcat -c || true

adb install -r "$APK"

start_page() {
  local page="$1"
  adb shell am force-stop "$PACKAGE" >/dev/null 2>&1 || true
  adb shell am start -W -n "$PACKAGE/$ACTIVITY"     --ez phototv_test_mode true     --ei phototv_test_page "$page" >/tmp/photo-tv-start.txt
  sleep 1.2
}

capture_page() {
  local index="$1"
  local name="$2"
  start_page "$index"
  adb shell screencap -p "/sdcard/${name}.png"
  adb pull "/sdcard/${name}.png" "$OUT/${name}.png" >/dev/null
  echo "captured Android TV $name"
}

state_log() {
  adb logcat -d -s PhotoTVState:I '*:S' | tail -n 40
}

assert_state() {
  local needle="$1"
  local dump
  dump="$(state_log)"
  echo "$dump"
  if ! grep -Fq "$needle" <<<"$dump"; then
    echo "Expected UI state not found: $needle" >&2
    exit 1
  fi
}

assert_foreground() {
  local top
  top="$(adb shell dumpsys activity activities | grep -E 'mResumedActivity|topResumedActivity' | head -n 1 || true)"
  echo "$top"
  grep -Fq "$PACKAGE" <<<"$top" || {
    echo "Photo TV is not the foreground activity." >&2
    exit 1
  }
}

key() {
  adb shell input keyevent "$1"
  sleep 0.35
  assert_foreground
}

capture_page 0 preview
capture_page 1 photos
capture_page 2 editor
capture_page 3 settings

# Remote regression: bottom navigation.
start_page 0
key KEYCODE_DPAD_DOWN
assert_state "page=0 navFocus=true"
key KEYCODE_DPAD_RIGHT
assert_state "page=1 navFocus=true"

# Photos: album row navigation and OK.
start_page 1
key 20
assert_state "photosRow=1"
key 22
assert_state "page=1"
key KEYCODE_DPAD_CENTER
assert_state "page=1"

# Editor: columns, arrows, OK, and long OK.
start_page 2
key 22
assert_state "editorColumn=1"
adb shell input keyevent --longpress KEYCODE_DPAD_CENTER || true
sleep 0.4
assert_foreground
assert_state "editorMoveMode=true"

start_page 2
key 22
assert_state "editorColumn=1"
key 22
assert_state "editorColumn=2"
key 20
assert_state "editorControl=1"
key 23
assert_state "page=2"

# Settings: enter panel, move control, activate, Back.
start_page 3
key 22
assert_state "settingsColumn=1"
key 20
assert_state "page=3"
key 23
assert_state "page=3"
key KEYCODE_BACK
assert_state "page=0"

# Reinstall the same signed APK in-place. This catches signer instability immediately.
adb install -r "$APK"
start_page 0
assert_foreground
assert_state "page=0"

# No crash/fatal exception from our package during the navigation run.
if adb logcat -d | grep -E "FATAL EXCEPTION|AndroidRuntime" | grep -Fq "$PACKAGE"; then
  adb logcat -d | grep -E "FATAL EXCEPTION|AndroidRuntime|$PACKAGE" | tail -n 120
  echo "Crash detected during Android TV remote test." >&2
  exit 1
fi

echo "Android TV remote navigation test passed."
