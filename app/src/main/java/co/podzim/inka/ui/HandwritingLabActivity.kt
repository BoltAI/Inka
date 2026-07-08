package co.podzim.inka.ui

import android.graphics.Color
import android.os.Bundle
import android.os.SystemClock
import android.util.Log
import android.view.Gravity
import android.view.Window
import android.view.WindowManager
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.lifecycle.lifecycleScope
import co.podzim.inka.data.Prefs
import co.podzim.inka.data.Prefs.Companion.DEFAULT_HANDWRITING_STROKE_WIDTH_MM
import co.podzim.inka.data.Prefs.Companion.MAX_HANDWRITING_FONT_SIZE_SP
import co.podzim.inka.data.Prefs.Companion.MAX_HANDWRITING_STROKE_WIDTH_MM
import co.podzim.inka.data.Prefs.Companion.MIN_HANDWRITING_FONT_SIZE_SP
import co.podzim.inka.data.Prefs.Companion.MIN_HANDWRITING_STROKE_WIDTH_MM
import co.podzim.inka.handwriting.HandwritingSynthesisRequest
import co.podzim.inka.handwriting.HandwritingSynthesisResult
import co.podzim.inka.handwriting.OkHttpHandwritingSynthesisClient
import co.podzim.inka.handwriting.handwritingSynthesisFontSizePx
import co.podzim.inka.page.EinkRefresher
import co.podzim.inka.page.PageCanvasView
import co.podzim.inka.page.PageRenderer
import kotlinx.coroutines.Job
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import kotlin.random.Random

