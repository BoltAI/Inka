## Project Scope

- This repository builds Inka, a sideloaded Android app for Boox e-ink tablets.
- Implement the product from `riddle-spec.md` as a native Kotlin Android app using classic Views, not Compose.
- The current product model is one active notebook that is always persisted. Do not reintroduce separate Fade/Manuscript modes, per-persona notebook files, or per-persona mode defaults; fade is a live-page presentation behavior only.
- Keep app strings neutral. Do not add Harry Potter references to user-facing copy, marketing copy, package names, or release text.

## Build And Verification

- Primary verification command: `./gradlew test assembleRelease`.
- Run focused tests while iterating, then run the primary verification before handing off.
- Boox pen latency, TouchHelper behavior, and e-ink refresh quality cannot be proven on a standard emulator. Keep emulator/fallback paths buildable, but record real-device checks in `verification.md`.
- Do not stage or commit changes unless the user explicitly asks.

## Android Constraints

- Keep `minSdk` no higher than 29.
- Keep Onyx SDK usage isolated behind `InkCaptureController` and `EinkRefresher` so hardware-specific behavior is contained.
- Use `EncryptedSharedPreferences` for provider API keys. Never log keys or include real keys in test fixtures.
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
