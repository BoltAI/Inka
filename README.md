# Inka

Inka is a sideloaded Android app for Boox e-ink tablets. It turns a blank page into a private handwritten diary that answers in script, one word at a time.

## Status

This repository contains the v1 implementation pass from `riddle-spec.md` plus the one-notebook persistence refactor from `manuscript-mode-spec.md`: classic Android Views, Onyx TouchHelper raw pen capture, ML Kit Digital Ink recognition, BYOK AI requests, e-ink fades, persisted notebook history, word-by-word reply rendering, Sketchbook drawing replies, onboarding, settings, tests, and release docs.

Real Boox hardware is still required for the product gate. A normal emulator can verify the app shell and fallback drawing, but it cannot validate TouchHelper latency or e-ink refresh modes.

## Build

```bash
./gradlew test assembleRelease
```

The release build is signed with the debug signing config so a clean clone can produce a sideloadable APK with no local secrets. Replace the signing config before a public release.

The APK will be at:

```text
app/build/outputs/apk/release/app-release.apk
```

## Install On Boox

1. Build the release APK.
2. Copy `app-release.apk` to the Boox tablet.
3. Open the file on the Boox device and allow installation from local files if prompted.
4. Launch Inka.
5. Choose Anthropic, OpenAI, or Groq during onboarding and paste that provider's API key.
6. Download the English handwriting model.
7. Write on the blank page and pause for the configured commit delay.

Sketchbook Drawing replies are configured in Settings -> Developer -> AI answer mode. Drawing mode currently supports Anthropic and OpenAI.

## Boox Smoke Test

With a Boox device connected over adb:

```bash
ANDROID_SERIAL=a8f9bed9 scripts/boox-smoke.sh
```

The script builds the debug APKs, installs with `adb install -r`, enables the app and test package, runs the instrumentation smoke tests through `am instrument`, and relaunches the app while capturing logcat under `build/boox-smoke/`.

Use this script instead of `./gradlew connectedDebugAndroidTest` on BOOX hardware. The Gradle connected runner can interact badly with BOOX app-freeze behavior and may uninstall or reset the app package during installation.

## Privacy

- No backend.
- No accounts.
- No analytics.
- No telemetry.
- No crash reporting.
- Provider API keys are stored in encrypted device preferences.
- One active notebook is stored as a local JSON file under app-private storage so recognized exchanges survive restarts.
- The live page still uses the fade illusion; persistence is storage behavior, not a separate writing mode.
- Handwriting recognition runs on-device after the ML Kit model download.
- Drawing-mode page snapshots are transient request inputs and are not persisted.

## V-next

- Exporting or sharing the notebook is intentionally out of scope for this pass. The JSON notebook file is structured so export can be added later without changing the core exchange model.

## Demo GIF

Placeholder: add a 20-30s Boox screen video or GIF showing fade and word-by-word reply before publishing a release.

## Licenses

- App code: see `LICENSE`.
- Dancing Script font: see `licenses/DANCING-SCRIPT-OFL.txt`.
- Onyx SDK: see BOOX/Onyx SDK terms for `com.onyx.android.sdk:onyxsdk-pen`.
- ML Kit: see Google ML Kit terms.
