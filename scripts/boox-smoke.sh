#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
SERIAL="${1:-${ANDROID_SERIAL:-}}"
APP_ID="co.podzim.inka"
TEST_ID="co.podzim.inka.test"
RUNNER="${TEST_ID}/androidx.test.runner.AndroidJUnitRunner"
LOG_DIR="${ROOT_DIR}/build/boox-smoke"
INSTRUMENT_LOG="${LOG_DIR}/instrumentation.log"
LAUNCH_LOG="${LOG_DIR}/launch.log"

adb_cmd=(adb)
if [[ -n "${SERIAL}" ]]; then
  adb_cmd+=(-s "${SERIAL}")
fi

run_adb() {
  "${adb_cmd[@]}" "$@"
}

mkdir -p "${LOG_DIR}"
cd "${ROOT_DIR}"

./gradlew testDebugUnitTest assembleDebug assembleDebugAndroidTest --no-daemon

run_adb install -r app/build/outputs/apk/debug/app-debug.apk
run_adb install -r app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk

run_adb shell pm enable --user 0 "${APP_ID}" >/dev/null
run_adb shell pm enable --user 0 "${TEST_ID}" >/dev/null

run_adb logcat -c
run_adb shell am instrument -w -r "${RUNNER}" | tee "${INSTRUMENT_LOG}"
grep -q "OK (" "${INSTRUMENT_LOG}"

run_adb shell am force-stop "${APP_ID}"
run_adb logcat -c
run_adb shell am start -n "${APP_ID}/.ui.MainActivity" >/dev/null
sleep 3
run_adb logcat -d -s AndroidRuntime InkaDebug InkCaptureController OnyxInkReplay | tee "${LAUNCH_LOG}"

if grep -q "FATAL EXCEPTION" "${LAUNCH_LOG}"; then
  echo "BOOX launch smoke failed: AndroidRuntime fatal exception found in ${LAUNCH_LOG}" >&2
  exit 1
fi

if grep -Eiq "Application Not Responding|ANR in|Input dispatching timed out|Watchdog" "${LAUNCH_LOG}"; then
  echo "BOOX launch smoke failed: ANR/freeze signal found in ${LAUNCH_LOG}" >&2
  exit 1
fi

echo "BOOX smoke passed. Logs: ${INSTRUMENT_LOG}, ${LAUNCH_LOG}"
