package com.inkwell.diary.page

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Typeface
import android.text.TextPaint
import android.view.SurfaceView
import com.inkwell.diary.data.InkStroke
import com.inkwell.diary.data.drawInkStrokes
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay

class PageRenderer(
    private val context: Context,
    private val refresher: EinkRefresher = EinkRefresher(),
) {
    private var surfaceView: SurfaceView? = null
    private var bitmap: Bitmap? = null
    private var canvas: Canvas? = null
    private var lastReplyBitmap: Bitmap? = null
    private val tapSignals = Channel<Unit>(Channel.CONFLATED)

    private val paperColor = Color.WHITE
    private val inkPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = INK_COLOR
        style = Paint.Style.STROKE
        strokeWidth = mm(1.0f)
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
    private val bitmapPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val scriptPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.BLACK
        textSize = sp(38f)
        typeface = HandwritingFont.default.loadTypeface(context)
    }
    private val hintPaint = TextPaint(scriptPaint).apply {
        color = Color.rgb(70, 70, 70)
        textSize = sp(24f)
    }
    private val bannerPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.BLACK
        textSize = sp(14f)
        typeface = android.graphics.Typeface.DEFAULT_BOLD
    }

    val hasReply: Boolean
        get() = lastReplyBitmap != null

    fun attach(
        surfaceView: SurfaceView,
        width: Int = surfaceView.width,
        height: Int = surfaceView.height,
    ) {
        this.surfaceView = surfaceView
        val recreated = ensureBitmap(width.coerceAtLeast(1), height.coerceAtLeast(1))
        if (recreated) {
            drawPaper()
        }
        render(full = true)
    }

    fun detach() {
        bitmap?.recycle()
        lastReplyBitmap?.recycle()
        bitmap = null
        lastReplyBitmap = null
        canvas = null
        surfaceView = null
    }

    fun signalTap() {
        tapSignals.trySend(Unit)
    }

    fun setHandwritingFont(font: HandwritingFont) {
        setHandwritingStyle(font, DEFAULT_REPLY_TEXT_SIZE_SP, bold = false)
    }

    fun setHandwritingStyle(font: HandwritingFont, sizeSp: Float, bold: Boolean) {
        val typeface = styledTypeface(font, bold)
        scriptPaint.typeface = typeface
        scriptPaint.textSize = sp(sizeSp)
        scriptPaint.isFakeBoldText = bold
        hintPaint.typeface = typeface
        hintPaint.isFakeBoldText = bold
    }

    fun clear() {
        drawPaper()
        lastReplyBitmap?.recycle()
        lastReplyBitmap = null
        render(full = true)
    }

    fun drawInitialHint() {
        drawPaper()
        val text = "Write here."
        val x = margin().toFloat()
        val y = margin().toFloat() + hintPaint.textSize
        canvas?.drawText(text, x, y, hintPaint)
        render(full = true)
    }

    suspend fun fadePreviousReply() {
        val reply = lastReplyBitmap ?: return
        val c = canvas ?: return
        listOf(180, 100, 40, 0).forEach { alpha ->
            drawPaper()
            if (alpha > 0) {
                bitmapPaint.alpha = alpha
                c.drawBitmap(reply, 0f, 0f, bitmapPaint)
                bitmapPaint.alpha = 255
            }
            render(full = false)
            delay(200)
        }
        reply.recycle()
        lastReplyBitmap = null
        drawPaper()
        render(full = true)
    }

    suspend fun fadeStrokes(strokes: List<InkStroke>) {
        val c = canvas ?: return
        val alphas = listOf(255, 170, 96, 32, 0)
        alphas.forEachIndexed { index, alpha ->
            drawPaper()
            if (alpha > 0) {
                inkPaint.alpha = alpha
                drawInkStrokes(c, strokes, inkPaint)
                inkPaint.alpha = 255
            }
            render(full = false)
            if (index < alphas.lastIndex) {
                delay(STROKE_FADE_STEP_MS)
            }
        }
    }

    fun showReply(text: String) {
        val b = bitmap ?: return
        val c = canvas ?: return
        val pages = ReplyLayout(
            margin = margin(),
            width = b.width,
            height = b.height,
            textPaint = scriptPaint,
        ).pagesFor(text.ifBlank { "The page has no answer yet." })
        val baselineShift = replyTop() - margin()

        drawPaper()
        pages.firstOrNull()?.words.orEmpty().forEach { word ->
            c.drawText(word.text, word.x, word.baseline + baselineShift, scriptPaint)
        }
        if (pages.size > 1) {
            c.drawText("continued in AI Log", margin().toFloat(), b.height - margin().toFloat(), bannerPaint)
        }
        render(full = true)
        lastReplyBitmap?.recycle()
        lastReplyBitmap = b.copy(Bitmap.Config.ARGB_8888, false)
    }

    fun showHint(text: String = "I couldn't read that - try again?") {
        drawPaper()
        val y = margin().toFloat() + scriptPaint.textSize
        canvas?.drawText(text, margin().toFloat(), y, hintPaint)
        render(full = true)
    }

    fun showCapturedStrokes(strokes: List<InkStroke>, dirtyRect: RectF? = null) {
        val c = canvas ?: return
        resetInkPaint()
        drawPaper()
        drawInkStrokes(c, strokes, inkPaint)
        render(full = false, dirtyRect = dirtyRect?.toPaddedRect())
    }

    fun showDiaryError(line: String, banner: String?) {
        drawPaper()
        val y = margin().toFloat() + scriptPaint.textSize
        canvas?.drawText(line, margin().toFloat(), y, scriptPaint)
        if (banner != null) {
            canvas?.drawText(banner, margin().toFloat(), margin().toFloat() / 2f + bannerPaint.textSize, bannerPaint)
        }
        render(full = true)
    }

    private fun ensureBitmap(width: Int, height: Int): Boolean {
        val existing = bitmap
        if (existing != null && existing.width == width && existing.height == height) return false
        existing?.recycle()
        bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        canvas = Canvas(bitmap!!)
        return true
    }

    private fun drawPaper() {
        canvas?.drawColor(paperColor)
    }

    private fun render(full: Boolean, dirtyRect: Rect? = null) {
        val view = surfaceView ?: return
        val b = bitmap ?: return
        val draw = {
            drawBitmapToSurface(view, b, dirtyRect)
        }
        if (full) {
            draw()
            refresher.requestDeepRefresh(view)
        } else {
            refresher.withFastRefresh(view) { draw() }
        }
    }

    private fun drawBitmapToSurface(view: SurfaceView, bitmap: Bitmap, dirtyRect: Rect?) {
        val holder = view.holder
        if (!holder.surface.isValid) return

        val boundedDirty = dirtyRect?.boundedTo(bitmap.width, bitmap.height)
        val screenCanvas = if (boundedDirty != null) {
            holder.lockCanvas(boundedDirty)
        } else {
            holder.lockCanvas()
        } ?: return

        try {
            if (boundedDirty != null) {
                screenCanvas.clipRect(boundedDirty)
            }
            screenCanvas.drawColor(paperColor)
            screenCanvas.drawBitmap(bitmap, 0f, 0f, bitmapPaint)
        } finally {
            holder.unlockCanvasAndPost(screenCanvas)
        }
    }

    private fun RectF.toPaddedRect(): Rect {
        val padding = (inkPaint.strokeWidth * 2f).toInt().coerceAtLeast(6)
        return Rect(
            left.toInt() - padding,
            top.toInt() - padding,
            right.toInt() + padding,
            bottom.toInt() + padding,
        )
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

    private fun margin(): Int = ((bitmap?.width ?: 1200) * 0.055f).toInt().coerceIn(36, 96)

    private fun replyTop(): Int {
        return margin() + dp(REPLY_TOP_EXTRA_DP)
    }

    private fun sp(value: Float): Float = value * context.resources.displayMetrics.scaledDensity

    private fun dp(value: Int): Int = (value * context.resources.displayMetrics.density).toInt()

    private fun mm(value: Float): Float = value * context.resources.displayMetrics.xdpi / MILLIMETERS_PER_INCH

    private fun styledTypeface(font: HandwritingFont, bold: Boolean): Typeface {
        return Typeface.create(font.loadTypeface(context), if (bold) Typeface.BOLD else Typeface.NORMAL)
    }

    private fun resetInkPaint() {
        inkPaint.color = INK_COLOR
        inkPaint.alpha = 255
    }

    companion object {
        private const val MILLIMETERS_PER_INCH = 25.4f
        private const val DEFAULT_REPLY_TEXT_SIZE_SP = 38f
        private const val REPLY_TOP_EXTRA_DP = 24
        private const val INK_COLOR = -0x1000000
        private const val STROKE_FADE_STEP_MS = 125L
    }
}
