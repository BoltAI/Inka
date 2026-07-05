package com.inkwell.diary.recognize

import com.inkwell.diary.data.InkMessage
import com.inkwell.diary.data.InkPoint
import com.inkwell.diary.data.InkStroke
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MlKitInkConverterTest {
    @Test
    fun `reports stroke and point counts with monotonic timestamps`() {
        val message = InkMessage(
            width = 300,
            height = 200,
            strokes = listOf(
                InkStroke(
                    listOf(
                        InkPoint(1f, 1f, 1f, 10L),
                        InkPoint(2f, 2f, 1f, 20L),
                    ),
                ),
                InkStroke(listOf(InkPoint(3f, 3f, 1f, 30L))),
            ),
        )

        val stats = MlKitInkConverter.stats(message)

        assertEquals(2, stats.strokeCount)
        assertEquals(3, stats.pointCount)
        assertTrue(stats.timestampsMonotonic)
        MlKitInkConverter.toInk(message)
    }
}

