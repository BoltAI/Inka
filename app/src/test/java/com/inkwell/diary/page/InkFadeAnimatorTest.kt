package com.inkwell.diary.page

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import com.inkwell.diary.data.InkPoint
import com.inkwell.diary.data.InkStroke
import com.inkwell.diary.data.drawInkStrokes
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class InkFadeAnimatorTest {
    @Test
    fun `watchdog returns false when animator throws`() = runTest {
        val completed = runFadeSafely(
            animator = object : InkFadeAnimator {
                override val watchdogMs: Long = 1_000L

                override suspend fun fade(
                    strokes: List<InkStroke>,
                    target: InkFadeTarget,
                    options: InkFadeOptions,
                ) {
                    error("frame failed")
                }
            },
            strokes = strokes(),
            target = fadeTarget(),
            options = InkFadeOptions(),
        )

        assertFalse(completed)
    }

    @Test
    fun `watchdog rethrows external cancellation`() = runTest {
        try {
            runFadeSafely(
                animator = object : InkFadeAnimator {
                    override val watchdogMs: Long = 1_000L

                    override suspend fun fade(
                        strokes: List<InkStroke>,
                        target: InkFadeTarget,
                        options: InkFadeOptions,
                    ) {
                        throw CancellationException("backgrounded")
                    }
                },
                strokes = strokes(),
                target = fadeTarget(),
                options = InkFadeOptions(),
            )
            fail("CancellationException should be rethrown")
        } catch (_: CancellationException) {
        }
    }

    @Test
    fun `watchdog returns true when animator completes`() = runTest {
        val completed = runFadeSafely(
            animator = object : InkFadeAnimator {
                override val watchdogMs: Long = 1_000L

                override suspend fun fade(
                    strokes: List<InkStroke>,
                    target: InkFadeTarget,
                    options: InkFadeOptions,
                ) = Unit
            },
            strokes = strokes(),
            target = fadeTarget(),
            options = InkFadeOptions(),
        )

        assertTrue(completed)
    }

    @Test
    fun `unreached dissolve cells keep original ink visible`() {
        val bitmap = Bitmap.createBitmap(4, 4, Bitmap.Config.ARGB_8888)
        val cell = DissolveCell(
            cellX = 0,
            cellY = 0,
            width = 4,
            height = 4,
            inkPixelIndices = intArrayOf(0, 5, 10),
            seed = 123,
            delayMs = 500L,
            driftAngleRad = 0f,
            driftDistPx = 80f,
            windPhaseRad = 0f,
        )
        val prepared = PreparedDissolve(
            baseBounds = Rect(0, 0, 4, 4),
            outputBounds = Rect(0, 0, 4, 4),
            maskWidth = 4,
            cells = listOf(cell),
            frameBitmap = bitmap,
            framePixels = IntArray(16),
        )

        prepared.renderFrame(elapsedMs = 0L, config = DissolveConfig())

        assertTrue(bitmap.getPixel(0, 0) == Color.BLACK)
        assertTrue(bitmap.getPixel(1, 1) == Color.BLACK)
        assertTrue(bitmap.getPixel(2, 2) == Color.BLACK)
    }

    @Test
    fun `active cells peel away while delayed cells stay in place`() {
        val bitmap = Bitmap.createBitmap(8, 4, Bitmap.Config.ARGB_8888)
        val progress = 0.5f
        val activeSeed = (0..1000).first { seed ->
            DissolvePlanner.survives(seed, pixelIndex = 0, progress = progress)
        }
        val activeCell = DissolveCell(
            cellX = 0,
            cellY = 0,
            width = 4,
            height = 4,
            inkPixelIndices = intArrayOf(0),
            seed = activeSeed,
            delayMs = 0L,
            driftAngleRad = 0f,
            driftDistPx = 2f,
            windPhaseRad = 0f,
        )
        val delayedCell = DissolveCell(
            cellX = 0,
            cellY = 0,
            width = 4,
            height = 4,
            inkPixelIndices = intArrayOf(5),
            seed = 123,
            delayMs = 600L,
            driftAngleRad = 0f,
            driftDistPx = 2f,
            windPhaseRad = 0f,
        )
        val prepared = PreparedDissolve(
            baseBounds = Rect(0, 0, 4, 4),
            outputBounds = Rect(0, 0, 8, 4),
            maskWidth = 4,
            cells = listOf(activeCell, delayedCell),
            frameBitmap = bitmap,
            framePixels = IntArray(32),
        )

        prepared.renderFrame(
            elapsedMs = 500L,
            config = DissolveConfig(
                cellLifeMs = 1000L,
                windShearPx = 0f,
                windFlutterPx = 0f,
                windTailPx = 0f,
            ),
        )

        assertTrue("active ink should leave its original pixel", bitmap.getPixel(0, 0) != Color.BLACK)
        assertTrue("active ink should move with the wind", bitmap.getPixel(2, 0) == Color.BLACK)
        assertTrue("unreached ink should remain in place", bitmap.getPixel(1, 1) == Color.BLACK)
    }

    @Test
    fun `bitmap dissolve frames preserve original pixel colors`() {
        val bitmap = Bitmap.createBitmap(4, 4, Bitmap.Config.ARGB_8888)
        val red = Color.rgb(220, 30, 30)
        val gray = Color.rgb(70, 70, 70)
        val sourcePixels = IntArray(16) { Color.TRANSPARENT }.apply {
            this[0] = red
            this[5] = gray
        }
        val cell = DissolveCell(
            cellX = 0,
            cellY = 0,
            width = 4,
            height = 4,
            inkPixelIndices = intArrayOf(0, 5),
            seed = 123,
            delayMs = 500L,
            driftAngleRad = 0f,
            driftDistPx = 80f,
            windPhaseRad = 0f,
        )
        val prepared = PreparedDissolve(
            baseBounds = Rect(0, 0, 4, 4),
            outputBounds = Rect(0, 0, 4, 4),
            maskWidth = 4,
            cells = listOf(cell),
            frameBitmap = bitmap,
            framePixels = IntArray(16),
            sourcePixels = sourcePixels,
        )

        prepared.renderFrame(elapsedMs = 0L, config = DissolveConfig())

        assertEquals(red, bitmap.getPixel(0, 0))
        assertEquals(gray, bitmap.getPixel(1, 1))
    }

    private fun fadeTarget(): InkFadeTarget {
        val bitmap = Bitmap.createBitmap(24, 24, Bitmap.Config.ARGB_8888)
        return InkFadeTarget(
            pageBitmap = bitmap,
            pageCanvas = Canvas(bitmap),
            inkPaint = Paint(Paint.ANTI_ALIAS_FLAG),
            drawStrokes = { canvas, strokes, paint -> drawInkStrokes(canvas, strokes, paint) },
            clearPage = {},
            clearRect = {},
            render = { _, _ -> },
            drawFrame = { _, _, _, _ -> },
            registerCancelCleanup = {},
        )
    }

    private fun strokes(): List<InkStroke> {
        return listOf(
            InkStroke(
                listOf(
                    InkPoint(x = 1f, y = 1f, pressure = 1f, timestampMs = 1L),
                    InkPoint(x = 10f, y = 10f, pressure = 1f, timestampMs = 2L),
                ),
            ),
        )
    }
}
