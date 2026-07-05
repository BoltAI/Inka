# RIDDLE — An AI Diary for E-Ink

**Agent brief: build this end-to-end as a shippable Android app.** You are implementing v1 of a product, not a demo. Read the entire spec before writing code. Where the spec is silent, prefer the simplest implementation that preserves the core illusion described in §2.

---

## 1. Problem statement

People who write by hand on e-ink tablets (Boox, reMarkable) love the focus of paper but lose the leverage of AI. To ask an LLM anything, they must leave the page, pick up a phone or laptop, and break flow. Meanwhile, every AI interface today is a chat app: glowing screen, keyboard, message bubbles.

There is an emotional gap too: handwriting is intimate and slow; chat UIs are transactional. Nobody has built an AI you *write to*.

## 2. Proposed solution

**Riddle** turns a Boox e-ink tablet into Tom Riddle's diary: the user writes on the page with the stylus, their ink fades away, and a reply writes itself back in handwriting-style script — word by word, as if an unseen hand were answering.

The core illusion has three beats, and the entire product succeeds or fails on their quality:

1. **You write** — native-feeling, low-latency ink.
2. **It fades** — your words dissolve into the page over ~1.5s.
3. **It answers** — the reply appears word by word in script, at handwriting pace.

Everything else (settings, onboarding, error states) exists to support strangers installing this and reaching that moment in under 2 minutes.

## 3. Target platform & constraints

- **Device:** Onyx Boox Note Air series (primary: Note Air 5c), Android-based, Wacom EMR stylus, e-ink display (~1872×1404 class, color Kaleido on the 5c but treat the UI as grayscale).
- **Distribution:** sideloaded APK (GitHub Releases). Google Play is a non-goal for v1 (the Onyx SDK lacks 16KB page-size support required by Play — this is fine for sideloading; do not "fix" it).
- **Min SDK:** match the Onyx SDK requirements; target the Android version shipped on recent Boox devices (Android 12–13). Set `minSdk` no higher than 29.
- **No backend.** BYOK: users paste their own Anthropic API key. The app calls the Anthropic Messages API directly from the device.
- **Offline:** graceful degradation only (see §7 error states). Recognition models are on-device after first download.

## 4. User experience specification

### 4.1 First run (onboarding)

Three full-screen e-ink-friendly pages (high contrast, no animation-heavy transitions):

1. **What this is.** One line: "A diary that writes back." One illustration-free paragraph. Button: *Continue*.
2. **API key.** Explanation ("Your key stays on this device. Requests go directly to Anthropic."), a text field, a *Paste* button, and a link rendered as plain text to `console.anthropic.com`. Validate the key with a 1-token test call; show a clear pass/fail state.
3. **Handwriting model download.** ML Kit digital-ink model for the user's chosen language (default: English, selectable later in Settings). Show download progress. This requires network; if it fails, allow retry.

After onboarding, drop the user straight onto the page with a single faint script line centered: *"Write something…"* which disappears on first pen-down.

### 4.2 The page (core screen)

- Full-screen, edge to edge. Paper-white background. No toolbar, no status bar.
- The **only** persistent chrome: a small (~24dp) unobtrusive glyph in the top-right corner that opens Settings. It must not respond to the stylus, only to finger touch, so it can never be hit mid-writing.
- User writes anywhere on the page. Multi-line, natural handwriting.
- **Commit gesture:** pen lifted AND no new stroke for **2.0 seconds** → the message is committed. (Make the delay a constant; we will tune it.) Alternative explicit commit: double-tap with finger anywhere. Support both.
- On commit: recognize → fade → think → reply (see 4.3–4.5).
- After the reply finishes writing, the reply stays on screen. When the user starts writing again (pen-down), the previous reply fades out over 800ms and the page is theirs. Conversation history is preserved in memory and sent to the model — the page clearing is visual only.

### 4.3 The fade (user's ink)

- Triggered on commit, after recognition succeeds (fading before recognition risks eating unrecognized text).
- Implementation: redraw the captured strokes at decreasing opacity in 4–5 steps (e.g., 100% → 60% → 35% → 15% → gone), ~300ms per step, then issue a **full e-ink refresh** (GC16/full update via the Onyx SDK) to eliminate ghosting. The final state must be a clean page.
- E-ink cannot do smooth alpha animation; a stepped fade with a terminal full refresh both looks intentional and reads as "dissolving."

