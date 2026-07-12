package co.podzim.inka.page

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import android.view.MotionEvent
import android.view.View
import kotlinx.coroutines.delay
import kotlin.math.max

class ReplyOverlayView(
    context: Context,
    private val requestFastPartialRefresh: (View, Rect) -> Unit = EinkRefresher()::requestFastPartialRefresh,
) : View(context) {
    private var replyText: String = ""
    private var contentTopInsetPx: Int = 0
    private var currentPageIndex: Int = 0
    private var cachedPagination: ReplyPagination? = null

    private val textPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.BLACK
        textSize = sp(40f)
        typeface = HandwritingFont.default.loadTypeface(context, HandwritingFontWeight.default)
    }
    private val pagePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(100, 100, 100)
        textAlign = Paint.Align.RIGHT
        textSize = sp(14f)
    }

    val pageCount: Int
        get() = pagination()?.pageStartLines?.size ?: 0

    val pageIndex: Int
        get() = currentPageIndex

    val isBlankContinuationPage: Boolean
        get() = pageCount > 0 && currentPageIndex == pageCount

    init {
        setWillNotDraw(false)
        setBackgroundColor(Color.TRANSPARENT)
        isClickable = false
        isFocusable = false
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
    }

    fun showReply(text: String) {
        replyText = text.ifBlank { "The page has no answer yet." }
        currentPageIndex = 0
        cachedPagination = null
        invalidateReplyArea()
    }

    fun beginReply() {
        replyText = ""
        currentPageIndex = 0
        cachedPagination = null
        invalidateReplyArea()
    }

    fun appendReplyText(text: String) {
        if (text.isEmpty()) return
        replyText += text
        cachedPagination = null
        currentPageIndex = (pageCount - 1).coerceAtLeast(0)
        invalidateReplyArea()
    }

    fun turnPage(delta: Int): Boolean {
        val count = pageCount
        if (count == 0) return false
        val next = (currentPageIndex + delta).coerceIn(0, count)
        if (next == currentPageIndex) return false
        currentPageIndex = next
        invalidateReplyArea(forceFastRefresh = false)
        return true
    }

    suspend fun revealReply(text: String) {
        val target = text.ifBlank { "The page has no answer yet." }
        beginReply()

        target.forEach { char ->
            appendReplyText(char.toString())
            delay(if (char.isWhitespace()) WORD_GAP_MS else CHARACTER_GAP_MS)
        }
    }

    fun clearReply() {
        if (replyText.isEmpty()) return
        replyText = ""
        currentPageIndex = 0
        cachedPagination = null
        invalidateReplyArea()
    }

    fun setHandwritingFont(font: HandwritingFont) {
        setHandwritingStyle(font, DEFAULT_REPLY_TEXT_SIZE_SP, HandwritingFontWeight.default)
    }

    fun setHandwritingStyle(font: HandwritingFont, sizeSp: Float, weightValue: Int) {
        setHandwritingStyle(font, sizeSp, HandwritingFontWeight.fromValue(weightValue))
    }

    fun setHandwritingStyle(font: HandwritingFont, sizeSp: Float, bold: Boolean) {
        setHandwritingStyle(
            font = font,
            sizeSp = sizeSp,
            weight = if (bold) HandwritingFontWeight.Bold else HandwritingFontWeight.Regular,
        )
    }

    fun setHandwritingStyle(font: HandwritingFont, sizeSp: Float, weight: HandwritingFontWeight) {
        textPaint.typeface = font.loadTypeface(context, weight)
        textPaint.textSize = sp(sizeSp)
        textPaint.isFakeBoldText = font.shouldFakeBold(weight)
        cachedPagination = null
        invalidateReplyArea()
    }

    fun setContentTopInset(px: Int) {
        val coerced = px.coerceAtLeast(0)
        if (contentTopInsetPx == coerced) return
        contentTopInsetPx = coerced
        cachedPagination = null
        invalidateReplyArea()
    }

    override fun onSizeChanged(width: Int, height: Int, oldWidth: Int, oldHeight: Int) {
        super.onSizeChanged(width, height, oldWidth, oldHeight)
        cachedPagination = null
        currentPageIndex = currentPageIndex.coerceAtMost(pageCount)
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (replyText.isBlank() || width <= 0 || height <= 0) return

        val pagination = pagination() ?: return
        val pageStarts = pagination.pageStartLines
        currentPageIndex = currentPageIndex.coerceIn(0, pageStarts.size)
        if (currentPageIndex == pageStarts.size) return

        val layout = pagination.layout
        val startLine = pageStarts[currentPageIndex]
        val endLineExclusive = pageStarts.getOrNull(currentPageIndex + 1) ?: layout.lineCount
        val margin = margin()
        val top = replyTop()
        val bottom = replyBottom()
        val assignedTextBottom = top + layout.getLineBottom(endLineExclusive - 1) - layout.getLineTop(startLine)

        canvas.save()
        canvas.clipRect(margin, top, width - margin, minOf(bottom, assignedTextBottom))
        canvas.translate(margin.toFloat(), (top - layout.getLineTop(startLine)).toFloat())
        layout.draw(canvas)
        canvas.restore()

        val forwardMark = if (currentPageIndex < pageStarts.size) "  >" else ""
        canvas.drawText(
            "${currentPageIndex + 1} / ${pageStarts.size}$forwardMark",
            (width - margin).toFloat(),
            (height - margin).toFloat(),
            pagePaint,
        )
    }

    override fun onTouchEvent(event: MotionEvent): Boolean = false

    private fun margin(): Int = (width * 0.055f).toInt().coerceIn(dp(36), dp(96))

    private fun replyTop(): Int = contentTopInsetPx + margin() + dp(REPLY_TOP_EXTRA_DP)

    private fun replyBottom(): Int = (height - margin() - dp(PAGE_FOOTER_HEIGHT_DP)).coerceAtLeast(replyTop() + 1)

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    private fun sp(value: Float): Float = value * resources.displayMetrics.scaledDensity

    private fun invalidateReplyArea(forceFastRefresh: Boolean = true) {
        val area = replyArea()
        invalidate(area)
        if (forceFastRefresh) {
            post { requestFastPartialRefresh(this, area) }
        }
    }

    private fun pagination(): ReplyPagination? {
        if (replyText.isBlank() || width <= 0 || height <= 0) return null
        cachedPagination?.let { return it }
        val contentWidth = max(1, width - margin() * 2)
        val layout = StaticLayout.Builder.obtain(replyText, 0, replyText.length, textPaint, contentWidth)
            .setAlignment(Layout.Alignment.ALIGN_NORMAL)
            .setLineSpacing(0f, LINE_SPACING_MULTIPLIER)
            .setIncludePad(false)
            .build()
        val availableHeight = (replyBottom() - replyTop()).coerceAtLeast(1)
        val pageStartLines = mutableListOf<Int>()
        var startLine = 0
        while (startLine < layout.lineCount) {
            pageStartLines += startLine
            var lastLine = startLine
            while (
                lastLine + 1 < layout.lineCount &&
                layout.getLineBottom(lastLine + 1) - layout.getLineTop(startLine) <= availableHeight
            ) {
                lastLine++
            }
            startLine = lastLine + 1
        }
        return ReplyPagination(layout, pageStartLines).also { cachedPagination = it }
    }

    private fun replyArea(): Rect {
        val margin = margin()
        return Rect(
            margin,
            (replyTop() - textPaint.textSize).toInt().coerceAtLeast(0),
            (width - margin).coerceAtLeast(margin + 1),
            (height - margin).coerceAtLeast(1),
        )
    }

    companion object {
        private const val LINE_SPACING_MULTIPLIER = 1.6f
        private const val CHARACTER_GAP_MS = 24L
        private const val WORD_GAP_MS = 55L
        private const val DEFAULT_REPLY_TEXT_SIZE_SP = 40f
        private const val REPLY_TOP_EXTRA_DP = 24
        private const val PAGE_FOOTER_HEIGHT_DP = 24
    }
}

private data class ReplyPagination(
    val layout: StaticLayout,
    val pageStartLines: List<Int>,
)
