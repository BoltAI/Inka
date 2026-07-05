package com.inkwell.diary.page

import android.graphics.Paint
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import kotlin.math.max

data class PositionedWord(
    val text: String,
    val x: Float,
    val baseline: Float,
    val pageIndex: Int,
)

data class ReplyPage(
    val index: Int,
    val words: List<PositionedWord>,
)

class ReplyLayout(
    private val margin: Int,
    private val width: Int,
    private val height: Int,
    private val textPaint: TextPaint,
) {
    fun pagesFor(text: String): List<ReplyPage> {
        val contentWidth = max(1, width - margin * 2)
        val contentHeight = max(1, height - margin * 2)
        val layout = StaticLayout.Builder.obtain(text, 0, text.length, textPaint, contentWidth)
            .setAlignment(Layout.Alignment.ALIGN_NORMAL)
            .setLineSpacing(0f, LINE_SPACING_MULTIPLIER)
            .setIncludePad(false)
            .build()

        val lineHeight = max(
            1f,
            (textPaint.fontMetrics.descent - textPaint.fontMetrics.ascent) * LINE_SPACING_MULTIPLIER,
        )
        val maxLinesPerPage = max(1, (contentHeight / lineHeight).toInt())
        val words = WORD_REGEX.findAll(text).mapNotNull { match ->
            val start = match.range.first
            val line = layout.getLineForOffset(start)
            val pageIndex = line / maxLinesPerPage
            val pageFirstLine = pageIndex * maxLinesPerPage
            val baseline = margin + layout.getLineBaseline(line) - layout.getLineTop(pageFirstLine)
            PositionedWord(
                text = match.value,
                x = margin + layout.getPrimaryHorizontal(start),
                baseline = baseline.toFloat(),
                pageIndex = pageIndex,
            )
        }.toList()

        return words.groupBy { it.pageIndex }
            .map { (index, pageWords) -> ReplyPage(index, pageWords) }
            .sortedBy { it.index }
    }

    companion object {
        const val LINE_SPACING_MULTIPLIER = 1.6f
        private val WORD_REGEX = Regex("\\S+\\s*")
    }
}
