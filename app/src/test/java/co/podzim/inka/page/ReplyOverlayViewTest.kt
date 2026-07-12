package co.podzim.inka.page

import android.app.Activity
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.os.Looper
import android.view.MotionEvent
import android.view.View
import org.junit.Assert.assertFalse
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.Shadows.shadowOf

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
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

    @Test
    fun longReplyCreatesTurnableContinuationPagesAndBlankWritingPage() {
        val view = ReplyOverlayView(RuntimeEnvironment.getApplication()).apply {
            measure(
                View.MeasureSpec.makeMeasureSpec(420, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(320, View.MeasureSpec.EXACTLY),
            )
            layout(0, 0, 420, 320)
            showReply(List(300) { "word$it" }.joinToString(" "))
        }

        assertTrue("expected multiple pages, got ${view.pageCount} at ${view.width}x${view.height}", view.pageCount > 1)
        assertEquals(0, view.pageIndex)
        assertFalse(view.isBlankContinuationPage)

        repeat(view.pageCount - 1) {
            assertTrue(view.turnPage(1))
        }
        assertEquals(view.pageCount - 1, view.pageIndex)
        assertTrue(view.turnPage(1))
        assertTrue(view.isBlankContinuationPage)
        assertFalse(view.turnPage(1))

        assertTrue(view.turnPage(-1))
        assertEquals(view.pageCount - 1, view.pageIndex)
        assertFalse(view.isBlankContinuationPage)
    }

    @Test
    fun streamingReplyFollowsNewestContinuationPage() {
        val view = ReplyOverlayView(RuntimeEnvironment.getApplication()).apply {
            measure(
                View.MeasureSpec.makeMeasureSpec(420, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(320, View.MeasureSpec.EXACTLY),
            )
            layout(0, 0, 420, 320)
            beginReply()
        }

        repeat(300) { index ->
            view.appendReplyText("word$index ")
        }

        assertTrue("expected multiple pages, got ${view.pageCount} at ${view.width}x${view.height}", view.pageCount > 1)
        assertEquals(view.pageCount - 1, view.pageIndex)
    }

    @Test
    fun pageTurnsUseSystemRefreshModeInsteadOfForcedFastRefresh() {
        var fastRefreshCount = 0
        val activity = Robolectric.buildActivity(Activity::class.java).setup().get()
        val view = ReplyOverlayView(RuntimeEnvironment.getApplication()) { _, _ ->
            fastRefreshCount++
        }
        activity.setContentView(view)
        view.apply {
            measure(
                View.MeasureSpec.makeMeasureSpec(420, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(320, View.MeasureSpec.EXACTLY),
            )
            layout(0, 0, 420, 320)
            showReply(List(300) { "word$it" }.joinToString(" "))
        }
        shadowOf(Looper.getMainLooper()).idle()
        assertTrue(fastRefreshCount > 0)
        fastRefreshCount = 0

        assertTrue(view.turnPage(1))
        shadowOf(Looper.getMainLooper()).idle()

        assertEquals(0, fastRefreshCount)

        view.appendReplyText("streamed")
        shadowOf(Looper.getMainLooper()).idle()

        assertEquals(1, fastRefreshCount)
    }
}
