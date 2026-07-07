package com.inkwell.diary.ui

import android.content.Context
import android.view.SurfaceView
import android.view.View
import android.widget.FrameLayout
import com.inkwell.diary.data.Notebook
import com.inkwell.diary.data.Prefs
import com.inkwell.diary.data.ReplyStyle
import com.inkwell.diary.data.StrokeStore
import com.inkwell.diary.data.newCanvasId
import com.inkwell.diary.ink.CommitTimer
import com.inkwell.diary.ink.InkCaptureController
import com.inkwell.diary.page.EinkRefresher
import com.inkwell.diary.page.PageRenderer
import com.inkwell.diary.page.ReplyOverlayView
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

internal class MainHistoryController(
    private val context: Context,
    private val scope: CoroutineScope,
    private val prefs: Prefs,
    private val renderer: PageRenderer,
    private val strokeStore: StrokeStore,
    private val replyOverlay: () -> ReplyOverlayView,
    private val surfaceView: () -> SurfaceView,
    private val root: () -> FrameLayout,
    private val einkRefresher: EinkRefresher,
    private val captureController: () -> InkCaptureController?,
    private val commitTimer: () -> CommitTimer?,
    private val alertDialogs: MainAlertDialogs,
    private val activeNotebook: () -> Notebook?,
    private val loadActiveNotebook: suspend () -> Notebook,
    private val currentCanvasElements: () -> List<com.inkwell.diary.data.NotebookElement>,
    private val setActiveCanvasId: (String) -> Unit,
    private val cancelPromptFade: () -> Unit,
    private val cancelFadeDisclosure: () -> Unit,
    private val renderTopBar: () -> Unit,
    private val refreshCaptureEnabled: () -> Unit,
    private val showDrawingModeHintIfNeeded: () -> Unit,
    private val busy: () -> Boolean,
    private val setStatus: (String) -> Unit,
    private val addDebug: (String) -> Unit,
) {
    private val pager = HistoryPager()

    val isOpen: Boolean
        get() = pager.isOpen

    fun renderHistoryPage(fullRefresh: Boolean = true) {
        if (!pager.isOpen) return
        val page = pager.currentPage ?: return
        renderer.renderNotebookPage(
            page = page,
            pageIndex = pager.pageIndex,
            pageCount = pager.pageCount,
            showPageStatus = pager.pageCount > 1,
            pageStatus = "History",
            fullRefresh = fullRefresh,
            showContinuationMark = pager.hasNextPage,
        )
    }

    fun openHistory(showEmptyWarning: Boolean = false) {
        val notebook = activeNotebook()
        if (notebook == null) {
            setStatus("Loading notebook")
            scope.launch {
                openHistory(loadActiveNotebook(), showEmptyWarning)
            }
            return
        }
        openHistory(notebook, showEmptyWarning)
    }

    fun openHistory(notebook: Notebook, showEmptyWarning: Boolean) {
        if (notebook.exchanges.isEmpty()) {
            addDebug("history empty")
            if (showEmptyWarning) {
                alertDialogs.showWarning(
                    title = "Nothing to read yet",
                    message = "Write something first, then come back to read the notebook.",
                )
            }
            return
        }
        cancelFadeDisclosure()
        val pages = renderer.historyPagesFor(notebook)
        pager.open(pages)
        cancelPromptFade()
        replyOverlay().clearReply()
        renderTopBar()
        captureController()?.setReadOnlyInputEnabled(true)
        renderHistoryPage(fullRefresh = true)
        setStatus("History")
        addDebug("history opened: pages=${pager.pageCount}, exchanges=${notebook.exchanges.size}")
    }

    fun closeHistory() {
        cancelFadeDisclosure()
        pager.close()
        renderTopBar()
        captureController()?.clearRawInkLayer()
        if (prefs.replyStyle == ReplyStyle.Drawing) {
            val elements = currentCanvasElements()
            if (elements.isNotEmpty()) {
                renderer.showNotebookElements(elements, fullRefresh = true)
            } else {
                renderer.clear()
                renderer.drawInitialHint()
            }
        } else {
            renderer.clear()
            renderer.drawInitialHint()
        }
        refreshCaptureEnabled()
        setStatus("Idle")
        addDebug("history closed")
    }

    fun resetHistoryState() {
        pager.close()
    }

    fun handleHistoryTap(x: Float): Boolean {
        val width = surfaceView().width.takeIf { it > 0 } ?: return false
        val hotZone = context.dp(PAGE_TURN_HOT_ZONE_DP).toFloat()
        return when {
            x <= hotZone -> {
                turnHistoryPage(-1)
                true
            }
            x >= width - hotZone -> {
                turnHistoryPage(1)
                true
            }
            else -> false
        }
    }

    fun turnHistoryPage(delta: Int) {
        if (busy()) return
        if (!pager.isOpen) return
        cancelFadeDisclosure()
        if (!pager.turn(delta)) return
        renderHistoryPage(fullRefresh = true)
        addDebug("history page ${pager.pageIndex + 1}/${pager.pageCount}")
    }

    fun handleDrawingPageTurnTap(x: Float): Boolean {
        val width = surfaceView().width.takeIf { it > 0 } ?: return false
        val hotZone = context.dp(PAGE_TURN_HOT_ZONE_DP).toFloat()
        if (x < width - hotZone) return false
        startFreshDrawingCanvas()
        return true
    }

    fun startFreshDrawingCanvas() {
        if (busy() || pager.isOpen) return
        val canvasId = newCanvasId(System.currentTimeMillis())
        setActiveCanvasId(canvasId)
        cancelPromptFade()
        commitTimer()?.cancel()
        strokeStore.clear()
        replyOverlay().clearReply()
        captureController()?.clearRawInkLayer()
        renderer.clear()
        renderer.drawInitialHint()
        showDrawingModeHintIfNeeded()
        setStatus("New canvas")
        addDebug("drawing canvas started: $canvasId")
        root().post { einkRefresher.requestFullRefresh(root()) }
    }

    fun handleToolbarRead() {
        if (pager.isOpen) {
            closeHistory()
        } else {
            openHistory(showEmptyWarning = true)
        }
    }

    private companion object {
        private const val PAGE_TURN_HOT_ZONE_DP = 48
    }
}
