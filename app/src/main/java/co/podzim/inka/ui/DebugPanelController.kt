package co.podzim.inka.ui

import android.content.Context
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.view.View
import android.widget.FrameLayout
import android.widget.ScrollView
import android.widget.TextView

internal class DebugPanelController(
    context: Context,
    maxLines: Int,
    uptimeMillis: () -> Long,
) {
    private val debugLog = DebugLogBuffer(maxLines, uptimeMillis)
    private val debugText = TextView(context).apply {
        paperText(13f)
        setTextColor(Color.BLACK)
        setPadding(context.dp(12), context.dp(10), context.dp(12), context.dp(10))
    }

    val view: ScrollView = ScrollView(context).apply {
        background = GradientDrawable().apply {
            setColor(Color.WHITE)
            setStroke(context.dp(2), Color.BLACK)
        }
        visibility = View.GONE
        addView(
            debugText,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.WRAP_CONTENT,
            ),
        )
    }

    var visible: Boolean = false
        private set

    fun append(line: String): String {
        val entry = debugLog.append(line)
        debugText.text = debugLog.text()
        view.post {
            view.fullScroll(View.FOCUS_DOWN)
        }
        return entry
    }

    fun toggle(): Boolean {
        setVisible(!visible)
        return visible
    }

    fun hide(): Boolean {
        if (!visible) return false
        setVisible(false)
        return true
    }

    private fun setVisible(value: Boolean) {
        visible = value
        view.visibility = if (value) View.VISIBLE else View.GONE
    }
}
