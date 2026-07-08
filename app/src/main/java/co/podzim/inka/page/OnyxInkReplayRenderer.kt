package co.podzim.inka.page

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.os.SystemClock
import android.util.Log
import co.podzim.inka.data.DEFAULT_INK_STROKE_WIDTH_MM
import co.podzim.inka.data.InkPoint
import co.podzim.inka.data.InkStroke
import co.podzim.inka.data.InkStrokeStyle
import co.podzim.inka.ink.OnyxTouchPointListCodec
import com.onyx.android.sdk.api.device.epd.EpdController
import com.onyx.android.sdk.data.note.TouchPoint
import com.onyx.android.sdk.pen.NeoFountainPenWrapper
import com.onyx.android.sdk.pen.PenUtils
import kotlin.math.max
import kotlin.math.roundToInt

interface InkReplayRenderer {
    fun draw(canvas: Canvas, strokes: List<InkStroke>, paint: Paint): Boolean
}

internal class OnyxInkReplayRenderer : InkReplayRenderer {
    private var disabledReason: Throwable? = null

    override fun draw(canvas: Canvas, strokes: List<InkStroke>, paint: Paint): Boolean {
        if (strokes.isEmpty()) return true
        if (disabledReason != null) return false
        if (canvas.width <= 0 || canvas.height <= 0) return false

        return try {
            val startedAt = SystemClock.elapsedRealtime()
            val maxPressure = maxTouchPressure()
            var fountainStrokeCount = 0
            var restoredPointListStrokeCount = 0

            for (stroke in strokes) {
                val (points, restoredPointList) = stroke.replayPoints(maxPressure)
                val strokeWidth = strokeWidthPx(paint.strokeWidth, stroke)
                val strokePaint = fountainPaint(paint, stroke)
                if (restoredPointList) {
                    restoredPointListStrokeCount += 1
                }
                when (points.size) {
                    0 -> Unit
                    1 -> drawSinglePoint(canvas, strokePaint, points.first(), strokeWidth, maxPressure)
                    else -> when (stroke.strokeStyle) {
                        InkStrokeStyle.Fountain -> {
                            if (!drawFountainStroke(canvas, strokePaint, points, strokeWidth, maxPressure)) {
                                return false
                            }
                            fountainStrokeCount += 1
                        }
                    }
                }
            }

            logRenderStrategy(strokes, fountainStrokeCount, restoredPointListStrokeCount, startedAt)
            true
        } catch (error: Throwable) {
            disable(error)
            false
        }
    }

    private fun InkStroke.replayPoints(maxPressure: Float): ReplayPoints {
        val restoredPoints = onyxTouchPointList
            ?.let { OnyxTouchPointListCodec.decode(it) }
            ?.getRenderPoints()
            ?.copyTouchPoints()
            .orEmpty()
        if (restoredPoints.isNotEmpty()) {
            return ReplayPoints(restoredPoints, restoredPointList = true)
        }
        return ReplayPoints(points.toRawTouchPoints(maxPressure), restoredPointList = false)
    }

    private fun strokeWidthPx(baseWidth: Float, stroke: InkStroke): Float {
        val widthMm = stroke.strokeWidthMm.takeIf { it.isFinite() && it > 0f } ?: DEFAULT_INK_STROKE_WIDTH_MM
        return baseWidth * (widthMm / DEFAULT_INK_STROKE_WIDTH_MM) * REPLAY_WIDTH_TWEAK
    }

    private fun fountainPaint(source: Paint, stroke: InkStroke): Paint {
        return Paint(source).apply {
            color = stroke.color
            alpha = ((Color.alpha(stroke.color) / 255f) * source.alpha).roundToInt().coerceIn(0, 255)
            style = Paint.Style.FILL
            strokeCap = Paint.Cap.ROUND
            strokeJoin = Paint.Join.ROUND
            isAntiAlias = true
            isDither = true
        }
    }

    private fun drawFountainStroke(
        canvas: Canvas,
        paint: Paint,
        points: List<TouchPoint>,
        width: Float,
        maxPressure: Float,
    ): Boolean {
        return runCatching {
            val renderPoints = NeoFountainPenWrapper.computeStrokePoints(
                points.copyTouchPointArrayList(),
                FOUNTAIN_SIZE_SCALE,
                width,
                nativePressureDivisor(points, maxPressure),
            )
            PenUtils.drawStrokeByPointSize(canvas, paint, renderPoints, false)
            true
        }.getOrElse { error ->
            Log.i(TAG, "Onyx fountain replay failed: ${error::class.java.simpleName}")
            false
        }
    }

