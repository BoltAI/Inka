package com.inkwell.diary.ui

import com.inkwell.diary.brain.ConversationEngine
import com.inkwell.diary.data.NotebookStore
import com.inkwell.diary.data.Persona
import com.inkwell.diary.data.Prefs
import com.inkwell.diary.data.ReplyStyle
import com.inkwell.diary.data.StrokeStore
import com.inkwell.diary.data.newCanvasId
import com.inkwell.diary.ink.CommitTimer
import com.inkwell.diary.ink.InkCaptureController
import com.inkwell.diary.page.PageRenderer
import com.inkwell.diary.page.ReplyOverlayView
import com.inkwell.diary.page.dissolveConfig
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

internal class MainNotebookActionsController(
    private val prefs: Prefs,
    private val engine: ConversationEngine,
    private val notebookStore: NotebookStore,
    private val renderer: PageRenderer,
    private val strokeStore: StrokeStore,
    private val scope: CoroutineScope,
    private val notebookController: MainNotebookController,
    private val historyController: MainHistoryController,
    private val captureController: () -> InkCaptureController?,
    private val commitTimer: () -> CommitTimer?,
    private val replyOverlay: () -> ReplyOverlayView,
    private val settingsOpen: () -> Boolean,
    private val cancelCommitJob: () -> Unit,
    private val cancelFadeDisclosure: () -> Unit,
    private val cancelPromptFade: () -> Unit,
    private val setBusy: (Boolean) -> Unit,
    private val renderTopBar: () -> Unit,
    private val refreshCaptureEnabled: () -> Unit,
    private val setStatus: (String) -> Unit,
    private val addDebug: (String) -> Unit,
) {
    fun clearPage() {
        cancelFadeDisclosure()
        cancelPromptFade()
        commitTimer()?.cancel()
        cancelCommitJob()
        setBusy(false)
        if (!settingsOpen()) {
            refreshCaptureEnabled()
        }
        captureController()?.clearRawInkLayer()
        strokeStore.clear()
        replyOverlay().clearReply()
        if (prefs.replyStyle == ReplyStyle.Drawing) {
            notebookController.activeCanvasId = newCanvasId(System.currentTimeMillis())
        }
        renderer.clear()
        renderer.drawInitialHint()
        setStatus("Idle")
        addDebug("page cleared")
    }

    fun burnNotebook(dissolveHistoryPage: Boolean) {
        cancelFadeDisclosure()
        cancelPromptFade()
        commitTimer()?.cancel()
        cancelCommitJob()
        val shouldDissolveHistory = dissolveHistoryPage && historyController.isOpen
        setBusy(shouldDissolveHistory)
        captureController()?.clearRawInkLayer()
        strokeStore.clear()
        replyOverlay().clearReply()
        scope.launch {
            try {
                if (shouldDissolveHistory) {
                    renderer.setDissolveConfig(burnDissolveConfig())
                    renderer.dissolveCurrentPage()
                }
                val current = notebookController.activeNotebook
                val fresh = notebookStore.burn(current?.id.orEmpty(), prefs.persona)
                notebookController.setActiveNotebook(fresh)
                notebookController.activeCanvasId = newCanvasId(System.currentTimeMillis())
                prefs.activeNotebookId = fresh.id
                prefs.persona = Persona.fromStoredName(fresh.personaId)
                historyController.resetHistoryState()
                engine.clearHistory()
                renderTopBar()
                renderer.clear()
                renderer.drawInitialHint()
                refreshCaptureEnabled()
                setStatus("Idle")
                addDebug("notebook burned")
            } finally {
                setBusy(false)
            }
        }
    }

    private fun burnDissolveConfig() = prefs.dissolveConfig().copy(
        sweepMs = prefs.dissolveSweepMs.coerceAtMost(BURN_DISSOLVE_SWEEP_MS),
        cellLifeMs = prefs.dissolveCellLifeMs.coerceAtMost(BURN_DISSOLVE_CELL_LIFE_MS),
        frameMs = BURN_DISSOLVE_FRAME_MS,
        delayJitterMs = BURN_DISSOLVE_DELAY_JITTER_MS,
        terminalHoldMs = BURN_DISSOLVE_TERMINAL_HOLD_MS,
        maxTotalMs = BURN_DISSOLVE_MAX_TOTAL_MS,
    )

    private companion object {
        private const val BURN_DISSOLVE_SWEEP_MS = 560L
        private const val BURN_DISSOLVE_CELL_LIFE_MS = 360L
        private const val BURN_DISSOLVE_FRAME_MS = 70L
        private const val BURN_DISSOLVE_DELAY_JITTER_MS = 60L
        private const val BURN_DISSOLVE_TERMINAL_HOLD_MS = 60L
        private const val BURN_DISSOLVE_MAX_TOTAL_MS = 1200L
    }
}
