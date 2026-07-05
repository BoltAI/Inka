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
    strokes.forEach { stroke ->
        val points = stroke.points
        if (points.isEmpty()) return@forEach
        if (points.size == 1) {
            val p = points.first()
            canvas.drawPoint(p.x, p.y, paint)
            return@forEach
        }

        val path = Path()
        path.moveTo(points.first().x, points.first().y)
        var previous = points.first()
        for (point in points.drop(1)) {
            path.quadTo(previous.x, previous.y, point.x, point.y)
            previous = point
        }
        canvas.drawPath(path, paint)
    }
}
