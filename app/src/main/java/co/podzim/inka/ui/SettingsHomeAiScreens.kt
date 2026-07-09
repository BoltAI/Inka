package co.podzim.inka.ui

import android.annotation.SuppressLint
import android.app.AlertDialog
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import co.podzim.inka.BuildConfig
import co.podzim.inka.brain.AnthropicResult
import co.podzim.inka.data.AiProvider
import co.podzim.inka.data.Prefs
import co.podzim.inka.data.ReasoningEffort
import co.podzim.inka.data.SecureStorageUnavailableException
import kotlinx.coroutines.launch

internal fun SettingsScreenContext.buildHomeScreen(): View {
    val panel = scrollPanel(topPaddingDp = 12, horizontalPaddingDp = 46)
    val group = groupedList()
    panel.addView(group, fullWidth())

    addTopicRow(group, "General") { navigate(SettingsRoute.Notebook) }
    addTopicRow(group, "AI Settings") { navigate(SettingsRoute.Ai) }
    addTopicRow(group, "Persona") { navigate(SettingsRoute.Persona) }
    addTopicRow(group, "Recognition Settings") { navigate(SettingsRoute.Recognition) }
    addTopicRow(group, "Writing Settings") { navigate(SettingsRoute.Writing) }
    addTopicRow(group, "Developer") { navigate(SettingsRoute.Developer) }
    addTopicRow(group, "About Inka") { navigate(SettingsRoute.About) }
    addValueRow(group, "Inka Version", BuildConfig.VERSION_NAME)

    return panelScrollView(panel)
}

