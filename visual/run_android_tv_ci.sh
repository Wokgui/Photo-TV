#!/usr/bin/env bash
set -euo pipefail

APK="${1:-Photo-TV.apk}"
OUT="${2:-android-visual-results}"

remote_status=0
visual_status=0

bash visual/android_tv_test.sh "$APK" "$OUT" || remote_status=$?
python3 visual/compare.py "$OUT" 72 68 || visual_status=$?

if [ "$remote_status" -ne 0 ] || [ "$visual_status" -ne 0 ]; then
  echo "Android TV regression failed: remote=$remote_status visual=$visual_status" >&2
  exit 1
fi

echo "Android TV regression passed."
