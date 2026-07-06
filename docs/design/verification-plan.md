# Verification Plan

Emulators can prove the app builds and opens, but they cannot prove BOOX raw pen latency, TouchHelper behavior, or e-ink refresh quality. Real-device checks are required for pen and display changes.

## Automated Gates

Run before handing off a meaningful app change:

```bash
./gradlew test assembleRelease
```

Run for broader local coverage:

```bash
./gradlew test assembleRelease assembleDebugAndroidTest
```

With a connected BOOX tablet:

```bash
ANDROID_SERIAL=<device-id> scripts/boox-smoke.sh
```

## BOOX Smoke Expectations

The smoke script should:

- Build debug, release, and android-test artifacts as needed.
- Install the app and test package.
- Enable packages if BOOX app-freeze behavior disabled them.
- Run instrumentation smoke tests.
- Relaunch the app.
- Capture logs under `build/boox-smoke/`.
- Fail on fatal exceptions, ANRs, or app-freeze signals.

## Manual Release Checklist

1. Cold start: fresh install, onboarding, first reply. Pass: the user reaches a reply without extra instructions.
2. Ink feel: write a full paragraph. Pass: no visible lag compared with BOOX Notes and no dead writing zones.
3. Recognition set: write several short and multi-line prompts. Pass: ordinary legible writing produces sensible recognition.
4. Fade or dissolve: film the commit animation. Pass: the original ink remains believable until it fades or dissolves, and the page lands clean.
5. Reply reveal: pass if reply text appears at handwriting pace, is readable on e-ink, and does not flash the whole screen unnecessarily.
6. Context: write a fact, then ask a follow-up. Pass: the reply uses prior notebook context.
7. Missing key: remove the active provider key and commit. Pass: a modal warning appears and the page remains writable.
8. Network failure: disable network and commit. Pass: a modal warning appears and retry works after reconnecting.
9. History: open History, page through prior entries, try pen input. Pass: history is readable and read-only.
10. Burn notebook: use the destructive action. Pass: confirmation appears, notebook data is deleted, and future requests have no prior context.
11. Settings: change provider/model/persona/commit delay/handwriting settings. Pass: changes apply without app restart.
12. Endurance: run a 20-turn session. Pass: no crash, ANR, stuck raw drawing state, or unusable ghosting.

## Drawing Experiment Checklist

Drawing replies are experimental and should stay developer-gated until these pass:

1. Draw half of a simple object, ask the diary to finish it, and commit. Pass: added strokes align with the existing drawing.
2. Ask for a simple object on a blank page. Pass: the result is recognizable and not over-rendered.
3. Make several drawing requests on the same canvas. Pass: additions accumulate and History composites them correctly.
4. Switch back to text-only mode. Pass: recognition, fade, and written replies resume normally.
5. Use an unsupported provider/model. Pass: the app shows a modal warning and remains writable.

## Release Gate

Before publishing a GitHub release:

- Attach the APK.
- Attach or link the completed release checklist.
- Include a short uncut screen video of the core write, dissolve, reply loop.