@SuppressLint("ClickableViewAccessibility")
internal fun SettingsScreenContext.buildAiScreen(): View {
    val panel = scrollPanel(topPaddingDp = 12, horizontalPaddingDp = 46)
    val status = TextView(context).paperText(16f)
    val group = groupedList()
    panel.addView(group, fullWidth())

    var currentProvider = prefs.provider
    var modelChoices = SettingsDisplay.modelChoices(currentProvider, prefs.model(currentProvider))
    var effortChoices = SettingsDisplay.reasoningChoices(currentProvider)
    lateinit var providerRow: ChoiceRowHandle
    lateinit var modelRow: ChoiceRowHandle
    lateinit var effortRow: ChoiceRowHandle
    lateinit var apiKeyRow: ChoiceRowHandle

    fun apiKeyOrBlank(provider: AiProvider): String {
        return try {
            prefs.apiKey(provider)
        } catch (_: SecureStorageUnavailableException) {
            status.text = SETTINGS_SECURE_STORAGE_ERROR
            ""
        }
    }

    fun hasApiKey(provider: AiProvider): Boolean = apiKeyOrBlank(provider).isNotBlank()

    fun updateApiKeyRow() {
        apiKeyRow.valueText.text = apiKeyStatus(currentProvider)
        apiKeyRow.warningIcon.visibility = if (hasApiKey(currentProvider)) View.GONE else View.VISIBLE
    }

    fun validateSavedKey(provider: AiProvider, apiKey: String) {
        val cleaned = apiKey.trim()
        if (cleaned.isBlank()) {
            status.text = "${provider.label} API key is required."
            return
        }
        val model = prefs.model(provider)
        status.text = "Validating ${provider.label} key..."
        scope.launch {
            when (engine.validateKey(provider, cleaned, model)) {
                is AnthropicResult.Success -> {
                    status.text = "${provider.label} key saved and validated."
                }
                is AnthropicResult.Failure -> {
                    status.text = "${provider.label} key saved, but validation failed."
                }
            }
        }
    }

    fun promptForApiKey(provider: AiProvider) {
        showTextInputDialog(
            title = "${provider.label} API Key",
            hint = "${provider.label} API key",
            initialValue = apiKeyOrBlank(provider),
            masked = false,
            multiLine = false,
        ) { value ->
            prefs.provider = provider
            try {
                prefs.setApiKey(provider, value)
            } catch (_: SecureStorageUnavailableException) {
                status.text = SETTINGS_SECURE_STORAGE_ERROR
                updateApiKeyRow()
                return@showTextInputDialog
            }
            currentProvider = provider
            updateApiKeyRow()
            validateSavedKey(provider, value)
        }
    }

    fun refreshProviderRows() {
        modelChoices = SettingsDisplay.modelChoices(currentProvider, prefs.model(currentProvider))
        effortChoices = SettingsDisplay.reasoningChoices(currentProvider)
        providerRow.valueText.text = currentProvider.label
        modelRow.valueText.text = prefs.model(currentProvider)
        effortRow.valueText.text = prefs.reasoningEffort(currentProvider).label
        updateApiKeyRow()
    }

    fun showPhoneApiKeySetup(provider: AiProvider) {
        val model = prefs.model(provider)
        val dialogStatus = TextView(context).paperText(16f).apply {
            gravity = Gravity.CENTER
        }
        val content = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(context.dp(24), context.dp(24), context.dp(24), 0)
        }
        var checking = false
        var dialog: AlertDialog? = null
        var setupServer: ApiKeySetupServer? = null
        val server = ApiKeySetupServer(
            providerLabel = provider.label,
            model = model,
            guideUrl = provider.apiKeyGuideUrl(),
        ) { apiKey ->
            content.post {
                if (checking) return@post
                checking = true
                dialogStatus.text = "Key received from phone. Checking..."
                scope.launch {
                    when (engine.validateKey(provider, apiKey.trim(), model)) {
                        is AnthropicResult.Success -> {
                            try {
                                prefs.provider = provider
                                prefs.setApiKey(provider, apiKey)
                                currentProvider = provider
                                refreshProviderRows()
                                status.text = "${provider.label} key saved and validated."
                                dialogStatus.text = "${provider.label} key saved and validated."
                                dialog?.setTitle("Success")
                                setupServer?.close()
                            } catch (_: SecureStorageUnavailableException) {
                                checking = false
                                status.text = SETTINGS_SECURE_STORAGE_ERROR
                                dialogStatus.text = SETTINGS_SECURE_STORAGE_ERROR
                            }
                        }
                        is AnthropicResult.Failure -> {
                            checking = false
                            status.text = "${provider.label} key was not saved. Validation failed."
                            dialogStatus.text = "${provider.label} key was not saved. Check it and try again."
                        }
                    }
                }
            }
        }
        setupServer = server
        val setup = server.start()

        if (setup is ApiKeySetupServerStart.Ready) {
            val qrSize = context.dp(210)
            content.addView(
                ImageView(context).apply {
                    setImageBitmap(qrBitmap(setup.url, qrSize))
                    scaleType = ImageView.ScaleType.FIT_CENTER
                    contentDescription = "QR code for phone API key setup"
                },
                LinearLayout.LayoutParams(qrSize, qrSize),
            )
            content.addView(
                TextView(context).apply {
                    text = "Scan with your phone. The page shows where to create a ${provider.label} key and lets you send it back to this tablet."
                    gravity = Gravity.CENTER
                    setLineSpacing(context.dp(4).toFloat(), 1f)
                    paperText(17f)
                },
                LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                ).apply {
                    topMargin = context.dp(12)
                },
            )
        } else {
            content.addView(
                TextView(context).apply {
                    text = "Phone setup is unavailable on this network. Use the API Key row instead."
                    gravity = Gravity.CENTER
                    paperText(17f)
                },
                LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                ),
            )
        }
        content.addView(
            dialogStatus,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply {
                topMargin = context.dp(14)
            },
        )

        AlertDialog.Builder(context)
            .setTitle("${provider.label} Phone Setup")
            .setView(content)
            .setNegativeButton("Close", null)
            .create()
            .apply {
                dialog = this
                setOnDismissListener { setupServer?.close() }
                show()
            }
    }

    providerRow = addChoiceRow(group, "Provider", currentProvider.label) {
        showChoiceDialog(
            title = "Provider",
            choices = aiProviders.map { it.label },
            selectedIndex = aiProviders.indexOf(currentProvider).coerceAtLeast(0),
        ) { index ->
            val selectedProvider = aiProviders.getOrElse(index) { AiProvider.Anthropic }
            currentProvider = selectedProvider
            prefs.provider = currentProvider
            refreshProviderRows()
            if (hasApiKey(selectedProvider)) {
                status.text = "${selectedProvider.label} selected."
            } else {
                status.text = "${selectedProvider.label} API key is required."
                panel.post { showPhoneApiKeySetup(selectedProvider) }
            }
        }
    }
    apiKeyRow = addChoiceRow(
        group = group,
        label = "API Key",
        value = apiKeyStatus(currentProvider),
        warningVisible = !hasApiKey(currentProvider),
    ) {
        promptForApiKey(currentProvider)
    }
    addChoiceRow(group, "Set Up by Phone", "Open") {
        showPhoneApiKeySetup(currentProvider)
    }
    modelRow = addChoiceRow(group, "Model", prefs.model(currentProvider)) {
        modelChoices = SettingsDisplay.modelChoices(currentProvider, prefs.model(currentProvider))
        showChoiceDialog(
            title = "Model",
            choices = modelChoices,
            selectedIndex = modelChoices.indexOf(prefs.model(currentProvider)).coerceAtLeast(0),
        ) { index ->
            val model = modelChoices.getOrElse(index) { currentProvider.defaultModel }
            prefs.provider = currentProvider
            prefs.setModel(currentProvider, model)
            modelRow.valueText.text = model
            status.text = "${currentProvider.label} model saved: $model"
        }
    }
    effortRow = addChoiceRow(group, "Thinking Effort", prefs.reasoningEffort(currentProvider).label) {
        effortChoices = SettingsDisplay.reasoningChoices(currentProvider)
        showChoiceDialog(
            title = "Thinking Effort",
            choices = effortChoices.map { it.label },
            selectedIndex = effortChoices.indexOf(prefs.reasoningEffort(currentProvider)).coerceAtLeast(0),
        ) { index ->
            val effort = effortChoices.getOrElse(index) { ReasoningEffort.Default }
            prefs.provider = currentProvider
            prefs.setReasoningEffort(currentProvider, effort)
            effortRow.valueText.text = effort.label
            status.text = "${currentProvider.label} thinking effort saved: ${effort.label}"
        }
    }
    status.text = if (hasApiKey(currentProvider)) "" else "${currentProvider.label} API key is required."
    panel.addGap(16)
    panel.addView(status, fullWidth())
    panel.addGap(36)
    return panelScrollView(panel)
}
