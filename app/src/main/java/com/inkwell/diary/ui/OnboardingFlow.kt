package com.inkwell.diary.ui

import android.content.ClipboardManager
import android.content.Context
import android.graphics.Color
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.Spinner
import android.widget.TextView
import com.inkwell.diary.brain.AnthropicResult
import com.inkwell.diary.brain.ConversationEngine
import com.inkwell.diary.data.AiProvider
import com.inkwell.diary.data.Prefs
import com.inkwell.diary.recognize.ModelDownloadOutcome
import com.inkwell.diary.recognize.RecognitionService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

class OnboardingFlow(
    context: Context,
    private val prefs: Prefs,
    private val engine: ConversationEngine,
    private val recognitionService: RecognitionService,
    private val scope: CoroutineScope,
    private val onDone: () -> Unit,
) : FrameLayout(context) {
    private val languages = listOf("en-US", "es-ES", "fr-FR", "de-DE", "vi-VN")
    private val providers = AiProvider.entries.toList()

    init {
        setBackgroundColor(Color.rgb(248, 247, 240))
        showIntro()
    }

    private fun showIntro() {
        val panel = pagePanel()
        panel.addText("A diary that writes back.", 34f, bold = true)
        panel.addGap(20)
        panel.addText(
            "Write by hand on a quiet page. Your ink fades away, and a short answer writes itself back in a loose script.",
            20f,
        )
        panel.addGap(28)
        panel.addView(context.paperButton("Continue").apply {
            setOnClickListener { showApiKey() }
        })
        replace(panel)
    }

    private fun showApiKey() {
        val panel = pagePanel()
        panel.addText("API key", 30f, bold = true)
        panel.addGap(12)
        panel.addText(
            "Your key stays on this device. Requests go directly to the selected provider.",
            18f,
        )
        panel.addGap(16)
        val providerSpinner = Spinner(context).apply {
            adapter = ArrayAdapter(
                context,
                android.R.layout.simple_spinner_dropdown_item,
                providers.map { it.label },
            )
            setSelection(providers.indexOf(prefs.provider).coerceAtLeast(0))
        }
        panel.addView(providerSpinner, fullWidth())
        panel.addGap(12)
        val input = context.paperEditText("${prefs.provider.label} API key", masked = true)
        input.setText(prefs.apiKey(prefs.provider))
        panel.addView(input, fullWidth())
        panel.addGap(8)
        val status = TextView(context).paperText(15f)
        val continueButton = context.paperButton("Continue").apply {
            isEnabled = false
            setOnClickListener { showModelDownload() }
        }
        val row = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(context.paperButton("Paste").apply {
                setOnClickListener {
                    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                    input.setText(clipboard.primaryClip?.getItemAt(0)?.coerceToText(context)?.toString().orEmpty())
                }
            })
            addView(context.paperButton("Validate").apply {
                setOnClickListener {
                    val provider = selectedProvider(providerSpinner)
                    status.text = "Validating ${provider.label}..."
                    continueButton.isEnabled = false
                    scope.launch {
                        when (engine.validateKey(provider, input.text.toString(), prefs.model(provider))) {
                            is AnthropicResult.Success -> {
                                prefs.provider = provider
                                prefs.setApiKey(provider, input.text.toString())
                                status.text = "${provider.label} key works."
                                continueButton.isEnabled = true
                            }
                            is AnthropicResult.Failure -> {
                                status.text = "That key did not validate."
                            }
                        }
                    }
                }
            })
        }
        providerSpinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                val provider = providers.getOrElse(position) { AiProvider.Anthropic }
                prefs.provider = provider
                input.hint = "${provider.label} API key"
                input.setText(prefs.apiKey(provider))
                continueButton.isEnabled = false
                status.text = "${provider.label} selected."
            }

            override fun onNothingSelected(parent: AdapterView<*>?) = Unit
        }
        panel.addView(row)
        panel.addGap(8)
        panel.addView(status, fullWidth())
        panel.addGap(20)
        panel.addView(continueButton)
        replace(panel)
    }

    private fun showModelDownload() {
        val panel = pagePanel()
        panel.addText("Handwriting model", 30f, bold = true)
        panel.addGap(12)
        panel.addText(
            "Download the on-device handwriting model once. Recognition keeps working offline after the model is ready.",
            18f,
        )
        panel.addGap(16)
        val spinner = Spinner(context).apply {
            adapter = ArrayAdapter(context, android.R.layout.simple_spinner_dropdown_item, languages)
            setSelection(languages.indexOf(prefs.recognitionLanguage).coerceAtLeast(0))
        }
        panel.addView(spinner, fullWidth())
        panel.addGap(12)
        val status = TextView(context).paperText(15f)
        val download = context.paperButton("Download model").apply {
            setOnClickListener {
                val language = spinner.selectedItem.toString()
                status.text = "Downloading $language..."
                isEnabled = false
                scope.launch {
                    when (val outcome = recognitionService.ensureModel(language)) {
                        ModelDownloadOutcome.Ready -> {
                            prefs.recognitionLanguage = language
                            prefs.onboardingComplete = true
                            status.text = "Model ready."
                            onDone()
                        }
                        is ModelDownloadOutcome.Failure -> {
                            status.text = outcome.message
                            this@apply.isEnabled = true
                        }
                    }
                }
            }
        }
        panel.addView(download)
        panel.addGap(8)
        panel.addView(status, fullWidth())
        replace(panel)
    }

    private fun pagePanel(): LinearLayout {
        return context.verticalPanel().apply {
            gravity = Gravity.CENTER
        }
    }

    private fun replace(view: LinearLayout) {
        removeAllViews()
        addView(view)
    }

    private fun fullWidth(): LinearLayout.LayoutParams {
        return LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT,
        )
    }

    private fun selectedProvider(spinner: Spinner): AiProvider {
        return providers.getOrElse(spinner.selectedItemPosition) { AiProvider.Anthropic }
    }
}
