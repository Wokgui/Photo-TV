#!/usr/bin/env bash
set -euo pipefail

RESOLUTION="${1:?resolution required}"
LABEL="${2:?label required}"
APK="${3:-Photo-TV.apk}"
OUT="resolution-results/${LABEL}"

adb shell wm size "$RESOLUTION"
adb install -r "$APK"
adb shell am force-stop fr.wokgui.phototv
adb shell am start -n fr.wokgui.phototv/.MainActivity --ez phototv_test_mode true --ei phototv_test_page 0
sleep 3

adb shell dumpsys activity activities | grep -q "fr.wokgui.phototv"
mkdir -p "$OUT"
adb exec-out screencap -p > "$OUT/preview.png"

python3 - "$RESOLUTION" "$OUT/preview.png" <<'PY'
from PIL import Image
from pathlib import Path
import sys

expected = sys.argv[1]
path = Path(sys.argv[2])
w, h = map(int, expected.split("x"))
with Image.open(path) as im:
    actual = im.size
if actual != (w, h):
    raise SystemExit(f"Unexpected screenshot size: {actual}, expected {(w, h)}")
print(f"Verified {actual[0]}x{actual[1]}")
PY

if adb logcat -d | grep -E "FATAL EXCEPTION|AndroidRuntime.*fr.wokgui.phototv"; then
  exit 1
fi
