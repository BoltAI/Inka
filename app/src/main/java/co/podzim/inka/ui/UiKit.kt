package co.podzim.inka.ui

import android.content.Context
import android.graphics.Color
import android.text.InputType
import android.text.method.PasswordTransformationMethod
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView

fun Context.dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

fun TextView.paperText(sizeSp: Float, bold: Boolean = false): TextView {
    textSize = sizeSp
    setTextColor(Color.rgb(17, 17, 17))
    if (bold) {
        typeface = android.graphics.Typeface.DEFAULT_BOLD
    }
    includeFontPadding = true
    return this
}

fun Context.paperButton(label: String): Button {
    return Button(this).apply {
        text = label
        paperText(16f, bold = true)
        minHeight = dp(48)
        setAllCaps(false)
    }
}

fun Context.paperEditText(hintText: String, masked: Boolean = false): EditText {
    return EditText(this).apply {
        hint = hintText
        textSize = 16f
        setTextColor(Color.rgb(17, 17, 17))
        setHintTextColor(Color.rgb(110, 110, 104))
        setSingleLine(!hintText.contains("prompt", ignoreCase = true))
        inputType = when {
            masked -> InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
            hintText.contains("api key", ignoreCase = true) ->
                InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
            else -> InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE
        }
        if (masked) {
            setSingleLine(true)
            transformationMethod = PasswordTransformationMethod.getInstance()
            typeface = android.graphics.Typeface.DEFAULT
        } else if (hintText.contains("api key", ignoreCase = true)) {
            setSingleLine(true)
        }
        setPadding(dp(12), dp(8), dp(12), dp(8))
    }
}

fun Context.verticalPanel(): LinearLayout {
    return LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        gravity = Gravity.CENTER_HORIZONTAL
        setPadding(dp(48), dp(32), dp(48), dp(32))
        layoutParams = ViewGroup.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.MATCH_PARENT,
        )
    }
}

fun LinearLayout.addGap(dp: Int) {
    addView(View(context), LinearLayout.LayoutParams(1, context.dp(dp)))
}

fun LinearLayout.addText(text: String, sizeSp: Float, bold: Boolean = false): TextView {
    return TextView(context).apply {
        this.text = text
        paperText(sizeSp, bold)
        addView(
            this,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            ),
        )
    }
}
