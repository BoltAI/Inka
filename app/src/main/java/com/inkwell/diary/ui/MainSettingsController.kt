package com.inkwell.diary.ui

import android.view.View
import android.widget.FrameLayout
import android.widget.LinearLayout
import androidx.lifecycle.LifecycleCoroutineScope
import com.inkwell.diary.brain.ConversationEngine
import com.inkwell.diary.data.Persona
import com.inkwell.diary.data.Prefs
import com.inkwell.diary.data.ReplyStyle
import com.inkwell.diary.data.newCanvasId
import com.inkwell.diary.ink.InkCaptureController
import com.inkwell.diary.page.HandwritingFont
import com.inkwell.diary.page.HandwritingFontWeight
import com.inkwell.diary.page.PageRenderer
import com.inkwell.diary.page.ReplyOverlayView
import com.inkwell.diary.recognize.RecognitionService

internal class MainSettingsController(
    private val activity: MainActivity,
    private val prefs: Prefs,
    private val engine: ConversationEngine,
    private val recognitionService: RecognitionService,
    private val scope: LifecycleCoroutineScope,
    private val renderer: PageRenderer,
    private val notebookController: MainNotebookController,
    private val root: () -> FrameLayout,
    private val topBar: () -> LinearLayout,
    private val replyOverlay: () -> ReplyOverlayView,
    private val hasPageSurface: () -> Boolean,
    private val captureController: () -> InkCaptureController?,
    private val hideDebugPanel: () -> Boolean,
    private val historyOpen: () -> Boolean,
    private val renderHistoryPage: () -> Unit,
    private val clearPage: () -> Unit,
    private val burnNotebook: () -> Unit,
    private val currentHandwritingFont: () -> HandwritingFont,
    private val startActiveNotebookLoad: () -> Unit,
    private val showDrawingModeHintIfNeeded: () -> Unit,
    private val renderTopBar: () -> Unit,
    private val refreshCaptureEnabled: () -> Unit,
    private val cancelFadeDisclosure: (clearPageLayer: Boolean) -> Unit,
    private val addDebug: (String) -> Unit,
) : SettingsPanel.Callbacks {
    private var panel: SettingsPanel? = null

    val isOpen: Boolean
        get() = panel != null

    fun openSettings() {
        if (panel != null) return
        cancelFadeDisclosure(true)
        captureController()?.setInputEnabled(false)
        topBar().visibility = View.INVISIBLE
        val settingsPanel = SettingsPanel(
            context = activity,
            prefs = prefs,
            engine = engine,
            recognitionService = recognitionService,
            scope = scope,
            callbacks = this,
        )
        panel = settingsPanel
        root().addView(
            settingsPanel,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT,
            ),
        )
    }

    fun handleBack(): Boolean {
        return panel?.handleBack() == true
    }

    override fun onCloseSettings() {
        panel?.let { settingsPanel ->
            root().removeView(settingsPanel)
        }
        panel = null
        topBar().visibility = View.VISIBLE
        if (historyOpen()) {
            renderHistoryPage()
        }
        refreshCaptureEnabled()
    }

    override fun onClearConversation() {
        clearPage()
    }

    override fun onBurnNotebook() {
        burnNotebook()
    }

    override fun onInkFadeStyleChanged() {
        renderer.setInkFadeStyle(prefs.inkFadeStyle)
        addDebug("ink animation: ${prefs.inkFadeStyle.label}")
    }

    override fun onHandwritingStyleChanged() {
        val font = currentHandwritingFont()
        renderer.setHandwritingStyle(font, prefs.handwritingFontSizeSp, prefs.handwritingFontWeight)
        if (hasPageSurface()) {
            replyOverlay().setHandwritingStyle(font, prefs.handwritingFontSizeSp, prefs.handwritingFontWeight)
        }
        val weight = HandwritingFontWeight.fromValue(prefs.handwritingFontWeight).label
        addDebug("handwriting style: ${font.label}, ${prefs.handwritingFontSizeSp.toInt()}sp, $weight")
    }

    override fun onToolbarSettingsChanged() {
        if (!prefs.showToolbarLogButton && hideDebugPanel()) {
            captureController()?.refreshLimits()
        }
        renderTopBar()
        addDebug("toolbar log button: ${if (prefs.showToolbarLogButton) "shown" else "hidden"}")
    }

    override fun onReplyStyleChanged() {
        renderTopBar()
        addDebug("reply style: ${prefs.replyStyle.label}, commit delay=${prefs.commitDelayMillis}ms")
        if (prefs.replyStyle == ReplyStyle.Drawing) {
            val notebook = notebookController.activeNotebook
            notebookController.activeCanvasId = notebookController.activeCanvasId
                ?: notebook?.let { NotebookCanvasProjector.latestCanvasId(it) }
                ?: newCanvasId(System.currentTimeMillis())
            if (notebook == null) {
                startActiveNotebookLoad()
            }
            showDrawingModeHintIfNeeded()
        }
    }

    override fun currentNotebookTitle(): String {
        return notebookController.currentNotebookTitle()
    }

    override fun currentNotebookPersona(): Persona {
        return notebookController.currentNotebookPersona()
    }

    override fun onNotebookTitleChanged(title: String) {
        notebookController.saveTitle(title)
    }

    override fun onNotebookPersonaChanged(persona: Persona) {
        notebookController.savePersona(persona)
    }
}
