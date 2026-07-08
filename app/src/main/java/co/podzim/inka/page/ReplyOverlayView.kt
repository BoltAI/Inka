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
import com.onyx.android.sdk.api.device.epd.EpdController
import com.onyx.android.sdk.api.device.epd.UpdateMode
import kotlinx.coroutines.delay
import kotlin.math.max

class ReplyOverlayView(context: Context) : View(context) {
    private var replyText: String = ""
    private var contentTopInsetPx: Int = 0

    private val textPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.BLACK
        textSize = sp(40f)
        typeface = HandwritingFont.default.loadTypeface(context, HandwritingFontWeight.default)
    }

    init {
        setWillNotDraw(false)
        setBackgroundColor(Color.TRANSPARENT)
        isClickable = false
        isFocusable = false
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
    }

    fun showReply(text: String) {
        replyText = text.ifBlank { "The page has no answer yet." }
        invalidateReplyArea()
    }

    fun beginReply() {
        replyText = ""
        invalidateReplyArea()
    }

    fun appendReplyText(text: String) {
        if (text.isEmpty()) return
        replyText += text
        invalidateReplyArea()
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
        invalidateReplyArea()
    }

    fun setContentTopInset(px: Int) {
        val coerced = px.coerceAtLeast(0)
        if (contentTopInsetPx == coerced) return
        contentTopInsetPx = coerced
        invalidateReplyArea()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (replyText.isBlank() || width <= 0 || height <= 0) return

        val margin = margin()
        val top = replyTop()
        val contentWidth = max(1, width - margin * 2)
        val layout = StaticLayout.Builder.obtain(replyText, 0, replyText.length, textPaint, contentWidth)
            .setAlignment(Layout.Alignment.ALIGN_NORMAL)
            .setLineSpacing(0f, LINE_SPACING_MULTIPLIER)
            .setIncludePad(false)
            .build()

        canvas.save()
        canvas.translate(margin.toFloat(), top.toFloat())
        layout.draw(canvas)
        canvas.restore()
    }

    override fun onTouchEvent(event: MotionEvent): Boolean = false

    private fun margin(): Int = (width * 0.055f).toInt().coerceIn(dp(36), dp(96))

    private fun replyTop(): Int = contentTopInsetPx + margin() + dp(REPLY_TOP_EXTRA_DP)

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    private fun sp(value: Float): Float = value * resources.displayMetrics.scaledDensity

    private fun invalidateReplyArea() {
        val area = replyArea()
        invalidate(area)
        post { requestPartialRefresh(area) }
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

    private fun requestPartialRefresh(area: Rect) {
        runCatching {
            EpdController.invalidate(this, area.left, area.top, area.right, area.bottom, UpdateMode.DU)
        }
    }

    companion object {
        private const val LINE_SPACING_MULTIPLIER = 1.6f
        private const val CHARACTER_GAP_MS = 24L
        private const val WORD_GAP_MS = 55L
        private const val DEFAULT_REPLY_TEXT_SIZE_SP = 40f
        private const val REPLY_TOP_EXTRA_DP = 24
    }
}
