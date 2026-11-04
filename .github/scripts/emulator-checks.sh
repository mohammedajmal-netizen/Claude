#!/usr/bin/env bash
# Runs inside the emulator job and drives the app the way a person would.
set -u
FAIL=0
fail() { echo "CHECK FAILED: $1"; FAIL=1; }
pass() { echo "CHECK OK: $1"; }

./gradlew --no-daemon connectedDebugAndroidTest || fail "instrumented tests"
./gradlew --no-daemon assembleDebug
adb install -r -g app/build/outputs/apk/debug/app-debug.apk
adb logcat -c
adb shell monkey -p app.truelog.camera -c android.intent.category.LAUNCHER 1
sleep 8

# Finds a control by its visible text or description and taps its centre.
tap() {
  adb shell uiautomator dump /sdcard/ui.xml >/dev/null 2>&1
  adb shell cat /sdcard/ui.xml > ui.xml
  point=$(python3 - "$1" <<'P'
import re, sys
xml = open('ui.xml').read()
for node in re.findall(r'<node [^>]*>', xml):
    if f'text="{sys.argv[1]}"' in node or f'content-desc="{sys.argv[1]}"' in node:
        a = re.search(r'bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"', node).groups()
        print((int(a[0]) + int(a[2])) // 2, (int(a[1]) + int(a[3])) // 2)
        break
P
)
  if [ -z "$point" ]; then fail "control '$1' not found"; return 1; fi
  adb shell input tap $point
}

count() {
  adb shell content query --uri "content://media/external/$1/media" --projection _display_name:_size 2>/dev/null | grep -c TL_
}

alive() {
  if adb shell pidof app.truelog.camera >/dev/null; then pass "$1"; else fail "$1: app is not running"; fi
}

PHOTOS=$(count images); VIDEOS=$(count video)

tap PHOTO; sleep 2
tap Shutter; sleep 5
[ "$(count images)" -gt "$PHOTOS" ] && pass "photo saved" || fail "photo was not saved"
adb exec-out screencap -p > emulator-photo.png || true

tap PRO; sleep 5
alive "PRO mode opens"
adb exec-out screencap -p > emulator-pro.png || true

tap VIDEO; sleep 5
tap Shutter; sleep 6
adb exec-out screencap -p > emulator-video-recording.png || true
tap Shutter; sleep 6
[ "$(count video)" -gt "$VIDEOS" ] && pass "video saved" || fail "video was not saved"
adb shell content query --uri content://media/external/video/media --projection _display_name:_size:duration:width:height | grep TL_ | tail -1
alive "VIDEO mode records and stops"

tap LOG; sleep 8
adb exec-out screencap -p > emulator-log.png || true
alive "LOG mode opens without crashing"
tap Shutter; sleep 6
adb exec-out screencap -p > emulator-log-recording.png || true
tap Shutter; sleep 8
alive "LOG record tap does not crash"
adb shell content query --uri content://media/external/video/media --projection _display_name:_size:duration | grep -i slog3 || echo "No Log clip on this emulator"

adb logcat -d > emulator-logcat.txt
echo "=== TrueLog log ==="
grep -E " TrueLog *:" emulator-logcat.txt | sed -E 's/^.*TrueLog *: //' | head -70
if grep -E "FATAL EXCEPTION" emulator-logcat.txt; then fail "app crashed"; fi
exit $FAIL
