## Project Scope

- This repository builds Inka, a sideloaded Android app for BOOX e-ink tablets.
- Use native Kotlin Android with classic Views. Do not migrate UI to Compose unless the project is intentionally redesigned.
- The product model is one active notebook that is always persisted. Fade is a live-page presentation behavior, not a separate storage mode.
- Keep user-facing strings neutral and public-ready. Do not add internal codenames, private specs, or joke references to app copy, package names, docs, or release text.
- Keep design notes under `docs/design/`; do not put draft specs or verification logs in the repository root.

## Build And Verification

- Primary verification command: `./gradlew test assembleRelease`.
- For a broader local check, run `./gradlew test assembleRelease assembleDebugAndroidTest`.
- For a connected BOOX tablet, run `ANDROID_SERIAL=<device-id> scripts/boox-smoke.sh`.
- Keep production release artifacts limited to `arm64-v8a` and `armeabi-v7a`. `onyxsdk-pen` transitively includes `com.tencent:mmkv:1.0.19`; its x86_64 native library is not 16 KB ELF-aligned. Keep x86 ABIs debug-only unless the Onyx dependency or MMKV build is replaced with a verified 16 KB-compatible version.
- Before uploading an APK to Google Play, run `scripts/build-release.sh`, check it with Android build-tools `zipalign -c -P 16 -v 4`, and verify that every packaged 64-bit native library has ELF `LOAD` alignment of at least `0x4000`.
- BOOX pen latency, TouchHelper behavior, and e-ink refresh quality cannot be proven on a standard emulator. Keep emulator and fallback paths buildable, but verify pen behavior on real hardware before calling a pen-change done.
- Do not stage or commit changes unless the user explicitly asks.

## Android Constraints

- Keep `minSdk` no higher than 29.
- Keep Onyx SDK usage isolated behind `InkCaptureController` and `EinkRefresher` so hardware-specific behavior stays contained.
- Use encrypted Android preferences for provider API keys when available. Never log keys or include real keys in test fixtures.
- No analytics, telemetry, accounts, backend, or crash reporting in v1.

## Settings Screen UX

- Match the BOOX Reader/Notes settings pattern: one rounded white card for the whole list, no internal row separators, label on the left, value/control on the right.
- Build settings screens with `SettingsPanel` helpers such as `groupedList`, `addTopicRow`, `addChoiceRow`, and `addToggleRow`; do not introduce ad hoc card stacks or inline form grids.
- Use topic rows to navigate into focused detail screens instead of putting every control on the root settings page.
- Use choice rows with value + chevron that open a modal radio list for provider/model/persona/font-style choices. Save the selected value immediately after selection.
- Do not use radio-list modals for numeric tuning. Numeric settings such as font size and commit delay should use a BOOX-style slider dialog with minus/plus controls plus OK/Cancel.
- Use text-input rows that open a dialog for long or sensitive values. API key rows must be visibly required when missing, must sit directly under Provider in AI Settings, and must validate immediately after saving.
- When switching AI provider, if that provider has no saved API key, immediately open the provider-specific API key prompt.
- Do not add standalone "validate key" or "save settings" buttons where the row interaction can save or validate immediately.
- Toggle rows should follow the Developer screen pattern: label and explanation on the left, simple ON/OFF toggle on the right.

## Credential Handling

- Do not search the filesystem for API keys, private keys, tokens, `.p8` files, auth files, or other credentials.
- Prefer project-provided CLIs, authenticated connectors, keychain-backed tools, or official SDK workflows for services such as App Store Connect, RevenueCat, GitHub, and cloud providers.
- Only inspect credential files or search credential paths when the user explicitly names the repository, file, or directory to inspect for that purpose.
