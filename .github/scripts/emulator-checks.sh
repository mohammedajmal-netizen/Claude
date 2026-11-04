#!/usr/bin/env bash
# Runs inside the emulator job and drives the app the way a person would.
set -u
FAIL=0
fail() { echo "CHECK FAILED: $1"; FAIL=1; }
pass() { echo "CHECK OK: $1"; }

./gradlew --no-daemon connectedDebugAndroidTest || fail "instrumented tests"
python3 .github/scripts/gpu-test-summary.py

./gradlew --no-daemon assembleDebug
adb install -r -g app/build/outputs/apk/debug/app-debug.apk
adb logcat -c
adb shell monkey -p app.truelog.camera -c android.intent.category.LAUNCHER 1
sleep 8

# Finds a control by its visible text or description and taps its centre.
tap() {
  adb shell uiautomator dump /sdcard/ui.xml >/dev/null 2>&1
  adb shell cat /sdcard/ui.xml > ui.xml
  point=$(python3 .github/scripts/find-control.py "$1")
  if [ -z "$point" ]; then fail "control '$1' not found"; return 1; fi
  adb shell input tap $point
}

saved() {
  adb logcat -d | grep -c "TrueLog.*$1"
}

alive() {
  if adb shell pidof app.truelog.camera >/dev/null; then pass "$1"; else fail "$1: app is not running"; fi
}

tap PHOTO; sleep 2
tap Shutter; sleep 5
[ "$(saved "Photo saved")" -gt 0 ] && pass "photo saved" || fail "photo was not saved"
adb exec-out screencap -p > emulator-photo.png || true

tap PRO; sleep 5
alive "PRO mode opens"
adb exec-out screencap -p > emulator-pro.png || true

tap VIDEO; sleep 5
tap Shutter; sleep 6
adb exec-out screencap -p > emulator-video-recording.png || true
tap Shutter; sleep 6
[ "$(saved "Clip saved")" -gt 0 ] && pass "video saved" || fail "video was not saved"
alive "VIDEO mode records and stops"

tap LOG
# Software graphics are far slower than a phone, so give the first frames time to arrive.
for i in $(seq 1 30); do
  sleep 3
  adb logcat -d | grep -q "TrueLog.*log frames=" && break
done
sleep 10
adb exec-out screencap -p > emulator-log.png || true
alive "LOG mode opens without crashing"
frames=$(adb logcat -d | grep "TrueLog.*log frames=" | tail -1)
echo "LOG STATS $frames"
[ -n "$frames" ] && pass "real camera RAW reached the Log pipeline" || fail "no RAW frames reached the Log pipeline"
adb shell uiautomator dump /sdcard/ui.xml >/dev/null 2>&1
adb shell cat /sdcard/ui.xml | grep -o 'text="[^"]*"' | sort -u | head -30
tap Shutter; sleep 6
adb exec-out screencap -p > emulator-log-recording.png || true
alive "LOG record tap does not crash"

adb logcat -d > emulator-logcat.txt
echo "=== TrueLog log ==="
grep -E " TrueLog *:" emulator-logcat.txt | sed -E 's/^.*TrueLog *: //' | grep -v -E "^(  |LENS|HEVC|Google|sdk_|$)" | tail -30
echo "=== camera and GL errors ==="
grep -E " [EW] (Camera|EmulatedCamera|Adreno|EGL|libEGL|OpenGLRenderer|ImageReader|BufferQueue|TrueLog|GL2)" emulator-logcat.txt | tail -25
if grep -E "FATAL EXCEPTION" emulator-logcat.txt; then fail "app crashed"; fi
exit $FAIL
