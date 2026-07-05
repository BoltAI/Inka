# Verification

Automated gate:

- [x] `./gradlew test assembleRelease` passes from a clean clone with no local secrets. Last run: 2026-07-05, local workstation.
- [ ] GitHub Actions release build passes.

Device smoke:

- [x] 2026-07-05: installed `app/build/outputs/apk/release/app-release.apk` on Boox device `a8f9bed9` with `adb install -r`, launched `com.inkwell.diary/.ui.MainActivity`, opened Settings -> AI Settings, and confirmed the provider selector plus key/model controls render. Logcat scan showed no app crash.
- [x] 2026-07-05: installed updated release APK on Boox device `a8f9bed9`, opened Settings -> AI Settings, confirmed Model renders as a spinner, and opened the model dropdown. Logcat scan showed no app crash.
- [x] 2026-07-05: installed updated release APK on Boox device `a8f9bed9`, confirmed toolbar exposes Erase page, AI log, and Settings, tapped AI log, and confirmed the debug panel shows active provider/model. Logcat scan showed no app crash.
- [x] 2026-07-05: installed updated release APK on Boox device `a8f9bed9`, cleared logcat, relaunched `com.inkwell.diary`, and confirmed `adb -s a8f9bed9 logcat -d -s InkwellDebug` emits app debug lines, starting with `ready`.
- [x] 2026-07-05: installed updated release APK on Boox device `a8f9bed9` with overlapping prompt fade, relaunched `com.inkwell.diary`, and confirmed `InkwellDebug` emits `ready` after launch. Logcat scan showed no app crash.
- [x] 2026-07-05: installed updated release APK on Boox device `a8f9bed9`, opened Settings, confirmed the standalone Handwriting Font row is gone, opened Writing Settings, and confirmed commit delay plus handwriting font, font size, font weight, and preview controls render on one page.
- [x] 2026-07-05: captured the Boox Reader Global Settings screen as a reference, then installed updated release APK on Boox device `a8f9bed9`, opened Settings -> Developer, confirmed Pause auto reply uses a Boox-style label/explanation-left and ON/OFF-toggle-right row, toggled it ON and back OFF, and left auto reply active. Logcat scan showed no app crash.
- [x] 2026-07-05: installed updated release APK on Boox device `a8f9bed9`, confirmed Pause auto reply was ON, returned to the page, tapped Erase page, and confirmed the page cleared with `InkwellDebug` logging `page cleared`. Logcat scan showed no app crash. Real stylus ink still needs manual confirmation because adb cannot synthesize BOOX raw pen input.
- [x] 2026-07-05: installed updated release APK on Boox device `a8f9bed9`, relaunched the app, and confirmed the toolbar renders the feather pen logo plus Inkwell name on the left while preserving Erase page, AI log, and Settings buttons on the right. Logcat scan showed no app crash.
- [x] 2026-07-05: installed updated release APK on Boox device `a8f9bed9`, confirmed the toolbar uses the smaller feather logo and bold Ms Madi app name, tapped the logo to switch to immersive logo-only mode with no app name, buttons, or divider, then tapped again to restore the full toolbar. Logcat showed `toolbar mode: immersive` and `toolbar mode: full` with no app crash.
- [x] 2026-07-05: installed updated release APK on Boox device `a8f9bed9`, confirmed the toolbar app name is larger and visually centered lower against the feather logo baseline. Logcat scan showed no app crash.
- [x] 2026-07-05: installed updated release APK on Boox device `a8f9bed9`, tapped the Inkwell app name to enter immersive mode, then tapped the logo to restore the full toolbar. Logcat showed both toolbar mode transitions with no app crash.
- [x] 2026-07-05: captured `/tmp/inkwell-current-draw-boundary.png`, compared the raw drawing bounds flow against `onyx-intl/OnyxAndroidDemo`, then installed updated debug APK on Boox device `a8f9bed9`. Logcat showed `Onyx raw drawing attached, reset=true limit=0 0 1860 2349 excludes=[]` and `raw drawing limits refreshed, reset=false limit=0 0 1860 2349 excludes=[]`; crash scan showed no app fatal exception. Screenshot: `/tmp/inkwell-final-canvas-bounds.png`.
- [x] 2026-07-05: ran `./gradlew test assembleDebug connectedDebugAndroidTest --no-daemon` against Boox device `a8f9bed9`. The connected smoke test passed after making it set/restore onboarding state and launch the target app with an explicit intent.
- [x] 2026-07-05: captured BOOX Reader style settings reference at `/tmp/boox-reader-more-settings.png`, then installed updated debug APK on Boox device `a8f9bed9` and verified AI Settings uses Reader-style rows with label-left/value-right/chevron. Opened the Model row and confirmed a modal radio list. Screenshots: `/tmp/inkwell-ai-settings-row-ui.png`, `/tmp/inkwell-ai-model-modal.png`. Logcat scan showed no app fatal exception.
- [x] 2026-07-05: ran `./gradlew test assembleDebug --no-daemon`, installed updated debug APK on Boox device `a8f9bed9`, opened Settings -> AI Settings, and confirmed the screen uses one rounded card with no row separators, has no standalone validate-key button, shows API Key directly under Provider with a required warning when missing, and auto-opens the provider-specific API key prompt after switching to OpenAI without a saved key. Screenshots: `/tmp/inkwell-ai-required-card.png`, `/tmp/inkwell-ai-key-required-prompt.png`. `AndroidRuntime`/`FATAL EXCEPTION` logcat scan showed no app fatal exception.
- [x] 2026-07-05: ran `./gradlew test assembleDebug --no-daemon`, installed updated debug APK on Boox device `a8f9bed9`, and confirmed Recognition, Writing, Conversation Data, and About Inkwell use the same one-card row layout instead of stock spinners, loose buttons, or paragraph-only pages. Opened Recognition Language and confirmed a radio-list modal for the discrete language choice. Screenshots: `/tmp/inkwell-recognition-row-ui.png`, `/tmp/inkwell-recognition-language-modal.png`, `/tmp/inkwell-writing-row-ui.png`, `/tmp/inkwell-conversation-row-ui.png`, `/tmp/inkwell-about-row-ui.png`. `AndroidRuntime`/`FATAL EXCEPTION` logcat scan showed no app fatal exception.
- [x] 2026-07-05: captured BOOX Reader text-size slider reference at `/tmp/boox-current-text-size-slider-ref.png`, then ran `./gradlew test assembleDebug --no-daemon`, installed updated debug APK on Boox device `a8f9bed9`, and confirmed Writing Settings uses slider dialogs with minus/plus plus OK/Cancel for numeric controls instead of radio-list modals. Screenshots: `/tmp/inkwell-font-size-slider-dialog-final.png`, `/tmp/inkwell-commit-delay-slider-dialog-final.png`. `AndroidRuntime`/`FATAL EXCEPTION` logcat scan showed no app fatal exception.