    private fun drawSinglePoint(canvas: Canvas, paint: Paint, point: TouchPoint, width: Float, maxPressure: Float) {
        val normalizedPressure = when {
            maxPressure > 1f && point.pressure > NORMALIZED_PRESSURE_MAX -> point.pressure / maxPressure
            else -> point.pressure
        }.coerceIn(0f, NORMALIZED_PRESSURE_MAX)
        val radius = width * (SINGLE_POINT_MIN_RADIUS_SCALE + normalizedPressure * SINGLE_POINT_PRESSURE_RADIUS_SCALE)
        canvas.drawCircle(point.x, point.y, radius, paint)
    }

    private fun List<InkPoint>.toRawTouchPoints(maxPressure: Float): List<TouchPoint> {
        val shouldExpandNormalizedPressure = maxPressure > NORMALIZED_PRESSURE_MAX &&
            isNotEmpty() &&
            all { it.pressure in 0f..NORMALIZED_PRESSURE_MAX }
        return map { point ->
            val pressure = if (shouldExpandNormalizedPressure) {
                point.pressure * maxPressure
            } else {
                point.pressure
            }
            point.toTouchPoint(pressure.coerceIn(0f, maxPressure.coerceAtLeast(NORMALIZED_PRESSURE_MAX)))
        }
    }

    private fun List<TouchPoint>.copyTouchPoints(): List<TouchPoint> {
        return map { TouchPoint(it) }
    }

    private fun List<TouchPoint>.copyTouchPointArrayList(): ArrayList<TouchPoint> {
        return ArrayList<TouchPoint>(size).also { list ->
            forEach { point -> list.add(TouchPoint(point)) }
        }
    }

    private fun InkPoint.toTouchPoint(pressure: Float): TouchPoint {
        return TouchPoint(
            x,
            y,
            pressure,
            size.coerceAtLeast(0f),
            tiltX,
            tiltY,
            timestampMs,
        )
    }

    private fun maxTouchPressure(): Float {
        return runCatching { EpdController.getMaxTouchPressure() }
            .getOrDefault(DEFAULT_MAX_TOUCH_PRESSURE)
            .takeIf { it.isFinite() && it > 0f }
            ?: DEFAULT_MAX_TOUCH_PRESSURE
    }

    private fun nativePressureDivisor(points: List<TouchPoint>, deviceMaxPressure: Float): Float {
        var maxPointPressure = 0f
        for (point in points) {
            if (point.pressure > 0f) {
                maxPointPressure = max(maxPointPressure, point.pressure)
            }
        }
        if (maxPointPressure <= 0f) return NORMALIZED_PRESSURE_MAX
        return if (maxPointPressure <= NORMALIZED_PRESSURE_THRESHOLD) {
            NORMALIZED_PRESSURE_MAX
        } else {
            max(deviceMaxPressure, maxPointPressure)
        }
    }

    private fun logRenderStrategy(
        strokes: List<InkStroke>,
        fountainStrokeCount: Int,
        restoredPointListStrokeCount: Int,
        startedAt: Long,
    ) {
        val elapsedMs = SystemClock.elapsedRealtime() - startedAt
        if (!strategyLogged) {
            strategyLogged = true
            Log.i(
                TAG,
                "Onyx native ink replay rendered with fountain=$fountainStrokeCount, restoredPointLists=$restoredPointListStrokeCount, strokes=${strokes.size}, points=${strokes.sumOf { it.points.size }}, elapsed=${elapsedMs}ms",
            )
        } else if (elapsedMs > SLOW_RENDER_LOG_MS) {
            Log.i(
                TAG,
                "Onyx native ink replay took ${elapsedMs}ms for strokes=${strokes.size}, points=${strokes.sumOf { it.points.size }}, fountain=$fountainStrokeCount, restoredPointLists=$restoredPointListStrokeCount",
            )
        }
    }

    private fun disable(error: Throwable) {
        disabledReason = error
        Log.i(TAG, "Onyx native ink replay disabled: ${error::class.java.simpleName}")
    }

    private data class ReplayPoints(
        val points: List<TouchPoint>,
        val restoredPointList: Boolean,
    )

    companion object {
        private const val TAG = "OnyxInkReplay"
        private const val SLOW_RENDER_LOG_MS = 250L
        private const val DEFAULT_MAX_TOUCH_PRESSURE = 4096f
        private const val NORMALIZED_PRESSURE_MAX = 1f
        private const val NORMALIZED_PRESSURE_THRESHOLD = 1.5f
        private const val FOUNTAIN_SIZE_SCALE = 1f
        private const val REPLAY_WIDTH_TWEAK = 1.5f
        private const val SINGLE_POINT_MIN_RADIUS_SCALE = 0.42f
        private const val SINGLE_POINT_PRESSURE_RADIUS_SCALE = 0.18f
        private var strategyLogged = false
    }
}
