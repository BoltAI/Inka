package com.inkwell.diary.page

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.view.MotionEvent
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ReplyOverlayViewTest {
    @Test
    fun showReplyDrawsVisibleInkPixels() {
        val view = ReplyOverlayView(RuntimeEnvironment.getApplication()).apply {
            layout(0, 0, 900, 700)
            showReply("A visible handwritten reply.")
        }
        val bitmap = Bitmap.createBitmap(900, 700, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap).apply {
            drawColor(Color.WHITE)
        }

        view.draw(canvas)

        var inkPixels = 0
        for (y in 0 until bitmap.height step 2) {
            for (x in 0 until bitmap.width step 2) {
                val pixel = bitmap.getPixel(x, y)
                if (Color.red(pixel) < 80 && Color.green(pixel) < 80 && Color.blue(pixel) < 80) {
                    inkPixels++
                }
            }
        }
        assertTrue("expected reply overlay to draw visible dark ink", inkPixels > 0)
    }

    @Test
    fun showReplyStartsNearTopOfWritingArea() {
        val view = ReplyOverlayView(RuntimeEnvironment.getApplication()).apply {
            layout(0, 0, 900, 700)
            showReply("A visible handwritten reply.")
        }
        val bitmap = Bitmap.createBitmap(900, 700, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap).apply {
            drawColor(Color.WHITE)
        }

        view.draw(canvas)

        var firstInkY = Int.MAX_VALUE
        for (y in 0 until bitmap.height step 2) {
            for (x in 0 until bitmap.width step 2) {
                val pixel = bitmap.getPixel(x, y)
                if (Color.red(pixel) < 80 && Color.green(pixel) < 80 && Color.blue(pixel) < 80) {
                    firstInkY = y
                    break
                }
            }
            if (firstInkY != Int.MAX_VALUE) break
        }

        assertTrue("expected reply ink to be near the top, firstInkY=$firstInkY", firstInkY < bitmap.height * 0.25f)
    }

    @Test
    fun touchEventsPassThroughToCanvasBelow() {
        val view = ReplyOverlayView(RuntimeEnvironment.getApplication())
        val event = MotionEvent.obtain(0L, 0L, MotionEvent.ACTION_DOWN, 10f, 10f, 0)

        assertFalse(view.dispatchTouchEvent(event))
        event.recycle()
    }
}
