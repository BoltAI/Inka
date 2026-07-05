# Feature Spec: Manuscript Mode (persistent pages)

**Agent brief.** You are extending the existing app (see `riddle-spec.md`, the v1 spec — read it first; all its conventions, module names, and constraints still apply). This spec adds a second interaction mode and the persistence layer it requires. Where this spec contradicts v1, this spec wins. Do not break Fade mode — it is the flagship demo and must remain pixel-identical to v1 behavior.

**Current implementation note.** Product decisions made during device iteration override the older page-turning notes below: Manuscript pages are editable when browsing backward, replies are same-page-only, and overflow/API failures use modal alerts instead of inline page text.

---

## 1. Problem & intent

In v1, every exchange is ephemeral: the user's ink fades, the reply appears, and the reply is wiped when the user writes again. That is perfect theater for The Whisper, but it makes the app a *conversation*. For every other persona the user isn't summoning a spirit — they are **co-authoring a document**: a story with the Storyteller, a brainstorm with the Muse, an inquiry with Socrates, notes with the Scholar.

**Manuscript Mode** makes the page persistent: the user's ink stays, the AI's reply is written *below* it in its own hand, and the two keep filling pages together, forever. The product goal is that holding the device feels like holding a notebook that you and the machine are writing together — the shared artifact IS the relationship.

Two modes, one metaphor each:

| | **Fade** ("the ink fades") | **Manuscript** ("the ink remains") |
|---|---|---|
| User's ink after reply | dissolves (v1 behavior) | stays forever |
| AI reply | replaces the page | appended below the user's ink |
| Page lifetime | one exchange | grows into a multi-page notebook |
| Persistence | none (memory only, as v1) | saved to disk, survives restarts |
| Default for | The Whisper | all other personas |

## 2. Mode selection & settings

- Each persona has a **default mode**: Whisper → Fade; Companion, Storyteller, Scholar, Muse, Socrates, Wit, and Custom → Manuscript.
- Per-persona **override** in Settings, presented in-fiction as a two-option choice: **"The ink fades" / "The ink remains."** Persist the override per persona (Prefs). No global toggle.
- Switching persona switches to that persona's mode and (in Manuscript) to that persona's own notebook. Mode never changes mid-page silently; it changes only via persona switch or the setting.

## 3. Data model & persistence

New in `data/`:

```
Notebook   { id, personaId, createdAt, updatedAt, schemaVersion, pages: [Page] }
Page       { index, elements: [Element] }
Element    = InkElement  { strokes: [Stroke], committedAt, recognizedText }
           | ReplyElement{ text, personaId, createdAt }
Stroke     { points: [{x, y, pressure, t}] }
```

- **One notebook per persona** (Manuscript personas only). Whisper/Fade personas persist nothing, exactly as v1.
- Storage: one JSON file per notebook in `filesDir/notebooks/{personaId}.json` (kotlinx.serialization). No Room/SQLite — flat files are sufficient, debuggable, and trivially exportable later. Include `schemaVersion: 1` and refuse-don't-crash on unknown versions.
- Write policy: serialize after every committed exchange (user commit + reply completion) and in `onStop`. Writes on `Dispatchers.IO`; never block the ink thread.
- `recognizedText` is stored with the ink so history can be rebuilt for the API without re-running recognition.
- **API context:** conversation history sent to the model is rebuilt from the notebook's elements in order (recognizedText as user turns, reply text as assistant turns), still capped at the last 20 turns per v1 §5.5. The page keeps everything visually; the model's memory window is separate and that divergence is acceptable and intentional.
- "Clear conversation" in Settings becomes, for Manuscript personas, **"Burn this notebook"** with a confirm dialog; it deletes the file and starts a fresh page. For Fade personas it keeps its v1 behavior.
- Missing API keys, provider/network/server failures, and same-page reply overflow are modal warnings, not inline writing on the paper.

## 4. Interaction spec

### 4.1 Writing (unchanged mechanics, new canvas rules)

- The user can write on any page of the active notebook. This is intentionally different from the original read-only browsing idea.
- Commit gesture identical to v1 (pen idle 2.0s or double-tap). On commit in Manuscript mode: recognize → **no fade** → reply appended if it fits on that same page.
- Do not render a thinking ellipsis or inline "write here" hint on the paper.

### 4.2 The reply — the second hand

- Reply text is laid out starting ~24dp below the lowest point of the user's latest committed ink, full text width, in the script font per v1 §4.5, word-by-word reveal unchanged.
- **Distinguish the two hands.** The AI's reply should read at a glance as "someone else wrote this" through the handwriting font, size, and weight. Current product decision: both user ink and diary ink are black; do not expose a diary-ink color setting until there is a real drawing/color feature.
- After the reveal completes: one full refresh (v1 refresh policy applies).

### 4.3 Page turning — a notebook, not a scroll

Never scroll. E-ink scrolling means full-screen repaints per frame; the metaphor and the hardware both say **pages**.

