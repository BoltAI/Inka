package com.inkwell.diary.page

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.os.SystemClock
import android.util.Log
import com.inkwell.diary.data.InkPoint
import com.inkwell.diary.data.InkStroke
import com.onyx.android.sdk.api.device.epd.EpdController
import com.onyx.android.sdk.data.note.TouchPoint
import com.onyx.android.sdk.pen.NeoPenRender
import com.onyx.android.sdk.pen.NeoFountainPenWrapper
import com.onyx.android.sdk.pen.utils.FountainShapes

internal class OnyxInkReplayRenderer {
    private var disabledReason: Throwable? = null

    fun draw(canvas: Canvas, strokes: List<InkStroke>, paint: Paint): Boolean {
        if (strokes.isEmpty()) return true
        if (disabledReason != null) return false
        if (strokes.size > MAX_NATIVE_REPLAY_STROKES || strokes.sumOf { it.points.size } > MAX_NATIVE_REPLAY_POINTS) {
            logPointLimitSkip(strokes)
            return false
        }
        if (canvas.width <= 0 || canvas.height <= 0) return false

        val replayBitmap = Bitmap.createBitmap(canvas.width, canvas.height, Bitmap.Config.ARGB_8888)
        return try {
            val replayCanvas = Canvas(replayBitmap)
            val startedAt = SystemClock.elapsedRealtime()
            val width = paint.strokeWidth
            val maxPressure = maxTouchPressure()
            val fountainPaint = Paint(paint).apply {
                // The Onyx fountain renderer returns filled closed paths, matching the official demo.
                style = Paint.Style.FILL
                strokeWidth = 0f
                isAntiAlias = true
                isDither = true
            }
            for (stroke in strokes) {
                val points = stroke.points.toTouchPoints(maxPressure)
                when (points.size) {
                    0 -> Unit
                    1 -> drawSinglePoint(replayCanvas, fountainPaint, points.first(), width)
                    else -> if (!drawFountainStroke(replayCanvas, fountainPaint, points, width)) {
                        return false
                    }
                }
            }
            canvas.drawBitmap(replayBitmap, 0f, 0f, null)
            val elapsedMs = SystemClock.elapsedRealtime() - startedAt
            if (elapsedMs > SLOW_RENDER_DISABLE_MS) {
                disable(SlowNativeReplayException(elapsedMs))
            } else if (elapsedMs > SLOW_RENDER_LOG_MS) {
                Log.i(TAG, "Onyx native ink replay took ${elapsedMs}ms")
            }
            true
        } catch (error: Throwable) {
            disable(error)
            false
        } finally {
            replayBitmap.recycle()
        }
    }

    private fun drawFountainStroke(
        canvas: Canvas,
        paint: Paint,
        points: List<TouchPoint>,
        width: Float,
    ): Boolean {
        val pen = FountainShapes.createNeoPenV2(
            width,
            NeoFountainPenWrapper.MIN_FOUNTAIN_PEN_WIDTH,
            DEFAULT_DISPLAY_SCALE,
            DEFAULT_DISPLAY_SCALE,
            SCALE_PRECISION,
            FOUNTAIN_WIDTH_SCALE,
            null,
            true,
            null,
        ) ?: return false
        val penRender = NeoPenRender(pen)
        return try {
            penRender.render(canvas, paint, points)
            true
        } finally {
            penRender.destroyPen()
        }
    }

    private fun drawSinglePoint(canvas: Canvas, paint: Paint, point: TouchPoint, width: Float) {
        val radius = width * (SINGLE_POINT_MIN_RADIUS_SCALE + point.pressure * SINGLE_POINT_PRESSURE_RADIUS_SCALE)
        canvas.drawCircle(point.x, point.y, radius, paint)
    }

    private fun List<InkPoint>.toTouchPoints(maxPressure: Float): List<TouchPoint> {
        val shouldNormalizePressure = any { it.pressure > RAW_PRESSURE_NORMALIZE_THRESHOLD }
        return map { point ->
            val pressure = if (shouldNormalizePressure) {
                point.pressure / maxPressure
            } else {
                point.pressure
            }
            TouchPoint(
                point.x,
                point.y,
                pressure.coerceIn(0f, NORMALIZED_PRESSURE_MAX),
                point.size.coerceAtLeast(0f),
                point.tiltX,
                point.tiltY,
                point.timestampMs,
            )
        }
    }

    private fun maxTouchPressure(): Float {
        return runCatching { EpdController.getMaxTouchPressure() }
            .getOrDefault(DEFAULT_MAX_TOUCH_PRESSURE)
            .takeIf { it.isFinite() && it > 0f }
            ?: DEFAULT_MAX_TOUCH_PRESSURE
    }

    private fun disable(error: Throwable) {
        disabledReason = error
        Log.i(TAG, "Onyx native ink replay disabled: ${error::class.java.simpleName}")
    }

    private fun logPointLimitSkip(strokes: List<InkStroke>) {
        if (pointLimitSkipLogged) return
        pointLimitSkipLogged = true
        Log.i(
            TAG,
            "Onyx native ink replay skipped for dense page: strokes=${strokes.size}, points=${strokes.sumOf { it.points.size }}",
        )
    }

    companion object {
        private const val TAG = "OnyxInkReplay"
        private const val MAX_NATIVE_REPLAY_STROKES = 24
        private const val MAX_NATIVE_REPLAY_POINTS = 900
        private const val SLOW_RENDER_LOG_MS = 150L
        private const val SLOW_RENDER_DISABLE_MS = 650L
        private const val DEFAULT_MAX_TOUCH_PRESSURE = 4096f
        private const val DEFAULT_DISPLAY_SCALE = 1f
        private const val NORMALIZED_PRESSURE_MAX = 1f
        private const val RAW_PRESSURE_NORMALIZE_THRESHOLD = 8f
        private const val SCALE_PRECISION = 1f
        private const val FOUNTAIN_WIDTH_SCALE = 1f
        private const val SINGLE_POINT_MIN_RADIUS_SCALE = 0.42f
        private const val SINGLE_POINT_PRESSURE_RADIUS_SCALE = 0.18f
        private var pointLimitSkipLogged = false
    }
}

private class SlowNativeReplayException(elapsedMs: Long) : RuntimeException("Native replay took ${elapsedMs}ms")
