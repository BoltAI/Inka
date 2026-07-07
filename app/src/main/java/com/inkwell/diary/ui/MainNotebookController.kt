package com.inkwell.diary.ui

import com.inkwell.diary.data.Notebook
import com.inkwell.diary.data.NotebookLoadResult
import com.inkwell.diary.data.NotebookStore
import com.inkwell.diary.data.Persona
import com.inkwell.diary.data.Prefs
import com.inkwell.diary.data.newCanvasId
import com.inkwell.diary.data.rebuildApiHistory
import com.inkwell.diary.brain.ConversationEngine
import com.inkwell.diary.page.PageRenderer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

internal class MainNotebookController(
    private val prefs: Prefs,
    private val engine: ConversationEngine,
    private val notebookStore: NotebookStore,
    private val scope: CoroutineScope,
    private val renderer: PageRenderer,
    private val addDebug: (String) -> Unit,
) {
    private var notebookLoadJob: Job? = null
    private val notebookLoadMutex = Mutex()
    var activeNotebook: Notebook? = null
        private set
    var activeCanvasId: String? = null
    var voiceChangedForNextRequest: Boolean = false

    fun startActiveNotebookLoad(
        renderOnComplete: Boolean,
        rendererInitialized: Boolean,
        pageSurfaceInitialized: Boolean,
        settingsPanelOpen: Boolean,
        historyOpen: Boolean,
        strokeStoreEmpty: Boolean,
        busy: Boolean,
    ) {
        if (activeNotebook != null || notebookLoadJob?.isActive == true) return
        notebookLoadJob = scope.launch {
            val notebook = loadActiveNotebook()
            if (
                shouldRenderAfterNotebookLoad(
                    renderOnComplete = renderOnComplete,
                    rendererInitialized = rendererInitialized,
                    pageSurfaceInitialized = pageSurfaceInitialized,
                    settingsPanelOpen = settingsPanelOpen,
                    historyOpen = historyOpen,
                    strokeStoreEmpty = strokeStoreEmpty,
                    busy = busy,
                )
            ) {
                val elements = NotebookCanvasProjector.canvasElements(notebook, activeCanvasId.orEmpty())
                if (prefs.replyStyle == com.inkwell.diary.data.ReplyStyle.Drawing && elements.isNotEmpty()) {
                    renderer.showNotebookElements(elements, fullRefresh = true)
                } else {
                    renderer.drawInitialHint()
                }
            }
        }
    }

    suspend fun loadActiveNotebook(): Notebook = notebookLoadMutex.withLock {
        activeNotebook?.let { return@withLock it }
        val result = notebookStore.loadOrCreateActive(prefs.activeNotebookId, prefs.persona)
        var notebook = when (result) {
            is NotebookLoadResult.Ready -> result.notebook
            is NotebookLoadResult.Recovered -> {
                addDebug("notebook recovered: ${result.detail.orEmpty()}")
                result.notebook
            }
        }
        if (!prefs.hasNormalizedBlankCustomPersona) {
            val loadedPersona = Persona.fromStoredName(notebook.personaId)
            if (loadedPersona == Persona.Custom && prefs.customPrompt.isBlank()) {
                notebook = notebook.withPersona(Persona.default, System.currentTimeMillis())
                scope.launch {
                    runCatching { notebookStore.save(notebook) }
                }
                addDebug("notebook persona normalized: Custom without prompt -> ${Persona.default.name}")
            }
            prefs.hasNormalizedBlankCustomPersona = true
        }
        setActiveNotebook(notebook)
        prefs.activeNotebookId = notebook.id
        prefs.persona = Persona.fromStoredName(notebook.personaId)
        activeCanvasId = NotebookCanvasProjector.latestCanvasId(notebook) ?: newCanvasId(System.currentTimeMillis())
        engine.replaceHistory(notebook.rebuildApiHistory())
        addDebug("notebook loaded: id=${notebook.id}, title=${notebook.title}, persona=${notebook.personaId}, exchanges=${notebook.exchanges.size}")
        notebook
    }

    fun setActiveNotebook(notebook: Notebook) {
        activeNotebook = notebook
    }

    fun currentNotebookTitle(): String {
        return activeNotebook?.title.orEmpty()
    }

    fun currentNotebookPersona(): Persona {
        return activeNotebook?.personaId?.let { Persona.fromStoredName(it) } ?: prefs.persona
    }

    fun currentCanvasElements(): List<com.inkwell.diary.data.NotebookElement> {
        val notebook = activeNotebook ?: return emptyList()
        val canvasId = activeCanvasId ?: NotebookCanvasProjector.latestCanvasId(notebook) ?: return emptyList()
        return NotebookCanvasProjector.canvasElements(notebook, canvasId)
    }

    fun currentInkElement(strokes: List<com.inkwell.diary.data.InkStroke>): com.inkwell.diary.data.InkElement {
        return NotebookCanvasProjector.currentInkElement(strokes, System.currentTimeMillis())
    }

    fun saveTitle(title: String) {
        scope.launch {
            val notebook = activeNotebook ?: loadActiveNotebook()
            val updated = notebook.withTitle(title, System.currentTimeMillis())
            setActiveNotebook(updated)
            notebookStore.save(updated)
            addDebug("notebook title saved: ${updated.title}")
        }
    }

    fun savePersona(persona: Persona) {
        scope.launch {
            val notebook = activeNotebook ?: loadActiveNotebook()
            val updated = notebook.withPersona(persona, System.currentTimeMillis())
            setActiveNotebook(updated)
            prefs.persona = persona
            voiceChangedForNextRequest = true
            engine.replaceHistory(updated.rebuildApiHistory())
            notebookStore.save(updated)
            addDebug("notebook persona saved: ${persona.label}")
        }
    }
}