- **Page-full while user writes:** the user simply runs out of room and turns the page themselves — finger swipe left (or tap the right edge, 48dp hot zone) → full refresh → fresh page. Strokes committed after a turn belong to the new page. If the commit timer fires while strokes exist on both sides of a turn (user continued a sentence across pages), gather all uncommitted strokes in page order, recognize per page, and join the text with a space — one user message.
- **Page-full during a reply:** if the next character would cross the bottom margin, do not continue on another page and do not persist partial assistant text. Restore the page, show a modal warning, and let the user choose a page with enough room.
- **Browsing:** swipe right / tap left edge pages backward. Older pages stay editable; raw drawing remains enabled on the visible page. Swiping forward from a non-empty last page creates a fresh page. Swiping forward from an already blank last page is a no-op.
- **Page indicator:** when a notebook has more than one page, show bottom-left status and bottom-center page number. There should be no right-side continuation artifact.
- Every page turn = one GC16 full refresh. Rebuild the page bitmap from the Page's elements (re-render strokes + laid-out reply text). Cache the rendered bitmap of the two adjacent pages to keep turns snappy.

### 4.4 Reopening the app

- Launch restores the last-used persona; Manuscript personas open **on their last page**, fully rendered, ready to write. Cold-start-to-writable target: < 3s on device.
- First-ever open of a Manuscript persona: blank page 1, with no inline hint text on the paper.

## 5. What does NOT change

- Fade mode remains the v1 ephemeral writing experience, including no persistence. API/setup failures now use modal warnings per the current product decision.
- Ink capture pipeline, recognition, ConversationEngine transport, onboarding, BYOK.
- Recognition remains strokes→text only. Users WILL annotate old text with arrows and circles on persistent pages; the model cannot see that yet. Out of scope (this is the v2 vision-snapshot feature; leave a `// V2:` comment where the page bitmap is built).
- Export/share of notebooks: out of scope (note as v-next in README).

## 6. Implementation plan

1. **M1 — Model & store:** data classes, JSON round-trip, NotebookStore with load/save/burn; unit tests (serialization round-trip incl. 10k-point strokes, schemaVersion rejection, turn-capping rebuild of API history).
2. **M2 — Page renderer:** render a Page's elements to bitmap (strokes + reply layout); page cache; reply-position calculation (below lowest ink); two-hands styling through font, size, and weight.
3. **M3 — Notebook navigation:** page turns (swipe + edge taps), editable browsing, bottom status/page indicator, and same-page reply overflow alert.
4. **M4 — Mode wiring:** persona→mode defaults, per-persona override UI ("the ink fades / the ink remains"), Burn this notebook, persistence hooks (post-exchange + onStop), launch restore.
5. **M5 — Regression pass on Fade mode** against the v1 verification script §9.2 — all items must still pass.

## 7. Definition of done

1. All v1 DoD items still hold; v1 verification script passes unchanged in Fade mode.
2. In Manuscript mode: a 15-exchange session with the Storyteller produces a multi-page notebook; kill the app process; relaunch → same persona, same last page, all ink and replies intact, and the next reply demonstrates context from before the restart (pronoun test).
3. User ink and AI reply text are visually distinct at arm's length through handwriting style; both remain black.
4. Manual page turn, same-page reply overflow warning (force with a Scholar long answer), and backward browsing all work with clean full refreshes and no ghost content from neighboring pages.
5. Writing on an older page is allowed; strokes save/replay on that page and replies appear on that page only if they fit.
6. A sentence written across a page turn commits as one message (verify recognized text in logcat).
7. "Burn this notebook" deletes the file (verify on disk), returns to a blank page 1, and the next exchange has no prior context.
8. Persona switch swaps notebooks with no cross-contamination: Storyteller's notebook never shows Muse's pages, and API history never mixes personas.
9. No ANR/jank while saving: write a stress page (dense sketch, 5k+ points), commit, and confirm the ink thread never stalls (systrace or frame callback logging).
10. `notebooks/*.json` files are human-readable and include schemaVersion.

## 8. On-device verification additions (append to verification.md)

1. **Restart integrity:** 5 exchanges (Storyteller) → force-stop app → relaunch. Pass: last page rendered < 3s, follow-up reply references earlier story detail.
2. **Two hands:** photograph a page with both inks. Pass: a stranger can tell who wrote what.
3. **Marathon:** 30 exchanges in one Muse session across ≥ 6 pages. Pass: no crash, page turns stay < 1.5s, JSON file loads on restart.
4. **Cross-page sentence:** start a sentence at page bottom, turn, finish it, let commit fire. Pass: one coherent message in logcat and one reply.
5. **Same-page fit guard:** Scholar, "tell me about the Roman Empire in six sentences." Pass: if the answer cannot fit on the current page, no assistant text is saved, no new page is created, and a modal warning appears.
6. **Burn:** burn the Wit's notebook. Pass: file gone, page blank, Wit remembers nothing; Storyteller's notebook untouched.
7. **Mode override:** set Whisper to "the ink remains," exchange twice. Pass: Whisper now appends and persists like any Manuscript persona; revert works.
8. **Fade regression:** rerun v1 §9.2 items 3, 4, 9 in Fade mode. Pass: identical to v1.

### Release gate addition

Ship with a second demo video: a hand flipping back through 4–5 filled pages of a Storyteller notebook, then writing the next line on the last page and the diary hand answering below it. The manuscript is the product story of this release; if flipping through the notebook doesn't make you want one, it isn't done.