### 4.4 Thinking state

- While waiting on the API: a single small ellipsis "…" written in the script font at the position where the reply will begin, pulsing by redrawing (…  ..  … ) once per second. Nothing else. No spinners.

### 4.5 The reply

- Rendered in a cursive/handwriting **font** (bundle one with an OFL license — e.g., Caveat, Homemade Apple, or Dancing Script; pick the most legible at small size on e-ink and record the choice + license in the repo).
- Font size ~26–30sp, ink-black, line height generous (1.6).
- **Word-by-word reveal:** words appear sequentially, 90–140ms apart with slight jitter, so a sentence takes a couple of seconds — handwriting pace, not teletype pace. Use the Onyx SDK's fast/partial refresh mode (A2/DU) during the reveal, then one full refresh at the end.
- Replies begin near the top-left of the page with comfortable margins and wrap naturally. If a reply would overflow the page, paginate: fill the page, wait for a finger tap, full-refresh, continue.
- **Reply length is a product constraint:** system prompt must instruct the model to answer in 1–4 sentences, warm, a little wry, never using markdown, lists, or emoji. It is a diary, not a chatbot. (Full system prompt: see §6.5.)

### 4.6 Settings (finger-tap glyph)

Plain, e-ink-friendly list:
- API key (masked, editable, re-validates on save)
- Model picker (default `claude-sonnet-4-6`; free-text override field for forward compatibility)
- Persona: 3 presets — *Diary* (default, the §6.5 prompt), *Socratic* (answers with a probing question), *Scholar* (denser, more factual) — plus a custom system-prompt text field
- Recognition language (re-triggers ML Kit model download)
- Commit delay slider (1.0–4.0s)
- "Clear conversation" (wipes in-memory history + page)
- About: version, licenses (Onyx SDK, ML Kit, font)

## 5. Technical architecture

### 5.1 Stack

- **Language:** Kotlin. **UI:** classic Android Views (NOT Compose — the Onyx `TouchHelper` raw-drawing pipeline targets a `SurfaceView` and bypasses the normal view pipeline; Compose adds friction for zero benefit here). Single-activity app.
- **Concurrency:** Kotlin coroutines. **HTTP:** OkHttp. **JSON:** kotlinx.serialization or Moshi.
- **Ink:** `com.onyx.android.sdk:onyxsdk-pen` (TouchHelper / RawInputCallback). Repos required in `settings.gradle`:
  ```
  maven { url "https://jitpack.io" }
  maven { url "http://repo.boox.com/repository/maven-public/"; allowInsecureProtocol = true }
  ```
  Note the Onyx repo is plain HTTP — `allowInsecureProtocol` is required.
- **Handwriting recognition:** ML Kit Digital Ink Recognition (`com.google.mlkit:digital-ink-recognition`). On-device inference; model downloaded once per language.
- **LLM:** Anthropic Messages API (`POST https://api.anthropic.com/v1/messages`), header `x-api-key`, `anthropic-version: 2023-06-01`. Non-streaming is acceptable for v1 (replies are short and the word-by-word reveal masks latency); implement the response reveal decoupled from transport.

### 5.2 Module structure

```
app/
  ink/        InkCaptureController (TouchHelper wiring, stroke store, redraw)
  recognize/  RecognitionService (ML Kit adapter: strokes -> text)
  brain/      ConversationEngine (history, system prompt, Anthropic client)
  page/       PageRenderer (fade animation, script text layout, word reveal,
              pagination, e-ink refresh control)
  ui/         MainActivity, OnboardingActivity, SettingsActivity
  data/       Prefs (EncryptedSharedPreferences for the API key), models
```

### 5.3 Ink capture — critical details

