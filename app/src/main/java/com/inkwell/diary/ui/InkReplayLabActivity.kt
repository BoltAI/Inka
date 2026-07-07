package com.inkwell.diary.ui

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.os.Bundle
import android.util.Log
import android.view.Gravity
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.view.View
import android.view.Window
import android.view.WindowManager
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import com.inkwell.diary.data.InkStroke
import com.inkwell.diary.data.StrokeStore
import com.inkwell.diary.data.drawInkStrokes
import com.inkwell.diary.ink.CommitScheduler
import com.inkwell.diary.ink.CommitTimer
import com.inkwell.diary.ink.InkCaptureController
import com.inkwell.diary.ink.OnyxTouchPointListCodec
import com.inkwell.diary.ink.ScheduledCommit
import com.inkwell.diary.page.EinkRefresher
import com.inkwell.diary.page.OnyxInkReplayRenderer
import com.onyx.android.sdk.api.device.epd.EpdController
import com.onyx.android.sdk.api.device.epd.UpdateMode

private const val INK_REPLAY_LAB_DEFAULT_WIDTH_MM = 1.10f

private enum class InkReplayLabMode {
    Write,
    Review,
    Replay,
}

private object InkReplayLabSession {
    val strokeStore = StrokeStore()
    var candidateWidthMm: Float = INK_REPLAY_LAB_DEFAULT_WIDTH_MM
    var capturedStrokes: List<InkStroke> = emptyList()
    var captureWidth: Int = 1
    var captureHeight: Int = 1

    fun clear(widthMm: Float) {
        strokeStore.clear()
        candidateWidthMm = widthMm
        capturedStrokes = emptyList()
        captureWidth = 1
        captureHeight = 1
    }
}

class InkReplayLabActivity : ComponentActivity(), InkCaptureController.Callbacks {
    private lateinit var rootView: FrameLayout
    private lateinit var surfaceView: SurfaceView
    private lateinit var canvasView: InkReplayLabCanvasView
    private lateinit var statusText: TextView
    private lateinit var modeButton: Button

    private val strokeStore = InkReplayLabSession.strokeStore
    private var captureController: InkCaptureController? = null
    private var candidateWidthMm = InkReplayLabSession.candidateWidthMm
    private var currentStrokes: List<InkStroke> = emptyList()
    private var mode = InkReplayLabMode.Write
    private var ignoreStaleCaptureUpdates = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        requestWindowFeature(Window.FEATURE_NO_TITLE)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        val root = FrameLayout(this).apply {
            setBackgroundColor(Color.WHITE)
        }
        rootView = root
        canvasView = InkReplayLabCanvasView(this)
        surfaceView = SurfaceView(this).apply {
            setBackgroundColor(Color.WHITE)
        }

