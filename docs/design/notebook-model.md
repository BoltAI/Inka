# Notebook Model

Inka uses one active notebook. The notebook is always persisted, and fade is only a live-page presentation behavior.

## Intent

The live page keeps the original illusion: the user writes with the BOOX raw pen layer, the ink dissolves or fades, and the diary replies in a handwritten voice. Persistence happens behind that illusion. Every recognized exchange is stored locally, even when the live ink disappears from the page.

After the first successful fade, the app may disclose this once:

> The ink fades from the page, but the diary keeps every word. Flip back anytime.

## Data Model

The active notebook is stored as JSON in app-private storage:

```text
filesDir/notebooks/{notebookId}.json
Prefs.activeNotebookId
```

Default notebook:

```text
id: default
title: Inka's Diary
schemaVersion: 3
```

Schema shape:

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
  canvasId: String?,
  ink: NotebookInk?,
  reply: NotebookReply?
}

NotebookInk {
  strokes: List<InkStroke>,
  recognizedText
}

NotebookReply {
  text?,
  sketch: NotebookSketch?,
  personaId,
  createdAt
}

NotebookSketch {
  strokes: List<InkStroke>
}
```

Write policy:

- Save after recognition and before the provider request. If the provider request fails, the exchange remains without a reply.
- Save again after a successful reply by updating the same exchange.
- Save on `onStop` as a defensive flush.
- Store writes run off the ink path.
- Unsupported or corrupt notebook files are renamed to `{id}.json.damaged`, then a fresh default notebook is created.

## Live Page

- Live pen strokes stay on the BOOX raw drawing layer until the prompt fade begins.
- On commit: recognize, persist exchange, dissolve or fade prompt, then stream or reveal the reply.
- `Turns to dust` is the primary committed-ink transition. `Simply fades` is the fallback for panels or firmware that smear too much.
- Missing API keys, provider failures, network failures, and unrecoverable errors are modal warnings. They are not written inline on the paper.
- The toolbar eraser clears only the current live page or draft state. Burning the persisted notebook is a destructive action with confirmation.

## History

History is a separate read-only screen generated from notebook exchanges.

- Enter history with the top toolbar read button.
- History replaces the main toolbar with screen chrome: Back and `History` on the left, and a single `Burn notebook` action on the right.
- Back returns to the live writing page.
- `Burn notebook` shows a destructive confirmation before deleting the active notebook.
- Historical ink and replies use the saved-ink renderer.
- Exchanges with the same `canvasId` composite onto one history page in commit order.
- Long replies split across rendered history pages when needed.
- Pen input is disabled in history.

## API Context

Before each request, rebuild provider history from the active notebook:

- User turns come from nonblank `exchange.ink.recognizedText`.
- Assistant turns come from nonblank `exchange.reply.text`.
- Exchanges without replies still contribute the user turn and skip the assistant turn.
- Cap to the last 20 turns.
- Persona switch mutates notebook metadata only. The next request gets a system note: `The voice of the diary has changed.`

## Developer Drawing Replies

Drawing replies are experimental and belong behind Developer settings.

- `AI answer mode` can be `Text only` or `Drawing`.
- Drawing mode should keep the user canvas intact and add diary-owned sketch strokes.
- History composites related drawing exchanges onto the same canvas.
- If the active provider or model cannot support the drawing path, show a modal warning and keep the page writable.
