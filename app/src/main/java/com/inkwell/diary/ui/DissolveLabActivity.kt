package com.inkwell.diary.ui

import android.graphics.Color
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
import androidx.lifecycle.lifecycleScope
import com.inkwell.diary.data.InkFadeStyle
import com.inkwell.diary.data.InkPoint
import com.inkwell.diary.data.InkStroke
import com.inkwell.diary.data.Prefs
import com.inkwell.diary.page.DissolveConfig
import com.inkwell.diary.page.DissolveLabStore
import com.inkwell.diary.page.EinkRefresher
import com.inkwell.diary.page.PageCanvasView
import com.inkwell.diary.page.PageRenderer
import com.inkwell.diary.page.dissolveConfig
import com.inkwell.diary.page.saveDissolveConfig
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

class DissolveLabActivity : ComponentActivity() {
    private lateinit var prefs: Prefs
    private lateinit var renderer: PageRenderer
    private lateinit var pageView: PageCanvasView
    private lateinit var statusText: TextView
    private val strokes: List<InkStroke> by lazy {
        DissolveLabStore.load(this).ifEmpty { sampleStrokes() }
    }
    private var config = DissolveConfig()
    private var replayJob: Job? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        requestWindowFeature(Window.FEATURE_NO_TITLE)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        prefs = Prefs(this)
        config = prefs.dissolveConfig()

        renderer = PageRenderer(this).apply {
            setInkFadeStyle(InkFadeStyle.TurnsToDust)
            setDissolveConfig(config)
        }
        pageView = PageCanvasView(this)

        val root = FrameLayout(this).apply {
            setBackgroundColor(Color.WHITE)
        }
        root.addView(
            pageView,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT,
            ),
        )
        root.addView(
            buildControls(),
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                dp(156),
                Gravity.BOTTOM,
            ),
        )
        setContentView(root)
        pageView.post {
            renderer.attach(pageView, pageView.width, pageView.height)
            drawSample()
            EinkRefresher().requestFullRefresh(pageView)
        }
    }

    override fun onDestroy() {
        replayJob?.cancel()
        renderer.detach()
        super.onDestroy()
    }

    private fun buildControls(): View {
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.WHITE)
            setPadding(dp(16), dp(8), dp(16), dp(8))
            statusText = TextView(context).apply {
                paperText(14f)
                text = configSummary()
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
                    controlButton("Replay") { replay() },
                    controlButton("Cell -") { updateConfig(config.copy(cellSizePx = (config.cellSizePx - 2).coerceAtLeast(Prefs.MIN_DISSOLVE_CELL_SIZE_PX))) },
                    controlButton("Cell +") { updateConfig(config.copy(cellSizePx = (config.cellSizePx + 2).coerceAtMost(Prefs.MAX_DISSOLVE_CELL_SIZE_PX))) },
                    controlButton("Sweep -") { updateConfig(config.copy(sweepMs = (config.sweepMs - 100L).coerceAtLeast(Prefs.MIN_DISSOLVE_SWEEP_MS))) },
                    controlButton("Sweep +") { updateConfig(config.copy(sweepMs = (config.sweepMs + 100L).coerceAtMost(Prefs.MAX_DISSOLVE_SWEEP_MS))) },
                    controlButton("Life -") { updateConfig(config.copy(cellLifeMs = (config.cellLifeMs - 100L).coerceAtLeast(Prefs.MIN_DISSOLVE_CELL_LIFE_MS))) },
                    controlButton("Life +") { updateConfig(config.copy(cellLifeMs = (config.cellLifeMs + 100L).coerceAtMost(Prefs.MAX_DISSOLVE_CELL_LIFE_MS))) },
                ),
                LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    0,
                    1f,
                ),
            )
            addView(
                controlRow(
                    controlButton("Wind -") { updateConfig(config.copy(windShearPx = (config.windShearPx - 8f).coerceAtLeast(Prefs.MIN_DISSOLVE_WIND_SHEAR_PX.toFloat()))) },
                    controlButton("Wind +") { updateConfig(config.copy(windShearPx = config.windShearPx + 8f)) },
                    controlButton("Drift -") { updateConfig(config.copy(driftMaxPx = (config.driftMaxPx - 8f).coerceAtLeast(Prefs.MIN_DISSOLVE_DRIFT_MAX_PX.toFloat()))) },
                    controlButton("Drift +") { updateConfig(config.copy(driftMaxPx = (config.driftMaxPx + 8f).coerceAtMost(Prefs.MAX_DISSOLVE_DRIFT_MAX_PX.toFloat()))) },
                    controlButton("Save") { saveConfig() },
                    controlButton("Reset") { resetConfig() },
                    controlButton("Close") { finish() },
                ),
                LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    0,
                    1f,
                ),
            )
        }
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

    private fun updateConfig(next: DissolveConfig) {
        config = next
        renderer.setDissolveConfig(config)
        statusText.text = configSummary("Unsaved")
    }

    private fun saveConfig() {
        prefs.saveDissolveConfig(config)
        config = prefs.dissolveConfig()
        renderer.setDissolveConfig(config)
        statusText.text = configSummary("Saved")
    }

    private fun resetConfig() {
        prefs.resetDissolveConfig()
        config = prefs.dissolveConfig()
        renderer.setDissolveConfig(config)
        statusText.text = configSummary("Reset")
        drawSample()
    }

    private fun replay() {
        replayJob?.cancel()
        renderer.setDissolveConfig(config)
        drawSample()
        replayJob = lifecycleScope.launch {
            renderer.fadeStrokes(strokes, includeFullOpacityFrame = true)
            drawSample()
        }
    }

    private fun drawSample() {
        renderer.showCapturedStrokes(strokes)
    }

    private fun configSummary(prefix: String = "Dissolve Lab"): String {
        return "$prefix: cell=${config.cellSizePx}px, sweep=${config.sweepMs}ms, life=${config.cellLifeMs}ms, wind=${config.windShearPx.toInt()}px, driftMax=${config.driftMaxPx.toInt()}px"
    }

    private fun sampleStrokes(): List<InkStroke> {
        val points = mutableListOf<InkPoint>()
        var time = 0L
        for (i in 0..140) {
            val x = 180f + i * 7f
            val y = 420f + kotlin.math.sin(i / 12f) * 42f
            points.add(InkPoint(x = x, y = y, pressure = 0.75f, timestampMs = time++))
        }
        return listOf(InkStroke(points))
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()
}
