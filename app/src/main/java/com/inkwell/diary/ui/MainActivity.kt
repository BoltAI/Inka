package com.inkwell.diary.ui

import android.app.AlertDialog
import android.content.Intent
import android.graphics.Color
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import android.util.Log
import android.view.Gravity
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.view.View
import android.view.Window
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.activity.OnBackPressedCallback
import androidx.activity.ComponentActivity
import androidx.lifecycle.lifecycleScope
import com.inkwell.diary.BuildConfig
import com.inkwell.diary.R
import com.inkwell.diary.brain.AnthropicResult
import com.inkwell.diary.brain.BrainErrorKind
import com.inkwell.diary.brain.ConversationEngine
import com.inkwell.diary.brain.ConversationSettings
import com.inkwell.diary.brain.DiaryError
import com.inkwell.diary.brain.DrawingReplyResult
import com.inkwell.diary.brain.ErrorMapper
import com.inkwell.diary.data.AiProvider
import com.inkwell.diary.data.Exchange
import com.inkwell.diary.data.InkMessage
import com.inkwell.diary.data.InkStroke
import com.inkwell.diary.data.Notebook
import com.inkwell.diary.data.InkElement
import com.inkwell.diary.data.NotebookInk
import com.inkwell.diary.data.NotebookLoadResult
import com.inkwell.diary.data.NotebookPage
import com.inkwell.diary.data.NotebookReply
import com.inkwell.diary.data.NotebookSketch
import com.inkwell.diary.data.NotebookStore
import com.inkwell.diary.data.Persona
import com.inkwell.diary.data.Prefs
import com.inkwell.diary.data.ReplyElement
import com.inkwell.diary.data.ReplyStyle
import com.inkwell.diary.data.SketchElement
import com.inkwell.diary.data.StrokeStore
import com.inkwell.diary.data.newCanvasId
import com.inkwell.diary.data.newExchangeId
import com.inkwell.diary.data.rebuildApiHistory
import com.inkwell.diary.ink.CommitTimer
import com.inkwell.diary.ink.CoroutineCommitScheduler
import com.inkwell.diary.ink.InkCaptureController
import com.inkwell.diary.page.DissolveLabStore
import com.inkwell.diary.page.EinkRefresher
import com.inkwell.diary.page.HandwritingFont
import com.inkwell.diary.page.HandwritingFontWeight
import com.inkwell.diary.page.PageCanvasView
import com.inkwell.diary.page.PageRenderer
import com.inkwell.diary.page.ReplyOverlayView
import com.inkwell.diary.page.SvgPathAdapter
import com.inkwell.diary.page.dissolveConfig
import com.inkwell.diary.recognize.MlKitRecognitionService
import com.inkwell.diary.recognize.RecognitionOutcome
import com.inkwell.diary.recognize.RecognitionService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : ComponentActivity(), InkCaptureController.Callbacks, SettingsPanel.Callbacks {
    private lateinit var prefs: Prefs
    private lateinit var engine: ConversationEngine
    private lateinit var recognitionService: RecognitionService
    private lateinit var notebookStore: NotebookStore
    private lateinit var renderer: PageRenderer
    private lateinit var root: FrameLayout
    private lateinit var topBar: LinearLayout
    private lateinit var surfaceView: SurfaceView
    private lateinit var pageView: PageCanvasView
    private lateinit var replyOverlay: ReplyOverlayView
    private lateinit var debugPanel: ScrollView
    private lateinit var debugText: TextView
    private val einkRefresher = EinkRefresher()
    private val strokeStore = StrokeStore()
    private val debugLines = ArrayDeque<String>()
    private var captureController: InkCaptureController? = null
    private var commitTimer: CommitTimer? = null
    private var commitJob: Job? = null
    private var promptFadeJob: Job? = null
    private var fadeDisclosureJob: Job? = null
    private var settingsPanel: SettingsPanel? = null
    private var busy = false
    private var pageSurfaceInitialized = false
    private var debugVisible = false
    private var pendingDebugReply: String? = null
    private var lastPenUpElapsedMs: Long? = null
    private var lastCommitRequestedElapsedMs: Long? = null
    private var lastSurfaceWidth = 0
    private var lastSurfaceHeight = 0
    private var toolbarImmersive = false
    private var activeNotebook: Notebook? = null
    private var historyOpen = false
    private var historyPages: List<NotebookPage> = emptyList()
    private var historyPageIndex = 0
    private var voiceChangedForNextRequest = false
    private var activeCanvasId: String? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        requestWindowFeature(Window.FEATURE_NO_TITLE)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        window.addFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN)
        configureSystemBars()
        einkRefresher.configureAppRefreshMode()

        prefs = Prefs(this)
        engine = ConversationEngine()
        recognitionService = MlKitRecognitionService()
        notebookStore = NotebookStore(filesDir)
        renderer = PageRenderer(this)
        renderer.setHandwritingStyle(currentHandwritingFont(), prefs.handwritingFontSizeSp, prefs.handwritingFontWeight)
        renderer.setInkFadeStyle(prefs.inkFadeStyle)
        renderer.setDissolveConfig(prefs.dissolveConfig())
        pendingDebugReply = debugReplyFrom(intent)
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                val panel = settingsPanel
                if (panel != null) {
                    if (!panel.handleBack()) {
                        onCloseSettings()
                    }
                } else if (historyOpen) {
                    closeHistory()
                } else {
                    finish()
                }
            }
        })

        if (prefs.onboardingComplete || pendingDebugReply != null) {
            loadActiveNotebook()
            showPage()
        } else {
            showOnboarding()
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        pendingDebugReply = debugReplyFrom(intent) ?: return
        if (::root.isInitialized) {
            root.post { drawPendingDebugReplyIfReady() }
        } else {
            showPage()
        }
    }

    override fun onDestroy() {
        cancelFadeDisclosure()
        captureController?.detach()
        renderer.detach()
        super.onDestroy()
    }

    override fun onStop() {
        if (::renderer.isInitialized) {
            val cancelledActiveFade = renderer.cancelFadeAnimation()
            if (cancelledActiveFade) {
                promptFadeJob?.cancel()
                promptFadeJob = null
            }
        }
        activeNotebook?.let { notebook ->
            lifecycleScope.launch {
                runCatching { notebookStore.save(notebook) }
            }
        }
        super.onStop()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) {
            configureSystemBars()
            if (::surfaceView.isInitialized) {
                surfaceView.post { captureController?.refreshLimits() }
            }
        }
    }

    private fun showOnboarding() {
        val flow = OnboardingFlow(
            context = this,
            prefs = prefs,
            engine = engine,
            recognitionService = recognitionService,
            scope = lifecycleScope,
        ) {
            completeOnboarding()
        }
        setContentView(flow)
    }

    private fun completeOnboarding() {
        prefs.persona = Persona.default
        val loaded = loadActiveNotebook()
        val defaulted = loaded.withPersona(Persona.default, System.currentTimeMillis())
        activeNotebook = defaulted
        prefs.persona = Persona.default
        engine.replaceHistory(defaulted.rebuildApiHistory())
        lifecycleScope.launch {
            runCatching { notebookStore.save(defaulted) }
        }
        showPage()
    }

    private fun showPage() {
        pageSurfaceInitialized = false
        lastSurfaceWidth = 0
        lastSurfaceHeight = 0
        root = FrameLayout(this).apply {
            setBackgroundColor(Color.WHITE)
        }
        val toolbarHeight = TOOLBAR_HEIGHT_DP.dp()
        renderer.setContentTopInset(toolbarHeight)
        topBar = buildTopBar()
        surfaceView = SurfaceView(this).apply {
            setBackgroundColor(Color.WHITE)
        }
        pageView = PageCanvasView(this)
        replyOverlay = ReplyOverlayView(this).apply {
            setContentTopInset(toolbarHeight)
            setHandwritingStyle(currentHandwritingFont(), prefs.handwritingFontSizeSp, prefs.handwritingFontWeight)
        }
        root.addView(
            surfaceView,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT,
            ),
        )
        root.addView(
            pageView,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT,
            ),
        )
        root.addView(
            replyOverlay,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT,
            ),
        )
        root.addView(
            topBar,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                toolbarHeight,
                Gravity.TOP,
            ),
        )
        debugPanel = buildDebugPanel()
        root.addView(
            debugPanel,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                DEBUG_PANEL_HEIGHT_DP.dp(),
                Gravity.TOP,
            ).apply {
                topMargin = toolbarHeight
            },
        )
        addDebug("ready")

        setContentView(root)
        root.post { einkRefresher.requestFullRefresh(root) }
        surfaceView.holder.addCallback(object : SurfaceHolder.Callback {
            override fun surfaceCreated(holder: SurfaceHolder) {
                surfaceView.post {
                    initializePageSurface(surfaceView.width, surfaceView.height)
                }
            }

            override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
                initializePageSurface(width, height)
            }

            override fun surfaceDestroyed(holder: SurfaceHolder) {
                captureController?.detach()
                captureController = null
            }
        })
        surfaceView.post {
            initializePageSurface(surfaceView.width, surfaceView.height)
        }
    }

    private fun initializePageSurface(width: Int, height: Int) {
        if (width <= 0 || height <= 0) return
        val surfaceSizeChanged = width != lastSurfaceWidth || height != lastSurfaceHeight
        if (surfaceSizeChanged) {
            addDebug("surface size: ${width}x$height")
            lastSurfaceWidth = width
            lastSurfaceHeight = height
        }
        clearRawSurface()
        renderer.attach(pageView, width, height)
        if (!pageSurfaceInitialized) {
            renderCurrentPageOrHint(fullRefresh = true)
            pageSurfaceInitialized = true
        } else if (historyOpen) {
            renderHistoryPage(fullRefresh = surfaceSizeChanged)
        } else if (prefs.replyStyle == ReplyStyle.Drawing) {
            val elements = currentCanvasElements()
            if (elements.isNotEmpty()) {
                renderer.showNotebookElements(elements, fullRefresh = surfaceSizeChanged)
            }
        } else if (!strokeStore.isEmpty() && captureController?.isRawDrawingActive() != true) {
            renderer.showCapturedStrokes(strokeStore.snapshotStrokes())
        }
        drawPendingDebugReplyIfReady()
        if (captureController == null) {
            installCapture()
        } else {
            captureController?.refreshLimits(resetRawSession = surfaceSizeChanged)
        }
    }

    private fun clearRawSurface() {
        val holder = surfaceView.holder
        if (!holder.surface.isValid) return
        val canvas = holder.lockCanvas() ?: return
        try {
            canvas.drawColor(Color.WHITE)
        } finally {
            holder.unlockCanvasAndPost(canvas)
        }
    }

    private fun renderCurrentPageOrHint(fullRefresh: Boolean = true) {
        if (prefs.replyStyle == ReplyStyle.Drawing) {
            val elements = currentCanvasElements()
            if (elements.isNotEmpty()) {
                renderer.showNotebookElements(elements, fullRefresh = fullRefresh)
                return
            }
        }
        renderer.drawInitialHint()
    }

    private fun installCapture() {
        if (captureController != null) return
        val timer = CommitTimer(
            delayMillisProvider = { prefs.commitDelayMillis },
            scheduler = CoroutineCommitScheduler(lifecycleScope),
        ) {
            onCommitRequested()
        }
        commitTimer = timer
        captureController = InkCaptureController(
            context = this,
            surfaceView = surfaceView,
            strokeStore = strokeStore,
            commitTimer = timer,
            pageRectProvider = { surfaceView.localVisibleDrawingRect() },
            excludeRectsProvider = {
                listOfNotNull(
                    topBar.relativeRectTo(surfaceView),
                    debugPanel.takeIf { debugVisible }?.relativeRectTo(surfaceView),
                )
            },
            callbacks = this,
        ).also { controller ->
            surfaceView.post { controller.attach() }
        }
    }

    override fun onPenDown(): Boolean {
        if (busy) return false
        cancelFadeDisclosure()
        if (historyOpen) {
            renderer.showHint("Return to the page to write.")
            addDebug("history page is read-only")
            return false
        }
        promptFadeJob?.cancel()
        promptFadeJob = null
        setStatus(if (prefs.replyStyle == ReplyStyle.Drawing) "Sketching" else "Writing")
        replyOverlay.clearReply()
        if (prefs.replyStyle == ReplyStyle.Drawing) {
            showDrawingModeHintIfNeeded()
            return true
        }
        if (renderer.hasReply) {
            lifecycleScope.launch {
                renderer.fadePreviousReply()
            }
        } else if (strokeStore.isEmpty()) {
            renderer.clear()
        }
        return true
    }

    override fun onPenUp() {
        if (busy) return
        lastPenUpElapsedMs = SystemClock.elapsedRealtime()
        addDebug("pen up, commit in ${prefs.commitDelayMillis}ms")
    }

    override fun onCommitRequested() {
        if (busy) return
        if (historyOpen || strokeStore.isEmpty()) return
        val now = SystemClock.elapsedRealtime()
        lastCommitRequestedElapsedMs = now
        val afterPenUp = lastPenUpElapsedMs?.let { ", afterPenUp=${now - it}ms" }.orEmpty()
        if (prefs.autoReplyPaused) {
            addDebug("auto reply paused; commit skipped$afterPenUp")
            setStatus("Auto reply paused")
            return
        }
        addDebug("commit requested$afterPenUp")
        setStatus("Reading")
        commitJob?.cancel()
        commitJob = lifecycleScope.launch {
            commitPage()
        }
    }

    override fun onFingerTap(x: Float, y: Float) {
        if (historyOpen && handleHistoryTap(x)) {
            return
        }
        if (!historyOpen && prefs.replyStyle == ReplyStyle.Drawing && handleDrawingPageTurnTap(x)) {
            return
        }
        renderer.signalTap()
    }

    override fun onFingerSwipeLeft() {
        if (historyOpen) {
            turnHistoryPage(1)
        } else if (prefs.replyStyle == ReplyStyle.Drawing) {
            startFreshDrawingCanvas()
        }
    }

    override fun onFingerSwipeRight() {
        if (historyOpen) {
            turnHistoryPage(-1)
        } else {
            openHistory()
        }
    }

    override fun onStrokeCaptured(strokes: List<InkStroke>, dirtyRect: RectF?) {
        if (historyOpen || captureController?.isRawDrawingActive() == true) {
            return
        }
        if (prefs.replyStyle == ReplyStyle.Drawing) {
            renderer.showNotebookElements(currentCanvasElements() + currentInkElement(strokes), fullRefresh = false)
            return
        }
        renderer.showCapturedStrokes(strokes, dirtyRect)
    }

    private suspend fun commitPage() {
        when (prefs.replyStyle) {
            ReplyStyle.Writing -> commitFadePage()
            ReplyStyle.Drawing -> commitDrawingPage()
        }
    }

    private suspend fun commitDrawingPage() {
        busy = true
        val message = strokeStore.snapshot(surfaceView.width, surfaceView.height)
        val strokes = message.strokes
        if (strokes.isEmpty()) {
            busy = false
            return
        }
        captureController?.setInputEnabled(false, keepRawInkVisible = true)
        setStatus("Recognizing")
        val recognitionStartedAt = SystemClock.elapsedRealtime()
        addDebug("drawing recognizing ${strokes.size} stroke(s), language=${prefs.recognitionLanguage}")
        val latestUserText = when (val outcome = recognitionService.recognize(message, prefs.recognitionLanguage)) {
            is RecognitionOutcome.Text -> {
                val recognitionMs = SystemClock.elapsedRealtime() - recognitionStartedAt
                val recognized = outcome.value.trim()
                addDebug("drawing recognized in ${recognitionMs}ms: ${recognized.ifBlank { "(blank)" }.shortForDebug()}")
                recognized
            }
            is RecognitionOutcome.Failure -> {
                val recognitionMs = SystemClock.elapsedRealtime() - recognitionStartedAt
                addDebug("drawing recognition failed in ${recognitionMs}ms: ${outcome.message.shortForDebug()}")
                ""
            }
        }
        val notebookBeforeRequest = activeNotebook ?: loadActiveNotebook()
        val canvasId = activeCanvasId ?: latestCanvasId(notebookBeforeRequest) ?: newCanvasId(System.currentTimeMillis()).also {
            activeCanvasId = it
        }
        val priorElements = canvasElements(notebookBeforeRequest, canvasId)
        val snapshot = renderer.snapshotForVision(
            elements = priorElements,
            draftStrokes = strokes,
        )
        if (snapshot == null) {
            captureController?.setInputEnabled(true)
            busy = false
            setStatus("Canvas not ready")
            addDebug("drawing snapshot failed: renderer not ready")
            return
        }

        val committedAt = System.currentTimeMillis()
        val exchangeId = newExchangeId(committedAt)
        var persistedNotebook = notebookBeforeRequest.withExchange(
            Exchange(
                id = exchangeId,
                committedAt = committedAt,
                canvasId = canvasId,
                ink = NotebookInk(
                    strokes = strokes,
                    recognizedText = latestUserText,
                ),
                reply = null,
            ),
            updatedAt = committedAt,
        )
        activeNotebook = persistedNotebook
        notebookStore.save(persistedNotebook)
        addDebug(
            "drawing exchange saved: canvas=$canvasId, strokes=${strokes.size}, snapshot=${snapshot.width}x${snapshot.height}, transcript=${latestUserText.ifBlank { "(blank)" }.shortForDebug()}",
        )

        val provider = prefs.provider
        if (prefs.apiKey.isBlank()) {
            showCommittedDrawingCanvas(canvasId, strokes)
            finishDrawingCommit("AI setup needed")
            addDebug("drawing ai setup missing: ${provider.label} API key")
            showMissingApiKeyWarning(provider.label)
            return
        }
        if (provider != AiProvider.Anthropic) {
            showCommittedDrawingCanvas(canvasId, strokes)
            finishDrawingCommit("Anthropic required")
            addDebug("drawing mode requires Anthropic, current=${provider.label}")
            showWarningDialog(
                title = "Anthropic required",
                message = "Drawing mode uses Anthropic vision tool calls. Switch Provider to Anthropic in Settings > AI Settings.",
            )
            return
        }

        engine.replaceHistory(notebookBeforeRequest.rebuildApiHistory())
        setStatus("Drawing")
        val systemPrompt = if (voiceChangedForNextRequest) {
            voiceChangedForNextRequest = false
            "${prefs.systemPrompt()}\n\nThe voice of the diary has changed."
        } else {
            prefs.systemPrompt()
        }
        val settings = ConversationSettings(
            apiKey = prefs.apiKey,
            model = prefs.model,
            systemPrompt = systemPrompt,
            provider = provider,
        )
        showCommittedDrawingCanvas(canvasId, strokes, fullRefresh = false)
        renderer.beginSketchReply()
        val aiStartedAt = SystemClock.elapsedRealtime()
        addDebug("draw stream start: ${provider.label} ${prefs.model}")
        var firstToolDeltaAt: Long? = null
        var firstPathParsedAt: Long? = null
        var firstStrokeRenderedAt: Long? = null
        val streamedPaths = mutableListOf<String>()
        val streamedReplyStrokes = mutableListOf<InkStroke>()
        val result = engine.streamDrawing(
            settings = settings,
            snapshot = snapshot,
            latestUserText = latestUserText,
            onPath = { path ->
                val pathAt = SystemClock.elapsedRealtime()
                if (firstPathParsedAt == null) {
                    firstPathParsedAt = pathAt
                    addDebug("draw first path parsed: ${pathAt - aiStartedAt}ms")
                }
                streamedPaths.add(path)
                val converted = SvgPathAdapter.convertOne(
                    path = path,
                    imageWidth = snapshot.width,
                    imageHeight = snapshot.height,
                    pageWidth = snapshot.pageWidth,
                    pageHeight = snapshot.pageHeight,
                )
                if (converted.strokes.isEmpty()) {
                    addDebug("draw streamed path rejected")
                } else {
                    withContext(Dispatchers.Main) {
                        converted.strokes.forEach { stroke ->
                            renderer.appendSketchStroke(stroke)
                            streamedReplyStrokes.add(stroke)
                            if (firstStrokeRenderedAt == null) {
                                val strokeAt = SystemClock.elapsedRealtime()
                                firstStrokeRenderedAt = strokeAt
                                addDebug("draw first stroke rendered: ${strokeAt - aiStartedAt}ms")
                            }
                        }
                    }
                }
            },
            onToolJsonDelta = {
                if (firstToolDeltaAt == null) {
                    val deltaAt = SystemClock.elapsedRealtime()
                    firstToolDeltaAt = deltaAt
                    addDebug("draw first tool delta: ${deltaAt - aiStartedAt}ms")
                }
            },
        )
        when (result) {
            is DrawingReplyResult.Success -> {
                val converted = SvgPathAdapter.convert(
                    paths = result.paths,
                    imageWidth = snapshot.width,
                    imageHeight = snapshot.height,
                    pageWidth = snapshot.pageWidth,
                    pageHeight = snapshot.pageHeight,
                )
                addDebug(
                    "draw tool done: paths=${result.paths.size}, valid=${converted.strokes.size}, rejected=${converted.rejectedPaths}, truncated=${converted.truncated}, total=${SystemClock.elapsedRealtime() - aiStartedAt}ms",
                )
                if (converted.strokes.isEmpty()) {
                    val error = ErrorMapper.from(BrainErrorKind.BadRequest)
                    addDebug("draw stream failed: no valid strokes after conversion")
                    showAiFailureWarning(error)
                    finishDrawingCommit("AI error")
                    return
                }
                val missingPaths = result.paths.drop(streamedPaths.size)
                missingPaths.forEach { path ->
                    val missing = SvgPathAdapter.convertOne(
                        path = path,
                        imageWidth = snapshot.width,
                        imageHeight = snapshot.height,
                        pageWidth = snapshot.pageWidth,
                        pageHeight = snapshot.pageHeight,
                    )
                    missing.strokes.forEach { stroke ->
                        renderer.appendSketchStroke(stroke)
                        streamedReplyStrokes.add(stroke)
                    }
                }
                val replyStrokes = if (streamedReplyStrokes.isNotEmpty()) {
                    streamedReplyStrokes.toList()
                } else {
                    converted.strokes
                }
                renderer.endSketchReply(fullRefresh = false)
                addDebug(
                    "draw stream complete: paths=${result.paths.size}, valid=${replyStrokes.size}, rejected=${converted.rejectedPaths}, truncated=${converted.truncated}, total=${SystemClock.elapsedRealtime() - aiStartedAt}ms",
                )
                val savedAt = System.currentTimeMillis()
                persistedNotebook = saveDrawingReply(
                    notebook = persistedNotebook,
                    exchangeId = exchangeId,
                    transcript = latestUserText.ifBlank { result.pageTextTranscript },
                    reply = NotebookReply(
                        text = "",
                        sketch = NotebookSketch(replyStrokes),
                        personaId = prefs.persona.name,
                        createdAt = savedAt,
                    ),
                    savedAt = savedAt,
                )
                engine.replaceHistory(persistedNotebook.rebuildApiHistory())
                finishDrawingCommit("Idle")
            }
            is DrawingReplyResult.Failure -> {
                val error = ErrorMapper.from(result.kind)
                addDebug(
                    "draw stream failed: ${provider.label} ${prefs.model}, ${result.kind}, partialStrokes=${streamedReplyStrokes.size}, total=${SystemClock.elapsedRealtime() - aiStartedAt}ms${result.detail?.let { " - ${it.shortForDebug()}" }.orEmpty()}",
                )
                setStatus("AI error: ${result.kind}")
                showAiFailureWarning(error)
                finishDrawingCommit("AI error")
            }
        }
    }

    private suspend fun saveDrawingReply(
        notebook: Notebook,
        exchangeId: String,
        transcript: String,
        reply: NotebookReply,
        savedAt: Long,
    ): Notebook {
        val exchange = notebook.exchanges.firstOrNull { it.id == exchangeId } ?: return notebook
        val updatedInk = exchange.ink?.copy(recognizedText = transcript)
        val updated = notebook.withExchange(
            exchange.copy(
                ink = updatedInk,
                reply = reply,
            ),
            updatedAt = savedAt,
        )
        activeNotebook = updated
        notebookStore.save(updated)
        addDebug("drawing reply saved: exchanges=${updated.exchanges.size}, transcript=${transcript.ifBlank { "(blank)" }.shortForDebug()}")
        return updated
    }

    private fun showCommittedDrawingCanvas(
        canvasId: String,
        newStrokes: List<InkStroke>,
        fullRefresh: Boolean = true,
    ) {
        val notebook = activeNotebook ?: loadActiveNotebook()
        val elements = canvasElements(notebook, canvasId).ifEmpty {
            listOf(currentInkElement(newStrokes))
        }
        captureController?.hideRawInkLayer()
        renderer.showNotebookElements(elements, fullRefresh = fullRefresh)
    }

    private fun finishDrawingCommit(status: String) {
        strokeStore.clear()
        captureController?.clearRawInkLayer()
        captureController?.setInputEnabled(true)
        busy = false
        setStatus(status)
    }

    private suspend fun commitFadePage() {
        busy = true
        val message = strokeStore.snapshot(surfaceView.width, surfaceView.height)
        val strokes = message.strokes
        if (BuildConfig.DEBUG) {
            runCatching { DissolveLabStore.save(this, strokes) }
        }
        captureController?.setInputEnabled(false, keepRawInkVisible = true)
        val recognitionStartedAt = SystemClock.elapsedRealtime()
        val afterCommit = lastCommitRequestedElapsedMs
            ?.let { ", afterCommit=${recognitionStartedAt - it}ms" }
            .orEmpty()
        addDebug("recognizing ${strokes.size} stroke(s), language=${prefs.recognitionLanguage}$afterCommit")
        val recognized = when (val outcome = recognitionService.recognize(message, prefs.recognitionLanguage)) {
            is RecognitionOutcome.Text -> {
                val recognitionMs = SystemClock.elapsedRealtime() - recognitionStartedAt
                addDebug("recognized in ${recognitionMs}ms: ${outcome.value.ifBlank { "(blank)" }.shortForDebug()}")
                outcome.value
            }
            is RecognitionOutcome.Failure -> {
                val recognitionMs = SystemClock.elapsedRealtime() - recognitionStartedAt
                addDebug("recognition failed in ${recognitionMs}ms: ${outcome.message.shortForDebug()}")
                ""
            }
        }

        if (recognized.isBlank()) {
            strokeStore.clear()
            renderer.showHint()
            captureController?.setInputEnabled(true)
            busy = false
            setStatus("Recognition failed")
            return
        }

        val notebookBeforeRequest = activeNotebook ?: loadActiveNotebook()
        engine.replaceHistory(notebookBeforeRequest.rebuildApiHistory())
        val committedAt = System.currentTimeMillis()
        val exchangeId = newExchangeId(committedAt)
        var persistedNotebook = notebookBeforeRequest.withExchange(
            Exchange(
                id = exchangeId,
                committedAt = committedAt,
                ink = NotebookInk(
                    strokes = strokes,
                    recognizedText = recognized.trim(),
                ),
                reply = null,
            ),
            updatedAt = committedAt,
        )
        activeNotebook = persistedNotebook
        notebookStore.save(persistedNotebook)
        addDebug("exchange saved: notebook=${persistedNotebook.id}, exchanges=${persistedNotebook.exchanges.size}")

        val promptFade = schedulePromptFade(strokes)
        val provider = prefs.provider
        if (prefs.apiKey.isBlank()) {
            promptFade.join()
            strokeStore.clear()
            captureController?.setInputEnabled(true)
            busy = false
            setStatus("AI setup needed")
            addDebug("ai setup missing: ${provider.label} API key")
            showMissingApiKeyWarning(provider.label)
            return
        }

        setStatus("Sending")
        addDebug("sending to ${provider.label} ${prefs.model}, chars=${recognized.length}")
        val aiStartedAt = SystemClock.elapsedRealtime()
        var firstReplyDeltaAt: Long? = null
        var replyStarted = false
        var streamedChars = 0
        var replyRenderMs = 0L
        val systemPrompt = if (voiceChangedForNextRequest) {
            voiceChangedForNextRequest = false
            "${prefs.systemPrompt()}\n\nThe voice of the diary has changed."
        } else {
            prefs.systemPrompt()
        }
        val settings = ConversationSettings(
            apiKey = prefs.apiKey,
            model = prefs.model,
            systemPrompt = systemPrompt,
            provider = provider,
        )
        val result = engine.streamMessage(settings, recognized) { delta ->
            if (delta.isEmpty()) return@streamMessage
            promptFade.join()
            withContext(Dispatchers.Main) {
                if (!replyStarted) {
                    val deltaAt = SystemClock.elapsedRealtime()
                    firstReplyDeltaAt = deltaAt
                    addDebug("first reply delta, ttft=${deltaAt - aiStartedAt}ms")
                    setStatus("Reply")
                    replyOverlay.beginReply()
                    replyStarted = true
                }
                val renderStartedAt = SystemClock.elapsedRealtime()
                writeReplyDelta(delta)
                replyRenderMs += SystemClock.elapsedRealtime() - renderStartedAt
                streamedChars += delta.length
            }
        }

        promptFade.join()
        when (result) {
            is AnthropicResult.Success -> {
                val totalMs = SystemClock.elapsedRealtime() - aiStartedAt
                val ttft = firstReplyDeltaAt?.let { "${it - aiStartedAt}ms" } ?: "n/a"
                addDebug(
                    "reply done: ${provider.label} ${prefs.model}, ttft=$ttft, stream+render=${totalMs}ms, render=${replyRenderMs}ms, chars=${result.text.length}, visible=$streamedChars",
                )
                if (!replyStarted) {
                    setStatus("Reply")
                    replyOverlay.revealReply(result.text)
                }
                val savedAt = System.currentTimeMillis()
                val exchange = persistedNotebook.exchanges.firstOrNull { it.id == exchangeId }
                if (exchange != null) {
                    persistedNotebook = persistedNotebook.withExchange(
                        exchange.copy(
                            reply = NotebookReply(
                                text = result.text,
                                personaId = prefs.persona.name,
                                createdAt = savedAt,
                            ),
                        ),
                        updatedAt = savedAt,
                    )
                    activeNotebook = persistedNotebook
                    notebookStore.save(persistedNotebook)
                    engine.replaceHistory(persistedNotebook.rebuildApiHistory())
                    addDebug("reply saved: notebook=${persistedNotebook.id}, exchanges=${persistedNotebook.exchanges.size}")
                }
            }
            is AnthropicResult.Failure -> {
                val error = ErrorMapper.from(result.kind)
                val totalMs = SystemClock.elapsedRealtime() - aiStartedAt
                addDebug(
                    "ai failed: ${provider.label} ${prefs.model}, ${result.kind}, total=${totalMs}ms${result.detail?.let { " - ${it.shortForDebug()}" }.orEmpty()}",
                )
                setStatus("AI error: ${result.kind}")
                showAiFailureWarning(error)
            }
        }
        strokeStore.clear()
        captureController?.setInputEnabled(true)
        busy = false
    }

    private fun schedulePromptFade(strokes: List<InkStroke>): Job {
        promptFadeJob?.cancel()
        addDebug("prompt fade scheduled in ${PROMPT_FADE_DELAY_MS}ms")
        val job = lifecycleScope.launch {
            delay(PROMPT_FADE_DELAY_MS)
            fadePrompt(strokes)
        }
        promptFadeJob = job
        return job
    }

    private suspend fun fadePrompt(strokes: List<InkStroke>) {
        addDebug("prompt fade started")
        captureController?.hideRawInkLayer()
        renderer.setInkFadeStyle(prefs.inkFadeStyle)
        renderer.setDissolveConfig(prefs.dissolveConfig())
        renderer.fadeStrokes(strokes, includeFullOpacityFrame = false)
        showFadeDisclosureOnce()
        addDebug("prompt fade done")
    }

    private fun showFadeDisclosureOnce() {
        if (prefs.hasSeenFadeDisclosure) return
        prefs.hasSeenFadeDisclosure = true
        cancelFadeDisclosure()
        fadeDisclosureJob = lifecycleScope.launch {
            renderer.showBottomLine(FADE_DISCLOSURE)
            delay(FADE_DISCLOSURE_VISIBLE_MS)
            renderer.fadeBottomLine(FADE_DISCLOSURE)
            fadeDisclosureJob = null
        }
    }

    private fun showDrawingModeHintIfNeeded() {
        if (prefs.replyStyle != ReplyStyle.Drawing || prefs.hasSeenDrawingModeHint) return
        prefs.hasSeenDrawingModeHint = true
        renderer.showBottomLine(DRAWING_MODE_HINT)
        addDebug("drawing hint shown")
    }

    private fun cancelFadeDisclosure(clearPageLayer: Boolean = false) {
        val hadDisclosure = fadeDisclosureJob != null
        fadeDisclosureJob?.cancel()
        fadeDisclosureJob = null
        if (clearPageLayer && hadDisclosure && ::renderer.isInitialized) {
            renderer.clear()
        }
    }

    private suspend fun writeReplyDelta(delta: String) {
        delta.forEach { char ->
            replyOverlay.appendReplyText(char.toString())
            delay(if (char.isWhitespace()) STREAM_WORD_GAP_MS else STREAM_CHARACTER_GAP_MS)
        }
    }

    private fun openSettings() {
        if (settingsPanel != null) return
        cancelFadeDisclosure(clearPageLayer = true)
        captureController?.setInputEnabled(false)
        topBar.visibility = View.INVISIBLE
        val panel = SettingsPanel(
            context = this,
            prefs = prefs,
            engine = engine,
            recognitionService = recognitionService,
            scope = lifecycleScope,
            callbacks = this,
        )
        settingsPanel = panel
        root.addView(
            panel,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT,
            ),
        )
    }

    override fun onCloseSettings() {
        settingsPanel?.let { panel ->
            root.removeView(panel)
        }
        settingsPanel = null
        topBar.visibility = View.VISIBLE
        if (historyOpen) {
            renderHistoryPage()
        }
        refreshCaptureEnabled()
    }

    override fun onClearConversation() {
        clearPage()
    }

    override fun onHandwritingStyleChanged() {
        val font = currentHandwritingFont()
        renderer.setHandwritingStyle(font, prefs.handwritingFontSizeSp, prefs.handwritingFontWeight)
        if (::replyOverlay.isInitialized) {
            replyOverlay.setHandwritingStyle(font, prefs.handwritingFontSizeSp, prefs.handwritingFontWeight)
        }
        val weight = HandwritingFontWeight.fromValue(prefs.handwritingFontWeight).label
        addDebug("handwriting style: ${font.label}, ${prefs.handwritingFontSizeSp.toInt()}sp, $weight")
    }

    override fun onToolbarSettingsChanged() {
        if (!prefs.showToolbarLogButton && debugVisible) {
            debugVisible = false
            debugPanel.visibility = View.GONE
            captureController?.refreshLimits()
        }
        renderTopBar()
        addDebug("toolbar log button: ${if (prefs.showToolbarLogButton) "shown" else "hidden"}")
    }

    override fun onInkFadeStyleChanged() {
        renderer.setInkFadeStyle(prefs.inkFadeStyle)
        addDebug("ink fade style: ${prefs.inkFadeStyle.label}")
    }

    override fun onReplyStyleChanged() {
        renderTopBar()
        addDebug("reply style: ${prefs.replyStyle.label}, commit delay=${prefs.commitDelayMillis}ms")
        if (prefs.replyStyle == ReplyStyle.Drawing) {
            activeCanvasId = activeCanvasId ?: latestCanvasId(activeNotebook ?: loadActiveNotebook()) ?: newCanvasId(System.currentTimeMillis())
            showDrawingModeHintIfNeeded()
        }
    }

    override fun currentNotebookTitle(): String {
        return activeNotebook?.title.orEmpty()
    }

    override fun currentNotebookPersona(): Persona {
        return activeNotebook?.personaId?.let { Persona.fromStoredName(it) } ?: prefs.persona
    }

    override fun onNotebookTitleChanged(title: String) {
        val notebook = activeNotebook ?: loadActiveNotebook()
        val updated = notebook.withTitle(title, System.currentTimeMillis())
        activeNotebook = updated
        lifecycleScope.launch { notebookStore.save(updated) }
        addDebug("notebook title saved: ${updated.title}")
    }

    override fun onNotebookPersonaChanged(persona: Persona) {
        val notebook = activeNotebook ?: loadActiveNotebook()
        val updated = notebook.withPersona(persona, System.currentTimeMillis())
        activeNotebook = updated
        prefs.persona = persona
        voiceChangedForNextRequest = true
        engine.replaceHistory(updated.rebuildApiHistory())
        lifecycleScope.launch { notebookStore.save(updated) }
        addDebug("notebook persona saved: ${persona.label}")
    }

    private fun loadActiveNotebook(): Notebook {
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
                lifecycleScope.launch {
                    runCatching { notebookStore.save(notebook) }
                }
                addDebug("notebook persona normalized: Custom without prompt -> ${Persona.default.name}")
            }
            prefs.hasNormalizedBlankCustomPersona = true
        }
        activeNotebook = notebook
        prefs.activeNotebookId = notebook.id
        prefs.persona = Persona.fromStoredName(notebook.personaId)
        activeCanvasId = latestCanvasId(notebook) ?: newCanvasId(System.currentTimeMillis())
        engine.replaceHistory(notebook.rebuildApiHistory())
        addDebug("notebook loaded: id=${notebook.id}, title=${notebook.title}, persona=${notebook.personaId}, exchanges=${notebook.exchanges.size}")
        return notebook
    }

    private fun latestCanvasId(notebook: Notebook): String? {
        return notebook.exchanges.sortedBy { it.committedAt }.lastOrNull { it.canvasId != null }?.canvasId
    }

    private fun currentCanvasElements(): List<com.inkwell.diary.data.NotebookElement> {
        val notebook = activeNotebook ?: loadActiveNotebook()
        val canvasId = activeCanvasId ?: latestCanvasId(notebook) ?: return emptyList()
        return canvasElements(notebook, canvasId)
    }

    private fun canvasElements(notebook: Notebook, canvasId: String): List<com.inkwell.diary.data.NotebookElement> {
        return notebook.exchanges
            .asSequence()
            .filter { it.canvasId == canvasId }
            .sortedBy { it.committedAt }
            .flatMap { exchange ->
                sequence {
                    exchange.ink?.let { ink ->
                        yield(
                            InkElement(
                                strokes = ink.strokes,
                                committedAt = exchange.committedAt,
                                recognizedText = ink.recognizedText,
                            ),
                        )
                    }
                    exchange.reply?.sketch?.let { sketch ->
                        yield(
                            SketchElement(
                                strokes = sketch.strokes,
                                personaId = exchange.reply.personaId,
                                createdAt = exchange.reply.createdAt,
                            ),
                        )
                    }
                    exchange.reply?.text?.takeIf { it.isNotBlank() }?.let { text ->
                        yield(
                            ReplyElement(
                                text = text,
                                personaId = exchange.reply.personaId,
                                createdAt = exchange.reply.createdAt,
                            ),
                        )
                    }
                }
            }
            .toList()
    }

    private fun currentInkElement(strokes: List<InkStroke>): InkElement {
        return InkElement(
            strokes = strokes,
            committedAt = System.currentTimeMillis(),
            recognizedText = "",
        )
    }

    private fun renderHistoryPage(fullRefresh: Boolean = true) {
        if (!historyOpen) return
        val page = historyPages.getOrNull(historyPageIndex) ?: return
        renderer.renderNotebookPage(
            page = page,
            pageIndex = historyPageIndex,
            pageCount = historyPages.size,
            showPageStatus = historyPages.size > 1,
            pageStatus = "History",
            fullRefresh = fullRefresh,
            showContinuationMark = historyPageIndex < historyPages.lastIndex,
        )
    }

    private fun openHistory(showEmptyWarning: Boolean = false) {
        val notebook = activeNotebook ?: loadActiveNotebook()
        if (notebook.exchanges.isEmpty()) {
            addDebug("history empty")
            if (showEmptyWarning) {
                showWarningDialog(
                    title = "Nothing to read yet",
                    message = "Write something first, then come back to read the notebook.",
                )
            }
            return
        }
        cancelFadeDisclosure()
        historyPages = renderer.historyPagesFor(notebook)
        historyPageIndex = historyPages.lastIndex
        historyOpen = true
        promptFadeJob?.cancel()
        promptFadeJob = null
        replyOverlay.clearReply()
        renderTopBar()
        captureController?.setReadOnlyInputEnabled(true)
        renderHistoryPage(fullRefresh = true)
        setStatus("History")
        addDebug("history opened: pages=${historyPages.size}, exchanges=${notebook.exchanges.size}")
    }

    private fun closeHistory() {
        cancelFadeDisclosure()
        historyOpen = false
        historyPages = emptyList()
        historyPageIndex = 0
        renderTopBar()
        captureController?.clearRawInkLayer()
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

    private fun handleHistoryTap(x: Float): Boolean {
        val width = surfaceView.width.takeIf { it > 0 } ?: return false
        val hotZone = PAGE_TURN_HOT_ZONE_DP.dp().toFloat()
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

    private fun turnHistoryPage(delta: Int) {
        if (busy) return
        if (!historyOpen) return
        cancelFadeDisclosure()
        val next = (historyPageIndex + delta).coerceIn(0, historyPages.lastIndex)
        if (next == historyPageIndex) return
        historyPageIndex = next
        renderHistoryPage(fullRefresh = true)
        addDebug("history page ${historyPageIndex + 1}/${historyPages.size}")
    }

    private fun handleDrawingPageTurnTap(x: Float): Boolean {
        val width = surfaceView.width.takeIf { it > 0 } ?: return false
        val hotZone = PAGE_TURN_HOT_ZONE_DP.dp().toFloat()
        if (x < width - hotZone) return false
        startFreshDrawingCanvas()
        return true
    }

    private fun startFreshDrawingCanvas() {
        if (busy || historyOpen) return
        activeCanvasId = newCanvasId(System.currentTimeMillis())
        promptFadeJob?.cancel()
        promptFadeJob = null
        commitTimer?.cancel()
        strokeStore.clear()
        replyOverlay.clearReply()
        captureController?.clearRawInkLayer()
        renderer.clear()
        renderer.drawInitialHint()
        showDrawingModeHintIfNeeded()
        setStatus("New canvas")
        addDebug("drawing canvas started: $activeCanvasId")
        root.post { einkRefresher.requestFullRefresh(root) }
    }

    private fun handleToolbarRead() {
        if (historyOpen) {
            closeHistory()
        } else {
            openHistory(showEmptyWarning = true)
        }
    }

    private fun refreshCaptureEnabled() {
        if (settingsPanel != null || busy) return
        if (historyOpen) {
            captureController?.setReadOnlyInputEnabled(true)
        } else {
            captureController?.setInputEnabled(true)
        }
    }

    private fun clearPage() {
        cancelFadeDisclosure()
        promptFadeJob?.cancel()
        promptFadeJob = null
        commitTimer?.cancel()
        commitJob?.cancel()
        commitJob = null
        busy = false
        if (settingsPanel == null) {
            refreshCaptureEnabled()
        }
        captureController?.clearRawInkLayer()
        strokeStore.clear()
        replyOverlay.clearReply()
        if (prefs.replyStyle == ReplyStyle.Drawing) {
            activeCanvasId = newCanvasId(System.currentTimeMillis())
        }
        renderer.clear()
        renderer.drawInitialHint()
        setStatus("Idle")
        addDebug("page cleared")
    }

    override fun onBurnNotebook() {
        burnNotebook(dissolveHistoryPage = false)
    }

    private fun burnNotebook(dissolveHistoryPage: Boolean) {
        cancelFadeDisclosure()
        promptFadeJob?.cancel()
        promptFadeJob = null
        commitTimer?.cancel()
        commitJob?.cancel()
        commitJob = null
        val shouldDissolveHistory = dissolveHistoryPage && historyOpen
        busy = shouldDissolveHistory
        captureController?.clearRawInkLayer()
        strokeStore.clear()
        replyOverlay.clearReply()
        lifecycleScope.launch {
            try {
                if (shouldDissolveHistory) {
                    renderer.setDissolveConfig(burnDissolveConfig())
                    renderer.dissolveCurrentPage()
                }
                val current = activeNotebook
                val fresh = notebookStore.burn(current?.id.orEmpty(), prefs.persona)
                activeNotebook = fresh
                activeCanvasId = newCanvasId(System.currentTimeMillis())
                prefs.activeNotebookId = fresh.id
                prefs.persona = Persona.fromStoredName(fresh.personaId)
                historyOpen = false
                historyPages = emptyList()
                historyPageIndex = 0
                engine.clearHistory()
                renderTopBar()
                renderer.clear()
                renderer.drawInitialHint()
                refreshCaptureEnabled()
                setStatus("Idle")
                addDebug("notebook burned")
            } finally {
                busy = false
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

    private fun handleToolbarErase() {
        clearPage()
    }

    private fun confirmBurnNotebookFromHistory() {
        AlertDialog.Builder(this)
            .setTitle("Burn this notebook?")
            .setMessage("This permanently deletes the saved notebook on this device.")
            .setPositiveButton("Burn") { _, _ -> burnNotebook(dissolveHistoryPage = true) }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun showMissingApiKeyWarning(providerLabel: String) {
        showWarningDialog(
            title = "$providerLabel API key required",
            message = "Add a $providerLabel API key in Settings > AI Settings before asking for a reply.",
        )
    }

    private fun showAiFailureWarning(error: DiaryError) {
        showWarningDialog(
            title = error.banner ?: "AI request failed",
            message = error.diegeticLine,
        )
    }

    private fun showWarningDialog(title: String, message: String) {
        if (isFinishing || isDestroyed) return
        AlertDialog.Builder(this)
            .setTitle(title)
            .setMessage(message)
            .setPositiveButton("OK", null)
            .show()
    }

    private fun buildTopBar(): LinearLayout {
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            renderTopBar(this)
        }
    }

    private fun renderTopBar(container: LinearLayout = topBar) {
        container.removeAllViews()
        if (historyOpen) {
            renderHistoryTopBar(container)
            return
        }
        container.setBackgroundColor(if (toolbarImmersive) Color.TRANSPARENT else Color.WHITE)

        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(18), dp(4), dp(12), dp(4))

            addView(toolbarBrandButton())
            if (!toolbarImmersive) {
                addView(
                    View(context),
                    LinearLayout.LayoutParams(
                        0,
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        1f,
                    ),
                )
                addView(iconButton(R.drawable.ic_toolbar_eraser, "Erase page") { handleToolbarErase() })
                addView(iconButton(R.drawable.ic_toolbar_book_open, "Read notebook") { handleToolbarRead() })
                if (prefs.showToolbarLogButton) {
                    addView(iconButton(R.drawable.ic_toolbar_terminal, "AI log") { toggleDebugPanel() })
                }
                addView(iconButton(R.drawable.ic_toolbar_gear, "Settings") { openSettings() })
            }
        }
        container.addView(
            row,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                0,
                1f,
            ),
        )
        if (!toolbarImmersive) {
            container.addView(
                View(this).apply { setBackgroundColor(Color.BLACK) },
                LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    dp(2),
                ),
            )
        }
    }

    private fun renderHistoryTopBar(container: LinearLayout) {
        container.setBackgroundColor(Color.WHITE)
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(22), dp(4), dp(12), dp(4))

            addView(
                ImageButton(context).apply {
                    contentDescription = "Back"
                    importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_YES
                    setBackgroundColor(Color.TRANSPARENT)
                    setImageResource(R.drawable.ic_nav_back)
                    scaleType = ImageView.ScaleType.CENTER
                    setPadding(dp(10), dp(10), dp(10), dp(10))
                    setOnClickListener { closeHistory() }
                },
                LinearLayout.LayoutParams(
                    dp(54),
                    LinearLayout.LayoutParams.MATCH_PARENT,
                ),
            )
            addView(
                TextView(context).apply {
                    text = "History"
                    gravity = Gravity.CENTER_VERTICAL
                    includeFontPadding = false
                    paperText(25f)
                },
                LinearLayout.LayoutParams(
                    0,
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    1f,
                ),
            )
            addView(iconButton(R.drawable.ic_toolbar_trash, "Burn notebook") { confirmBurnNotebookFromHistory() })
        }
        container.addView(
            row,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                0,
                1f,
            ),
        )
        container.addView(
            View(this).apply { setBackgroundColor(Color.BLACK) },
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                dp(2),
            ),
        )
    }

    private fun toolbarBrandButton(): LinearLayout {
        return LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            contentDescription = "Toggle immersive mode"
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_YES
            setBackgroundColor(Color.TRANSPARENT)
            isClickable = true
            isFocusable = true
            setOnClickListener { toggleToolbarMode() }
            addView(
                ImageView(context).apply {
                    importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
                    setImageResource(R.drawable.ic_app_logo)
                    scaleType = ImageView.ScaleType.CENTER
                    setPadding(dp(4), dp(4), dp(4), dp(4))
                },
                LinearLayout.LayoutParams(
                    dp(46),
                    LinearLayout.LayoutParams.MATCH_PARENT,
                ),
            )
            if (!toolbarImmersive) {
                addView(toolbarAppName())
            }
        }
    }

    private fun toolbarAppName(): TextView {
        return TextView(this).apply {
            text = getString(R.string.app_name)
            gravity = Gravity.CENTER_VERTICAL
            includeFontPadding = false
            paperText(36f)
            typeface = HandwritingFont.DancingScript.loadTypeface(this@MainActivity, HandwritingFontWeight.Bold)
            paint.isFakeBoldText = HandwritingFont.DancingScript.shouldFakeBold(HandwritingFontWeight.Bold)
            translationY = dp(4).toFloat()
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            ).apply {
                leftMargin = dp(10)
            }
        }
    }

    private fun toggleToolbarMode() {
        toolbarImmersive = !toolbarImmersive
        if (toolbarImmersive && debugVisible) {
            debugVisible = false
            debugPanel.visibility = View.GONE
            captureController?.refreshLimits()
        }
        renderTopBar()
        configureSystemBars()
        addDebug("toolbar mode: ${if (toolbarImmersive) "immersive" else "full"}")
    }

    private fun iconButton(iconResId: Int, label: String, onClick: () -> Unit): ImageButton {
        return ImageButton(this).apply {
            contentDescription = label
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_YES
            setBackgroundColor(Color.TRANSPARENT)
            setImageResource(iconResId)
            scaleType = ImageView.ScaleType.CENTER
            setPadding(dp(14), dp(10), dp(14), dp(10))
            setOnClickListener {
                configureSystemBars()
                onClick()
                configureSystemBars()
            }
            layoutParams = LinearLayout.LayoutParams(
                dp(70),
                LinearLayout.LayoutParams.MATCH_PARENT,
            ).apply {
                leftMargin = dp(2)
            }
        }
    }

    private fun buildDebugPanel(): ScrollView {
        debugText = TextView(this).apply {
            paperText(13f)
            setTextColor(Color.BLACK)
            setPadding(dp(12), dp(10), dp(12), dp(10))
        }
        return ScrollView(this).apply {
            background = GradientDrawable().apply {
                setColor(Color.WHITE)
                setStroke(dp(2), Color.BLACK)
            }
            visibility = View.GONE
            addView(
                debugText,
                FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT,
                    FrameLayout.LayoutParams.WRAP_CONTENT,
                ),
            )
        }
    }

    private fun toggleDebugPanel() {
        debugVisible = !debugVisible
        debugPanel.visibility = if (debugVisible) View.VISIBLE else View.GONE
        if (debugVisible) {
            addDebug("ai config: ${prefs.provider.label} ${prefs.model}")
        }
        captureController?.refreshLimits()
    }

    private fun setStatus(value: String) {
        addDebug("status: $value")
    }

    private fun addDebug(line: String) {
        val uptimeMs = SystemClock.uptimeMillis()
        val timestamp = "${uptimeMs / 1000}.${(uptimeMs % 1000).toString().padStart(3, '0')}"
        val entry = "$timestamp  $line"
        Log.i(DEBUG_LOG_TAG, entry)
        debugLines.addLast(entry)
        while (debugLines.size > MAX_DEBUG_LINES) {
            debugLines.removeFirst()
        }
        if (::debugText.isInitialized) {
            debugText.text = debugLines.joinToString("\n")
            debugPanel.post {
                debugPanel.fullScroll(View.FOCUS_DOWN)
            }
        }
    }

    private fun debugReplyFrom(intent: Intent?): String? {
        if (!BuildConfig.DEBUG || intent?.action != DEBUG_REPLY_ACTION) return null
        return intent.getStringExtra(DEBUG_REPLY_EXTRA)
            ?.takeIf { it.isNotBlank() }
            ?: DEBUG_REPLY_FALLBACK
    }

    private fun drawPendingDebugReplyIfReady() {
        val text = pendingDebugReply ?: return
        if (!::replyOverlay.isInitialized || !::surfaceView.isInitialized) return
        if (surfaceView.width <= 0 || surfaceView.height <= 0) return

        pendingDebugReply = null
        setStatus("Reply")
        lifecycleScope.launch {
            replyOverlay.revealReply(text)
            addDebug("debug reply drawn, chars=${text.length}")
        }
    }

    private fun currentHandwritingFont(): HandwritingFont {
        return HandwritingFont.fromKey(prefs.handwritingFontKey)
    }

    private fun String.shortForDebug(limit: Int = 160): String {
        val normalized = replace('\n', ' ').trim()
        return if (normalized.length <= limit) normalized else normalized.take(limit) + "..."
    }

    private fun Int.dp(): Int = dp(this)

    private fun configureSystemBars() {
        window.statusBarColor = Color.WHITE
        window.navigationBarColor = Color.WHITE
        window.addFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN)
        val decorView = window.decorView

        if (Build.VERSION.SDK_INT >= 30) {
            window.setDecorFitsSystemWindows(false)
            hideSystemBars(decorView)
            decorView.post { hideSystemBars(decorView) }
        } else {
            @Suppress("DEPRECATION")
            decorView.systemUiVisibility =
                View.SYSTEM_UI_FLAG_FULLSCREEN or
                    View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or
                    View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY or
                    View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or
                    View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION or
                    View.SYSTEM_UI_FLAG_LAYOUT_STABLE
        }
    }

    private fun hideSystemBars(decorView: View) {
        if (Build.VERSION.SDK_INT < 30) return
        decorView.windowInsetsController?.let { controller ->
            controller.systemBarsBehavior =
                WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            controller.hide(WindowInsets.Type.statusBars() or WindowInsets.Type.navigationBars())
        }
    }

    private fun View.relativeRectTo(parent: View): Rect {
        val parentLocation = IntArray(2)
        val childLocation = IntArray(2)
        parent.getLocationOnScreen(parentLocation)
        getLocationOnScreen(childLocation)
        return Rect(
            childLocation[0] - parentLocation[0],
            childLocation[1] - parentLocation[1],
            childLocation[0] - parentLocation[0] + width,
            childLocation[1] - parentLocation[1] + height,
        )
    }

    private fun View.localVisibleDrawingRect(): Rect {
        val rect = Rect()
        return if (getLocalVisibleRect(rect) && rect.width() > 0 && rect.height() > 0) {
            rect
        } else {
            Rect(0, 0, width, height)
        }
    }

    companion object {
        private const val TOOLBAR_HEIGHT_DP = 70
        private const val DEBUG_PANEL_HEIGHT_DP = 220
        private const val MAX_DEBUG_LINES = 80
        private const val PROMPT_FADE_DELAY_MS = 500L
        private const val STREAM_CHARACTER_GAP_MS = 24L
        private const val STREAM_WORD_GAP_MS = 55L
        private const val PAGE_TURN_HOT_ZONE_DP = 48
        private const val DEBUG_REPLY_ACTION = "com.inkwell.diary.DEBUG_REPLY"
        private const val DEBUG_REPLY_EXTRA = "reply"
        private const val DEBUG_REPLY_FALLBACK = "This is a handwriting reply test."
        private const val DEBUG_LOG_TAG = "InkwellDebug"
        private const val FADE_DISCLOSURE = "The ink fades from the page, but the diary keeps every word. Flip back anytime."
        private const val DRAWING_MODE_HINT = "Draw or write, then tap twice when it's my turn."
        private const val FADE_DISCLOSURE_VISIBLE_MS = 5_000L
        private const val BURN_DISSOLVE_SWEEP_MS = 560L
        private const val BURN_DISSOLVE_CELL_LIFE_MS = 360L
        private const val BURN_DISSOLVE_FRAME_MS = 70L
        private const val BURN_DISSOLVE_DELAY_JITTER_MS = 60L
        private const val BURN_DISSOLVE_TERMINAL_HOLD_MS = 60L
        private const val BURN_DISSOLVE_MAX_TOTAL_MS = 1200L
    }
}
