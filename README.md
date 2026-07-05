# Inkwell

Inkwell is a sideloaded Android app for Boox e-ink tablets. It turns a blank page into a private handwritten diary that answers in script, one word at a time.

## Status

This repository contains the v1 implementation pass from `riddle-spec.md` plus the Manuscript Mode extension from `manuscript-mode-spec.md`: classic Android Views, Onyx TouchHelper raw pen capture, ML Kit Digital Ink recognition, BYOK AI requests, stepped e-ink fades, persistent notebook pages, word-by-word reply rendering, onboarding, settings, tests, and release docs.

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
4. Launch Inkwell.
5. Choose Anthropic, OpenAI, or Groq during onboarding and paste that provider's API key.
6. Download the English handwriting model.
7. Write on the blank page and pause for the configured commit delay.

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
- Fade-mode conversation history is memory-only and clears when the process dies.
- Manuscript-mode notebooks are stored as local JSON files under app-private storage so pages survive restarts.
- Handwriting recognition runs on-device after the ML Kit model download.

## V-next

- Exporting or sharing Manuscript notebooks is intentionally out of scope for this pass. The JSON notebook files are structured so export can be added later without changing the core page model.

## Demo GIF

Placeholder: add a 20-30s Boox screen video or GIF showing fade and word-by-word reply before publishing a release.

## Licenses

- App code: see `LICENSE`.
- Caveat font: see `licenses/CAVEAT-OFL.txt`.
- Onyx SDK: see BOOX/Onyx SDK terms for `com.onyx.android.sdk:onyxsdk-pen`.
- ML Kit: see Google ML Kit terms.
