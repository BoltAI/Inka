# Feature Spec: One Notebook, Always Persisted

This document supersedes the older "Manuscript Mode" plan. The app no longer has separate diary modes, per-persona mode defaults, or an "ink remains" setting. There is one active notebook, it is always persisted, and fading is only how the live page is presented.

## Product Intent

The live page keeps the original v1 illusion: the user writes with the BOOX raw pen layer, their ink fades, and the diary reply writes back in script. The difference is persistence. Every recognized user exchange is stored in the notebook even though the ink visually fades from the live page.

After the first successful fade, show this disclosure once:

> The ink fades from the page, but the diary keeps every word. Flip back anytime.

## Data Model

Store one JSON file for the active notebook:

```text
filesDir/notebooks/{notebookId}.json
Prefs.activeNotebookId
```

Default notebook:

```text
id: default
title: Inka's Diary
schemaVersion: 2
```

Schema v2:

```kotlin
Notebook {
  id,
  title,
  personaId,
  createdAt,
  updatedAt,
  schemaVersion,
  exchanges: List<Exchange>
}

Exchange {
  id,
  committedAt,
  ink: NotebookInk?,
  reply: NotebookReply?
}

NotebookInk {
  strokes: List<InkStroke>,
  recognizedText
}

NotebookReply {
  text,
  personaId,
  createdAt
}
```

Write policy:

- Save after recognition, before the provider request. If the provider request fails, the exchange remains with `reply = null`.
- Save again after a successful reply by updating the same exchange.
- Save on `onStop` as a defensive flush.
- Do not block the ink thread; store writes run off the main path.
- Unsupported or corrupt notebook files are renamed to `{id}.json.damaged`, then a fresh default notebook is created.
- If no active notebook id exists, migrate the latest v1 persona-page notebook into schema v2 and leave all old files untouched.

## Live Page Behavior

- All personas use the v1 Fade live-writing behavior.
- Live pen strokes must stay on the BOOX raw drawing layer until the prompt fade begins. Do not replace the visible live ink with replayed bitmap strokes before fade.
- On commit: recognize -> persist exchange -> dissolve/fade prompt -> stream/reveal reply.
- The default committed-ink transition is `Turns to dust`: a longer left-to-right stochastic dissolve where ink particles get carried mostly rightward by wind, lift slightly upward, leave short ash streaks, then land on a final full refresh. The fallback `Simply fades` keeps the v1 stepped-opacity fade.
- The dissolve must preserve the illusion that wind peels ink off the page: the app first renders a full original-ink handoff frame, cells not yet reached by the sweep stay in their original positions, and only active cells move away from their original ink pixels.
- The wind dissolve parameters live in `DissolveConfig`; keep that data shape platform-neutral so a future iOS build can mirror the same animation curve and timings.
- The dust dissolve applies only to the user's committed ink. Reply fade-on-pen-down, disclosure text, and hint fades stay as quick stepped fades.
- Missing API keys, provider failures, network failures, and unrecoverable errors are modal warnings. They are not written inline on the paper.
- The toolbar eraser clears only the current live page/draft state. Burning the persisted notebook is a Settings action with confirmation.

## History View

History is a separate read-only screen generated from `Notebook.exchanges`.

- Enter history with the top toolbar read button or with a right swipe from the live page.
- History replaces the main toolbar with screen chrome: back button and `History` title on the left, and a single `Burn notebook` action on the right.
- Back is the explicit way to return to the live writing page. Swiping past the last history page must not silently leave History.
- `Burn notebook` shows a destructive confirmation before deleting the active notebook.
- Render historical ink/replies with the saved-ink renderer.
- Split long replies across rendered history pages when needed.
- Swipe/tap forward and backward through history pages.
- Pen input in history is rejected and should show: `Return to the page to write.`
- Do not let history become an editable notebook surface.

## API Context

Before each request, rebuild provider history from the active notebook:

- User turns come from nonblank `exchange.ink.recognizedText`.
- Assistant turns come from nonblank `exchange.reply.text`.
- Exchanges without replies still contribute the user turn and skip the assistant turn.
- Cap to the last 20 turns.
- Persona switch mutates notebook metadata only. The next request gets a system note: `The voice of the diary has changed.`

## Settings

Top-level Settings contains `Notebook` and `Persona` rows.

Notebook detail screen:

- Title row.
- `How the ink fades` row: `Turns to dust` by default, with `Simply fades` as the fallback for panels or firmware that smear too much.
- Burn this notebook row with confirmation.
- The sentence: `Everything you write is stored on this device until you burn the notebook.`

Persona detail screen:

- Persona picker with descriptions and radio buttons.
- Custom prompt row appears only when `Custom` is selected.

There is no Writing Mode row and no per-persona mode override.

## Verification

Automated coverage should include:

- Schema v2 round trip, 10k-point strokes, and damaged-file recovery.
- v1 migration into schema v2 without deleting old files.
- API history ordering, 20-turn cap, and unanswered exchanges.
- History page projection and long-reply splitting.
- Prefs for active notebook id and one-time fade disclosure.
- Android smoke: onboarding launch and dense active notebook launch.

Manual BOOX checks still matter:

- Live writing quality must match the original Fade behavior.
- Prompt must not switch to replayed bitmap strokes before fading.
- After writing with no API key or offline provider failure, the prompt still fades and the app remains writable.
- History is readable and returns to the live page.
- Burn deletes the active notebook only after confirmation.
