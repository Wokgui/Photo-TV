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
  sleep 1.0
  assert_foreground
  assert_state "page=$page"
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
  adb logcat -d -s PhotoTVState:I '*:S' | tail -n 100
}

latest_state() {
  state_log | tail -n 1
}

assert_state() {
  local needle="$1"
  local state
  state="$(latest_state)"
  echo "$state"
  if ! grep -Fq "$needle" <<<"$state"; then
    echo "Expected UI state not found: $needle" >&2
    echo "Recent PhotoTV state log:" >&2
    state_log >&2
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
  local code="$1"
  case "$code" in
    KEYCODE_DPAD_UP) code=19 ;;
    KEYCODE_DPAD_DOWN) code=20 ;;
    KEYCODE_DPAD_LEFT) code=21 ;;
    KEYCODE_DPAD_RIGHT) code=22 ;;
    KEYCODE_DPAD_CENTER|KEYCODE_ENTER) code=23 ;;
    KEYCODE_BACK) code=4 ;;
  esac
  adb shell input keyevent "$code"
  sleep 0.25
  assert_foreground
}

repeat_key() {
  local code="$1"
  local count="$2"
  for _ in $(seq 1 "$count"); do
    key "$code"
  done
}

long_center() {
  adb shell input keyevent --longpress 23
  sleep 0.45
  assert_foreground
}

# Real APK screenshots used by the visual regression gate.
capture_page 0 preview
capture_page 1 photos
capture_page 2 editor
capture_page 3 settings

# Bottom navigation: every page must be reachable and reversible.
start_page 0
key KEYCODE_DPAD_DOWN
assert_state "page=0 navFocus=true"
key KEYCODE_DPAD_RIGHT
assert_state "page=1 navFocus=true"
key KEYCODE_DPAD_RIGHT
assert_state "page=2 navFocus=true"
key KEYCODE_DPAD_RIGHT
assert_state "page=3 navFocus=true"
key KEYCODE_DPAD_LEFT
assert_state "page=2 navFocus=true"
key KEYCODE_DPAD_CENTER
assert_state "page=2 navFocus=false"
assert_state "editorColumn=0"

# Photos: all three sources, album row, photo row, and exit to bottom navigation.
start_page 1
key KEYCODE_DPAD_RIGHT
assert_state "sourceFocus=1"
key KEYCODE_DPAD_RIGHT
assert_state "sourceFocus=2"
key KEYCODE_DPAD_LEFT
assert_state "sourceFocus=1"
key KEYCODE_DPAD_LEFT
assert_state "sourceFocus=0"

key KEYCODE_DPAD_DOWN
assert_state "photosRow=1"
repeat_key KEYCODE_DPAD_RIGHT 5
assert_state "albumFocus=5"
repeat_key KEYCODE_DPAD_LEFT 5
assert_state "albumFocus=0"

key KEYCODE_DPAD_DOWN
assert_state "photosRow=2"
repeat_key KEYCODE_DPAD_RIGHT 5
assert_state "photoFocus=5"
repeat_key KEYCODE_DPAD_LEFT 5
assert_state "photoFocus=0"

key KEYCODE_DPAD_DOWN
assert_state "photosRow=3"
assert_state "navFocus=true"
key KEYCODE_DPAD_LEFT
assert_state "page=0 navFocus=true"

# Editor: every metadata item, every column, long-OK movement mode, and all panel controls.
start_page 2
repeat_key KEYCODE_DPAD_DOWN 9
assert_state "editorElement=9"
repeat_key KEYCODE_DPAD_UP 9
assert_state "editorElement=0"

key KEYCODE_DPAD_RIGHT
assert_state "editorColumn=1"
long_center
assert_state "editorMoveMode=true"
key KEYCODE_DPAD_RIGHT
assert_state "editorMoveMode=true"
key KEYCODE_DPAD_CENTER
assert_state "editorMoveMode=false"
key KEYCODE_DPAD_RIGHT
assert_state "editorColumn=2"

repeat_key KEYCODE_DPAD_DOWN 11
assert_state "editorControl=11"
repeat_key KEYCODE_DPAD_UP 11
assert_state "editorControl=0"
key KEYCODE_DPAD_LEFT
assert_state "editorColumn=1"
key KEYCODE_DPAD_LEFT
assert_state "editorColumn=0"

# Settings sidebar: every category is reachable.
start_page 3
repeat_key KEYCODE_DPAD_DOWN 7
assert_state "settingsCategory=7"
repeat_key KEYCODE_DPAD_UP 7
assert_state "settingsCategory=0"

# Diaporama panel: every control is reachable.
key KEYCODE_DPAD_RIGHT
assert_state "settingsColumn=1"
repeat_key KEYCODE_DPAD_DOWN 7
assert_state "settingsControl=7"
repeat_key KEYCODE_DPAD_UP 7
assert_state "settingsControl=0"
key KEYCODE_DPAD_LEFT
assert_state "settingsColumn=0"

# Advanced panel: diagnostics and nested album rules are both reachable and escapable.
repeat_key KEYCODE_DPAD_DOWN 7
assert_state "settingsCategory=7"
key KEYCODE_DPAD_RIGHT
assert_state "settingsColumn=1"
repeat_key KEYCODE_DPAD_DOWN 12
assert_state "settingsControl=12"
key KEYCODE_DPAD_CENTER
assert_state "diagnostics=true"

key KEYCODE_DPAD_UP
assert_state "settingsControl=11"
key KEYCODE_DPAD_CENTER
assert_state "rulesOpen=true"
assert_state "settingsControl=0"
repeat_key KEYCODE_DPAD_DOWN 8
assert_state "settingsControl=8"
key KEYCODE_DPAD_CENTER
assert_state "rulesOpen=false"
assert_state "settingsControl=0"

key KEYCODE_BACK
assert_state "page=0"

# Reinstall the exact same signed APK in place. Any signing drift breaks this.
adb install -r "$APK"
start_page 0
assert_foreground
assert_state "page=0"

# Capture package/signing metadata for the regression artifact.
adb shell dumpsys package "$PACKAGE" > "$OUT/package-dump.txt"
adb shell pm path "$PACKAGE" > "$OUT/package-path.txt"
state_log > "$OUT/remote-state-log.txt"

# No crash/fatal exception from our package during the complete navigation run.
if adb logcat -d | grep -E "FATAL EXCEPTION|AndroidRuntime" | grep -Fq "$PACKAGE"; then
  adb logcat -d | grep -E "FATAL EXCEPTION|AndroidRuntime|$PACKAGE" | tail -n 160
  echo "Crash detected during Android TV remote test." >&2
  exit 1
fi

echo "Android TV remote navigation test passed."
