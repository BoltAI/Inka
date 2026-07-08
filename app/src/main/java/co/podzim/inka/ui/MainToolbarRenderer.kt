package co.podzim.inka.ui

import android.content.Context
import android.graphics.Color
import android.view.Gravity
import android.view.View
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import co.podzim.inka.R
import co.podzim.inka.page.HandwritingFont
import co.podzim.inka.page.HandwritingFontWeight

internal data class MainToolbarState(
    val historyOpen: Boolean,
    val immersive: Boolean,
    val showLogButton: Boolean,
)

internal class MainToolbarRenderer(
    private val context: Context,
    private val callbacks: Callbacks,
    private val appName: String = context.getString(R.string.app_name),
) {
    interface Callbacks {
        fun onToggleImmersive()
        fun onErase()
        fun onRead()
        fun onToggleLog()
        fun onSettings()
        fun onCloseHistory()
        fun onBurnNotebook()
        fun onIconInteraction()
    }

    fun create(state: MainToolbarState): LinearLayout {
        return LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            render(this, state)
        }
    }

    fun render(container: LinearLayout, state: MainToolbarState) {
        container.removeAllViews()
        if (state.historyOpen) {
            renderHistory(container)
        } else {
            renderMain(container, state)
        }
    }

    private fun renderMain(container: LinearLayout, state: MainToolbarState) {
        container.setBackgroundColor(if (state.immersive) Color.TRANSPARENT else Color.WHITE)

        val row = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(context.dp(18), context.dp(4), context.dp(12), context.dp(4))

            addView(brandButton(state))
            if (!state.immersive) {
                addView(
                    View(context),
                    LinearLayout.LayoutParams(
                        0,
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        1f,
                    ),
                )
                addView(iconButton(R.drawable.ic_toolbar_eraser, "Erase page") { callbacks.onErase() })
                addView(iconButton(R.drawable.ic_toolbar_book_open, "Read notebook") { callbacks.onRead() })
                if (state.showLogButton) {
                    addView(iconButton(R.drawable.ic_toolbar_terminal, "AI log") { callbacks.onToggleLog() })
                }
                addView(iconButton(R.drawable.ic_toolbar_gear, "Settings") { callbacks.onSettings() })
            }
        }
        container.addView(
            row,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                0,
                1f,
            ),
        )
        if (!state.immersive) {
            container.addView(
                View(context).apply { setBackgroundColor(Color.BLACK) },
                LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    context.dp(2),
                ),
            )
        }
    }

    private fun renderHistory(container: LinearLayout) {
        container.setBackgroundColor(Color.WHITE)
        val row = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(context.dp(22), context.dp(4), context.dp(12), context.dp(4))

            addView(
                ImageButton(context).apply {
                    contentDescription = "Back"
                    importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_YES
                    setBackgroundColor(Color.TRANSPARENT)
                    setImageResource(R.drawable.ic_nav_back)
                    scaleType = ImageView.ScaleType.CENTER
                    setPadding(context.dp(10), context.dp(10), context.dp(10), context.dp(10))
                    setOnClickListener { callbacks.onCloseHistory() }
                },
                LinearLayout.LayoutParams(
                    context.dp(54),
                    LinearLayout.LayoutParams.MATCH_PARENT,
                ),
            )
            addView(
                TextView(context).apply {
                    text = "History"
                    gravity = Gravity.CENTER_VERTICAL
                    includeFontPadding = false
                    paperText(25f)
                },
                LinearLayout.LayoutParams(
                    0,
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    1f,
                ),
            )
            addView(iconButton(R.drawable.ic_toolbar_trash, "Burn notebook") { callbacks.onBurnNotebook() })
        }
        container.addView(
            row,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                0,
                1f,
            ),
        )
        container.addView(
            View(context).apply { setBackgroundColor(Color.BLACK) },
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                context.dp(2),
            ),
        )
    }

    private fun brandButton(state: MainToolbarState): LinearLayout {
        return LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            contentDescription = "Toggle immersive mode"
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_YES
            setBackgroundColor(Color.TRANSPARENT)
            isClickable = true
            isFocusable = true
            setOnClickListener { callbacks.onToggleImmersive() }
            addView(
                ImageView(context).apply {
                    importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
                    setImageResource(R.drawable.ic_app_logo)
                    scaleType = ImageView.ScaleType.CENTER
                    setPadding(context.dp(4), context.dp(4), context.dp(4), context.dp(4))
                },
                LinearLayout.LayoutParams(
                    context.dp(46),
                    LinearLayout.LayoutParams.MATCH_PARENT,
                ),
            )
            if (!state.immersive) {
                addView(appName())
            }
        }
    }

    private fun appName(): TextView {
        return TextView(context).apply {
            text = appName
            gravity = Gravity.CENTER_VERTICAL
            includeFontPadding = false
            paperText(36f)
            typeface = HandwritingFont.DancingScript.loadTypeface(context, HandwritingFontWeight.Bold)
            paint.isFakeBoldText = HandwritingFont.DancingScript.shouldFakeBold(HandwritingFontWeight.Bold)
            translationY = context.dp(4).toFloat()
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            ).apply {
                leftMargin = context.dp(10)
            }
        }
    }

    private fun iconButton(iconResId: Int, label: String, onClick: () -> Unit): ImageButton {
        return ImageButton(context).apply {
            contentDescription = label
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_YES
            setBackgroundColor(Color.TRANSPARENT)
            setImageResource(iconResId)
            scaleType = ImageView.ScaleType.CENTER
            setPadding(context.dp(14), context.dp(10), context.dp(14), context.dp(10))
            setOnClickListener {
                callbacks.onIconInteraction()
                onClick()
                callbacks.onIconInteraction()
            }
            layoutParams = LinearLayout.LayoutParams(
                context.dp(70),
                LinearLayout.LayoutParams.MATCH_PARENT,
            ).apply {
                leftMargin = context.dp(2)
            }
        }
    }

    private fun Context.dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()
}
