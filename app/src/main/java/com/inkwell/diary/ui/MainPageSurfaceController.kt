package com.inkwell.diary.ui

import android.graphics.Color
import android.graphics.Rect
import android.os.SystemClock
import android.view.Gravity
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.view.View
import android.widget.FrameLayout
import android.widget.LinearLayout
import androidx.activity.ComponentActivity
import com.inkwell.diary.data.NotebookElement
import com.inkwell.diary.data.Prefs
import com.inkwell.diary.data.ReplyStyle
import com.inkwell.diary.data.StrokeStore
import com.inkwell.diary.ink.CommitTimer
import com.inkwell.diary.ink.CoroutineCommitScheduler
import com.inkwell.diary.ink.InkCaptureController
import com.inkwell.diary.page.EinkRefresher
import com.inkwell.diary.page.HandwritingFont
import com.inkwell.diary.page.PageCanvasView
import com.inkwell.diary.page.PageRenderer
import com.inkwell.diary.page.ReplyOverlayView
import kotlinx.coroutines.CoroutineScope

internal class MainPageSurfaceController(
    private val activity: ComponentActivity,
    private val prefs: Prefs,
    private val renderer: PageRenderer,
    private val strokeStore: StrokeStore,
    private val einkRefresher: EinkRefresher,
    private val scope: CoroutineScope,
    private val captureCallbacks: InkCaptureController.Callbacks,
    private val buildTopBar: () -> LinearLayout,
    private val currentHandwritingFont: () -> HandwritingFont,
    private val renderCurrentPageOrHint: (fullRefresh: Boolean) -> Unit,
    private val isHistoryOpen: () -> Boolean,
    private val renderHistoryPage: (fullRefresh: Boolean) -> Unit,
    private val currentCanvasElements: () -> List<NotebookElement>,
    private val drawPendingDebugReplyIfReady: () -> Unit,
    private val applyCaptureStateForCurrentUi: () -> Unit,
    private val onCommitRequested: () -> Unit,
    private val addDebug: (String) -> Unit,
) {
    lateinit var root: FrameLayout
        private set
    lateinit var topBar: LinearLayout
        private set
    lateinit var surfaceView: SurfaceView
        private set
    lateinit var pageView: PageCanvasView
        private set
    lateinit var replyOverlay: ReplyOverlayView
        private set
    lateinit var debugPanelController: DebugPanelController
        private set

    var captureController: InkCaptureController? = null
        private set
    var commitTimer: CommitTimer? = null
        private set
    var pageSurfaceInitialized = false
        private set

    private var lastSurfaceWidth = 0
    private var lastSurfaceHeight = 0

    val hasRoot: Boolean
        get() = ::root.isInitialized

    val hasSurfaceView: Boolean
        get() = ::surfaceView.isInitialized

    fun showPage() {
        pageSurfaceInitialized = false
        lastSurfaceWidth = 0
        lastSurfaceHeight = 0
        root = FrameLayout(activity).apply {
            setBackgroundColor(Color.WHITE)
        }
        val toolbarHeight = activity.dp(TOOLBAR_HEIGHT_DP)
        renderer.setContentTopInset(toolbarHeight)
        topBar = buildTopBar()
        surfaceView = SurfaceView(activity).apply {
            setBackgroundColor(Color.WHITE)
        }
        pageView = PageCanvasView(activity)
        replyOverlay = ReplyOverlayView(activity).apply {
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
        debugPanelController = DebugPanelController(activity, MAX_DEBUG_LINES) { SystemClock.uptimeMillis() }
        root.addView(
            debugPanelController.view,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                activity.dp(DEBUG_PANEL_HEIGHT_DP),
                Gravity.TOP,
            ).apply {
                topMargin = toolbarHeight
            },
        )
        addDebug("ready")

        activity.setContentView(root)
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

    fun onPause() {
        captureController?.setInputEnabled(false)
    }

    fun onResume() {
        if (hasSurfaceView) {
            surfaceView.post { applyCaptureStateForCurrentUi() }
        }
    }

    fun onDestroy() {
        captureController?.detach()
    }

    fun onWindowFocusChanged() {
        if (hasSurfaceView) {
            surfaceView.post { captureController?.refreshLimits() }
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
            renderCurrentPageOrHint(true)
            pageSurfaceInitialized = true
        } else if (isHistoryOpen()) {
            renderHistoryPage(surfaceSizeChanged)
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

    private fun installCapture() {
        if (captureController != null) return
        val timer = CommitTimer(
            delayMillisProvider = { prefs.commitDelayMillis },
            scheduler = CoroutineCommitScheduler(scope),
        ) {
            onCommitRequested()
        }
        commitTimer = timer
        captureController = InkCaptureController(
            context = activity,
            surfaceView = surfaceView,
            strokeStore = strokeStore,
            commitTimer = timer,
            pageRectProvider = { surfaceView.localVisibleDrawingRect() },
            excludeRectsProvider = {
                listOfNotNull(
                    topBar.relativeRectTo(surfaceView),
                    debugPanelController.view.takeIf { debugPanelController.visible }?.relativeRectTo(surfaceView),
                )
            },
            callbacks = captureCallbacks,
        ).also { controller ->
            surfaceView.post {
                controller.attach()
                applyCaptureStateForCurrentUi()
            }
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

    private companion object {
        private const val TOOLBAR_HEIGHT_DP = 70
        private const val DEBUG_PANEL_HEIGHT_DP = 220
        private const val MAX_DEBUG_LINES = 80
    }
}
