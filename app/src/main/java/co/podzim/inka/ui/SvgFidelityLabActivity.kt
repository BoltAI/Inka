package co.podzim.inka.ui

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PathMeasure
import android.graphics.RectF
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.Window
import android.view.WindowManager
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.core.graphics.PathParser
import co.podzim.inka.data.InkPoint
import co.podzim.inka.data.InkStroke
import co.podzim.inka.data.drawInkStrokes
import co.podzim.inka.page.SvgFidelityLabStore
import co.podzim.inka.page.SvgPathAdapter
import kotlin.math.hypot
import kotlin.math.max

class SvgFidelityLabActivity : ComponentActivity() {
    private lateinit var canvasView: SvgFidelityCanvasView
    private lateinit var statusText: TextView
    private var cases = emptyList<SvgLabCase>()
    private var selectedIndex = 0

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        requestWindowFeature(Window.FEATURE_NO_TITLE)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        cases = buildCases()

        val root = FrameLayout(this).apply {
            setBackgroundColor(Color.WHITE)
        }
        canvasView = SvgFidelityCanvasView(this)
        root.addView(
            canvasView,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT,
            ),
        )
        root.addView(
            buildControls(),
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                dp(164),
                Gravity.BOTTOM,
            ),
        )
        setContentView(root)
        showCase(0)
    }

    private fun buildControls(): View {
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.WHITE)
            setPadding(dp(16), dp(8), dp(16), dp(8))
            statusText = TextView(context).apply {
                paperText(14f)
            }
            addView(
                statusText,
                LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    0,
                    1f,
                ),
            )
            addView(
                controlRow(
                    controlButton("Prev") { showCase(selectedIndex - 1) },
                    controlButton("Next") { showCase(selectedIndex + 1) },
                    controlButton("Run All") { runAll() },
                    controlButton("Last AI") { showLastAi() },
                    controlButton("Close") { finish() },
                ),
                LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    dp(62),
                ),
            )
        }
    }

    private fun showCase(index: Int) {
        if (cases.isEmpty()) return
        selectedIndex = ((index % cases.size) + cases.size) % cases.size
        val labCase = cases[selectedIndex]
        val result = canvasView.show(labCase)
        statusText.text = result.summary
    }

    private fun runAll() {
        val results = cases.map { canvasView.analyze(it) }
        val failed = results.filter { !it.pass }
        statusText.text = if (failed.isEmpty()) {
            "All ${results.size} SVG fidelity checks passed."
        } else {
            "Failed ${failed.size}/${results.size}: ${failed.joinToString { it.name }}"
        }
        canvasView.show(cases[selectedIndex])
    }

    private fun showLastAi() {
        val index = cases.indexOfFirst { it.lastAi }
        if (index >= 0) {
            showCase(index)
        } else {
            statusText.text = "No saved AI SVG yet. Generate one drawing reply, then reopen this lab."
        }
    }

    private fun buildCases(): List<SvgLabCase> {
        val defaults = listOf(
            SvgLabCase(
                name = "Rectangle",
                paths = listOf("M 60 70 L 270 70 L 270 210 L 60 210 Z"),
            ),
            SvgLabCase(
                name = "Oval Cubics",
                paths = listOf("M 160 55 C 230 55 285 110 285 180 C 285 250 230 305 160 305 C 90 305 35 250 35 180 C 35 110 90 55 160 55 Z"),
            ),
            SvgLabCase(
                name = "Diagonal Stroke",
                paths = listOf("M 45 260 L 300 70"),
            ),
            SvgLabCase(
                name = "Quadratic Curve",
                paths = listOf("M 45 235 Q 165 30 295 235"),
            ),
            SvgLabCase(
                name = "Cubic S Curve",
                paths = listOf("M 45 230 C 95 45 225 330 300 155"),
            ),
            SvgLabCase(
                name = "Relative Rectangle",
                paths = listOf("M 70 80 l 180 0 l 0 125 l -180 0 z"),
            ),
        )
        val last = SvgFidelityLabStore.load(this)?.takeIf { it.paths.isNotEmpty() }?.let {
            SvgLabCase(
                name = "Last AI SVG (${it.paths.size} paths)",
                paths = it.paths,
                imageWidth = it.imageWidth,
                imageHeight = it.imageHeight,
                lastAi = true,
            )
        }
        return listOfNotNull(last) + defaults
    }

    private fun controlRow(vararg controls: View): LinearLayout {
        return LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            controls.forEach { addView(it) }
        }
    }

    private fun controlButton(label: String, onClick: () -> Unit): Button {
        return paperButton(label).apply {
            textSize = 12f
            setOnClickListener { onClick() }
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1f)
        }
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()
}

private data class SvgLabCase(
    val name: String,
    val paths: List<String>,
    val imageWidth: Int = 320,
    val imageHeight: Int = 320,
    val lastAi: Boolean = false,
)

