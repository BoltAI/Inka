package com.inkwell.diary.ui

import android.app.AlertDialog
import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.text.InputType
import android.view.Gravity
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.TextView
import com.inkwell.diary.page.HandwritingFont

internal class SettingsDialogs(
    private val context: Context,
    private val handwritingFonts: List<HandwritingFont>,
) {
    fun showChoice(
        title: String,
        choices: List<String>,
        selectedIndex: Int,
        onSelected: (Int) -> Unit,
    ) {
        AlertDialog.Builder(context)
            .setTitle(title)
            .setSingleChoiceItems(choices.toTypedArray(), selectedIndex) { dialog, which ->
                dialog.dismiss()
                onSelected(which)
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    fun showTextInput(
        title: String,
        hint: String,
        initialValue: String,
        masked: Boolean,
        multiLine: Boolean,
        onSaved: (String) -> Unit,
    ) {
        val input = context.paperEditText(hint, masked = masked).apply {
            setText(initialValue)
            setSelectAllOnFocus(true)
            setSingleLine(!multiLine)
            minLines = if (multiLine) 5 else 1
            inputType = when {
                masked -> InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
                multiLine -> InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE
                else -> InputType.TYPE_CLASS_TEXT
            }
        }
        AlertDialog.Builder(context)
            .setTitle(title)
            .setView(input)
            .setPositiveButton("Save") { _, _ -> onSaved(input.text.toString()) }
            .setNegativeButton("Cancel", null)
            .show()
    }

    fun showSlider(
        title: String,
        min: Int,
        max: Int,
        step: Int,
        current: Int,
        valueLabel: (Int) -> String,
        onSaved: (Int) -> Unit,
    ) {
        val range = SettingsSliderRange(min, max, step)

        val label = TextView(context).apply {
            gravity = Gravity.CENTER
            includeFontPadding = false
            paperText(21f)
        }
        lateinit var slider: SeekBar
        fun setSliderProgress(progress: Int) {
            slider.progress = progress.coerceIn(0, range.maxProgress)
            label.text = valueLabel(range.valueForProgress(slider.progress))
        }

        slider = SeekBar(context).apply {
            this.max = range.maxProgress
            progress = range.progressForValue(current)
            progressTintList = ColorStateList.valueOf(Color.BLACK)
            progressBackgroundTintList = ColorStateList.valueOf(Color.rgb(120, 120, 120))
            thumbTintList = ColorStateList.valueOf(Color.BLACK)
            splitTrack = false
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(seekBar: SeekBar, progress: Int, fromUser: Boolean) {
                    label.text = valueLabel(range.valueForProgress(progress))
                }

                override fun onStartTrackingTouch(seekBar: SeekBar) = Unit
                override fun onStopTrackingTouch(seekBar: SeekBar) = Unit
            })
        }
        label.text = valueLabel(range.valueForProgress(slider.progress))

        val sliderRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, context.dp(26), 0, context.dp(22))
            addView(
                sliderButton("-") { setSliderProgress(slider.progress - 1) },
                LinearLayout.LayoutParams(context.dp(54), context.dp(54)),
            )
            addView(
                slider,
                LinearLayout.LayoutParams(
                    0,
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    1f,
                ).apply {
                    leftMargin = context.dp(28)
                    rightMargin = context.dp(28)
                },
            )
            addView(
                sliderButton("+") { setSliderProgress(slider.progress + 1) },
                LinearLayout.LayoutParams(context.dp(54), context.dp(54)),
            )
        }

        val content = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            contentDescription = title
            setPadding(context.dp(28), context.dp(28), context.dp(28), 0)
            addView(label, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
            addView(sliderRow, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
        }

        AlertDialog.Builder(context)
            .setView(content)
            .setPositiveButton("OK") { _, _ -> onSaved(range.valueForProgress(slider.progress)) }
            .setNegativeButton("Cancel", null)
            .show()
    }

    fun showFontLicenses() {
        val licenseSummary = handwritingFonts.joinToString("\n\n") { font ->
            "${font.label}\n${font.licenseName}\n${font.licensePath}"
        }
        AlertDialog.Builder(context)
            .setTitle("Font Licenses")
            .setMessage(licenseSummary)
            .setPositiveButton("OK", null)
            .show()
    }

    private fun sliderButton(label: String, onClick: () -> Unit): TextView {
        return TextView(context).apply {
            text = label
            gravity = Gravity.CENTER
            includeFontPadding = false
            paperText(28f)
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(Color.WHITE)
                setStroke(context.dp(3), Color.BLACK)
            }
            setOnClickListener { onClick() }
        }
    }
}

internal class SettingsSliderRange(
    min: Int,
    max: Int,
    step: Int,
) {
    private val normalizedMin = min.coerceAtMost(max)
    private val normalizedMax = max.coerceAtLeast(min)
    private val normalizedStep = step.coerceAtLeast(1)
    val maxProgress: Int = ((normalizedMax - normalizedMin) / normalizedStep).coerceAtLeast(0)

    fun valueForProgress(progress: Int): Int {
        return normalizedMin + progress.coerceIn(0, maxProgress) * normalizedStep
    }

    fun progressForValue(value: Int): Int {
        return ((value.coerceIn(normalizedMin, normalizedMax) - normalizedMin) / normalizedStep)
            .coerceIn(0, maxProgress)
    }
}
