package com.inkwell.diary.ui

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
import com.inkwell.diary.brain.ConversationEngine
import com.inkwell.diary.brain.ConversationSettings
import com.inkwell.diary.brain.ErrorMapper
import com.inkwell.diary.data.InkStroke
import com.inkwell.diary.data.Prefs
import com.inkwell.diary.data.StrokeStore
import com.inkwell.diary.ink.CommitTimer
import com.inkwell.diary.ink.CoroutineCommitScheduler
import com.inkwell.diary.ink.InkCaptureController
import com.inkwell.diary.page.EinkRefresher
import com.inkwell.diary.page.HandwritingFont
import com.inkwell.diary.page.PageRenderer
import com.inkwell.diary.page.ReplyOverlayView
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
    private lateinit var renderer: PageRenderer
    private lateinit var root: FrameLayout
    private lateinit var topBar: LinearLayout
    private lateinit var surfaceView: SurfaceView
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
        renderer = PageRenderer(this)
        renderer.setHandwritingStyle(currentHandwritingFont(), prefs.handwritingFontSizeSp, prefs.handwritingFontBold)
        pendingDebugReply = debugReplyFrom(intent)
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                val panel = settingsPanel
                if (panel != null) {
                    if (!panel.handleBack()) {
                        onCloseSettings()
                    }
                } else {
                    finish()
                }
            }
        })

        if (prefs.onboardingComplete || pendingDebugReply != null) {
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
            drawPendingDebugReplyIfReady()
        } else {
            showPage()
        }
    }

    override fun onDestroy() {
        captureController?.detach()
        renderer.detach()
        super.onDestroy()
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
            showPage()
        }
        setContentView(flow)
    }

    private fun showPage() {
        pageSurfaceInitialized = false
        lastSurfaceWidth = 0
        lastSurfaceHeight = 0
        root = FrameLayout(this).apply {
            setBackgroundColor(Color.WHITE)
        }
        topBar = buildTopBar()
        surfaceView = SurfaceView(this).apply {
            setBackgroundColor(Color.WHITE)
        }
        replyOverlay = ReplyOverlayView(this).apply {
            setHandwritingStyle(currentHandwritingFont(), prefs.handwritingFontSizeSp, prefs.handwritingFontBold)
        }
        root.addView(
            surfaceView,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT,
            ).apply {
                topMargin = TOOLBAR_HEIGHT_DP.dp()
            },
        )
        root.addView(
            replyOverlay,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT,
            ).apply {
                topMargin = TOOLBAR_HEIGHT_DP.dp()
            },
        )
        root.addView(
            topBar,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                TOOLBAR_HEIGHT_DP.dp(),
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
                topMargin = TOOLBAR_HEIGHT_DP.dp()
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
        renderer.attach(surfaceView, width, height)
        if (!pageSurfaceInitialized) {
            renderer.drawInitialHint()
            pageSurfaceInitialized = true
        } else if (!strokeStore.isEmpty()) {
            renderer.showCapturedStrokes(strokeStore.snapshotStrokes())
        }
        drawPendingDebugReplyIfReady()
        if (captureController == null) {
            installCapture()
        } else {
            captureController?.refreshLimits(resetRawSession = surfaceSizeChanged)
        }
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
                    debugPanel.takeIf { debugVisible }?.relativeRectTo(surfaceView),
                )
            },
            callbacks = this,
        ).also { controller ->
            surfaceView.post { controller.attach() }
        }
    }

    override fun onPenDown() {
        if (busy) return
        promptFadeJob?.cancel()
        promptFadeJob = null
        setStatus("Writing")
        replyOverlay.clearReply()
        if (renderer.hasReply) {
            lifecycleScope.launch {
                renderer.fadePreviousReply()
            }
        } else if (strokeStore.isEmpty()) {
            renderer.clear()
        }
    }

    override fun onPenUp() {
        if (busy) return
        lastPenUpElapsedMs = SystemClock.elapsedRealtime()
        addDebug("pen up, commit in ${prefs.commitDelayMillis}ms")
    }

    override fun onCommitRequested() {
        if (busy || strokeStore.isEmpty()) return
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

    override fun onFingerTap() {
        renderer.signalTap()
    }

    override fun onStrokeCaptured(strokes: List<InkStroke>, dirtyRect: RectF?) {
        renderer.showCapturedStrokes(strokes, dirtyRect)
    }

    private suspend fun commitPage() {
        busy = true
        val message = strokeStore.snapshot(surfaceView.width, surfaceView.height)
        val strokes = message.strokes
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

        schedulePromptFade(strokes)
        setStatus("Sending")
        val provider = prefs.provider
        addDebug("sending to ${provider.label} ${prefs.model}, chars=${recognized.length}")
        val aiStartedAt = SystemClock.elapsedRealtime()
        var firstReplyDeltaAt: Long? = null
        var replyStarted = false
        var streamedChars = 0
        var replyRenderMs = 0L
        val settings = ConversationSettings(
            apiKey = prefs.apiKey,
            model = prefs.model,
            systemPrompt = prefs.systemPrompt(),
            provider = provider,
        )
        val result = engine.streamMessage(settings, recognized) { delta ->
            if (delta.isEmpty()) return@streamMessage
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
            }
            is AnthropicResult.Failure -> {
                val error = ErrorMapper.from(result.kind)
                val totalMs = SystemClock.elapsedRealtime() - aiStartedAt
                addDebug(
                    "ai failed: ${provider.label} ${prefs.model}, ${result.kind}, total=${totalMs}ms${result.detail?.let { " - ${it.shortForDebug()}" }.orEmpty()}",
                )
                setStatus("AI error: ${result.kind}")
                val errorText = listOfNotNull(error.banner, error.diegeticLine).joinToString("\n")
                if (replyStarted) {
                    replyOverlay.appendReplyText("\n\n$errorText")
                } else {
                    replyOverlay.revealReply(errorText)
                }
            }
        }
        strokeStore.clear()
        captureController?.setInputEnabled(true)
        busy = false
    }

    private fun schedulePromptFade(strokes: List<InkStroke>) {
        promptFadeJob?.cancel()
        addDebug("prompt fade scheduled in ${PROMPT_FADE_DELAY_MS}ms")
        promptFadeJob = lifecycleScope.launch {
            delay(PROMPT_FADE_DELAY_MS)
            fadePrompt(strokes)
        }
    }

    private suspend fun fadePrompt(strokes: List<InkStroke>) {
        addDebug("prompt fade started")
        renderer.showCapturedStrokes(strokes)
        captureController?.hideRawInkLayer()
        renderer.fadeStrokes(strokes)
        addDebug("prompt fade done")
    }

    private suspend fun writeReplyDelta(delta: String) {
        delta.forEach { char ->
            replyOverlay.appendReplyText(char.toString())
            delay(if (char.isWhitespace()) STREAM_WORD_GAP_MS else STREAM_CHARACTER_GAP_MS)
        }
    }

    private fun openSettings() {
        if (settingsPanel != null) return
        captureController?.setInputEnabled(false)
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
        captureController?.setInputEnabled(true)
    }

    override fun onClearConversation() {
        clearPage()
    }

    override fun onHandwritingStyleChanged() {
        val font = currentHandwritingFont()
        renderer.setHandwritingStyle(font, prefs.handwritingFontSizeSp, prefs.handwritingFontBold)
        if (::replyOverlay.isInitialized) {
            replyOverlay.setHandwritingStyle(font, prefs.handwritingFontSizeSp, prefs.handwritingFontBold)
        }
        val weight = if (prefs.handwritingFontBold) "bold" else "regular"
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

    private fun clearPage() {
        promptFadeJob?.cancel()
        promptFadeJob = null
        commitTimer?.cancel()
        commitJob?.cancel()
        commitJob = null
        busy = false
        if (settingsPanel == null) {
            captureController?.setInputEnabled(true)
        }
        captureController?.clearRawInkLayer()
        engine.clearHistory()
        strokeStore.clear()
        replyOverlay.clearReply()
        renderer.clear()
        renderer.drawInitialHint()
        setStatus("Idle")
        addDebug("page cleared")
    }

    private fun buildTopBar(): LinearLayout {
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            renderTopBar(this)
        }
    }

    private fun renderTopBar(container: LinearLayout = topBar) {
        container.removeAllViews()
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
                addView(iconButton(R.drawable.ic_toolbar_eraser, "Erase page") { clearPage() })
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
            typeface = android.graphics.Typeface.create(
                HandwritingFont.MsMadi.loadTypeface(this@MainActivity),
                android.graphics.Typeface.BOLD,
            )
            paint.isFakeBoldText = true
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
        private const val DEBUG_REPLY_ACTION = "com.inkwell.diary.DEBUG_REPLY"
        private const val DEBUG_REPLY_EXTRA = "reply"
        private const val DEBUG_REPLY_FALLBACK = "This is a handwriting reply test."
        private const val DEBUG_LOG_TAG = "InkwellDebug"
    }
}
