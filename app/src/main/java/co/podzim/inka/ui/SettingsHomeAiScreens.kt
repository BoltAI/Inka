package co.podzim.inka.ui

import android.annotation.SuppressLint
import android.view.View
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

    providerRow = addChoiceRow(group, "Provider", currentProvider.label) {
        showChoiceDialog(
            title = "Provider",
            choices = aiProviders.map { it.label },
            selectedIndex = aiProviders.indexOf(currentProvider).coerceAtLeast(0),
        ) { index ->
            currentProvider = aiProviders.getOrElse(index) { AiProvider.Anthropic }
            prefs.provider = currentProvider
            refreshProviderRows()
            if (hasApiKey(currentProvider)) {
                status.text = "${currentProvider.label} selected."
            } else {
                status.text = "${currentProvider.label} API key is required."
                panel.post { promptForApiKey(currentProvider) }
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
