package co.podzim.inka.ui

import android.content.Intent
import android.graphics.RectF
import android.os.Bundle
import android.os.SystemClock
import android.util.Log
import android.view.SurfaceView
import android.view.Window
import android.widget.FrameLayout
import android.widget.LinearLayout
import androidx.activity.OnBackPressedCallback
import androidx.activity.ComponentActivity
import androidx.lifecycle.lifecycleScope
import co.podzim.inka.BuildConfig
import co.podzim.inka.R
import co.podzim.inka.brain.ConversationEngine
import co.podzim.inka.data.AiProvider
import co.podzim.inka.device.DeviceCompatibility
import co.podzim.inka.data.InkStroke
import co.podzim.inka.data.NotebookStore
import co.podzim.inka.data.Prefs
import co.podzim.inka.data.ReplyStyle
import co.podzim.inka.data.SecureStorageUnavailableException
import co.podzim.inka.data.StrokeStore
import co.podzim.inka.ink.CommitTimer
import co.podzim.inka.ink.InkCaptureController
import co.podzim.inka.page.EinkRefresher
import co.podzim.inka.page.HandwritingFont
import co.podzim.inka.page.PageRenderer
import co.podzim.inka.page.ReplyOverlayView
import co.podzim.inka.page.dissolveConfig
import co.podzim.inka.recognize.MlKitRecognitionService
import co.podzim.inka.recognize.RecognitionService
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity(), InkCaptureController.Callbacks {
    private lateinit var prefs: Prefs
    private lateinit var engine: ConversationEngine
    private lateinit var recognitionService: RecognitionService
    private lateinit var notebookStore: NotebookStore
    private lateinit var renderer: PageRenderer
    private lateinit var toolbarRenderer: MainToolbarRenderer
    private lateinit var alertDialogs: MainAlertDialogs
    private lateinit var notebookController: MainNotebookController
    private lateinit var commitController: MainCommitController
    private lateinit var historyController: MainHistoryController
    private lateinit var pageSurfaceController: MainPageSurfaceController
    private lateinit var settingsController: MainSettingsController
    private lateinit var fadeController: MainFadeController
    private lateinit var actionsController: MainNotebookActionsController
    private val einkRefresher = EinkRefresher()
    private val strokeStore = StrokeStore()
    private var commitJob: Job? = null
    private var busy = false
    private var pendingDebugReply: String? = null
    private var lastPenUpElapsedMs: Long? = null
    private var lastCommitRequestedElapsedMs: Long? = null
    private var toolbarImmersive = false
    private var onboardingOverlay: OnboardingFlow? = null
    private var unsupportedDeviceWarningOpen = false

    private val root: FrameLayout get() = pageSurfaceController.root
    private val topBar: LinearLayout get() = pageSurfaceController.topBar
    private val surfaceView: SurfaceView get() = pageSurfaceController.surfaceView
    private val replyOverlay: ReplyOverlayView get() = pageSurfaceController.replyOverlay
    private val debugPanelController: DebugPanelController get() = pageSurfaceController.debugPanelController
    private val captureController: InkCaptureController? get() = pageSurfaceController.captureController
    private val commitTimer: CommitTimer? get() = pageSurfaceController.commitTimer

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        requestWindowFeature(Window.FEATURE_NO_TITLE)
        ImmersiveSystemBars.applyStartupFlags(window)
        configureSystemBars()
        einkRefresher.configureNewSurfaces()

        prefs = Prefs(this)
        engine = ConversationEngine()
        recognitionService = MlKitRecognitionService()
        notebookStore = NotebookStore(filesDir)
        renderer = PageRenderer(this)
        alertDialogs = MainAlertDialogs(this)
        pageSurfaceController = MainPageSurfaceController(
            activity = this,
            prefs = prefs,
            renderer = renderer,
            strokeStore = strokeStore,
            einkRefresher = einkRefresher,
            scope = lifecycleScope,
            captureCallbacks = this,
            buildTopBar = { buildTopBar() },
            currentHandwritingFont = { currentHandwritingFont() },
            renderCurrentPageOrHint = { renderCurrentPageOrHint(it) },
            isHistoryOpen = { historyController.isOpen },
            renderHistoryPage = { historyController.renderHistoryPage(fullRefresh = it) },
            currentCanvasElements = { notebookController.currentCanvasElements() },
            drawPendingDebugReplyIfReady = { drawPendingDebugReplyIfReady() },
            applyCaptureStateForCurrentUi = { applyCaptureStateForCurrentUi() },
            onCommitRequested = { onCommitRequested() },
            addDebug = { addDebug(it) },
        )
        fadeController = MainFadeController(
            prefs = prefs,
            renderer = renderer,
            scope = lifecycleScope,
            captureController = { captureController },
            addDebug = { addDebug(it) },
        )
        notebookController = MainNotebookController(
            prefs = prefs,
            engine = engine,
            notebookStore = notebookStore,
            scope = lifecycleScope,
            renderer = renderer,
            addDebug = { addDebug(it) },
        )
        historyController = MainHistoryController(
            context = this,
            scope = lifecycleScope,
            prefs = prefs,
            renderer = renderer,
            strokeStore = strokeStore,
            replyOverlay = { replyOverlay },
            surfaceView = { surfaceView },
            root = { root },
            einkRefresher = einkRefresher,
            captureController = { captureController },
            commitTimer = { commitTimer },
            alertDialogs = alertDialogs,
            activeNotebook = { notebookController.activeNotebook },
            loadActiveNotebook = { notebookController.loadActiveNotebook() },
            currentCanvasElements = { notebookController.currentCanvasElements() },
            setActiveCanvasId = { notebookController.activeCanvasId = it },
            cancelPromptFade = { fadeController.cancelPromptFade() },
            cancelFadeDisclosure = { fadeController.cancelFadeDisclosure() },
            renderTopBar = { renderTopBar() },
            refreshCaptureEnabled = { refreshCaptureEnabled() },
            showDrawingModeHintIfNeeded = { fadeController.showDrawingModeHintIfNeeded() },
            busy = { busy },
            setStatus = { setStatus(it) },
            addDebug = { addDebug(it) },
        )
        actionsController = MainNotebookActionsController(
            prefs = prefs,
            engine = engine,
            notebookStore = notebookStore,
            renderer = renderer,
            strokeStore = strokeStore,
            scope = lifecycleScope,
            notebookController = notebookController,
            historyController = historyController,
            captureController = { captureController },
            commitTimer = { commitTimer },
            replyOverlay = { replyOverlay },
            settingsOpen = { ::settingsController.isInitialized && settingsController.isOpen },
            cancelCommitJob = {
                commitJob?.cancel()
                commitJob = null
            },
            cancelFadeDisclosure = { fadeController.cancelFadeDisclosure() },
            cancelPromptFade = { fadeController.cancelPromptFade() },
            setBusy = { busy = it },
            renderTopBar = { renderTopBar() },
            refreshCaptureEnabled = { refreshCaptureEnabled() },
            setStatus = { setStatus(it) },
            addDebug = { addDebug(it) },
        )
        settingsController = MainSettingsController(
            activity = this,
            prefs = prefs,
            engine = engine,
            recognitionService = recognitionService,
            scope = lifecycleScope,
            renderer = renderer,
            notebookController = notebookController,
            root = { root },
            topBar = { topBar },
            replyOverlay = { replyOverlay },
            hasPageSurface = { pageSurfaceController.hasRoot },
            captureController = { captureController },
            hideDebugPanel = { debugPanelController.hide() },
            historyOpen = { historyController.isOpen },
            renderHistoryPage = { historyController.renderHistoryPage() },
            clearPage = { actionsController.clearPage() },
            burnNotebook = { actionsController.burnNotebook(dissolveHistoryPage = false) },
            resetOnboarding = { resetOnboardingNow() },
            currentHandwritingFont = { currentHandwritingFont() },
            startActiveNotebookLoad = { startActiveNotebookLoad(renderOnComplete = true) },
            showDrawingModeHintIfNeeded = { fadeController.showDrawingModeHintIfNeeded() },
            renderTopBar = { renderTopBar() },
            refreshCaptureEnabled = { refreshCaptureEnabled() },
            cancelFadeDisclosure = { clearPageLayer -> fadeController.cancelFadeDisclosure(clearPageLayer) },
            addDebug = { addDebug(it) },
        )
        commitController = MainCommitController(
            context = this,
            prefs = prefs,
            engine = engine,
            recognitionService = recognitionService,
            notebookStore = notebookStore,
            renderer = renderer,
            strokeStore = strokeStore,
            surfaceView = { surfaceView },
            replyOverlay = { replyOverlay },
            captureController = { captureController },
            alertDialogs = alertDialogs,
            activeNotebook = { notebookController.activeNotebook },
            setActiveNotebook = { notebookController.setActiveNotebook(it) },
            activeCanvasId = { notebookController.activeCanvasId },
            setActiveCanvasId = { notebookController.activeCanvasId = it },
            voiceChangedForNextRequest = { notebookController.voiceChangedForNextRequest },
            setVoiceChangedForNextRequest = { notebookController.voiceChangedForNextRequest = it },
            lastCommitRequestedElapsedMs = { lastCommitRequestedElapsedMs },
            setBusy = { busy = it },
            schedulePromptFade = { fadeController.schedulePromptFade(it) },
            loadActiveNotebook = { notebookController.loadActiveNotebook() },
            apiKeyForRequest = { apiKeyForRequest(it) },
            onTextReplyCompleted = { showReplyPaginationHelpIfNeeded() },
            setStatus = { setStatus(it) },
            addDebug = { addDebug(it) },
        )
        toolbarRenderer = MainToolbarRenderer(
            context = this,
            callbacks = object : MainToolbarRenderer.Callbacks {
                override fun onToggleImmersive() = toggleToolbarMode()
                override fun onErase() = actionsController.clearPage()
                override fun onRead() = historyController.handleToolbarRead()
                override fun onToggleLog() = toggleDebugPanel()
                override fun onSettings() = settingsController.openSettings()
                override fun onCloseHistory() = historyController.closeHistory()
                override fun onBurnNotebook() = confirmBurnNotebookFromHistory()
                override fun onIconInteraction() = configureSystemBars()
            },
        )
        renderer.setHandwritingStyle(currentHandwritingFont(), prefs.handwritingFontSizeSp, prefs.handwritingFontWeight)
        renderer.setInkFadeStyle(prefs.inkFadeStyle)
        renderer.setDissolveConfig(prefs.dissolveConfig())
        pendingDebugReply = debugReplyFrom(intent)
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (settingsController.isOpen) {
                    if (!settingsController.handleBack()) {
                        settingsController.onCloseSettings()
                    }
                } else if (historyController.isOpen) {
                    historyController.closeHistory()
                } else {
                    finish()
                }
            }
        })

        val firstRunOnboarding = !prefs.onboardingComplete && pendingDebugReply == null
        toolbarImmersive = firstRunOnboarding
        showPage()
        startActiveNotebookLoad(renderOnComplete = true)
        if (firstRunOnboarding) {
            showOnboarding()
        }
        root.post { showUnsupportedDeviceWarningIfNeeded() }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        pendingDebugReply = debugReplyFrom(intent) ?: return
        if (pageSurfaceController.hasRoot) {
            root.post { drawPendingDebugReplyIfReady() }
        } else {
            showPage()
            startActiveNotebookLoad(renderOnComplete = true)
        }
    }

    override fun onPause() {
        pageSurfaceController.onPause()
        super.onPause()
    }

    override fun onResume() {
        super.onResume()
        pageSurfaceController.onResume()
    }

    override fun onDestroy() {
        fadeController.cancelFadeDisclosure()
        pageSurfaceController.onDestroy()
        renderer.detach()
        super.onDestroy()
    }

    override fun onStop() {
        if (::renderer.isInitialized) {
            val cancelledActiveFade = renderer.cancelFadeAnimation()
            if (cancelledActiveFade) {
        fadeController.cancelPromptFade()
            }
        }
        notebookController.activeNotebook?.let { notebook ->
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
            pageSurfaceController.onWindowFocusChanged()
        }
    }

    private fun showOnboarding() {
        if (!pageSurfaceController.hasRoot || onboardingOverlay != null) return
        val flow = OnboardingFlow(
            context = this,
            prefs = prefs,
            engine = engine,
            recognitionService = recognitionService,
            scope = lifecycleScope,
        ) { completeOnboarding() }
        onboardingOverlay = flow
        root.addView(
            flow,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT,
            ),
        )
        flow.bringToFront()
        applyCaptureStateForCurrentUi()
    }

    private fun completeOnboarding() {
        prefs.onboardingComplete = true
        onboardingOverlay?.let { root.removeView(it) }
        onboardingOverlay = null
        toolbarImmersive = false
        renderTopBar()
        applyCaptureStateForCurrentUi()
        root.post { einkRefresher.requestFullRefresh(root) }
    }

    private fun resetOnboardingNow() {
        prefs.onboardingComplete = false
        pendingDebugReply = null
        onboardingOverlay?.let { root.removeView(it) }
        onboardingOverlay = null
        if (historyController.isOpen) {
            historyController.closeHistory()
        }
        actionsController.clearPage()
        engine.clearHistory()
        toolbarImmersive = true
        renderTopBar()
        addDebug("onboarding reset")
        showOnboarding()
        root.post { einkRefresher.requestFullRefresh(root) }
    }

    private fun showPage() {
        pageSurfaceController.showPage()
    }

    private fun renderCurrentPageOrHint(fullRefresh: Boolean = true) {
        if (prefs.replyStyle == ReplyStyle.Drawing) {
            val elements = notebookController.currentCanvasElements()
            if (elements.isNotEmpty()) {
                renderer.showNotebookElements(elements, fullRefresh = fullRefresh)
                return
            }
            if (notebookController.activeNotebook == null) {
                startActiveNotebookLoad(renderOnComplete = true)
            }
        }
        renderer.drawInitialHint()
    }

    override fun onPenDown(): Boolean {
        if (busy) return false
        fadeController.cancelFadeDisclosure()
        if (historyController.isOpen) {
            renderer.showHint("Return to the page to write.")
            addDebug("history page is read-only")
            return false
        }
                fadeController.cancelPromptFade()
        setStatus(if (prefs.replyStyle == ReplyStyle.Drawing) "Sketching" else "Writing")
        replyOverlay.clearReply()
        if (prefs.replyStyle == ReplyStyle.Drawing) {
            fadeController.showDrawingModeHintIfNeeded()
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
        if (historyController.isOpen || strokeStore.isEmpty()) return
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
        if (historyController.isOpen && historyController.handleHistoryTap(x)) {
            return
        }
        if (!historyController.isOpen && prefs.replyStyle == ReplyStyle.Drawing && historyController.handleDrawingPageTurnTap(x)) {
            return
        }
        renderer.signalTap()
    }

    override fun onFingerSwipeLeft() {
        if (historyController.isOpen) {
            historyController.turnHistoryPage(1)
        } else if (replyOverlay.turnPage(1)) {
            setStatus(if (replyOverlay.isBlankContinuationPage) "New page" else "Reply")
        }
    }

    override fun onFingerSwipeRight() {
        if (historyController.isOpen) {
            historyController.turnHistoryPage(-1)
        } else if (replyOverlay.turnPage(-1)) {
            setStatus("Reply")
        }
    }

    override fun onStrokeCaptured(strokes: List<InkStroke>, dirtyRect: RectF?) {
        if (historyController.isOpen || captureController?.isRawDrawingActive() == true) {
            return
        }
        if (prefs.replyStyle == ReplyStyle.Drawing) {
            renderer.showNotebookElements(
                notebookController.currentCanvasElements() + notebookController.currentInkElement(strokes),
                fullRefresh = false,
            )
            return
        }
        renderer.showCapturedStrokes(strokes, dirtyRect)
    }

    private suspend fun commitPage() {
        commitController.commitPage()
    }

    private fun startActiveNotebookLoad(renderOnComplete: Boolean) {
        notebookController.startActiveNotebookLoad(
            renderOnComplete = renderOnComplete,
            rendererInitialized = ::renderer.isInitialized,
            pageSurfaceInitialized = pageSurfaceController.pageSurfaceInitialized,
            settingsPanelOpen = settingsController.isOpen,
            historyOpen = historyController.isOpen,
            strokeStoreEmpty = strokeStore.isEmpty(),
            busy = busy,
        )
    }

    private fun refreshCaptureEnabled() {
        applyCaptureStateForCurrentUi()
    }

    private fun applyCaptureStateForCurrentUi() {
        val controller = captureController ?: return
        when (
            captureInputMode(
                settingsPanelOpen = settingsController.isOpen,
                busy = busy,
                historyOpen = historyController.isOpen,
                modalOverlayOpen = onboardingOverlay != null || unsupportedDeviceWarningOpen,
            )
        ) {
            CaptureInputMode.Disabled -> controller.setInputEnabled(false)
            CaptureInputMode.ReadOnly -> controller.setReadOnlyInputEnabled(true)
            CaptureInputMode.Writable -> controller.setInputEnabled(true)
        }
    }

    private fun confirmBurnNotebookFromHistory() {
        alertDialogs.confirmBurnNotebookFromHistory {
            actionsController.burnNotebook(dissolveHistoryPage = true)
        }
    }

    private fun showUnsupportedDeviceWarningIfNeeded() {
        if (prefs.hasAcknowledgedUnsupportedDeviceWarning) return
        if (DeviceCompatibility.isBooxDevice()) return

        unsupportedDeviceWarningOpen = true
        applyCaptureStateForCurrentUi()
        val shown = alertDialogs.showUnsupportedDeviceWarning(
            onContinue = {
                prefs.hasAcknowledgedUnsupportedDeviceWarning = true
                unsupportedDeviceWarningOpen = false
                applyCaptureStateForCurrentUi()
                addDebug("unsupported device warning acknowledged")
            },
            onQuit = {
                unsupportedDeviceWarningOpen = false
                applyCaptureStateForCurrentUi()
                finish()
            },
        )
        if (!shown) {
            unsupportedDeviceWarningOpen = false
            applyCaptureStateForCurrentUi()
        }
    }

    private fun apiKeyForRequest(provider: AiProvider): String? {
        return try {
            prefs.apiKey(provider)
        } catch (error: SecureStorageUnavailableException) {
            addDebug("secure storage unavailable: ${error.cause?.javaClass?.simpleName ?: error.javaClass.simpleName}")
            alertDialogs.showSecureStorageWarning()
            null
        }
    }

    private fun buildTopBar(): LinearLayout {
        return toolbarRenderer.create(toolbarState())
    }

    private fun renderTopBar(container: LinearLayout = topBar) {
        toolbarRenderer.render(container, toolbarState())
    }

    private fun toolbarState(): MainToolbarState {
        return MainToolbarState(
            historyOpen = historyController.isOpen,
            immersive = toolbarImmersive,
            showLogButton = prefs.showToolbarLogButton,
        )
    }

    private fun toggleToolbarMode() {
        toolbarImmersive = !toolbarImmersive
        if (toolbarImmersive && debugPanelController.hide()) {
            captureController?.refreshLimits()
        }
        renderTopBar()
        configureSystemBars()
        addDebug("toolbar mode: ${if (toolbarImmersive) "immersive" else "full"}")
    }

    private fun toggleDebugPanel() {
        if (debugPanelController.toggle()) {
            addDebug("ai config: ${prefs.provider.label} ${prefs.model}")
        }
        captureController?.refreshLimits()
    }

    private fun setStatus(value: String) {
        addDebug("status: $value")
    }

    private fun addDebug(line: String) {
        val entry = debugPanelController.append(line)
        Log.i(DEBUG_LOG_TAG, entry)
    }

    private fun debugReplyFrom(intent: Intent?): String? {
        if (!BuildConfig.DEBUG || intent?.action != DEBUG_REPLY_ACTION) return null
        return intent.getStringExtra(DEBUG_REPLY_EXTRA)
            ?.takeIf { it.isNotBlank() }
            ?: DEBUG_REPLY_FALLBACK
    }

    private fun drawPendingDebugReplyIfReady() {
        val text = pendingDebugReply ?: return
        if (!pageSurfaceController.hasSurfaceView) return
        if (surfaceView.width <= 0 || surfaceView.height <= 0) return

        pendingDebugReply = null
        setStatus("Reply")
        lifecycleScope.launch {
            replyOverlay.revealReply(text)
            addDebug("debug reply drawn, chars=${text.length}")
            showReplyPaginationHelpIfNeeded()
        }
    }

    private fun showReplyPaginationHelpIfNeeded() {
        if (prefs.hasSeenReplyPaginationHelp || replyOverlay.pageCount <= 1) return
        prefs.hasSeenReplyPaginationHelp = true
        alertDialogs.showReplyPaginationHelp()
    }

    private fun currentHandwritingFont(): HandwritingFont {
        return HandwritingFont.fromKey(prefs.handwritingFontKey)
    }

    private fun configureSystemBars() {
        ImmersiveSystemBars.configure(window)
    }

    companion object {
        private const val DEBUG_REPLY_ACTION = "co.podzim.inka.DEBUG_REPLY"
        private const val DEBUG_REPLY_EXTRA = "reply"
        private const val DEBUG_REPLY_FALLBACK = "This is a handwriting reply test."
        private const val DEBUG_LOG_TAG = "InkaDebug"
    }
}
