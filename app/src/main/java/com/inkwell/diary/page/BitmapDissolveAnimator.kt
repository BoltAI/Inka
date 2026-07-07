package com.inkwell.diary.page

import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

internal suspend fun runBitmapDissolveSafely(
    animator: BitmapDissolveAnimator,
    source: Bitmap,
    target: InkFadeTarget,
): Boolean {
    return try {
        withTimeoutOrNull(animator.watchdogMs) {
            animator.fade(source, target)
            true
        } == true
    } catch (exception: CancellationException) {
        throw exception
    } catch (_: Throwable) {
        false
    }
}

class BitmapDissolveAnimator(
    private val config: DissolveConfig = DissolveConfig(),
) {
    val watchdogMs: Long = config.maxTotalMs + 1000L

    suspend fun fade(source: Bitmap, target: InkFadeTarget) {
        target.clearPage()
        target.pageCanvas.drawBitmap(source, 0f, 0f, Paint(Paint.ANTI_ALIAS_FLAG))
        target.render(false, null)
        val prepared = withContext(Dispatchers.Default) {
            prepare(source, target)
        } ?: run {
            target.clearPage()
            target.render(true, null)
            return
        }
        target.registerCancelCleanup {
            target.clearRect(prepared.outputBounds)
        }
        try {
            runDissolveFrames(prepared, target, config)
        } finally {
            target.registerCancelCleanup(null)
            prepared.recycle()
        }
    }

    private fun prepare(source: Bitmap, target: InkFadeTarget): PreparedDissolve? {
        val contentBounds = visibleBounds(source) ?: return null
        val baseBounds = Rect(
            contentBounds.left - config.boundsPaddingPx,
            contentBounds.top - config.boundsPaddingPx,
            contentBounds.right + config.boundsPaddingPx,
            contentBounds.bottom + config.boundsPaddingPx,
        ).boundedTo(target.pageBitmap.width, target.pageBitmap.height) ?: return null
        val sourcePixels = IntArray(baseBounds.width() * baseBounds.height())
        source.getPixels(
            sourcePixels,
            0,
            baseBounds.width(),
            baseBounds.left,
            baseBounds.top,
            baseBounds.width(),
            baseBounds.height(),
        )
        val mask = BooleanArray(sourcePixels.size) { index -> Color.alpha(sourcePixels[index]) > 0 }
        val cells = DissolvePlanner.cellsForMask(
            mask = mask,
            maskWidth = baseBounds.width(),
            maskHeight = baseBounds.height(),
            config = config,
        )
        if (cells.isEmpty()) return null
        val maxDrift = config.maxDisplacementPx
        val outputBounds = Rect(
            baseBounds.left - maxDrift,
            baseBounds.top - maxDrift,
            baseBounds.right + maxDrift,
            baseBounds.bottom + maxDrift,
        ).boundedTo(target.pageBitmap.width, target.pageBitmap.height) ?: baseBounds
        val frameBitmap = Bitmap.createBitmap(outputBounds.width(), outputBounds.height(), Bitmap.Config.ARGB_8888)
        val framePixels = IntArray(outputBounds.width() * outputBounds.height())
        return PreparedDissolve(
            baseBounds = baseBounds,
            outputBounds = outputBounds,
            maskWidth = baseBounds.width(),
            cells = cells,
            frameBitmap = frameBitmap,
            framePixels = framePixels,
            sourcePixels = sourcePixels,
        )
    }

    private fun visibleBounds(source: Bitmap): Rect? {
        val width = source.width
        val height = source.height
        if (width <= 0 || height <= 0) return null
        val row = IntArray(width)
        var left = width
        var top = height
        var right = -1
        var bottom = -1
        for (y in 0 until height) {
            source.getPixels(row, 0, width, 0, y, width, 1)
            for (x in 0 until width) {
                if (Color.alpha(row[x]) > 0) {
                    if (x < left) left = x
                    if (x > right) right = x
                    if (y < top) top = y
                    if (y > bottom) bottom = y
                }
            }
        }
        return if (right >= left && bottom >= top) {
            Rect(left, top, right + 1, bottom + 1)
        } else {
            null
        }
    }

    private fun Rect.boundedTo(width: Int, height: Int): Rect? {
        val bounded = Rect(
            left.coerceAtLeast(0),
            top.coerceAtLeast(0),
            right.coerceAtMost(width),
            bottom.coerceAtMost(height),
        )
        return if (bounded.width() > 0 && bounded.height() > 0) bounded else null
    }
}
