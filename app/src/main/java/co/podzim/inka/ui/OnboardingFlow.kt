package co.podzim.inka.ui

import android.content.ClipboardManager
import android.content.Context
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.Spinner
import android.widget.TextView
import co.podzim.inka.brain.AnthropicResult
import co.podzim.inka.brain.BrainErrorKind
import co.podzim.inka.brain.ConversationEngine
import co.podzim.inka.data.AiProvider
import co.podzim.inka.data.Prefs
import co.podzim.inka.data.SecureStorageUnavailableException
import co.podzim.inka.recognize.ModelDownloadOutcome
import co.podzim.inka.recognize.RecognitionService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

class OnboardingFlow(
    context: Context,
    private val prefs: Prefs,
    private val engine: ConversationEngine,
    private val recognitionService: RecognitionService,
    private val scope: CoroutineScope,
    private val onDone: () -> Unit,
) : FrameLayout(context) {
    init {
        isClickable = true
        setBackgroundColor(Color.TRANSPARENT)
        showIntro()
    }

    private fun showIntro() {
        renderPanel {
            val content = contentColumn()
            content.addCenteredText("Inka", 39f, bold = true)
            content.addGap(8)
            content.addCenteredText("A diary that writes back.", 26f)
            content.addGap(18)
            content.addBodyText(
                "Write on the page with your pen. Your words will fade into the paper, and something will answer in ink of its own.",
            )
            content.addGap(14)
            content.addBodyText("Your notebook is stored on this device.")
            addView(content, contentLayoutParams())
            addView(View(context), spacerLayoutParams())
            addView(primaryButton("Begin") { showKey() }, ctaLayoutParams())
        }
    }

    private fun showKey() {
        renderPanel {
            val content = contentColumn()
            content.addCenteredText("Inka needs a key", 35f, bold = true)
            content.addGap(14)
            content.addBodyText(
                "Inka can think with OpenAI, so she needs your OpenAI API key. It is stored only on this device, and your words go directly to OpenAI.",
            )
            content.addGap(18)

            val status = TextView(context).paperText(19f).apply {
                gravity = Gravity.CENTER
                text = ""
            }
            val input = context.paperEditText("OpenAI API key", masked = false).apply {
                gravity = Gravity.CENTER_VERTICAL
                minHeight = context.dp(62)
                setText(apiKeyOrBlank(AiProvider.OpenAI, status))
            }
            content.addView(apiKeyRow(input), fullWidthWrapContent())
            content.addGap(10)
            content.addView(status, fullWidthWrapContent())

            addView(content, contentLayoutParams())
            addView(View(context), spacerLayoutParams())
            addView(keyButtonRow(input, status), ctaLayoutParams())
        }
    }

    private fun showModelDownload() {
        renderPanel {
            val content = contentColumn()
            content.addCenteredText("Teaching Inka to read your hand", 32f, bold = true)
            content.addGap(14)
            content.addBodyText("One small download so Inka can read handwriting. This happens once.")
            content.addGap(18)

            val spinner = languageSpinner()
            content.addView(spinner, fullWidthWrapContent())
            content.addGap(14)

            val progress = ProgressBar(context, null, android.R.attr.progressBarStyleHorizontal).apply {
                isIndeterminate = true
                visibility = View.GONE
            }
            val status = TextView(context).paperText(19f).apply {
                gravity = Gravity.CENTER
                text = ""
            }
            content.addView(progress, fullWidthWrapContent())
            content.addGap(10)
            content.addView(status, fullWidthWrapContent())

            addView(content, contentLayoutParams())
            addView(View(context), spacerLayoutParams())
            addView(downloadButton(spinner, progress, status), ctaLayoutParams())
        }
    }

    private fun renderPanel(build: LinearLayout.() -> Unit) {
        removeAllViews()
        addView(
            LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER_HORIZONTAL
                background = modalBackground()
                setPadding(context.dp(30), context.dp(30), context.dp(30), context.dp(26))
                build()
            },
            modalLayoutParams(),
        )
    }

    private fun contentColumn(): LinearLayout {
        return LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
        }
    }

    private fun apiKeyRow(input: EditText): LinearLayout {
        return LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            isBaselineAligned = false
            addView(
                input,
                LinearLayout.LayoutParams(0, context.dp(62), 1f),
            )
            addView(
                secondaryButton("Paste") {
                    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                    input.setText(clipboard.primaryClip?.getItemAt(0)?.coerceToText(context)?.toString().orEmpty())
                },
                LinearLayout.LayoutParams(context.dp(118), context.dp(62)).apply {
                    leftMargin = context.dp(10)
                },
            )
        }
    }

    private fun keyButtonRow(input: EditText, status: TextView): LinearLayout {
        lateinit var unlock: Button
        lateinit var skip: Button
        unlock = primaryButton("Unlock") {
            val apiKey = input.text?.toString().orEmpty().trim()
            if (apiKey.isBlank()) {
                status.text = "Paste your OpenAI key or skip this for later."
                return@primaryButton
            }
            unlock.text = "Checking..."
            unlock.isEnabled = false
            skip.isEnabled = false
            status.text = ""
            scope.launch {
                when (val result = engine.validateKey(AiProvider.OpenAI, apiKey, prefs.model(AiProvider.OpenAI))) {
                    is AnthropicResult.Success -> {
                        try {
                            prefs.provider = AiProvider.OpenAI
                            prefs.setApiKey(AiProvider.OpenAI, apiKey)
                            status.text = "The key turns."
                            delay(650)
                            showModelDownload()
                        } catch (_: SecureStorageUnavailableException) {
                            unlock.text = "Unlock"
                            unlock.isEnabled = true
                            skip.isEnabled = true
                            status.text = "Secure storage is unavailable."
                        }
                    }
                    is AnthropicResult.Failure -> {
                        unlock.text = "Unlock"
                        unlock.isEnabled = true
                        skip.isEnabled = true
                        status.text = keyFailureText(result)
                    }
                }
            }
        }
        skip = secondaryButton("Skip") {
            prefs.provider = AiProvider.OpenAI
            showModelDownload()
        }
        return LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(
                skip,
                LinearLayout.LayoutParams(context.dp(132), ViewGroup.LayoutParams.MATCH_PARENT).apply {
                    rightMargin = context.dp(12)
                },
            )
            addView(
                unlock,
                LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f),
            )
        }
    }

    private fun languageSpinner(): Spinner {
        val labels = RecognitionLanguages.choices.map { it.label }
        val selectedTag = prefs.recognitionLanguage
        return Spinner(context).apply {
            adapter = ArrayAdapter(
                context,
                android.R.layout.simple_spinner_item,
                labels,
            ).also {
                it.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
            }
            setSelection(
                RecognitionLanguages.choices.indexOfFirst { it.tag == selectedTag }
                    .takeIf { it >= 0 }
                    ?: 0,
            )
        }
    }

    private fun downloadButton(
        spinner: Spinner,
        progress: ProgressBar,
        status: TextView,
    ): Button {
        lateinit var button: Button
        button = primaryButton("Download") {
            val choice = RecognitionLanguages.choices.getOrElse(spinner.selectedItemPosition) {
                RecognitionLanguages.choiceFor(Prefs.DEFAULT_LANGUAGE)
            }
            spinner.isEnabled = false
            progress.visibility = View.VISIBLE
            status.text = "Downloading..."
            button.text = "Downloading..."
            button.isEnabled = false
            scope.launch {
                when (recognitionService.ensureModel(choice.tag)) {
                    ModelDownloadOutcome.Ready -> {
                        prefs.recognitionLanguage = choice.tag
                        progress.visibility = View.GONE
                        status.text = "Done."
                        delay(650)
                        onDone()
                    }
                    is ModelDownloadOutcome.Failure -> {
                        spinner.isEnabled = true
                        progress.visibility = View.GONE
                        status.text = "The download slipped. Check your connection and try again."
                        button.text = "Retry"
                        button.isEnabled = true
                    }
                }
            }
        }
        return button
    }

    private fun primaryButton(label: String, onClick: () -> Unit): Button {
        return context.paperButton(label).apply {
            paperText(22f, bold = true)
            setTextColor(Color.WHITE)
            minHeight = context.dp(70)
            background = buttonBackground(fill = Color.BLACK, stroke = Color.BLACK)
            setOnClickListener { onClick() }
        }
    }

    private fun secondaryButton(label: String, onClick: () -> Unit): Button {
        return context.paperButton(label).apply {
            paperText(20f, bold = true)
            minHeight = context.dp(62)
            background = buttonBackground(fill = Color.WHITE, stroke = Color.BLACK)
            setOnClickListener { onClick() }
        }
    }

    private fun modalBackground(): GradientDrawable {
        return GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = context.dp(14).toFloat()
            setColor(Color.rgb(255, 255, 250))
            setStroke(context.dp(4), Color.BLACK)
        }
    }

    private fun modalLayoutParams(): LayoutParams {
        val metrics = resources.displayMetrics
        val size = minOf(context.dp(560), metrics.widthPixels - context.dp(170), metrics.heightPixels - context.dp(360))
        return LayoutParams(size, size, Gravity.CENTER)
    }

    private fun contentLayoutParams(): LinearLayout.LayoutParams {
        return LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT,
        )
    }

    private fun spacerLayoutParams(): LinearLayout.LayoutParams {
        return LinearLayout.LayoutParams(1, 0, 1f)
    }

    private fun ctaLayoutParams(): LinearLayout.LayoutParams {
        return LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            context.dp(70),
        )
    }

    private fun fullWidthWrapContent(): LinearLayout.LayoutParams {
        return LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT,
        )
    }

    private fun LinearLayout.addCenteredText(text: String, sizeSp: Float, bold: Boolean = false): TextView {
        return TextView(context).apply {
            this.text = text
            gravity = Gravity.CENTER
            paperText(sizeSp, bold)
            addView(this, fullWidthWrapContent())
        }
    }

    private fun LinearLayout.addBodyText(text: String): TextView {
        return TextView(context).apply {
            this.text = text
            gravity = Gravity.CENTER
            setLineSpacing(context.dp(5).toFloat(), 1f)
            paperText(23f)
            addView(this, fullWidthWrapContent())
        }
    }

    private fun buttonBackground(fill: Int, stroke: Int): GradientDrawable {
        return GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = context.dp(4).toFloat()
            setColor(fill)
            setStroke(context.dp(3), stroke)
        }
    }

    private fun apiKeyOrBlank(provider: AiProvider, status: TextView): String {
        return try {
            prefs.apiKey(provider)
        } catch (_: SecureStorageUnavailableException) {
            status.text = "Secure storage is unavailable."
            ""
        }
    }

    private fun keyFailureText(failure: AnthropicResult.Failure): String {
        return when (failure.kind) {
            BrainErrorKind.InvalidKey -> "That key doesn't fit. Check it and try again."
            BrainErrorKind.Network -> "OpenAI could not be reached. Check Wi-Fi and try again."
            BrainErrorKind.Server -> "OpenAI is not responding. Try again in a moment."
            BrainErrorKind.BadRequest -> "OpenAI rejected the setup request. You can skip this for later."
            BrainErrorKind.Unknown -> "The key could not be checked. You can skip this for later."
        }
    }
}
