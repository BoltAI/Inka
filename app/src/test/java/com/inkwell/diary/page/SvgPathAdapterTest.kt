package com.inkwell.diary.page

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SvgPathAdapterTest {
    @Test
    fun `parses allowed commands including relative and repeated groups`() {
        val result = SvgPathAdapter.convert(
            paths = listOf("M 0 0 10 0 l 0 10 q 10 10 20 0 c 5 0 10 5 15 5 z"),
            imageWidth = 100,
            imageHeight = 100,
            pageWidth = 200,
            pageHeight = 300,
        )

        assertEquals(0, result.rejectedPaths)
        assertEquals(1, result.strokes.size)
        assertTrue(result.strokes.single().points.size > 6)
        assertEquals(0f, result.strokes.single().points.first().x, 0.01f)
        assertEquals(0f, result.strokes.single().points.first().y, 0.01f)
    }

    @Test
    fun `rejects unsupported and malformed paths without killing the batch`() {
        val result = SvgPathAdapter.convert(
            paths = listOf(
                "M 0 0 A 10 10 0 0 1 20 20",
                "M 0 0 H 20",
                "M 0 0 L 10 nope",
                "M 0 0 L 10 10",
            ),
            imageWidth = 100,
            imageHeight = 100,
            pageWidth = 100,
            pageHeight = 100,
        )

        assertEquals(3, result.rejectedPaths)
        assertEquals(1, result.strokes.size)
        assertEquals(2, result.strokes.single().points.size)
    }

    @Test
    fun `scales image coordinates to page coordinates`() {
        val result = SvgPathAdapter.convert(
            paths = listOf("M 0 0 L 50 50 L 100 100"),
            imageWidth = 100,
            imageHeight = 100,
            pageWidth = 200,
            pageHeight = 300,
        )

        val points = result.strokes.single().points
        val message = points.joinToString { "(${it.x},${it.y})" }
        assertEquals(message, 0f, points[0].x, 0.01f)
        assertEquals(message, 0f, points[0].y, 0.01f)
        assertEquals(message, 100f, points[1].x, 0.01f)
        assertEquals(message, 150f, points[1].y, 0.01f)
        assertEquals(message, 200f, points[2].x, 0.01f)
        assertEquals(message, 300f, points[2].y, 0.01f)
    }

    @Test
    fun `point cap truncates deterministically`() {
        val path = buildString {
            append("M 0 0")
            repeat(20) { index ->
                append(" L ${index + 1} ${index + 1}")
            }
        }

        val result = SvgPathAdapter.convert(
            paths = listOf(path, "M 0 0 L 1 1"),
            imageWidth = 100,
            imageHeight = 100,
            pageWidth = 100,
            pageHeight = 100,
            pointCap = 3,
        )

        assertTrue(result.truncated)
        assertEquals(1, result.strokes.size)
        assertEquals(3, result.strokes.single().points.size)
    }
}
