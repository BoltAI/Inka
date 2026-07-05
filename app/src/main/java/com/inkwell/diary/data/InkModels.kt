package com.inkwell.diary.data

import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class InkPoint(
    val x: Float,
    val y: Float,
    val pressure: Float = 1f,
    @SerialName("t")
    val timestampMs: Long,
    val size: Float = 1f,
    val tiltX: Int = 0,
    val tiltY: Int = 0,
)

@Serializable
data class InkStroke(
    val points: List<InkPoint>,
)

data class InkMessage(
    val strokes: List<InkStroke>,
    val width: Int,
    val height: Int,
)

class StrokeStore {
    private val strokes = mutableListOf<InkStroke>()
    private var current = mutableListOf<InkPoint>()

    fun beginStroke(point: InkPoint) {
        finishCurrent()
        current = mutableListOf(point)
    }

    fun addPoint(point: InkPoint) {
        if (current.isEmpty()) {
            current.add(point)
        } else if (current.last() != point) {
            current.add(point)
        }
    }

    fun replaceCurrentStroke(points: List<InkPoint>) {
        current = points.toMutableList()
    }

    fun finishCurrent() {
        if (current.isNotEmpty()) {
            strokes.add(InkStroke(current.toList()))
            current.clear()
        }
    }

    fun snapshot(width: Int, height: Int): InkMessage {
        finishCurrent()
        return InkMessage(strokes = strokes.toList(), width = width, height = height)
    }

    fun snapshotStrokes(): List<InkStroke> {
        val all = strokes.toMutableList()
        if (current.isNotEmpty()) {
            all.add(InkStroke(current.toList()))
        }
        return all
    }

    fun isEmpty(): Boolean = strokes.isEmpty() && current.isEmpty()

    fun clear() {
        strokes.clear()
        current.clear()
    }
}

fun drawInkStrokes(canvas: Canvas, strokes: List<InkStroke>, paint: Paint) {
    if (strokes.sumOf { it.points.size } > DENSE_REPLAY_POINT_LIMIT) {
        drawDenseInkStrokes(canvas, strokes, paint)
        return
    }

    val strokePaint = Paint(paint).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
        isAntiAlias = true
    }
    val fillPaint = Paint(strokePaint).apply {
        style = Paint.Style.FILL
    }
    val baseWidth = paint.strokeWidth.coerceAtLeast(1f)

    strokes.forEach { stroke ->
        val points = stroke.points
        if (points.isEmpty()) return@forEach
        if (points.size == 1) {
            val p = points.first()
            val width = replayWidth(baseWidth, p.pressure)
            canvas.drawCircle(p.x, p.y, width / 2f, fillPaint)
            return@forEach
        }

        var previous = points.first()
        for (point in points.drop(1)) {
            val width = replayWidth(baseWidth, (previous.pressure + point.pressure) / 2f)
            strokePaint.strokeWidth = width
            canvas.drawLine(previous.x, previous.y, point.x, point.y, strokePaint)
            canvas.drawCircle(previous.x, previous.y, width / 2f, fillPaint)
            canvas.drawCircle(point.x, point.y, width / 2f, fillPaint)
            previous = point
        }
    }
}

private fun drawDenseInkStrokes(canvas: Canvas, strokes: List<InkStroke>, paint: Paint) {
    val strokePaint = Paint(paint).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
        isAntiAlias = true
        strokeWidth = paint.strokeWidth.coerceAtLeast(1f)
    }
    val fillPaint = Paint(strokePaint).apply {
        style = Paint.Style.FILL
    }

    strokes.forEach { stroke ->
        val points = stroke.points
        if (points.isEmpty()) return@forEach
        if (points.size == 1) {
            val p = points.first()
            canvas.drawCircle(p.x, p.y, strokePaint.strokeWidth / 2f, fillPaint)
            return@forEach
        }
        val path = Path()
        val first = points.first()
        path.moveTo(first.x, first.y)
        points.drop(1).forEach { point ->
            path.lineTo(point.x, point.y)
        }
        canvas.drawPath(path, strokePaint)
    }
}

private fun replayWidth(baseWidth: Float, rawPressure: Float): Float {
    val pressure = normalizedPressure(rawPressure)
    return baseWidth * (0.9f + pressure * 0.42f)
}

private fun normalizedPressure(rawPressure: Float): Float {
    val scaled = when {
        !rawPressure.isFinite() || rawPressure <= 0f -> 0.5f
        rawPressure > RAW_PRESSURE_MAX -> 1f
        rawPressure > 8f -> rawPressure / RAW_PRESSURE_MAX
        else -> rawPressure
    }
    return scaled.coerceIn(0.28f, 1f)
}

private const val RAW_PRESSURE_MAX = 4096f
private const val DENSE_REPLAY_POINT_LIMIT = 900