class HandwritingLabActivity : ComponentActivity() {
    private lateinit var prefs: Prefs
    private lateinit var renderer: PageRenderer
    private lateinit var pageView: PageCanvasView
    private lateinit var statusValue: TextView
    private lateinit var seedValue: TextView
    private lateinit var fontSizeValue: TextView
    private lateinit var strokeWidthValue: TextView
    private val httpClient = OkHttpHandwritingSynthesisClient()
    private var renderJob: Job? = null
    private var labRunId: Int = 0
    private var labFontSizeSpValue: Float = 0f
    private var labStrokeWidthMmValue: Float = DEFAULT_HANDWRITING_STROKE_WIDTH_MM
    private var labSeedValue: Long = DEFAULT_LAB_SEED

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        requestWindowFeature(Window.FEATURE_NO_TITLE)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                finish()
            }
        })

        prefs = Prefs(this)
        labFontSizeSpValue = initialFontSizeSp()
        labStrokeWidthMmValue = initialStrokeWidthMm()
        labSeedValue = initialSeed()
        renderer = PageRenderer(this)
        pageView = PageCanvasView(this)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.WHITE)
        }
        root.addView(
            pageView,
            LinearLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                0,
                1f,
            ),
        )
        root.addView(buildControls())
        setContentView(root)

        pageView.post {
            renderer.attach(pageView, pageView.width, pageView.height)
            EinkRefresher().requestFullRefresh(pageView)
            updateStatus("Ready")
        }
    }

    override fun onDestroy() {
        renderJob?.cancel()
        renderer.detach()
        super.onDestroy()
    }

    private fun runLab() {
        val runId = ++labRunId
        renderJob?.cancel()
        val serverUrl = labServerUrl()
        val text = labText()
        renderer.clear()

        if (serverUrl.isBlank()) {
            updateStatus("Server endpoint not set")
            renderer.showHint("Set Server Endpoint in Developer Settings.")
            return
        }
        val area = renderer.replyWritingArea()
        if (area == null) {
            updateStatus("Page surface not ready")
            return
        }

        renderJob = lifecycleScope.launch {
            try {
                updateStatus("Sending to hosted endpoint")
                val request = HandwritingSynthesisRequest(
                    text = text,
                    pageWidth = area.pageWidth,
                    pageHeight = area.pageHeight,
                    left = area.left,
                    top = area.top,
                    maxWidth = area.maxWidth,
                    fontSizeSp = handwritingSynthesisFontSizePx(
                        fontSizeSp = labFontSizeSpValue,
                        scaledDensity = resources.displayMetrics.density * resources.configuration.fontScale,
                    ),
                    strokeWidthMm = labStrokeWidthMmValue,
                    style = styleForSeed(labSeedValue),
                    seed = labSeedValue,
                )
                val synthStartedAt = SystemClock.elapsedRealtime()
                when (val result = withTimeout(LAB_REQUEST_TIMEOUT_MS) { httpClient.synthesize(serverUrl, request) }) {
                    is HandwritingSynthesisResult.Success -> {
                        val synthMs = SystemClock.elapsedRealtime() - synthStartedAt
                        val points = result.strokes.sumOf { it.points.size }
                        updateStatus("Generated ${result.strokes.size} stroke(s), $points point(s) in ${synthMs}ms")
                        val renderStartedAt = SystemClock.elapsedRealtime()
                        renderer.beginSketchReply()
                        updateStatus("Rendering ${result.strokes.size} stroke(s)")
                        renderer.revealGeneratedHandwritingStrokes(result.strokes)
                        val renderMs = SystemClock.elapsedRealtime() - renderStartedAt
                        updateStatus("Rendered ${result.strokes.size} stroke(s), $points point(s) in ${renderMs}ms")
                    }
                    is HandwritingSynthesisResult.Failure -> {
                        val synthMs = SystemClock.elapsedRealtime() - synthStartedAt
                        updateStatus("Request failed after ${synthMs}ms: ${result.message}")
                        renderer.showHint("Handwriting request failed.")
                    }
                }
            } catch (error: TimeoutCancellationException) {
                updateStatus("Request timed out after ${LAB_REQUEST_TIMEOUT_MS / 1000}s")
                renderer.showHint("Handwriting request timed out.")
            } finally {
                if (runId == labRunId) {
                    pageView.invalidate()
                }
            }
        }
    }

    private fun updateStatus(value: String) {
        Log.i(TAG, value)
        if (::statusValue.isInitialized) {
            runOnUiThread {
                statusValue.text = value
            }
        }
    }

    private fun labServerUrl(): String {
        return intent.getStringExtra(EXTRA_SERVER_URL)
            ?.trim()
            ?.takeIf { it.isNotBlank() }
            ?: prefs.handwritingSynthesisServerUrl
    }

    private fun labText(): String {
        return intent.getStringExtra(EXTRA_TEXT)
            ?.trim()
            ?.takeIf { it.isNotBlank() }
            ?: DEFAULT_TEXT
    }

    private fun initialFontSizeSp(): Float {
        return intent.getFloatExtra(EXTRA_FONT_SIZE_SP, prefs.handwritingFontSizeSp)
            .takeIf { it.isFinite() && it > 0f }
            ?: prefs.handwritingFontSizeSp
    }

    private fun initialStrokeWidthMm(): Float {
        return intent.getFloatExtra(EXTRA_STROKE_WIDTH_MM, prefs.handwritingStrokeWidthMm)
            .takeIf { it.isFinite() && it > 0f }
            ?.coerceIn(MIN_HANDWRITING_STROKE_WIDTH_MM, MAX_HANDWRITING_STROKE_WIDTH_MM)
            ?: prefs.handwritingStrokeWidthMm
    }

    private fun initialSeed(): Long {
        val seed = intent.getLongExtra(EXTRA_SEED, DEFAULT_LAB_SEED)
        return seed.takeIf { it > 0L } ?: DEFAULT_LAB_SEED
    }

    private fun buildControls(): LinearLayout {
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.WHITE)
            setPadding(dp(10), dp(8), dp(10), dp(8))
            addView(statusText().also { statusValue = it })
            addView(controlRow(controlButton("Close") { finish() }, controlButton("Run") { runLab() }))
            addView(
                controlRow(
                    controlButton("Random") { randomizeSeed() },
                    valueText().also { seedValue = it },
                    controlButton("Save") { saveTuning() },
                ),
            )
            addView(
                controlRow(
                    controlButton("Size -") { adjustFontSize(-FONT_SIZE_STEP_SP) },
                    valueText().also { fontSizeValue = it },
                    controlButton("Size +") { adjustFontSize(FONT_SIZE_STEP_SP) },
                ),
            )
            addView(
                controlRow(
                    controlButton("Ink -") { adjustStrokeWidth(-STROKE_WIDTH_STEP_MM) },
                    valueText().also { strokeWidthValue = it },
                    controlButton("Ink +") { adjustStrokeWidth(STROKE_WIDTH_STEP_MM) },
                ),
            )
            refreshControlValues()
        }
    }

    private fun controlRow(vararg views: android.view.View): LinearLayout {
        return LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            views.forEach { view ->
                addView(
                    view,
                    LinearLayout.LayoutParams(0, dp(44), 1f).apply {
                        setMargins(dp(3), dp(3), dp(3), dp(3))
                    },
                )
            }
        }
    }

    private fun controlButton(label: String, onClick: () -> Unit): Button {
        return paperButton(label).apply {
            textSize = 12f
            setOnClickListener { onClick() }
        }
    }

    private fun valueText(): TextView {
        return TextView(this).apply {
            gravity = Gravity.CENTER
            paperText(12f, bold = true)
            setBackgroundColor(Color.rgb(238, 238, 238))
        }
    }

    private fun statusText(): TextView {
        return TextView(this).apply {
            gravity = Gravity.CENTER_VERTICAL
            paperText(12f, bold = true)
            text = "Ready"
            setPadding(dp(8), 0, dp(8), 0)
            setBackgroundColor(Color.WHITE)
            layoutParams = LinearLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                dp(28),
            ).apply {
                setMargins(dp(3), 0, dp(3), dp(3))
            }
        }
    }

    private fun adjustFontSize(delta: Float) {
        labFontSizeSpValue = (labFontSizeSpValue + delta).coerceIn(MIN_HANDWRITING_FONT_SIZE_SP, MAX_HANDWRITING_FONT_SIZE_SP)
        refreshControlValues()
        runLab()
    }

    private fun adjustStrokeWidth(delta: Float) {
        labStrokeWidthMmValue = (labStrokeWidthMmValue + delta)
            .coerceIn(MIN_HANDWRITING_STROKE_WIDTH_MM, MAX_HANDWRITING_STROKE_WIDTH_MM)
        refreshControlValues()
        runLab()
    }

    private fun randomizeSeed() {
        labSeedValue = Random.nextInt(MIN_RANDOM_SEED, MAX_RANDOM_SEED_EXCLUSIVE).toLong()
        refreshControlValues()
        runLab()
    }

    private fun saveTuning() {
        prefs.handwritingFontSizeSp = labFontSizeSpValue
        prefs.handwritingStrokeWidthMm = labStrokeWidthMmValue
        labFontSizeSpValue = prefs.handwritingFontSizeSp
        labStrokeWidthMmValue = prefs.handwritingStrokeWidthMm
        refreshControlValues()
        updateStatus("Saved handwriting tuning: ${labFontSizeSpValue.toInt()} sp, ${String.format("%.2f", labStrokeWidthMmValue)} mm")
    }

    private fun refreshControlValues() {
        if (::seedValue.isInitialized) {
            seedValue.text = "seed $labSeedValue"
        }
        if (::fontSizeValue.isInitialized) {
            fontSizeValue.text = "${labFontSizeSpValue.toInt()} sp"
        }
        if (::strokeWidthValue.isInitialized) {
            strokeWidthValue.text = String.format("%.2f mm", labStrokeWidthMmValue)
        }
    }

    private fun styleForSeed(seed: Long): String = "lab-$seed"

    companion object {
        const val EXTRA_SERVER_URL = "co.podzim.inka.extra.HANDWRITING_SERVER_URL"
        const val EXTRA_TEXT = "co.podzim.inka.extra.HANDWRITING_TEXT"
        const val EXTRA_FONT_SIZE_SP = "co.podzim.inka.extra.HANDWRITING_FONT_SIZE_SP"
        const val EXTRA_STROKE_WIDTH_MM = "co.podzim.inka.extra.HANDWRITING_STROKE_WIDTH_MM"
        const val EXTRA_SEED = "co.podzim.inka.extra.HANDWRITING_SEED"
        private const val TAG = "HandwritingLab"
        private const val DEFAULT_TEXT = "a little note for today"
        private const val FONT_SIZE_STEP_SP = 4f
        private const val STROKE_WIDTH_STEP_MM = 0.05f
        private const val LAB_REQUEST_TIMEOUT_MS = 95_000L
        private const val DEFAULT_LAB_SEED = 111111L
        private const val MIN_RANDOM_SEED = 100000
        private const val MAX_RANDOM_SEED_EXCLUSIVE = 1000000
    }
}