        root.addView(
            canvasView,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT,
            ),
        )
        root.addView(surfaceView, FrameLayout.LayoutParams(1, 1))
        root.addView(
            buildControls(),
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                dp(CONTROLS_HEIGHT_DP),
                Gravity.BOTTOM,
            ),
        )
        setContentView(root)
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                closeLab()
            }
        })

        surfaceView.holder.addCallback(object : SurfaceHolder.Callback {
            override fun surfaceCreated(holder: SurfaceHolder) {
                surfaceView.post {
                    if (syncSurfaceBounds() && mode == InkReplayLabMode.Write) {
                        installCapture()
                    }
                }
            }

            override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
                surfaceView.post {
                    syncSurfaceBounds()
                    if (mode == InkReplayLabMode.Write) {
                        clearRawSurface()
                        canvasView.setCaptureSurfaceSize(surfaceView.width, surfaceView.height)
                        if (captureController == null) {
                            installCapture()
                        } else {
                            resetRawLayerForLiveInput("surfaceChanged")
                        }
                        EinkRefresher().requestFullRefresh(canvasView)
                    }
                }
            }

            override fun surfaceDestroyed(holder: SurfaceHolder) {
                captureController?.detach()
                captureController = null
            }
        })
        canvasView.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ ->
            surfaceView.post {
                if (syncSurfaceBounds() && mode == InkReplayLabMode.Write) {
                    if (captureController == null) {
                        installCapture()
                    } else {
                        resetRawLayerForLiveInput("layout")
                    }
                }
            }
        }
        canvasView.post {
            startFresh()
        }
    }

    override fun onPause() {
        captureController?.setInputEnabled(false)
        super.onPause()
    }

    override fun onResume() {
        super.onResume()
        surfaceView.post {
            when (mode) {
                InkReplayLabMode.Write -> resetRawLayerForLiveInput("resume")
                InkReplayLabMode.Review -> captureController?.setInputEnabled(false)
                InkReplayLabMode.Replay -> captureController?.setInputEnabled(false)
            }
        }
    }

    override fun onDestroy() {
        captureController?.detach()
        captureController = null
        super.onDestroy()
    }

    override fun onPenDown(): Boolean {
        if (mode != InkReplayLabMode.Write) return false
        ignoreStaleCaptureUpdates = false
        canvasView.showWriteMode(candidateWidthMm)
        updateStatus("Writing")
        return true
    }

    override fun onPenUp() {
        if (mode == InkReplayLabMode.Write) {
            updateStatus("Captured")
        }
    }

    override fun onCommitRequested() = Unit

    override fun onFingerTap(x: Float, y: Float) = Unit

    override fun onFingerSwipeLeft() = Unit

    override fun onFingerSwipeRight() = Unit

    override fun onStrokeCaptured(strokes: List<InkStroke>, dirtyRect: RectF?) {
        if (ignoreStaleCaptureUpdates || mode != InkReplayLabMode.Write) return
        currentStrokes = strokes
        InkReplayLabSession.capturedStrokes = strokes
        InkReplayLabSession.captureWidth = surfaceView.width.coerceAtLeast(1)
        InkReplayLabSession.captureHeight = surfaceView.height.coerceAtLeast(1)
        canvasView.setCapturedStrokes(
            strokes = strokes,
            widthMm = candidateWidthMm,
            captureWidth = InkReplayLabSession.captureWidth,
            captureHeight = InkReplayLabSession.captureHeight,
        )
        updateStatus("Captured")
    }

    private fun buildControls(): View {
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.WHITE)
            setPadding(dp(16), dp(8), dp(16), dp(8))
            statusText = TextView(context).apply {
                paperText(14f)
            }
            addView(
                statusText,
                LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    0,
                    1f,
                ),
            )
            addView(
                controlRow(
                    controlButton("Finish writing").also { modeButton = it },
                    controlButton("Clear") { clearLiveInk() },
                    controlButton("Close") { closeLab() },
                ),
                LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    dp(62),
                ),
            )
        }
    }

    private fun controlRow(vararg controls: View): LinearLayout {
        return LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            controls.forEach { addView(it) }
        }
    }

    private fun controlButton(label: String, onClick: (() -> Unit)? = null): Button {
        return paperButton(label).apply {
            textSize = 12f
            setOnClickListener {
                if (onClick != null) {
                    onClick()
                } else {
                    toggleMode()
                }
            }
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1f)
        }
    }

    private fun toggleMode() {
        when (mode) {
            InkReplayLabMode.Write -> finishWriting()
            InkReplayLabMode.Review -> replayNow()
            InkReplayLabMode.Replay -> returnToWrite()
        }
    }

    private fun finishWriting() {
        if (currentStrokes.isEmpty()) {
            canvasView.showWriteMode(candidateWidthMm)
            updateStatus("No capture")
            return
        }
        mode = InkReplayLabMode.Review
        modeButton.text = "Replay"
        InkReplayLabSession.capturedStrokes = currentStrokes
        InkReplayLabSession.candidateWidthMm = candidateWidthMm
        InkReplayLabSession.captureWidth = surfaceView.width.coerceAtLeast(1)
        InkReplayLabSession.captureHeight = surfaceView.height.coerceAtLeast(1)
        captureController?.setInputEnabled(false)
        captureController?.hideRawInkLayer()
        clearRawSurface()
        syncSurfaceBounds()
        canvasView.showReviewMode(
            strokes = currentStrokes,
            widthMm = candidateWidthMm,
            captureWidth = InkReplayLabSession.captureWidth,
            captureHeight = InkReplayLabSession.captureHeight,
        )
        updateStatus("Finished")
        forceDisplayRefresh("finish")
        Log.i(
            TAG,
            "finished writing strokes=${currentStrokes.size} points=${currentStrokes.sumOf { it.points.size }} raw=${currentStrokes.count { !it.onyxTouchPointList.isNullOrBlank() }} source=${InkReplayLabSession.captureWidth}x${InkReplayLabSession.captureHeight} width=${candidateWidthMm.formatMm()}mm",
        )
    }

    private fun replayNow() {
        if (currentStrokes.isEmpty()) {
            canvasView.showWriteMode(candidateWidthMm)
            updateStatus("No capture")
            return
        }
        mode = InkReplayLabMode.Replay
        InkReplayLabSession.capturedStrokes = currentStrokes
        InkReplayLabSession.candidateWidthMm = candidateWidthMm
        modeButton.text = "Write again"
        captureController?.setInputEnabled(false)
        captureController?.hideRawInkLayer()
        clearRawSurface()
        syncSurfaceBounds()
        canvasView.showReplayMode(
            strokes = currentStrokes,
            widthMm = candidateWidthMm,
            captureWidth = InkReplayLabSession.captureWidth,
            captureHeight = InkReplayLabSession.captureHeight,
        )
        forceDisplayRefresh("replay")
        updateStatus("Replay")
        Log.i(
            TAG,
            "entered replay mode strokes=${currentStrokes.size} points=${currentStrokes.sumOf { it.points.size }} raw=${currentStrokes.count { !it.onyxTouchPointList.isNullOrBlank() }} source=${InkReplayLabSession.captureWidth}x${InkReplayLabSession.captureHeight} width=${candidateWidthMm.formatMm()}mm",
        )
    }

    private fun returnToWrite() {
        mode = InkReplayLabMode.Write
        modeButton.text = "Finish writing"
        InkReplayLabSession.clear(candidateWidthMm)
        strokeStore.clear()
        currentStrokes = emptyList()
        canvasView.showWriteMode(candidateWidthMm)
        syncSurfaceBounds()
        if (captureController == null) {
            installCapture()
        } else {
            resetRawLayerForLiveInput("returnToWrite")
        }
        updateStatus("Write")
    }

    private fun clearLiveInk() {
        ignoreStaleCaptureUpdates = true
        InkReplayLabSession.clear(candidateWidthMm)
        strokeStore.clear()
        currentStrokes = emptyList()
        mode = InkReplayLabMode.Write
        modeButton.text = "Finish writing"
        canvasView.clear(candidateWidthMm)
        syncSurfaceBounds()
        clearRawSurface()
        captureController?.clearRawInkLayer()
        surfaceView.postDelayed(
            {
                resetRawLayerForLiveInput("clear")
                updateStatus("Cleared")
            },
            RAW_LAYER_RESET_DELAY_MS,
        )
        forceDisplayRefresh("clear")
    }

    private fun forceDisplayRefresh(reason: String) {
        canvasView.invalidate()
        rootView.invalidate()
        window.decorView.invalidate()
        rootView.post {
            runCatching { EpdController.enableScreenUpdate(rootView, true) }
            runCatching { EpdController.invalidate(rootView, UpdateMode.GC) }
            runCatching { EpdController.invalidate(window.decorView, UpdateMode.GC) }
            runCatching { EpdController.refreshScreen(rootView, UpdateMode.GC) }
            runCatching { EpdController.refreshScreen(window.decorView, UpdateMode.GC) }
            Log.i(TAG, "requested lab display refresh: $reason")
        }
    }

    private fun closeLab() {
        InkReplayLabSession.clear(candidateWidthMm)
        captureController?.setInputEnabled(false)
        captureController?.clearRawInkLayer()
        finish()
    }

    private fun startFresh() {
        InkReplayLabSession.clear(candidateWidthMm)
        currentStrokes = emptyList()
        mode = InkReplayLabMode.Write
        modeButton.text = "Finish writing"
        canvasView.clear(candidateWidthMm)
        syncSurfaceBounds()
        surfaceView.post {
            syncSurfaceBounds()
            installCapture()
        }
        updateStatus("Write")
    }

    private fun updateStatus(prefix: String) {
        val rawCount = currentStrokes.count { !it.onyxTouchPointList.isNullOrBlank() }
        val points = currentStrokes.sumOf { it.points.size }
        val modeLabel = when (mode) {
            InkReplayLabMode.Write -> "write, then tap Finish writing"
            InkReplayLabMode.Review -> "raw drawing stopped; replay projection is visible, screenshot and compare"
            InkReplayLabMode.Replay -> "replay projection, screenshot and compare"
        }
        statusText.text = "$prefix: $modeLabel. width=${candidateWidthMm.formatMm()}mm, captured=${currentStrokes.size}, points=$points, raw=$rawCount/${currentStrokes.size}"
    }

    private fun installCapture() {
        if (mode != InkReplayLabMode.Write || captureController != null || surfaceView.width <= 1 || surfaceView.height <= 1) return
        clearRawSurface()
        val timer = CommitTimer(
            delayMillisProvider = { 60_000L },
            scheduler = NoopCommitScheduler,
        ) {
            onCommitRequested()
        }
        captureController = InkCaptureController(
            context = this,
            surfaceView = surfaceView,
            strokeStore = strokeStore,
            commitTimer = timer,
            pageRectProvider = {
                Rect(
                    0,
                    0,
                    surfaceView.width.coerceAtLeast(1),
                    surfaceView.height.coerceAtLeast(1),
                )
            },
            excludeRectsProvider = { emptyList() },
            callbacks = this,
            consumeFingerGestures = false,
        ).also { controller ->
            surfaceView.post {
                controller.attach()
                resetRawLayerForLiveInput("attach")
            }
        }
    }

    private fun resetRawLayerForLiveInput(reason: String) {
        val controller = captureController ?: return
        if (mode != InkReplayLabMode.Write || surfaceView.width <= 0 || surfaceView.height <= 0) return
        controller.setInputEnabled(true)
        clearRawSurface()
        controller.clearRawInkLayer()
        surfaceView.postDelayed(
            {
                if (mode != InkReplayLabMode.Write) return@postDelayed
                controller.setInputEnabled(true)
                controller.refreshLimits(resetRawSession = true)
                Log.i(TAG, "raw layer reset for lab write input: $reason")
            },
            RAW_LAYER_RESET_DELAY_MS,
        )
    }

    private fun syncSurfaceBounds(): Boolean {
        if (canvasView.width <= 0 || canvasView.height <= 0) return false
        val bounds = when (mode) {
            InkReplayLabMode.Write -> canvasView.inkSurfaceBounds()
            InkReplayLabMode.Review,
            InkReplayLabMode.Replay -> Rect(0, 0, 1, 1)
        }
        if (bounds.width() <= 0 || bounds.height() <= 0) return false
        surfaceView.visibility = if (mode == InkReplayLabMode.Replay) View.INVISIBLE else View.VISIBLE
        if (mode != InkReplayLabMode.Replay) {
            canvasView.setCaptureSurfaceSize(bounds.width(), bounds.height())
        }

        val current = surfaceView.layoutParams as? FrameLayout.LayoutParams
        val needsUpdate = current == null ||
            current.width != bounds.width() ||
            current.height != bounds.height() ||
            current.leftMargin != bounds.left ||
            current.topMargin != bounds.top
        if (needsUpdate) {
            surfaceView.layoutParams = FrameLayout.LayoutParams(bounds.width(), bounds.height()).apply {
                leftMargin = bounds.left
                topMargin = bounds.top
            }
        }
        return true
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

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    private object NoopCommitScheduler : CommitScheduler {
        override fun schedule(delayMillis: Long, block: () -> Unit): ScheduledCommit {
            return object : ScheduledCommit {
                override fun cancel() = Unit
            }
        }
    }

    private companion object {
        private const val TAG = "InkReplayLab"
        private const val CONTROLS_HEIGHT_DP = 164
        private const val RAW_LAYER_RESET_DELAY_MS = 80L
    }
}

