# Product Brief

Inka is a handwritten AI diary for BOOX e-ink tablets. The product is not a chat app translated onto a tablet; it is a sheet of paper that can answer.

## Problem

People who write by hand on e-ink tablets get the focus of paper, but lose the leverage of AI. To ask a model anything, they usually leave the page, pick up a phone or laptop, and break flow.

There is an emotional mismatch too. Handwriting is slow and intimate; most AI interfaces are transactional. Inka exists to make the AI interaction feel native to the page.

## Core Illusion

The core loop has three beats:

1. You write with low-latency native-feeling ink.
2. Your ink dissolves or fades from the page after you pause.
3. The diary answers back in a handwritten voice, word by word.

Every other feature supports that moment: onboarding, settings, error states, history, and verification all exist so a new user can reach the loop quickly and trust it.

## Platform

- Primary device: BOOX Android e-ink tablets with EMR pen input.
- Distribution: sideloaded APK from GitHub Releases.
- Minimum Android: Android 10 or newer.
- UI: native Kotlin Android Views.
- Pen input: BOOX/Onyx raw drawing APIs when available, emulator fallback when not.
- Recognition: first-run handwriting recognition model download, then offline recognition once ready.
- AI: bring-your-own-key provider setup.
- Privacy: no project backend, accounts, analytics, telemetry, or crash reporting.

## First Run

Onboarding should keep the user on the page while finishing only the setup that matters:

1. Open the blank page immediately.
2. Show a square, high-contrast modal without a dimmed backdrop.
3. Keep the toolbar logo-only until onboarding is finished.
4. Explain the premise briefly, then offer optional OpenAI key setup.
5. Download the handwriting recognition model before dropping the user onto the full page.

The user should not need to understand models, provider internals, or app architecture before trying the product.

## Page UX

- The page is the product. Chrome should stay quiet.
- Pen input must feel close to the built-in BOOX Notes app.
- Finger taps operate toolbar/settings/history; the pen writes.
- Commit happens when the pen is lifted and the user pauses.
- Errors should be explicit modal warnings when action is required.
- Previous replies can be read through History, not through a chat transcript UI.

## Reply UX

- Replies are short, handwritten, and page-aware.
- Word-by-word reveal should feel like writing, not a terminal.
- The reply should begin near the top of the page with enough margin to avoid the toolbar.
- If a reply cannot fit on the current page, show a warning instead of silently spilling into unrelated space.

## Technical Shape

Important boundaries:

- Keep BOOX SDK calls isolated behind ink and refresh adapters.
- Keep API keys in encrypted preferences when available.
- Keep notebook data in app-private local storage.
- Keep provider history rebuilt from the active notebook so follow-up questions have context.
- Keep Developer experiments hidden from the normal settings path until they are ready.

## Non-goals

- No backend account system.
- No cloud sync.
- No public analytics.
- No Play Store distribution requirement for v1.
- No multi-notebook product surface until the one-notebook loop feels excellent.

## Definition Of Done

The app is shippable when:

- A clean clone builds a release APK with no private signing material.
- A connected BOOX smoke test passes.
- A first-time user can install, dismiss onboarding, write immediately, configure a provider, and receive a reply.
- Live ink quality is close to BOOX Notes.
- Recognition, provider errors, missing keys, and offline states recover without leaving the page stuck.
- History is readable and read-only.
- The README explains install, privacy, and device requirements without internal implementation detail.