private data class SvgLabResult(
    val name: String,
    val pass: Boolean,
    val rejected: Int,
    val points: Int,
    val boundsErrorPx: Float,
    val avgDistancePx: Float,
    val maxDistancePx: Float,
) {
    val summary: String
        get() {
            val verdict = if (pass) "Faithful" else "Mismatch"
            return "$name: $verdict | rejected=$rejected, points=$points, bounds=${boundsErrorPx.format()}px, avg=${avgDistancePx.format()}px, max=${maxDistancePx.format()}px"
        }
}

private class SvgFidelityCanvasView(context: android.content.Context) : View(context) {
    private var labCase: SvgLabCase? = null
    private var lastResult: SvgLabResult? = null
    private val sourcePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(35, 35, 35)
        style = Paint.Style.STROKE
        strokeWidth = 5f
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
    private val convertedPaint = Paint(sourcePaint).apply {
        color = Color.BLACK
        strokeWidth = 5f
    }
    private val framePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(70, 70, 70)
        style = Paint.Style.STROKE
        strokeWidth = 2f
    }
    private val gridPaint = Paint(framePaint).apply {
        color = Color.rgb(210, 210, 210)
        strokeWidth = 1f
    }
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.BLACK
        textSize = 24f
        typeface = android.graphics.Typeface.DEFAULT_BOLD
    }
    private val notePaint = Paint(textPaint).apply {
        textSize = 18f
        typeface = android.graphics.Typeface.DEFAULT
    }

    fun show(next: SvgLabCase): SvgLabResult {
        labCase = next
        lastResult = analyze(next)
        invalidate()
        return lastResult ?: analyze(next)
    }

    fun analyze(testCase: SvgLabCase): SvgLabResult {
        val drawRect = drawingRect()
        return analyzeCase(testCase, drawRect.width().toInt().coerceAtLeast(1), drawRect.height().toInt().coerceAtLeast(1))
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        canvas.drawColor(Color.WHITE)
        val current = labCase ?: return
        val available = drawingRect()
        val gap = dp(28).toFloat()
        val panelWidth = (available.width() - gap) / 2f
        val left = RectF(available.left, available.top, available.left + panelWidth, available.bottom)
        val right = RectF(left.right + gap, available.top, available.right, available.bottom)

        drawPanel(canvas, left, "Source SVG")
        drawPanel(canvas, right, "Converted Ink")
        drawSource(canvas, current, left)
        drawConverted(canvas, current, right)

        val result = lastResult ?: analyze(current)
        val verdict = if (result.pass) "faithful" else "mismatch"
        canvas.drawText("${current.name}: $verdict", available.left, available.bottom + dp(32).toFloat(), textPaint)
        canvas.drawText("bounds ${result.boundsErrorPx.format()}px  avg ${result.avgDistancePx.format()}px  max ${result.maxDistancePx.format()}px", available.left, available.bottom + dp(58).toFloat(), notePaint)
    }

    private fun drawPanel(canvas: Canvas, rect: RectF, label: String) {
        canvas.drawRect(rect, framePaint)
        val step = rect.width() / 4f
        for (i in 1..3) {
            val x = rect.left + step * i
            val y = rect.top + step * i
            canvas.drawLine(x, rect.top, x, rect.bottom, gridPaint)
            canvas.drawLine(rect.left, y, rect.right, y, gridPaint)
        }
        canvas.drawText(label, rect.left, rect.top - dp(12).toFloat(), textPaint)
    }

    private fun drawSource(canvas: Canvas, testCase: SvgLabCase, rect: RectF) {
        testCase.paths.forEach { raw ->
            val path = sourcePath(raw, testCase, rect) ?: return@forEach
            canvas.drawPath(path, sourcePaint)
        }
    }

    private fun drawConverted(canvas: Canvas, testCase: SvgLabCase, rect: RectF) {
        val converted = SvgPathAdapter.convert(
            paths = testCase.paths,
            imageWidth = testCase.imageWidth,
            imageHeight = testCase.imageHeight,
            pageWidth = rect.width().toInt().coerceAtLeast(1),
            pageHeight = rect.height().toInt().coerceAtLeast(1),
        )
        canvas.save()
        canvas.translate(rect.left, rect.top)
        drawInkStrokes(canvas, converted.strokes, convertedPaint)
        canvas.restore()
    }

    private fun analyzeCase(testCase: SvgLabCase, pageWidth: Int, pageHeight: Int): SvgLabResult {
        val sourcePaths = testCase.paths.mapNotNull { sourcePath(it, testCase, RectF(0f, 0f, pageWidth.toFloat(), pageHeight.toFloat())) }
        val converted = SvgPathAdapter.convert(
            paths = testCase.paths,
            imageWidth = testCase.imageWidth,
            imageHeight = testCase.imageHeight,
            pageWidth = pageWidth,
            pageHeight = pageHeight,
        )
        val sourceSamples = sourcePaths.flatMap { samplePath(it) }
        val convertedPoints = converted.strokes.flatMap { stroke -> stroke.points.map { PointF(it.x, it.y) } }
        val sourceBounds = boundsFor(sourceSamples)
        val convertedBounds = boundsFor(convertedPoints)
        val boundsError = boundsError(sourceBounds, convertedBounds)
        val distances = sourceSamples.map { point -> distanceToPolyline(point, converted.strokes) }
        val avg = distances.takeIf { it.isNotEmpty() }?.average()?.toFloat() ?: Float.POSITIVE_INFINITY
        val maxDistance = distances.maxOrNull() ?: Float.POSITIVE_INFINITY
        val pointCount = converted.strokes.sumOf { it.points.size }
        val pass = converted.rejectedPaths == 0 && pointCount > 0 && boundsError <= BOUNDS_TOLERANCE_PX && maxDistance <= MAX_DISTANCE_TOLERANCE_PX
        return SvgLabResult(
            name = testCase.name,
            pass = pass,
            rejected = converted.rejectedPaths,
            points = pointCount,
            boundsErrorPx = boundsError,
            avgDistancePx = avg,
            maxDistancePx = maxDistance,
        )
    }

    private fun sourcePath(raw: String, testCase: SvgLabCase, rect: RectF): Path? {
        return runCatching {
            val path = PathParser.createPathFromPathData(raw)
            val matrix = Matrix().apply {
                setScale(rect.width() / testCase.imageWidth.toFloat(), rect.height() / testCase.imageHeight.toFloat())
                postTranslate(rect.left, rect.top)
            }
            path.transform(matrix)
            path
        }.getOrNull()
    }

    private fun samplePath(path: Path): List<PointF> {
        val measure = PathMeasure(path, false)
        val samples = mutableListOf<PointF>()
        val pos = FloatArray(2)
        do {
            val length = measure.length
            val count = max(8, (length / 12f).toInt())
            for (i in 0..count) {
                val distance = length * i / count.toFloat()
                if (measure.getPosTan(distance, pos, null)) {
                    samples.add(PointF(pos[0], pos[1]))
                }
            }
        } while (measure.nextContour())
        return samples
    }

    private fun drawingRect(): RectF {
        val margin = dp(42).toFloat()
        val top = dp(72).toFloat()
        val bottom = (height - dp(230)).coerceAtLeast(dp(240)).toFloat()
        return RectF(margin, top, (width - dp(42)).coerceAtLeast(dp(360)).toFloat(), bottom)
    }

    private fun boundsFor(points: List<PointF>): RectF? {
        if (points.isEmpty()) return null
        var left = points.first().x
        var top = points.first().y
        var right = points.first().x
        var bottom = points.first().y
        points.forEach {
            left = minOf(left, it.x)
            top = minOf(top, it.y)
            right = maxOf(right, it.x)
            bottom = maxOf(bottom, it.y)
        }
        return RectF(left, top, right, bottom)
    }

    private fun boundsError(a: RectF?, b: RectF?): Float {
        if (a == null || b == null) return Float.POSITIVE_INFINITY
        return listOf(
            kotlin.math.abs(a.left - b.left),
            kotlin.math.abs(a.top - b.top),
            kotlin.math.abs(a.right - b.right),
            kotlin.math.abs(a.bottom - b.bottom),
        ).maxOrNull() ?: Float.POSITIVE_INFINITY
    }

    private fun distanceToPolyline(point: PointF, strokes: List<InkStroke>): Float {
        var best = Float.POSITIVE_INFINITY
        strokes.forEach { stroke ->
            val points = stroke.points
            if (points.size == 1) {
                best = minOf(best, distance(point, points.first()))
            }
            points.zipWithNext().forEach { (a, b) ->
                best = minOf(best, distancePointToSegment(point, a, b))
            }
        }
        return best
    }

    private fun distance(point: PointF, inkPoint: InkPoint): Float = hypot(point.x - inkPoint.x, point.y - inkPoint.y)

    private fun distancePointToSegment(point: PointF, a: InkPoint, b: InkPoint): Float {
        val dx = b.x - a.x
        val dy = b.y - a.y
        val lengthSquared = dx * dx + dy * dy
        if (lengthSquared == 0f) return distance(point, a)
        val t = (((point.x - a.x) * dx + (point.y - a.y) * dy) / lengthSquared).coerceIn(0f, 1f)
        val projectedX = a.x + t * dx
        val projectedY = a.y + t * dy
        return hypot(point.x - projectedX, point.y - projectedY)
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    private companion object {
        const val BOUNDS_TOLERANCE_PX = 10f
        const val MAX_DISTANCE_TOLERANCE_PX = 14f
    }
}

private data class PointF(val x: Float, val y: Float)

private fun Float.format(): String = if (isFinite()) "%.1f".format(this) else "inf"