private class InkReplayLabCanvasView(context: android.content.Context) : View(context) {
    private var mode = InkReplayLabMode.Write
    private var capturedStrokes: List<InkStroke> = emptyList()
    private var replayStrokes: List<InkStroke> = emptyList()
    private var candidateWidthMm: Float = INK_REPLAY_LAB_DEFAULT_WIDTH_MM
    private var captureSurfaceWidth = 1
    private var captureSurfaceHeight = 1
    private val onyxRenderer = OnyxInkReplayRenderer()
    private val inkPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.BLACK
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
    private val paperPaint = Paint().apply {
        color = Color.WHITE
        style = Paint.Style.FILL
    }
    private val boundaryPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(42, 42, 42)
        style = Paint.Style.STROKE
        strokeWidth = 2f
    }

    init {
        setWillNotDraw(false)
        setBackgroundColor(Color.WHITE)
    }

    fun showWriteMode(widthMm: Float) {
        mode = InkReplayLabMode.Write
        candidateWidthMm = widthMm
        replayStrokes = emptyList()
        invalidate()
    }

    fun showReviewMode(strokes: List<InkStroke>, widthMm: Float, captureWidth: Int, captureHeight: Int) {
        mode = InkReplayLabMode.Review
        capturedStrokes = strokes
        replayStrokes = strokes
        candidateWidthMm = widthMm
        captureSurfaceWidth = captureWidth.coerceAtLeast(1)
        captureSurfaceHeight = captureHeight.coerceAtLeast(1)
        Log.d(
            TAG,
            "show review strokes=${replayStrokes.size} points=${replayStrokes.sumOf { it.points.size }} raw=${replayStrokes.count { !it.onyxTouchPointList.isNullOrBlank() }} source=${captureSurfaceWidth}x$captureSurfaceHeight target=${inkSurfaceBounds().width()}x${inkSurfaceBounds().height()} width=${candidateWidthMm.formatMm()}mm",
        )
        invalidate()
    }

    fun setCapturedStrokes(strokes: List<InkStroke>, widthMm: Float, captureWidth: Int, captureHeight: Int) {
        capturedStrokes = strokes
        candidateWidthMm = widthMm
        captureSurfaceWidth = captureWidth.coerceAtLeast(1)
        captureSurfaceHeight = captureHeight.coerceAtLeast(1)
    }

    fun showReplayMode(strokes: List<InkStroke>, widthMm: Float, captureWidth: Int, captureHeight: Int) {
        mode = InkReplayLabMode.Replay
        capturedStrokes = strokes
        replayStrokes = strokes
        candidateWidthMm = widthMm
        captureSurfaceWidth = captureWidth.coerceAtLeast(1)
        captureSurfaceHeight = captureHeight.coerceAtLeast(1)
        Log.d(
            TAG,
            "show replay strokes=${replayStrokes.size} points=${replayStrokes.sumOf { it.points.size }} raw=${replayStrokes.count { !it.onyxTouchPointList.isNullOrBlank() }} source=${captureSurfaceWidth}x$captureSurfaceHeight target=${inkSurfaceBounds().width()}x${inkSurfaceBounds().height()} width=${candidateWidthMm.formatMm()}mm",
        )
        invalidate()
    }

    fun clear(widthMm: Float) {
        mode = InkReplayLabMode.Write
        capturedStrokes = emptyList()
        replayStrokes = emptyList()
        candidateWidthMm = widthMm
        invalidate()
    }

    fun setCaptureSurfaceSize(surfaceWidth: Int, surfaceHeight: Int) {
        captureSurfaceWidth = surfaceWidth.coerceAtLeast(1)
        captureSurfaceHeight = surfaceHeight.coerceAtLeast(1)
    }

    fun inkSurfaceBounds(): Rect {
        return Rect(
            0,
            0,
            width.coerceAtLeast(1),
            (height - dp(CONTROLS_HEIGHT_DP)).coerceAtLeast(1),
        )
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val inkRect = inkSurfaceBounds()
        canvas.drawRect(inkRect, paperPaint)
        canvas.drawLine(
            inkRect.left.toFloat(),
            inkRect.bottom.toFloat(),
            inkRect.right.toFloat(),
            inkRect.bottom.toFloat(),
            boundaryPaint,
        )

        val visibleReplayStrokes = when (mode) {
            InkReplayLabMode.Review,
            InkReplayLabMode.Replay -> replayStrokes
            InkReplayLabMode.Write -> emptyList()
        }
        if (visibleReplayStrokes.isEmpty()) return

        val save = canvas.save()
        canvas.clipRect(inkRect)
        val projectedStrokes = visibleReplayStrokes.projectForReplay(
            sourceWidth = captureSurfaceWidth,
            sourceHeight = captureSurfaceHeight,
            target = RectF(inkRect),
        )
        inkPaint.strokeWidth = mm(candidateWidthMm)
        inkPaint.alpha = 255
        if (!onyxRenderer.draw(canvas, projectedStrokes, inkPaint)) {
            drawInkStrokes(canvas, projectedStrokes, inkPaint)
        }
        canvas.restoreToCount(save)
    }

    private fun List<InkStroke>.projectForReplay(sourceWidth: Int, sourceHeight: Int, target: RectF): List<InkStroke> {
        val safeSourceWidth = sourceWidth.coerceAtLeast(1).toFloat()
        val safeSourceHeight = sourceHeight.coerceAtLeast(1).toFloat()
        val scaleX = target.width() / safeSourceWidth
        val scaleY = target.height() / safeSourceHeight
        val isIdentityProjection = target.left == 0f &&
            target.top == 0f &&
            kotlin.math.abs(scaleX - 1f) < IDENTITY_EPSILON &&
            kotlin.math.abs(scaleY - 1f) < IDENTITY_EPSILON

        return map { stroke ->
            val colorCorrectedStroke = stroke.copy(color = Color.BLACK)
            if (isIdentityProjection) {
                colorCorrectedStroke
            } else {
                val matrix = Matrix().apply {
                    setScale(scaleX, scaleY)
                    postTranslate(target.left, target.top)
                }
                colorCorrectedStroke.copy(
                    points = stroke.points.map { point ->
                        point.copy(
                            x = point.x * scaleX + target.left,
                            y = point.y * scaleY + target.top,
                        )
                    },
                    onyxTouchPointList = stroke.onyxTouchPointList
                        ?.let { OnyxTouchPointListCodec.decode(it) }
                        ?.cloneMatrixPoints(matrix)
                        ?.let { OnyxTouchPointListCodec.encode(it) },
                )
            }
        }
    }

    private fun mm(value: Float): Float = value * resources.displayMetrics.xdpi / MILLIMETERS_PER_INCH

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    private companion object {
        private const val TAG = "InkReplayLab"
        private const val CONTROLS_HEIGHT_DP = 164
        private const val MILLIMETERS_PER_INCH = 25.4f
        private const val IDENTITY_EPSILON = 0.001f
    }
}

private fun Float.formatMm(): String = "%.2f".format(this)
