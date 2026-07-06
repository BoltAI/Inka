package com.inkwell.diary.page

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.Rect
import android.graphics.RectF
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import android.util.Base64
import com.inkwell.diary.brain.PageSnapshot
import com.inkwell.diary.data.InkFadeStyle
import com.inkwell.diary.data.InkElement
import com.inkwell.diary.data.InkStroke
import com.inkwell.diary.data.Notebook
import com.inkwell.diary.data.NotebookElement
import com.inkwell.diary.data.NotebookPage
import com.inkwell.diary.data.ReplyElement
import com.inkwell.diary.data.SketchElement
import com.inkwell.diary.data.drawInkStrokes
import java.io.ByteArrayOutputStream
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlin.math.min
import kotlin.math.max

class PageRenderer(
    private val context: Context,
    private val refresher: EinkRefresher = EinkRefresher(),
) {
    private var pageView: PageCanvasView? = null
    private var bitmap: Bitmap? = null
    private var canvas: Canvas? = null
    private var lastReplyBitmap: Bitmap? = null
    private var contentTopInsetPx: Int = 0
    private val tapSignals = Channel<Unit>(Channel.CONFLATED)
    private val onyxInkReplayRenderer = OnyxInkReplayRenderer()
    private val steppedFadeAnimator = SteppedFadeAnimator()
    private var dissolveFadeAnimator = DissolveFadeAnimator()
    private var bitmapDissolveAnimator = BitmapDissolveAnimator()
    private var inkFadeStyle = InkFadeStyle.default
    private var activeFadeCleanup: (() -> Unit)? = null
    private val notebookPageCache = object : LinkedHashMap<PageCacheKey, Bitmap>(3, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<PageCacheKey, Bitmap>?): Boolean {
            val shouldRemove = size > MAX_CACHED_NOTEBOOK_PAGES
            if (shouldRemove) {
                eldest?.value?.recycle()
            }
            return shouldRemove
        }
    }

    private val paperColor = Color.WHITE
    private val inkPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = INK_COLOR
        style = Paint.Style.STROKE
        strokeWidth = mm(REPLAY_STROKE_WIDTH_MM)
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
    private val replyInkPaint = Paint(inkPaint).apply {
        color = REPLY_INK_COLOR
        strokeWidth = mm(REPLY_STROKE_WIDTH_MM)
    }
    private val bitmapPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val scriptPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.BLACK
        textSize = sp(38f)
        typeface = HandwritingFont.default.loadTypeface(context, HandwritingFontWeight.default)
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
        pageView: PageCanvasView,
        width: Int = pageView.width,
        height: Int = pageView.height,
    ) {
        this.pageView = pageView
        val recreated = ensureBitmap(width.coerceAtLeast(1), height.coerceAtLeast(1))
        if (recreated) {
            drawPaper()
        }
        render(full = true)
    }

    fun detach() {
        bitmap?.recycle()
        lastReplyBitmap?.recycle()
        notebookPageCache.values.forEach { it.recycle() }
        notebookPageCache.clear()
        bitmap = null
        lastReplyBitmap = null
        canvas = null
        pageView = null
    }

    fun signalTap() {
        tapSignals.trySend(Unit)
    }

    fun setHandwritingFont(font: HandwritingFont) {
        setHandwritingStyle(font, DEFAULT_REPLY_TEXT_SIZE_SP, HandwritingFontWeight.default)
    }

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
        val typeface = font.loadTypeface(context, weight)
        val fakeBold = font.shouldFakeBold(weight)
        scriptPaint.typeface = typeface
        scriptPaint.textSize = sp(sizeSp)
        scriptPaint.isFakeBoldText = fakeBold
        hintPaint.typeface = typeface
        hintPaint.isFakeBoldText = fakeBold
        notebookPageCache.values.forEach { it.recycle() }
        notebookPageCache.clear()
    }

    fun setContentTopInset(px: Int) {
        val coerced = px.coerceAtLeast(0)
        if (contentTopInsetPx == coerced) return
        contentTopInsetPx = coerced
        notebookPageCache.values.forEach { it.recycle() }
        notebookPageCache.clear()
    }

    fun clear() {
        drawPaper()
        lastReplyBitmap?.recycle()
        lastReplyBitmap = null
        notebookPageCache.values.forEach { it.recycle() }
        notebookPageCache.clear()
        render(full = true)
    }

    fun drawInitialHint() {
        drawPaper()
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

    suspend fun fadeStrokes(strokes: List<InkStroke>, includeFullOpacityFrame: Boolean = true) {
        val b = bitmap ?: return
        val c = canvas ?: return
        val animator = if (inkFadeStyle == InkFadeStyle.SimplyFades) {
            steppedFadeAnimator
        } else {
            dissolveFadeAnimator
        }
        val target = InkFadeTarget(
            pageBitmap = b,
            pageCanvas = c,
            inkPaint = inkPaint,
            clearPage = { drawPaper() },
            clearRect = { rect -> clearRect(rect) },
            render = { full, dirtyRect -> render(full = full, dirtyRect = dirtyRect) },
            drawFrame = { frame, left, top, dirtyRect ->
                clearRect(dirtyRect)
                c.drawBitmap(frame, left.toFloat(), top.toFloat(), bitmapPaint)
                render(full = false, dirtyRect = dirtyRect)
            },
            registerCancelCleanup = { cleanup -> activeFadeCleanup = cleanup },
        )
        val completed = try {
            runFadeSafely(animator, strokes, target, InkFadeOptions(includeFullOpacityFrame))
        } finally {
            activeFadeCleanup = null
        }
        if (!completed) {
            drawPaper()
            render(full = true)
        }
    }

    suspend fun dissolveCurrentPage() {
        val b = bitmap ?: return
        val c = canvas ?: return
        val source = b.copy(Bitmap.Config.ARGB_8888, false)
        val target = InkFadeTarget(
            pageBitmap = b,
            pageCanvas = c,
            inkPaint = inkPaint,
            clearPage = { drawPaper() },
            clearRect = { rect -> clearRect(rect) },
            render = { full, dirtyRect -> render(full = full, dirtyRect = dirtyRect) },
            drawFrame = { frame, left, top, dirtyRect ->
                clearRect(dirtyRect)
                c.drawBitmap(frame, left.toFloat(), top.toFloat(), bitmapPaint)
                render(full = false, dirtyRect = dirtyRect)
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
            drawPaper()
            render(full = true)
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
        val y = contentTopInsetPx + margin().toFloat() + scriptPaint.textSize
        canvas?.drawText(text, margin().toFloat(), y, hintPaint)
        render(full = true)
    }

    fun showBottomLine(text: String) {
        val b = bitmap ?: return
        val y = b.height - margin().toFloat() - scriptPaint.textSize
        canvas?.drawText(text, margin().toFloat(), y, hintPaint)
        render(full = false)
    }

    suspend fun fadeBottomLine(text: String) {
        val b = bitmap ?: return
        val y = b.height - margin().toFloat() - scriptPaint.textSize
        listOf(160, 80, 0).forEachIndexed { index, alpha ->
            drawPaper()
            if (alpha > 0) {
                hintPaint.alpha = alpha
                canvas?.drawText(text, margin().toFloat(), y, hintPaint)
                hintPaint.alpha = 255
            }
            render(full = false)
            if (index < 2) delay(STROKE_FADE_STEP_MS)
        }
    }

    fun showCapturedStrokes(strokes: List<InkStroke>, dirtyRect: RectF? = null) {
        val c = canvas ?: return
        resetInkPaint()
        drawPaper()
        drawInkStrokes(c, strokes, inkPaint)
        render(full = false, dirtyRect = dirtyRect?.toPaddedRect())
    }

    fun showNotebookElements(elements: List<NotebookElement>, fullRefresh: Boolean = true) {
        val c = canvas ?: return
        lastReplyBitmap?.recycle()
        lastReplyBitmap = null
        drawPaper()
        drawNotebookElements(c, elements)
        render(full = fullRefresh)
    }

    fun snapshotForVision(
        elements: List<NotebookElement>,
        draftStrokes: List<InkStroke>,
        maxLongEdge: Int = VISION_MAX_LONG_EDGE_PX,
    ): PageSnapshot? {
        val source = bitmap ?: return null
        val sourceBitmap = Bitmap.createBitmap(source.width, source.height, Bitmap.Config.ARGB_8888)
        val sourceCanvas = Canvas(sourceBitmap)
        sourceCanvas.drawColor(Color.WHITE)
        drawNotebookElements(sourceCanvas, elements)
        resetInkPaint()
        drawNotebookInkStrokes(sourceCanvas, draftStrokes)

        val longEdge = max(sourceBitmap.width, sourceBitmap.height).coerceAtLeast(1)
        val scale = min(1f, maxLongEdge.toFloat() / longEdge.toFloat())
        val scaledWidth = max(1, (sourceBitmap.width * scale).toInt())
        val scaledHeight = max(1, (sourceBitmap.height * scale).toInt())
        val scaled = Bitmap.createBitmap(scaledWidth, scaledHeight, Bitmap.Config.ARGB_8888)
        val grayscalePaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG).apply {
            colorFilter = ColorMatrixColorFilter(
                ColorMatrix().apply { setSaturation(0f) },
            )
        }
        Canvas(scaled).drawBitmap(
            sourceBitmap,
            null,
            Rect(0, 0, scaledWidth, scaledHeight),
            grayscalePaint,
        )
        val bytes = ByteArrayOutputStream()
        scaled.compress(Bitmap.CompressFormat.PNG, 100, bytes)
        val encoded = Base64.encodeToString(bytes.toByteArray(), Base64.NO_WRAP)
        scaled.recycle()
        sourceBitmap.recycle()
        return PageSnapshot(
            pngBase64 = encoded,
            width = scaledWidth,
            height = scaledHeight,
            pageWidth = source.width,
            pageHeight = source.height,
        )
    }

    suspend fun revealSketchStrokes(strokes: List<InkStroke>, caption: String) {
        val drawn = mutableListOf<InkStroke>()
        strokes.forEachIndexed { index, stroke ->
            drawn.add(stroke)
            appendSketchStroke(stroke)
            if (index < strokes.lastIndex) {
                delay(REPLY_STROKE_GAP_MS)
            }
        }
        if (caption.isNotBlank()) {
            appendSketchCaption(caption, drawn)
        }
        endSketchReply(fullRefresh = true)
    }

    fun beginSketchReply() {
        resetReplyInkPaint()
    }

    fun appendSketchStroke(stroke: InkStroke) {
        val c = canvas ?: return
        resetReplyInkPaint()
        drawReplyInkStrokes(c, listOf(stroke))
        render(full = false, dirtyRect = strokesBounds(listOf(stroke))?.toPaddedRect())
    }

    suspend fun appendSketchCaption(caption: String, strokes: List<InkStroke>) {
        if (caption.isNotBlank()) {
            revealSketchCaption(caption, strokes)
        }
    }

    fun endSketchReply(fullRefresh: Boolean = false) {
        if (fullRefresh) {
            render(full = true)
        }
    }

    fun renderNotebookPage(
        page: NotebookPage,
        pageIndex: Int,
        pageCount: Int,
        showPageStatus: Boolean,
        pageStatus: String? = null,
        draftStrokes: List<InkStroke> = emptyList(),
        draftReply: String = "",
        fullRefresh: Boolean = true,
        dirtyRect: RectF? = null,
        showContinuationMark: Boolean = false,
    ) {
        val c = canvas ?: return
        lastReplyBitmap?.recycle()
        lastReplyBitmap = null
        val cacheKey = PageCacheKey(page.index, page.hashCode())
        val cachedPage = notebookPageCache[cacheKey]
        if (cachedPage != null) {
            drawPaper()
            c.drawBitmap(cachedPage, 0f, 0f, bitmapPaint)
        } else {
            drawPaper()
            // V2: this is the boundary for page bitmap snapshots once recognition can use vision context.
            drawNotebookElements(c, page.elements)
            notebookPageCache[cacheKey]?.recycle()
            notebookPageCache[cacheKey] = bitmap?.copy(Bitmap.Config.ARGB_8888, false) ?: return
        }
        if (draftReply.isNotBlank()) {
            val top = nextReplyTop(page.elements)
            drawNotebookReply(c, draftReply, top)
        }
        resetInkPaint()
        drawNotebookInkStrokes(c, draftStrokes)
        if (showPageStatus && pageCount > 1) {
            drawPageStatus(pageIndex, pageCount, pageStatus.orEmpty())
        }
        if (showContinuationMark) {
            drawContinuationMark()
        }
        render(full = fullRefresh, dirtyRect = dirtyRect?.toPaddedRect())
    }

    fun notebookReplyFits(page: NotebookPage, replyText: String): Boolean {
        return replyFits(page.elements, replyText)
    }

    fun historyPagesFor(notebook: Notebook): List<NotebookPage> {
        val pages = mutableListOf<NotebookPage>()
        val sorted = notebook.exchanges.sortedBy { it.committedAt }
        val handledCanvasIds = mutableSetOf<String>()
        sorted.forEach { exchange ->
            val canvasId = exchange.canvasId
            if (canvasId != null && handledCanvasIds.add(canvasId)) {
                pages.add(
                    NotebookPage(
                        index = pages.size,
                        elements = sorted.filter { it.canvasId == canvasId }.flatMap { it.notebookElements(notebook.personaId) },
                    ),
                )
                return@forEach
            } else if (canvasId != null) {
                return@forEach
            }
            val baseElements = exchange.notebookElements(notebook.personaId).filterIsInstance<InkElement>()
            val reply = exchange.reply
            val replyText = reply?.text.orEmpty()
            if (replyText.isBlank()) {
                pages.add(NotebookPage(index = pages.size, elements = baseElements))
                return@forEach
            }

            var remaining = replyText
            var pageBase = baseElements
            while (remaining.isNotBlank()) {
                val chunk = fittingReplyChunk(pageBase, remaining)
                if (chunk.isBlank()) {
                    if (pageBase.isEmpty()) {
                        val forced = remaining.take(FORCED_REPLY_CHUNK_CHARS)
                        pages.add(
                            NotebookPage(
                                index = pages.size,
                                elements = listOf(
                                    ReplyElement(
                                        text = forced,
                                        personaId = reply?.personaId ?: notebook.personaId,
                                        createdAt = reply?.createdAt ?: exchange.committedAt,
                                    ),
                                ),
                            ),
                        )
                        remaining = remaining.drop(forced.length).trimStart()
                        continue
                    }
                    pages.add(NotebookPage(index = pages.size, elements = pageBase))
                    pageBase = emptyList()
                    continue
                }
                pages.add(
                    NotebookPage(
                        index = pages.size,
                        elements = pageBase + ReplyElement(
                            text = chunk,
                            personaId = reply?.personaId ?: notebook.personaId,
                            createdAt = reply?.createdAt ?: exchange.committedAt,
                        ),
                    ),
                )
                remaining = remaining.removePrefix(chunk).trimStart()
                pageBase = emptyList()
            }
        }
        return pages.ifEmpty { listOf(NotebookPage(index = 0)) }
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
        canvas?.drawColor(Color.TRANSPARENT, PorterDuff.Mode.CLEAR)
    }

    private fun clearRect(rect: Rect) {
        val c = canvas ?: return
        c.save()
        c.clipRect(rect)
        c.drawColor(Color.TRANSPARENT, PorterDuff.Mode.CLEAR)
        c.restore()
    }

    private fun drawNotebookElements(c: Canvas, elements: List<NotebookElement>) {
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

    private fun nextReplyTop(elements: List<NotebookElement>): Float {
        var cursor = contentTopInsetPx + margin().toFloat() + dp(MANUSCRIPT_TOP_EXTRA_DP)
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

    private fun replyFits(elements: List<NotebookElement>, replyText: String): Boolean {
        if (replyText.isBlank()) return true
        val top = nextReplyTop(elements)
        val layout = textLayout(replyText)
        return top + layout.height <= notebookContentBottom()
    }

    private fun fittingReplyChunk(elements: List<NotebookElement>, text: String): String {
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

    private fun drawNotebookReply(c: Canvas, text: String, top: Float): Boolean {
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

    private fun drawNotebookInkStrokes(c: Canvas, strokes: List<InkStroke>) {
        if (strokes.isEmpty()) return
        if (!onyxInkReplayRenderer.draw(c, strokes, inkPaint)) {
            drawInkStrokes(c, strokes, inkPaint)
        }
    }

    private fun drawReplyInkStrokes(c: Canvas, strokes: List<InkStroke>) {
        if (strokes.isEmpty()) return
        drawInkStrokes(c, strokes, replyInkPaint)
    }

    private suspend fun revealSketchCaption(caption: String, strokes: List<InkStroke>) {
        val c = canvas ?: return
        val words = caption.trim().split(Regex("""\s+""")).filter { it.isNotBlank() }
        if (words.isEmpty()) return
        val top = ((strokesBounds(strokes)?.bottom ?: replyTop().toFloat()) + dp(REPLY_AFTER_INK_GAP_DP))
            .coerceAtMost(notebookContentBottom().toFloat())
        val baseline = top + scriptPaint.textSize
        var text = ""
        words.forEachIndexed { index, word ->
            text = if (text.isBlank()) word else "$text $word"
            clearCaptionBand(top)
            scriptPaint.color = Color.rgb(70, 70, 70)
            c.drawText(text, margin().toFloat(), baseline, scriptPaint)
            scriptPaint.color = Color.BLACK
            render(full = false)
            if (index < words.lastIndex) delay(CAPTION_WORD_GAP_MS)
        }
    }

    private fun clearCaptionBand(top: Float) {
        val b = bitmap ?: return
        clearRect(Rect(0, top.toInt(), b.width, (top + scriptPaint.textSize * 1.8f).toInt()))
    }

    private fun textLayout(text: String): StaticLayout {
        val b = bitmap
        val contentWidth = ((b?.width ?: 1200) - margin() * 2).coerceAtLeast(1)
        return StaticLayout.Builder.obtain(text, 0, text.length, scriptPaint, contentWidth)
            .setAlignment(Layout.Alignment.ALIGN_NORMAL)
            .setLineSpacing(0f, ReplyLayout.LINE_SPACING_MULTIPLIER)
            .setIncludePad(false)
            .build()
    }

    private fun strokesBounds(strokes: List<InkStroke>): RectF? {
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

    private fun notebookContentBottom(): Int {
        val b = bitmap
        return ((b?.height ?: 1600) - margin() - dp(MANUSCRIPT_BOTTOM_RESERVED_DP)).coerceAtLeast(margin())
    }

    private fun drawPageStatus(pageIndex: Int, pageCount: Int, status: String) {
        val b = bitmap ?: return
        val text = "${pageIndex + 1} / ${pageCount.coerceAtLeast(1)}"
        val x = b.width / 2f - bannerPaint.measureText(text) / 2f
        canvas?.drawText(text, x, b.height - margin().toFloat() / 2f, bannerPaint)
        if (status.isNotBlank()) {
            canvas?.drawText(status, margin().toFloat(), b.height - margin().toFloat() / 2f, bannerPaint)
        }
    }

    private fun drawContinuationMark() {
        val b = bitmap ?: return
        val text = "⌐"
        canvas?.drawText(
            text,
            b.width - margin().toFloat() - bannerPaint.measureText(text),
            b.height - margin().toFloat() / 2f,
            bannerPaint,
        )
    }

    private fun render(full: Boolean, dirtyRect: Rect? = null) {
        val view = pageView ?: return
        val b = bitmap ?: return
        val boundedDirty = dirtyRect?.boundedTo(b.width, b.height)
        val draw = {
            view.present(b, boundedDirty)
        }
        if (full) {
            draw()
            refresher.requestDeepRefresh(view)
        } else {
            refresher.withFastRefresh(view) { draw() }
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
        return contentTopInsetPx + margin() + dp(REPLY_TOP_EXTRA_DP)
    }

    private fun sp(value: Float): Float = value * context.resources.displayMetrics.scaledDensity

    private fun dp(value: Int): Int = (value * context.resources.displayMetrics.density).toInt()

    private fun mm(value: Float): Float = value * context.resources.displayMetrics.xdpi / MILLIMETERS_PER_INCH

    private fun resetInkPaint() {
        inkPaint.color = INK_COLOR
        inkPaint.alpha = 255
    }

    private fun resetReplyInkPaint() {
        replyInkPaint.color = REPLY_INK_COLOR
        replyInkPaint.alpha = 255
    }

    companion object {
        private const val MILLIMETERS_PER_INCH = 25.4f
        private const val REPLAY_STROKE_WIDTH_MM = 1.0f
        private const val REPLY_STROKE_WIDTH_MM = 0.9f
        private const val MAX_CACHED_NOTEBOOK_PAGES = 3
        private const val VISION_MAX_LONG_EDGE_PX = 768
        private const val DEFAULT_REPLY_TEXT_SIZE_SP = 38f
        private const val REPLY_TOP_EXTRA_DP = 24
        private const val MANUSCRIPT_TOP_EXTRA_DP = 20
        private const val MANUSCRIPT_BOTTOM_RESERVED_DP = 44
        private const val REPLY_AFTER_INK_GAP_DP = 22
        private const val REPLY_AFTER_REPLY_GAP_DP = 26
        private const val INK_COLOR = -0x1000000
        private val REPLY_INK_COLOR = Color.rgb(40, 84, 160)
        private const val STROKE_FADE_STEP_MS = 125L
        private const val REPLY_STROKE_GAP_MS = 150L
        private const val CAPTION_WORD_GAP_MS = 80L
        private const val FORCED_REPLY_CHUNK_CHARS = 120
    }
}

private data class PageCacheKey(
    val pageIndex: Int,
    val pageHash: Int,
)

private fun com.inkwell.diary.data.Exchange.notebookElements(fallbackPersonaId: String): List<NotebookElement> {
    return buildList {
        ink?.let { ink ->
            add(
                InkElement(
                    strokes = ink.strokes,
                    committedAt = committedAt,
                    recognizedText = ink.recognizedText,
                ),
            )
        }
        reply?.sketch?.let { sketch ->
            add(
                SketchElement(
                    strokes = sketch.strokes,
                    personaId = reply?.personaId ?: fallbackPersonaId,
                    createdAt = reply?.createdAt ?: committedAt,
                ),
            )
        }
        reply?.text?.takeIf { it.isNotBlank() }?.let { text ->
            add(
                ReplyElement(
                    text = text,
                    personaId = reply?.personaId ?: fallbackPersonaId,
                    createdAt = reply?.createdAt ?: committedAt,
                ),
            )
        }
    }
}
