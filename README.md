# Inka

Hi, I'm [Daniel](https://x.com/daniel_nguyenx), and I build [AI apps](https://boltai.com). Inspired by [Maxime Rivest's Riddle](https://x.com/MaximeRivest/status/2073544461473169432), I built Inka as an experiment exploring different ways to interact with an AI model on an e-ink device.

Inka is a native Android app built specifically for BOOX Note Air tablets, tested on the BOOX Note Air 5C.

Write with the pen, rest your hand, and the page answers back in a flowing handwritten voice.

## Demo

https://github.com/user-attachments/assets/b2f76d75-4ed7-45aa-aeef-24a856df218d

## Why

Mostly, this exists because it was fun to build, and because I can wow my son with it. I do not let him use it unattended ofc.

## Features

- Pen-first writing on BOOX tablets
- Handwritten replies that appear directly on the page
- Word-by-word reply animation
- Ink fade/dissolve animation after you finish writing
- Local notebook history
- Built-in writing personas
- Native, reader-style UI for BOOX tablets
- Bring-your-own-key AI provider setup
- Optional experimental drawing replies in Developer settings
- No accounts, analytics, telemetry, backend, or crash reporting

## Requirements

- A BOOX Android e-ink tablet
- Android 10 or newer
- Network access for AI replies
- An API key for one supported provider during onboarding

Inka uses BOOX pen APIs for the best writing feel. A normal Android emulator can build and open the app, but it cannot prove BOOX pen latency or e-ink refresh quality.

## Install

### Prebuilt APK

1. Download the latest APK from GitHub Releases.
2. Copy the APK to your BOOX tablet.
3. Open the APK on the tablet.
4. Allow installation from local files if Android asks.
5. Launch **Inka**.
6. Complete onboarding: choose a provider, paste your API key, and download the handwriting model.
7. Write on the blank page and pause.

### Build From Source

```bash
./gradlew test assembleRelease
```

The APK is generated at:

```text
app/build/outputs/apk/release/app-release-unsigned.apk
```

Release builds from source are unsigned. Official distribution builds are signed separately for Google Play.

## Use

- Write with the pen.
- Pause to let Inka read the page.
- Tap the book button to read notebook history.
- Tap the eraser to clear the live page.
- Open Settings to change provider, model, handwriting, recognition language, and privacy-local notebook settings.

Experimental drawing replies are hidden under `Settings -> Developer -> AI answer mode`.

## BOOX Smoke Test

With a BOOX device connected over adb:

```bash
ANDROID_SERIAL=<device-id> scripts/boox-smoke.sh
```

This builds, installs, runs instrumentation smoke tests, relaunches the app, and stores logs under `build/boox-smoke/`.

## Privacy

- No server owned by this project
- No account system
- No analytics
- No telemetry
- No crash reporting
- API keys are stored in encrypted Android preferences when available
- Notebook data is stored locally in app-private storage
- Handwriting recognition runs on-device after the model download

## Device Note

This is an unofficial BOOX app. It is not affiliated with Onyx or BOOX. Sideloading software is your responsibility; keep a way to uninstall or reset the app if something behaves badly.

## License

App code is MIT licensed. See `LICENSE`.

Dancing Script is bundled under the SIL Open Font License. See `licenses/DANCING-SCRIPT-OFL.txt`.
