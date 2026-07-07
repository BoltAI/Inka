package com.inkwell.diary.page

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import android.util.Log
import com.inkwell.diary.data.InkElement
import com.inkwell.diary.data.InkStroke
import com.inkwell.diary.data.NotebookElement
import com.inkwell.diary.data.ReplyElement
import com.inkwell.diary.data.SketchElement
import com.inkwell.diary.data.drawInkStrokes
import kotlin.math.max

internal class NotebookPagePainter(
    private val context: Context,
    private val bitmapProvider: () -> Bitmap?,
    private val contentTopInsetProvider: () -> Int,
    private val inkReplayRenderer: InkReplayRenderer,
    private val fallbackInkDrawer: (Canvas, List<InkStroke>, Paint) -> Unit,
    private val inkPaint: Paint,
    private val replyInkPaint: Paint,
    private val scriptPaint: TextPaint,
) {
    fun drawNotebookElements(c: Canvas, elements: List<NotebookElement>) {
        elements.forEach { element ->
            when (element) {
                is InkElement -> {
                    resetInkPaint()
                    drawNotebookInkStrokes(c, element.strokes)
                }
                is ReplyElement -> {
                    drawNotebookReply(c, element.text, nextReplyTop(elements.takeWhile { it !== element }))
                }
                is SketchElement -> {
                    resetReplyInkPaint()
                    drawReplyInkStrokes(c, element.strokes)
                }
            }
        }
    }

    fun nextReplyTop(elements: List<NotebookElement>): Float {
        var cursor = contentTopInsetProvider() + margin().toFloat() + dp(MANUSCRIPT_TOP_EXTRA_DP)
        elements.forEach { element ->
            when (element) {
                is InkElement -> {
                    val bottom = strokesBounds(element.strokes)?.bottom ?: cursor
                    cursor = max(cursor, bottom + dp(REPLY_AFTER_INK_GAP_DP))
                }
                is ReplyElement -> {
                    val layout = textLayout(element.text)
                    cursor += layout.height + dp(REPLY_AFTER_REPLY_GAP_DP)
                }
                is SketchElement -> {
                    val bottom = strokesBounds(element.strokes)?.bottom ?: cursor
                    cursor = max(cursor, bottom + dp(REPLY_AFTER_INK_GAP_DP))
                }
            }
        }
        return cursor.coerceAtMost(notebookContentBottom().toFloat())
    }

    fun replyFits(elements: List<NotebookElement>, replyText: String): Boolean {
        if (replyText.isBlank()) return true
        val top = nextReplyTop(elements)
        val layout = textLayout(replyText)
        return top + layout.height <= notebookContentBottom()
    }

    fun fittingReplyChunk(elements: List<NotebookElement>, text: String): String {
        if (replyFits(elements, text)) return text
        val tokens = Regex("""\S+\s*|\s+""").findAll(text).map { it.value }.toList()
        val chunk = StringBuilder()
        tokens.forEach { token ->
            val candidate = chunk.toString() + token
            if (replyFits(elements, candidate)) {
                chunk.append(token)
            } else {
                return chunk.toString().trimEnd()
            }
        }
        return chunk.toString().trimEnd()
    }

    fun drawNotebookReply(c: Canvas, text: String, top: Float): Boolean {
        if (text.isBlank()) return false
        val previousColor = scriptPaint.color
        scriptPaint.color = Color.rgb(70, 70, 70)
        val layout = textLayout(text)
        c.save()
        c.translate(margin().toFloat(), top)
        layout.draw(c)
        c.restore()
        scriptPaint.color = previousColor
        return top + layout.height > notebookContentBottom()
    }

    fun drawNotebookInkStrokes(c: Canvas, strokes: List<InkStroke>) {
        if (strokes.isEmpty()) return
        resetInkPaint()
        if (!inkReplayRenderer.draw(c, strokes, inkPaint)) {
            Log.i(TAG, "notebook stroke renderer=canvas reason=onyx_failed strokes=${strokes.size} points=${strokes.sumOf { it.points.size }}")
            fallbackInkDrawer(c, strokes, inkPaint)
        }
    }

    fun drawReplyInkStrokes(c: Canvas, strokes: List<InkStroke>) {
        if (strokes.isEmpty()) return
        resetReplyInkPaint()
        drawInkStrokes(c, strokes, replyInkPaint)
    }

    fun strokesBounds(strokes: List<InkStroke>): RectF? {
        var bounds: RectF? = null
        strokes.forEach { stroke ->
            stroke.points.forEach { point ->
                if (bounds == null) {
                    bounds = RectF(point.x, point.y, point.x, point.y)
                } else {
                    bounds?.union(point.x, point.y)
                }
            }
        }
        return bounds
    }

    fun notebookContentBottom(): Int {
        val b = bitmapProvider()
        return ((b?.height ?: 1600) - margin() - dp(MANUSCRIPT_BOTTOM_RESERVED_DP)).coerceAtLeast(margin())
    }

    private fun textLayout(text: String): StaticLayout {
        val b = bitmapProvider()
        val contentWidth = ((b?.width ?: 1200) - margin() * 2).coerceAtLeast(1)
        return StaticLayout.Builder.obtain(text, 0, text.length, scriptPaint, contentWidth)
            .setAlignment(Layout.Alignment.ALIGN_NORMAL)
            .setLineSpacing(0f, ReplyLayout.LINE_SPACING_MULTIPLIER)
            .setIncludePad(false)
            .build()
    }

    private fun margin(): Int = ((bitmapProvider()?.width ?: 1200) * 0.055f).toInt().coerceIn(36, 96)

    private fun dp(value: Int): Int = (value * context.resources.displayMetrics.density).toInt()

    private fun resetInkPaint() {
        inkPaint.color = INK_COLOR
        inkPaint.alpha = 255
    }

    private fun resetReplyInkPaint() {
        replyInkPaint.color = REPLY_INK_COLOR
        replyInkPaint.alpha = 255
    }

    private companion object {
        private const val TAG = "NotebookPagePainter"
        private const val MANUSCRIPT_TOP_EXTRA_DP = 20
        private const val MANUSCRIPT_BOTTOM_RESERVED_DP = 44
        private const val REPLY_AFTER_INK_GAP_DP = 22
        private const val REPLY_AFTER_REPLY_GAP_DP = 26
        private const val INK_COLOR = -0x1000000
        private const val REPLY_INK_COLOR = -0x1000000
    }
}
