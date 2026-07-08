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
- Bring-your-own-key AI provider setup, with phone-assisted QR setup
- Built-in font rendering for replies by default
- Optional experimental neural handwriting synthesis through a user-configured server
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
6. Complete onboarding: scan the phone setup QR or paste your API key, then download the handwriting recognition model.
7. Write on the blank page and pause.

### Build Debug APK From Source

For local testing and sideloading:

```bash
./gradlew test assembleDebug
```

The APK is generated at:

```text
app/build/outputs/apk/debug/app-debug.apk
```

With a BOOX device connected over adb:

```bash
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

### Build Signed Release

Release signing is local-only. Keystores and signing config are ignored by git.

Create a keystore if you do not already have one:

```bash
mkdir -p release
keytool -genkeypair \
  -v \
  -keystore release/inka-upload-key.jks \
  -storetype JKS \
  -keyalg RSA \
  -keysize 2048 \
  -validity 10000 \
  -alias inka
```

Create local signing config:

```bash
cp .release-signing.properties.example .release-signing.properties
```

Then fill in `.release-signing.properties` with your keystore path, alias, and passwords.

Build signed release artifacts:

```bash
scripts/build-release.sh
```

The signed artifacts are generated at:

```text
app/build/outputs/apk/release/app-release.apk
app/build/outputs/bundle/release/app-release.aab
```

Use the `.aab` for Google Play. Each Play upload needs a higher `versionCode` in `app/build.gradle.kts`.

## Use

- Write with the pen.
- Pause to let Inka read the page.
- Tap the book button to read notebook history.
- Tap the eraser to clear the live page.
- Open Settings to change provider, model, handwriting, recognition language, and privacy-local notebook settings.
- Use `Settings -> AI Settings -> Set Up by Phone` to scan a QR code, open a setup page on your phone, and send an API key directly to the tablet.

Experimental drawing replies are hidden under `Settings -> Developer -> AI answer mode`.
Developer settings also include `Reset onboarding`, which immediately clears the live page and starts onboarding again for testing setup flows.

## Experimental Handwriting Synthesis

Inka uses the built-in font renderer by default. Developer settings also include
an experimental hosted handwriting synthesis mode that turns reply text into
generated ink strokes through a server you run or configure yourself.

The fastest local setup is Docker:

```bash
scripts/run-handwriting-server-docker.sh
```

On macOS, you can also double-click:

```text
scripts/start-handwriting-server-mac.command
```

Then set Inka:

```text
Settings -> Developer -> Experimental handwriting -> Hosted synthesis
Settings -> Developer -> Server Endpoint -> http://<server-ip>:8878
```

For USB testing with adb:

```bash
adb reverse tcp:8878 tcp:8878
```

Then use:

```text
http://127.0.0.1:8878
```

The developer handwriting lab does not run automatically on open. Press
`Run` to send the sample request. The lab shows live status text and logs timing
with the `HandwritingLab` tag.

Full setup, Cloudflare Container notes, troubleshooting, and privacy details:
`docs/design/handwriting-server-setup.md`.

## BOOX Smoke Test

With a BOOX device connected over adb:

```bash
ANDROID_SERIAL=<device-id> scripts/boox-smoke.sh
```

This builds, installs, runs instrumentation smoke tests, relaunches the app, and stores logs under `build/boox-smoke/`.

## Privacy

- No required Inka-owned backend
- No account system
- No analytics
- No telemetry
- No crash reporting
- API keys are stored in encrypted Android preferences when available
- Notebook data is stored locally in app-private storage
- Handwriting recognition runs on-device after the model download
- Phone-assisted API key setup starts a temporary local HTTP server on the
  tablet while the QR setup screen is open. The phone sends the key directly to
  the tablet on the local network; Inka saves it on the tablet and validates it
  with the selected AI provider.
- Experimental hosted handwriting synthesis sends generated reply text to the
  server endpoint you configure. The local Docker setup keeps that server on
  your own machine; a cloud endpoint should be treated as a privacy choice.

## Device Note

This is an unofficial BOOX app. It is not affiliated with Onyx or BOOX. Sideloading software is your responsibility; keep a way to uninstall or reset the app if something behaves badly.

## License

App code is MIT licensed. See `LICENSE`.

Dancing Script is bundled under the SIL Open Font License. See `licenses/DANCING-SCRIPT-OFL.txt`.

The optional Docker handwriting synthesis server downloads and runs
`X-rayLaser/pytorch-handwriting-synthesis-toolkit`, which is MIT licensed. Keep
its license notice when distributing a server image or hosted deployment.
