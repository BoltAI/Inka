package com.inkwell.diary.page

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import android.util.Log
import com.inkwell.diary.data.InkFadeStyle
import com.inkwell.diary.data.InkStroke

internal class PageFadeController(
    private val bitmapProvider: () -> Bitmap?,
    private val canvasProvider: () -> Canvas?,
    private val inkPaint: Paint,
    private val bitmapPaint: Paint,
    private val inkReplayRenderer: InkReplayRenderer,
    private val fallbackInkDrawer: (Canvas, List<InkStroke>, Paint) -> Unit,
    private val clearPage: () -> Unit,
    private val clearRect: (Rect) -> Unit,
    private val render: (full: Boolean, dirtyRect: Rect?) -> Unit,
) {
    private val steppedFadeAnimator = SteppedFadeAnimator()
    private var dissolveFadeAnimator = DissolveFadeAnimator()
    private var bitmapDissolveAnimator = BitmapDissolveAnimator()
    private var inkFadeStyle = InkFadeStyle.default
    private var activeFadeCleanup: (() -> Unit)? = null

    fun setInkFadeStyle(style: InkFadeStyle) {
        inkFadeStyle = style
    }

    fun setDissolveConfig(config: DissolveConfig) {
        dissolveFadeAnimator = DissolveFadeAnimator(config)
        bitmapDissolveAnimator = BitmapDissolveAnimator(config)
    }

    fun cancelFadeAnimation(): Boolean {
        val cleanup = activeFadeCleanup ?: return false
        cleanup()
        activeFadeCleanup = null
        return true
    }

    suspend fun fadeStrokes(strokes: List<InkStroke>, includeFullOpacityFrame: Boolean = true) {
        val b = bitmapProvider() ?: return
        val c = canvasProvider() ?: return
        val animator = if (inkFadeStyle == InkFadeStyle.SimplyFades) {
            steppedFadeAnimator
        } else {
            dissolveFadeAnimator
        }
        val strokeDrawer = if (inkFadeStyle == InkFadeStyle.SimplyFades) {
            ::drawSteppedFadeInkStrokes
        } else {
            ::drawDissolveInkStrokes
        }
        val target = InkFadeTarget(
            pageBitmap = b,
            pageCanvas = c,
            inkPaint = inkPaint,
            drawStrokes = strokeDrawer,
            clearPage = clearPage,
            clearRect = clearRect,
            render = render,
            drawFrame = { frame, left, top, dirtyRect ->
                clearRect(dirtyRect)
                c.drawBitmap(frame, left.toFloat(), top.toFloat(), bitmapPaint)
                render(false, dirtyRect)
            },
            registerCancelCleanup = { cleanup -> activeFadeCleanup = cleanup },
        )
        val completed = try {
            runFadeSafely(animator, strokes, target, InkFadeOptions(includeFullOpacityFrame))
        } finally {
            activeFadeCleanup = null
        }
        if (!completed) {
            clearPage()
            render(true, null)
        }
    }

    suspend fun dissolveCurrentPage() {
        val b = bitmapProvider() ?: return
        val c = canvasProvider() ?: return
        val source = b.copy(Bitmap.Config.ARGB_8888, false)
        val target = InkFadeTarget(
            pageBitmap = b,
            pageCanvas = c,
            inkPaint = inkPaint,
            drawStrokes = ::drawDissolveInkStrokes,
            clearPage = clearPage,
            clearRect = clearRect,
            render = render,
            drawFrame = { frame, left, top, dirtyRect ->
                clearRect(dirtyRect)
                c.drawBitmap(frame, left.toFloat(), top.toFloat(), bitmapPaint)
                render(false, dirtyRect)
            },
            registerCancelCleanup = { cleanup -> activeFadeCleanup = cleanup },
        )
        val completed = try {
            runBitmapDissolveSafely(bitmapDissolveAnimator, source, target)
        } finally {
            activeFadeCleanup = null
            source.recycle()
        }
        if (!completed) {
            clearPage()
            render(true, null)
        }
    }

    private fun drawSteppedFadeInkStrokes(c: Canvas, strokes: List<InkStroke>, paint: Paint) {
        Log.i(TAG, "stepped fade stroke renderer=canvas alpha=${paint.alpha} strokes=${strokes.size} points=${strokes.sumOf { it.points.size }}")
        fallbackInkDrawer(c, strokes, paint)
    }

    private fun drawDissolveInkStrokes(c: Canvas, strokes: List<InkStroke>, paint: Paint) {
        if (inkReplayRenderer.draw(c, strokes, paint)) {
            Log.i(TAG, "dissolve stroke renderer=onyx alpha=${paint.alpha} strokes=${strokes.size} points=${strokes.sumOf { it.points.size }}")
            return
        }
        Log.i(TAG, "dissolve stroke renderer=canvas reason=onyx_failed alpha=${paint.alpha} strokes=${strokes.size} points=${strokes.sumOf { it.points.size }}")
        fallbackInkDrawer(c, strokes, paint)
    }

    private companion object {
        private const val TAG = "PageFadeController"
    }
}
