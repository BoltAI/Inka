package com.inkwell.diary.ui

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.util.Log
import android.view.View
import com.inkwell.diary.data.InkStroke
import com.inkwell.diary.data.drawInkStrokes
import com.inkwell.diary.ink.OnyxTouchPointListCodec
import com.inkwell.diary.page.OnyxInkReplayRenderer

internal class InkReplayLabCanvasView(context: android.content.Context) : View(context) {
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

internal fun Float.formatMm(): String = "%.2f".format(this)