- `TouchHelper.create(surfaceView, rawInputCallback).setStrokeWidth(3.5f).setLimitRect(pageRect, listOf(settingsGlyphRect)).openRawDrawing()`; enable with `setRawDrawingEnabled(true)`. Use `STROKE_STYLE_FOUNTAIN`.
- **Gotcha you must handle:** TouchHelper draws directly to the e-ink layer, bypassing your `SurfaceView` canvas. You must independently record every stroke from `RawInputCallback` (`onRawDrawingTouchPointListReceived` gives `TouchPointList`; each `TouchPoint` has x, y, pressure, timestamp) into your own model, because (a) the fade requires re-rendering the strokes yourself, (b) any full refresh wipes the raw-drawn ink. After commit, call `setRawDrawingEnabled(false)`, take ownership of rendering, and only re-enable raw drawing when the page is ready for input again.
- Reset the pen-idle commit timer on every `onRawDrawingTouchPointMoveReceived` / `onEndRawDrawing`.
- Palm rejection: rely on the EMR digitizer (pen events only via TouchHelper); finger touches go to the normal view layer (used for double-tap commit, pagination taps, settings glyph).

### 5.4 Recognition

- Convert stored strokes to ML Kit `Ink`: one `Ink.Stroke` per pen stroke, `Ink.Point.create(x, y, t)`.
- Provide `WritingArea(width, height)` and pre-context (empty for v1) via `RecognitionContext` — this measurably improves accuracy.
- Take the top candidate. If the result is empty/whitespace: do not call the API; write back a faint one-line hint in script ("I couldn't read that — try again?") that disappears on pen-down.

### 5.5 Conversation engine

- History: list of `{role, content}` alternating user/assistant, capped at the last 20 turns (drop oldest beyond that).
- Request: system prompt (persona) + history + new user message; `max_tokens: 300`; model from settings.
- Timeouts: 30s connect+read. Retries: one automatic retry on 5xx/timeout.
- Error rendering happens **in-world** whenever possible: network failure → the diary writes *"The ink won't flow — I can't reach the outside world right now."* Invalid key (401) → *"Something is wrong with the key that binds me."* plus a plain small banner "Check API key in Settings" (errors must be diegetic AND actionable).

### 5.6 Page rendering & e-ink refresh

- Own a single offscreen `Bitmap` + `Canvas` mirroring the page; blit to the SurfaceView. All fades/reveals draw to this bitmap.
- Refresh policy via Onyx `EpdController` / update-mode APIs: partial/fast (A2 or DU) during fade steps and word reveal; one GC16 full refresh at fade end and at reply end. Encapsulate ALL refresh calls in one `EinkRefresher` class so device-specific tweaks live in one file.
- Text layout: `StaticLayout` with the bundled script Typeface; compute word boxes so the reveal can draw word N without re-rendering words 0..N-1 (append-only drawing = minimal e-ink flashing).

### 5.7 Security & privacy

- API key in `EncryptedSharedPreferences`. Never logged. `android:allowBackup="false"`.
- No analytics, no telemetry, no crash reporting in v1. State it in the README as a feature.
- Conversation history is memory-only; process death clears it. (Persistence is a v2 decision.)

## 6. Implementation plan (build in this order)

1. **M0 — Scaffold:** project, Gradle repos, Onyx + ML Kit deps compile; blank full-screen SurfaceView activity runs on-device.
2. **M1 — Ink:** raw drawing works; strokes captured into the model; debug overlay can re-render captured strokes (proves 5.3 ownership handoff).
3. **M2 — Recognition:** commit gesture → recognized string in logcat; empty-result hint path.
4. **M3 — Brain:** Anthropic client + settings-stored key; round trip logged.
5. **M4 — The illusion:** fade animation, thinking ellipsis, word-by-word script reveal, refresh policy, pagination. *This milestone is 50% of the product's value — budget accordingly.*
6. **M5 — Product shell:** onboarding (3 pages incl. key validation + model download), settings screen, personas, error states.
7. **M6 — Release:** app icon (simple inkwell/quill mark, monochrome), versioning, signed release APK, README with install instructions (sideloading steps for Boox), LICENSE files.

### 6.5 Default system prompt (Diary persona) — use verbatim

> You are a diary that writes back — a quiet, perceptive companion living inside the pages of a notebook. The person writes to you by hand; your replies are inked onto the page. Answer in one to four sentences. Be warm, specific, and occasionally wry. Ask at most one question, and only when it truly serves them. Never use markdown, bullet points, emoji, or headings — you write in flowing sentences only, as handwriting on paper. Never mention that you are an AI, a language model, or an app unless directly asked.

