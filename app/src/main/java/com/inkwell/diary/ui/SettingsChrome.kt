package com.inkwell.diary.ui

import android.content.Context
import android.graphics.Color
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import com.inkwell.diary.R

internal class SettingsChrome(
    private val context: Context,
    private val onBack: () -> Unit,
) {
    val view: View
    private lateinit var titleText: TextView
    private lateinit var contentHost: FrameLayout

    init {
        val container = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.WHITE)
        }
        container.addView(
            buildTopNav(),
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                context.dp(NAV_HEIGHT_DP),
            ),
        )

        contentHost = FrameLayout(context).apply {
            setBackgroundColor(Color.WHITE)
        }
        container.addView(
            contentHost,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                0,
                1f,
            ),
        )
        view = container
    }

    fun setTitle(title: String) {
        titleText.text = title
    }

    fun setContent(content: View) {
        contentHost.removeAllViews()
        contentHost.addView(
            content,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT,
            ),
        )
    }

    private fun buildTopNav(): LinearLayout {
        return LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setBackgroundColor(Color.WHITE)
            setPadding(context.dp(22), 0, context.dp(30), 0)

            addView(
                ImageButton(context).apply {
                    contentDescription = "Back"
                    importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_YES
                    setBackgroundColor(Color.TRANSPARENT)
                    setImageResource(R.drawable.ic_nav_back)
                    scaleType = ImageView.ScaleType.CENTER
                    setPadding(context.dp(10), context.dp(10), context.dp(10), context.dp(10))
                    setOnClickListener { onBack() }
                },
                LinearLayout.LayoutParams(
                    context.dp(54),
                    LinearLayout.LayoutParams.MATCH_PARENT,
                ),
            )
            titleText = TextView(context).apply {
                gravity = Gravity.CENTER_VERTICAL
                includeFontPadding = false
                paperText(25f)
            }
            addView(
                titleText,
                LinearLayout.LayoutParams(
                    0,
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    1f,
                ),
            )
        }
    }
}

private const val NAV_HEIGHT_DP = 72
