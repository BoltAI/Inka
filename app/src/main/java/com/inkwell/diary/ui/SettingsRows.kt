package com.inkwell.diary.ui

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.RadioButton
import android.widget.TextView
import com.inkwell.diary.R
import com.inkwell.diary.data.Persona

internal class SettingsRows(
    private val context: Context,
) {
    fun groupedList(): LinearLayout {
        return LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            background = GradientDrawable().apply {
                setColor(Color.WHITE)
                cornerRadius = context.dp(12).toFloat()
                setStroke(context.dp(1), Color.rgb(135, 135, 135))
            }
        }
    }

    fun addPersonaRow(
        group: LinearLayout,
        persona: Persona,
        onClick: () -> Unit,
    ): RadioButton {
        val row = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            isClickable = true
            isFocusable = true
            minimumHeight = context.dp(DESCRIPTION_ROW_HEIGHT_DP)
            setPadding(context.dp(26), context.dp(10), context.dp(24), context.dp(10))
            setOnClickListener { onClick() }
        }
        val textColumn = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_VERTICAL
        }
        textColumn.addView(
            TextView(context).apply {
                text = persona.label
                includeFontPadding = false
                paperText(23f)
            },
            fullWidth(),
        )
        textColumn.addView(
            TextView(context).apply {
                text = persona.pickerDescription
                paperText(17f)
                setTextColor(Color.rgb(45, 45, 45))
            },
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            ).apply {
                topMargin = context.dp(4)
            },
        )
        row.addView(
            textColumn,
            LinearLayout.LayoutParams(
                0,
                LinearLayout.LayoutParams.WRAP_CONTENT,
                1f,
            ),
        )
        val radioButton = RadioButton(context).apply {
            isClickable = false
            isFocusable = false
            buttonTintList = ColorStateList.valueOf(Color.BLACK)
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }
        row.addView(
            radioButton,
            LinearLayout.LayoutParams(
                context.dp(54),
                context.dp(54),
            ).apply {
                leftMargin = context.dp(18)
            },
        )
        group.addView(row, fullWidth())
        return radioButton
    }

    fun addChoiceRow(
        group: LinearLayout,
        label: String,
        value: String,
        warningVisible: Boolean = false,
        onClick: () -> Unit,
    ): ChoiceRowHandle {
        val row = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            isClickable = true
            isFocusable = true
            minimumHeight = context.dp(SINGLE_LINE_ROW_HEIGHT_DP)
            setPadding(context.dp(26), 0, context.dp(20), 0)
            setOnClickListener { onClick() }
        }
        row.addView(
            TextView(context).apply {
                text = label
                gravity = Gravity.CENTER_VERTICAL
                includeFontPadding = false
                paperText(23f)
            },
            LinearLayout.LayoutParams(
                0,
                LinearLayout.LayoutParams.MATCH_PARENT,
                1f,
            ),
        )
        val valueText = TextView(context).apply {
            text = value
            gravity = Gravity.CENTER_VERTICAL or Gravity.END
            includeFontPadding = false
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
            maxWidth = context.dp(620)
            paperText(20f)
        }
        val warningIcon = TextView(context).apply {
            text = "!"
            gravity = Gravity.CENTER
            includeFontPadding = false
            paperText(15f, bold = true)
            visibility = if (warningVisible) View.VISIBLE else View.GONE
            background = GradientDrawable().apply {
                setColor(Color.WHITE)
                cornerRadius = context.dp(2).toFloat()
                setStroke(context.dp(2), Color.BLACK)
            }
        }
        val valueGroup = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL or Gravity.END
            addView(
                warningIcon,
                LinearLayout.LayoutParams(
                    context.dp(28),
                    context.dp(28),
                ).apply {
                    rightMargin = context.dp(14)
                },
            )
            addView(
                valueText,
                LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.MATCH_PARENT,
                ),
            )
        }
        row.addView(
            valueGroup,
            LinearLayout.LayoutParams(
                0,
                LinearLayout.LayoutParams.MATCH_PARENT,
                1.25f,
            ),
        )
        row.addView(
            ImageView(context).apply {
                importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
                setImageResource(R.drawable.ic_nav_chevron_right)
                scaleType = ImageView.ScaleType.CENTER
            },
            LinearLayout.LayoutParams(
                context.dp(42),
                LinearLayout.LayoutParams.MATCH_PARENT,
            ),
        )
        group.addView(row, fullWidth())
        return ChoiceRowHandle(valueText, warningIcon)
    }

    fun addTopicRow(group: LinearLayout, label: String, onClick: () -> Unit) {
        val row = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            isClickable = true
            isFocusable = true
            minimumHeight = context.dp(SINGLE_LINE_ROW_HEIGHT_DP)
            setPadding(context.dp(26), 0, context.dp(20), 0)
            setOnClickListener { onClick() }
        }
        row.addView(
            TextView(context).apply {
                text = label
                gravity = Gravity.CENTER_VERTICAL
                includeFontPadding = false
                paperText(23f)
            },
            LinearLayout.LayoutParams(
                0,
                LinearLayout.LayoutParams.MATCH_PARENT,
                1f,
            ),
        )
        row.addView(
            ImageView(context).apply {
                importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
                setImageResource(R.drawable.ic_nav_chevron_right)
                scaleType = ImageView.ScaleType.CENTER
            },
            LinearLayout.LayoutParams(
                context.dp(44),
                LinearLayout.LayoutParams.MATCH_PARENT,
            ),
        )
        group.addView(row, fullWidth())
    }

    fun addValueRow(group: LinearLayout, label: String, value: String) {
        val row = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            minimumHeight = context.dp(SINGLE_LINE_ROW_HEIGHT_DP)
            setPadding(context.dp(26), context.dp(4), context.dp(26), context.dp(4))
        }
        row.addView(
            TextView(context).apply {
                text = label
                gravity = Gravity.CENTER_VERTICAL
                includeFontPadding = false
                paperText(23f)
            },
            LinearLayout.LayoutParams(
                0,
                LinearLayout.LayoutParams.MATCH_PARENT,
                1f,
            ),
        )
        row.addView(
            TextView(context).apply {
                text = value
                gravity = Gravity.CENTER_VERTICAL or Gravity.END
                includeFontPadding = false
                maxLines = 2
                ellipsize = TextUtils.TruncateAt.END
                paperText(19f)
            },
            LinearLayout.LayoutParams(
                0,
                LinearLayout.LayoutParams.MATCH_PARENT,
                1f,
            ),
        )
        group.addView(row, fullWidth())
    }

    fun addSectionLabel(group: LinearLayout, label: String) {
        group.addView(
            TextView(context).apply {
                text = label
                gravity = Gravity.CENTER_VERTICAL
                includeFontPadding = false
                setPadding(context.dp(26), context.dp(18), context.dp(26), context.dp(2))
                paperText(17f, bold = true)
                setTextColor(Color.rgb(70, 70, 70))
            },
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                context.dp(50),
            ),
        )
    }

    fun addModelRow(
        group: LinearLayout,
        label: String,
        status: String,
        progressVisible: Boolean,
        actionLabel: String?,
        onAction: () -> Unit,
    ) {
        val row = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            minimumHeight = context.dp(DESCRIPTION_ROW_HEIGHT_DP)
            setPadding(context.dp(26), context.dp(8), context.dp(24), context.dp(8))
        }
        row.addView(
            TextView(context).apply {
                text = label
                gravity = Gravity.CENTER_VERTICAL
                includeFontPadding = false
                maxLines = 1
                ellipsize = TextUtils.TruncateAt.END
                paperText(23f)
            },
            LinearLayout.LayoutParams(
                0,
                LinearLayout.LayoutParams.MATCH_PARENT,
                1f,
            ),
        )
        val statusColumn = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_VERTICAL or Gravity.END
        }
        statusColumn.addView(
            TextView(context).apply {
                text = status
                gravity = Gravity.END
                includeFontPadding = false
                maxLines = 1
                ellipsize = TextUtils.TruncateAt.END
                paperText(18f)
            },
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            ),
        )
        statusColumn.addView(
            ProgressBar(context, null, android.R.attr.progressBarStyleHorizontal).apply {
                isIndeterminate = true
                visibility = if (progressVisible) View.VISIBLE else View.GONE
            },
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                context.dp(8),
            ).apply {
                topMargin = context.dp(10)
            },
        )
        row.addView(
            statusColumn,
            LinearLayout.LayoutParams(
                0,
                LinearLayout.LayoutParams.WRAP_CONTENT,
                1f,
            ).apply {
                leftMargin = context.dp(18)
            },
        )
        if (actionLabel != null) {
            row.addView(
                Button(context).apply {
                    text = actionLabel
                    setAllCaps(false)
                    minHeight = 0
                    minimumHeight = 0
                    paperText(16f, bold = true)
                    background = GradientDrawable().apply {
                        setColor(Color.WHITE)
                        cornerRadius = context.dp(3).toFloat()
                        setStroke(context.dp(2), Color.BLACK)
                    }
                    setOnClickListener { onAction() }
                },
                LinearLayout.LayoutParams(
                    context.dp(128),
                    context.dp(52),
                ).apply {
                    leftMargin = context.dp(18)
                },
            )
        }
        group.addView(row, fullWidth())
    }

    fun addPreviewBlock(parent: LinearLayout, label: String, value: String): TextView {
        val block = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            minimumHeight = context.dp(132)
            setPadding(context.dp(8), context.dp(6), context.dp(8), context.dp(4))
        }
        block.addView(
            TextView(context).apply {
                text = label
                gravity = Gravity.CENTER_VERTICAL or Gravity.START
                includeFontPadding = false
                paperText(20f)
            },
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            ),
        )
        val previewText = TextView(context).apply {
            text = value
            gravity = Gravity.CENTER_VERTICAL or Gravity.START
            includeFontPadding = false
            maxLines = 2
            ellipsize = TextUtils.TruncateAt.END
            setTextColor(Color.BLACK)
        }
        block.addView(
            previewText,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                0,
                1f,
            ).apply {
                topMargin = context.dp(12)
            },
        )
        parent.addView(block, fullWidth())
        return previewText
    }

    fun addToggleRow(
        group: LinearLayout,
        label: String,
        explanation: String,
        checked: Boolean,
        onCheckedChange: (Boolean) -> Unit,
    ) {
        var isChecked = checked
        val toggle = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }

        fun renderToggle() {
            toggle.removeAllViews()
            toggle.background = GradientDrawable().apply {
                setColor(if (isChecked) Color.BLACK else Color.WHITE)
                cornerRadius = context.dp(2).toFloat()
                setStroke(context.dp(2), Color.BLACK)
            }

            val knob = View(context).apply {
                background = GradientDrawable().apply {
                    setColor(if (isChecked) Color.WHITE else Color.BLACK)
                    cornerRadius = context.dp(1).toFloat()
                }
            }
            val knobParams = LinearLayout.LayoutParams(context.dp(20), context.dp(20)).apply {
                leftMargin = context.dp(5)
                rightMargin = context.dp(5)
            }
            val stateText = TextView(context).apply {
                text = if (isChecked) "ON" else "OFF"
                gravity = Gravity.CENTER
                includeFontPadding = false
                paperText(14f, bold = true)
                setTextColor(if (isChecked) Color.WHITE else Color.BLACK)
            }
            val stateParams = LinearLayout.LayoutParams(context.dp(42), LinearLayout.LayoutParams.MATCH_PARENT)

            if (isChecked) {
                toggle.addView(stateText, stateParams)
                toggle.addView(knob, knobParams)
            } else {
                toggle.addView(knob, knobParams)
                toggle.addView(stateText, stateParams)
            }
        }

        val row = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            isClickable = true
            isFocusable = true
            minimumHeight = context.dp(DESCRIPTION_ROW_HEIGHT_DP)
            setPadding(context.dp(26), context.dp(8), context.dp(26), context.dp(8))
            setOnClickListener {
                isChecked = !isChecked
                renderToggle()
                onCheckedChange(isChecked)
            }
        }
        val textColumn = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_VERTICAL
        }
        textColumn.addView(
            TextView(context).apply {
                text = label
                includeFontPadding = false
                paperText(23f)
            },
            fullWidth(),
        )
        textColumn.addView(
            TextView(context).apply {
                text = explanation
                paperText(17f)
                setTextColor(Color.rgb(45, 45, 45))
            },
            fullWidth(),
        )
        row.addView(
            textColumn,
            LinearLayout.LayoutParams(
                0,
                LinearLayout.LayoutParams.WRAP_CONTENT,
                1f,
            ),
        )
        row.addView(
            toggle,
            LinearLayout.LayoutParams(
                context.dp(82),
                context.dp(38),
            ),
        )
        renderToggle()
        group.addView(row, fullWidth())
    }

    fun fullWidth(): LinearLayout.LayoutParams = fullWidthParams()

    companion object {
        private const val SINGLE_LINE_ROW_HEIGHT_DP = 72
        private const val DESCRIPTION_ROW_HEIGHT_DP = 96

        fun fullWidthParams(): LinearLayout.LayoutParams {
            return LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            )
        }
    }
}

internal data class ChoiceRowHandle(
    val valueText: TextView,
    val warningIcon: TextView,
)
