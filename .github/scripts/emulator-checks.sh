#!/usr/bin/env bash
# Runs inside the emulator job. Fails if the shaders give wrong values or the app crashes.
set -u
./gradlew --no-daemon connectedDebugAndroidTest
TEST_RESULT=$?

./gradlew --no-daemon assembleDebug
adb install -r -g app/build/outputs/apk/debug/app-debug.apk
adb logcat -c
adb shell monkey -p app.truelog.camera -c android.intent.category.LAUNCHER 1
sleep 8
adb exec-out screencap -p > emulator-photo.png || true
adb shell input tap 2000 400 || true
sleep 3
adb logcat -d > emulator-logcat.txt
if grep -E "FATAL EXCEPTION|AndroidRuntime: Process: app.truelog" emulator-logcat.txt; then
  echo "App crashed on launch"
  exit 1
fi
if ! adb shell pidof app.truelog.camera; then
  echo "App is not running"
  exit 1
fi
exit $TEST_RESULT