On-device manual script. Execute on real Boox hardware; emulator cannot exercise TouchHelper or e-ink refresh.

1. [ ] **Cold start:** fresh install -> onboarding -> first reply. Time it. Pass: < 2 min.
2. [ ] **Ink feel:** write a full paragraph. Pass: no visible lag vs. Boox Notes; no stray marks in the settings-glyph corner.
3. [ ] **Recognition set:** write, one at a time, committing each: "hello", "What should I cook tonight?", "I've been feeling stuck on my startup lately", a 4-line multi-line note, and one deliberately sloppy sentence. Pass: at least 4/5 produce a sensible reply; sloppy case may trigger the can't-read hint.
4. [ ] **The illusion:** film the fade + reveal. Pass: fade completes < 2.5s, page is clean, reply writes word-by-word at reading pace, terminal full refresh leaves crisp text.
5. [ ] **Context:** write "My dog is named Biscuit." -> commit -> after reply, write "What breed do you think he is?" Pass: reply references Biscuit/the dog.
6. [ ] **Pagination:** switch persona to Scholar, ask for something long ("tell me about the history of ink"). Pass: page fills, tap advances, no text clipped.
7. [ ] **Failure - network:** airplane mode -> write -> commit. Pass: plain offline line appears; restoring network and retrying works.
8. [ ] **Failure - auth:** corrupt the key in Settings -> write. Pass: plain key message + actionable banner; fixing key recovers without restart.
9. [ ] **Endurance:** 20-turn conversation. Pass: no crash, no ANR, no ghosting accumulation, memory stable while watching logcat.
10. [ ] **Settings matrix:** change commit delay to 4s, switch persona, clear conversation. Pass: timing changes, tone changes next turn, pronoun no longer resolves after clearing.

Release gate:

- [ ] Attach the APK to the GitHub release.
- [ ] Attach the completed `verification.md`.
- [ ] Attach a 20-30s screen video of item 4.
- [ ] Do not release without the video.
