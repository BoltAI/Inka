package com.inkwell.diary.recognize

import com.google.mlkit.vision.digitalink.recognition.Ink
import com.inkwell.diary.data.InkMessage

data class InkConversionStats(
    val strokeCount: Int,
    val pointCount: Int,
    val timestampsMonotonic: Boolean,
)

object MlKitInkConverter {
    fun toInk(message: InkMessage): Ink {
        val inkBuilder = Ink.builder()
        message.strokes.forEach { stroke ->
            val strokeBuilder = Ink.Stroke.builder()
            stroke.points.forEach { point ->
                strokeBuilder.addPoint(Ink.Point.create(point.x, point.y, point.timestampMs))
            }
            inkBuilder.addStroke(strokeBuilder.build())
        }
        return inkBuilder.build()
    }

    fun stats(message: InkMessage): InkConversionStats {
        val timestamps = message.strokes.flatMap { stroke -> stroke.points.map { it.timestampMs } }
        return InkConversionStats(
            strokeCount = message.strokes.size,
            pointCount = message.strokes.sumOf { it.points.size },
            timestampsMonotonic = timestamps.zipWithNext().all { (a, b) -> a <= b },
        )
    }
}