## 7. Non-goals (v1)

- No streaming, no voice, no drawing-understanding (ink → text only), no cloud sync, no accounts, no Google Play, no multi-notebook, no history persistence across restarts, no reMarkable port.

## 8. Definition of done

v1 is done when ALL of the following are true:

1. A signed release APK installs on a Boox Note Air (5c-class) via sideload and runs full-screen.
2. A brand-new user with only an Anthropic API key completes onboarding and receives their first handwritten reply in **under 2 minutes**, with no instructions beyond the README.
3. Ink latency while writing is indistinguishable from the built-in Boox notes app (raw drawing path confirmed active).
4. The commit → fade → reply loop works ≥ 20 consecutive times in one session without crash, ghosting buildup, or stuck refresh state.
5. Handwriting recognition succeeds on ordinary legible print and cursive English (see verification script).
6. All error states in §5.5 render diegetically and recover (airplane-mode test, bad-key test).
7. Multi-page replies paginate on tap; previous reply fades on next pen-down; history actually reaches the API (verify a follow-up question resolves a pronoun from the prior turn).
8. Settings changes (persona, model, commit delay, language) take effect without app restart.
9. Repo contains: README (install + BYOK setup + demo GIF placeholder), LICENSE, font license, `CHANGELOG.md`, and a `verification.md` that is the checklist in §9 with checkboxes.
10. `./gradlew assembleRelease` succeeds from a clean clone with no local secrets required.

## 9. Verification

### 9.1 Automated (run in CI / pre-commit)

- Unit tests: stroke-store round trip (points in = points redrawn); ML Kit `Ink` conversion (stroke/point counts, timestamps monotonic); conversation history capping; Anthropic request-body builder (system prompt, history order, max_tokens); error-mapper (HTTP code → diegetic message).
- A fake-clock test for the commit timer (strokes reset it; fires at exactly the configured delay).
- Lint + release build in CI (GitHub Actions): `assembleRelease` on every push.

### 9.2 On-device manual script (must be executed on real hardware; emulator cannot exercise TouchHelper or e-ink)

Copy into `verification.md`; each item is a checkbox with pass criteria:

1. **Cold start:** fresh install → onboarding → first reply. Time it. Pass: < 2 min.
2. **Ink feel:** write a full paragraph. Pass: no visible lag vs. Boox Notes; no stray marks in the settings-glyph corner.
3. **Recognition set:** write, one at a time, committing each: "hello", "What should I cook tonight?", "I've been feeling stuck on my startup lately", a 4-line multi-line note, and one deliberately sloppy sentence. Pass: ≥ 4/5 produce a sensible reply; sloppy case may trigger the can't-read hint (acceptable).
4. **The illusion:** film the fade + reveal. Pass: fade completes < 2.5s, page is clean (no ghost strokes), reply writes word-by-word at reading pace, terminal full refresh leaves crisp text.
5. **Context:** write "My dog is named Biscuit." → commit → after reply, write "What breed do you think he is?" Pass: reply references Biscuit/the dog.
6. **Pagination:** switch persona to Scholar, ask for something long ("tell me about the history of ink"). Pass: page fills, tap advances, no text clipped.
7. **Failure — network:** airplane mode → write → commit. Pass: diegetic offline line appears; restoring network and retrying works.
8. **Failure — auth:** corrupt the key in Settings → write. Pass: diegetic key message + actionable banner; fixing key recovers without restart.
9. **Endurance:** 20-turn conversation. Pass: no crash, no ANR, no ghosting accumulation, memory stable (watch logcat).
10. **Settings matrix:** change commit delay to 4s (verify timing), switch persona (verify tone change next turn), clear conversation (verify pronoun no longer resolves).

### 9.3 Release gate

Attach to the GitHub release: the APK, the completed `verification.md`, and a 20–30s screen video of item 9.2.4. **No release without the video** — the illusion is the product; if it doesn't look magical on film, it isn't done.

---

*Naming note: "Riddle" is a working codename. Ship the repo under a neutral name (e.g., `inkwell`, `palimpsest`) and keep all Harry Potter references out of app strings, store copy, and marketing — the mechanic is inspired-by, the IP is not ours.*
