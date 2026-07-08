package com.inkwell.diary.ui

import android.app.AlertDialog
import android.view.View
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.TextView
import com.inkwell.diary.data.InkFadeStyle
import com.inkwell.diary.data.Persona
import com.inkwell.diary.data.Prefs
import com.inkwell.diary.recognize.ModelDeleteOutcome
import com.inkwell.diary.recognize.ModelDownloadOutcome
import com.inkwell.diary.recognize.RecognitionModelsOutcome
import kotlinx.coroutines.launch

internal fun SettingsScreenContext.buildNotebookScreen(): View {
    val panel = scrollPanel(topPaddingDp = 12, horizontalPaddingDp = 46)
    val status = TextView(context).paperText(16f)
    val notebookGroup = groupedList()
    panel.addView(notebookGroup, fullWidth())

    lateinit var titleRow: ChoiceRowHandle
    titleRow = addChoiceRow(notebookGroup, "Title", callbacks.currentNotebookTitle().ifBlank { "Inka's Diary" }) {
        showTextInputDialog(
            title = "Notebook Title",
            hint = "Notebook title",
            initialValue = callbacks.currentNotebookTitle(),
            masked = false,
            multiLine = false,
        ) { value ->
            callbacks.onNotebookTitleChanged(value)
            titleRow.valueText.text = callbacks.currentNotebookTitle().ifBlank { "Inka's Diary" }
            status.text = "Notebook title saved."
        }
    }
    addChoiceRow(notebookGroup, "Persona", callbacks.currentNotebookPersona().label) {
        navigate(SettingsRoute.Persona)
    }

    lateinit var inkFadeRow: ChoiceRowHandle
    inkFadeRow = addChoiceRow(notebookGroup, "Ink Animation", prefs.inkFadeStyle.label) {
        val styles = listOf(InkFadeStyle.SimplyFades, InkFadeStyle.TurnsToDust)
        showChoiceDialog(
            title = "Ink Animation",
            choices = styles.map { it.label },
            selectedIndex = styles.indexOf(prefs.inkFadeStyle).coerceAtLeast(0),
        ) { index ->
            val style = styles.getOrElse(index) { InkFadeStyle.default }
            prefs.inkFadeStyle = style
            callbacks.onInkFadeStyleChanged()
            inkFadeRow.valueText.text = style.label
            status.text = "Ink animation saved: ${style.label}."
        }
    }

    addValueRow(
        notebookGroup,
        "Storage",
        "Everything you write is stored on this device until you burn the notebook.",
    )

    addChoiceRow(notebookGroup, "Burn this notebook", "Burn") {
        AlertDialog.Builder(context)
            .setTitle("Burn this notebook")
            .setMessage("Delete this notebook and start a fresh Inka's Diary?")
            .setPositiveButton("Burn") { _, _ ->
                callbacks.onBurnNotebook()
                titleRow.valueText.text = "Inka's Diary"
                status.text = "Notebook burned."
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    panel.addGap(16)
    panel.addView(status, fullWidth())
    panel.addGap(36)
    return panelScrollView(panel)
}

internal fun SettingsScreenContext.buildPersonaScreen(): View {
    val panel = scrollPanel(topPaddingDp = 12, horizontalPaddingDp = 46)
    val status = TextView(context).paperText(16f)
    val group = groupedList()
    panel.addView(group, fullWidth())

    val radioButtons = linkedMapOf<Persona, RadioButton>()
    val customGap = View(context).apply {
        visibility = if (callbacks.currentNotebookPersona() == Persona.Custom) View.VISIBLE else View.GONE
    }
    val customGroup = groupedList().apply {
        visibility = if (callbacks.currentNotebookPersona() == Persona.Custom) View.VISIBLE else View.GONE
    }

    fun renderSelection() {
        val selected = callbacks.currentNotebookPersona()
        radioButtons.forEach { (persona, radioButton) ->
            radioButton.isChecked = persona == selected
        }
        val customVisibility = if (selected == Persona.Custom) View.VISIBLE else View.GONE
        customGap.visibility = customVisibility
        customGroup.visibility = customVisibility
    }

    fun selectPersona(persona: Persona) {
        callbacks.onNotebookPersonaChanged(persona)
        renderSelection()
        status.text = if (persona == Persona.Custom && prefs.customPrompt.isBlank()) {
            "Custom persona selected. Add a custom prompt below."
        } else {
            "${persona.label} selected."
        }
    }

    Persona.entries.forEach { persona ->
        radioButtons[persona] = addPersonaRow(group, persona) {
            selectPersona(persona)
        }
    }
    renderSelection()

    panel.addView(
        customGap,
        LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            context.dp(18),
        ),
    )
    panel.addView(customGroup, fullWidth())
    lateinit var customPromptRow: ChoiceRowHandle
    customPromptRow = addChoiceRow(customGroup, "Custom Prompt", SettingsDisplay.customPromptStatus(prefs.customPrompt)) {
        showTextInputDialog(
            title = "Custom Prompt",
            hint = "Custom system prompt",
            initialValue = prefs.customPrompt,
            masked = false,
            multiLine = true,
        ) { value ->
            prefs.customPrompt = value
            callbacks.onNotebookPersonaChanged(Persona.Custom)
            renderSelection()
            customPromptRow.valueText.text = SettingsDisplay.customPromptStatus(prefs.customPrompt)
            status.text = "Custom prompt saved."
        }
    }

    panel.addGap(16)
    panel.addView(status, fullWidth())
    panel.addGap(36)
    return panelScrollView(panel)
}

internal fun SettingsScreenContext.buildRecognitionScreen(): View {
    val panel = scrollPanel(topPaddingDp = 12, horizontalPaddingDp = 46)
    val status = TextView(context).paperText(16f)
    val languageGroup = groupedList()
    val modelsGroup = groupedList()
    panel.addView(languageGroup, fullWidth())
    panel.addGap(14)
    panel.addView(modelsGroup, fullWidth())

    val downloadedTags = linkedSetOf<String>()
    val downloadingTags = mutableSetOf<String>()
    val deletingTags = mutableSetOf<String>()
    val failedDownloads = mutableMapOf<String, String>()
    var checkingModels = true
    lateinit var renderModelRows: () -> Unit
    lateinit var startModelDownload: (RecognitionLanguageChoice) -> Unit
    lateinit var deleteModel: (RecognitionLanguageChoice) -> Unit

    fun choiceFor(tag: String): RecognitionLanguageChoice {
        return RecognitionLanguages.choices.firstOrNull { it.tag == tag }
            ?: RecognitionLanguages.choiceFor(Prefs.DEFAULT_LANGUAGE)
    }

    fun modelRowTags(): List<String> {
        val tags = linkedSetOf(prefs.recognitionLanguage)
        RecognitionLanguages.tags.forEach { tag ->
            if (
                tag in downloadingTags ||
                tag in deletingTags ||
                tag in failedDownloads ||
                tag in downloadedTags
            ) {
                tags.add(tag)
            }
        }
        return tags.toList()
    }

    renderModelRows = {
        languageGroup.removeAllViews()
        modelsGroup.removeAllViews()
        addChoiceRow(languageGroup, "Language", choiceFor(prefs.recognitionLanguage).label) {
            showChoiceDialog(
                title = "Recognition Language",
                choices = RecognitionLanguages.choices.map { it.label },
                selectedIndex = RecognitionLanguages.choices
                    .indexOfFirst { it.tag == prefs.recognitionLanguage }
                    .coerceAtLeast(0),
            ) { index ->
                val choice = RecognitionLanguages.choices.getOrElse(index) {
                    RecognitionLanguages.choiceFor(Prefs.DEFAULT_LANGUAGE)
                }
                prefs.recognitionLanguage = choice.tag
                status.text = "Language saved: ${choice.label}."
                startModelDownload(choice)
            }
        }
        addSectionLabel(modelsGroup, "Models")

        modelRowTags().forEach { tag ->
            val choice = choiceFor(tag)
            val isDownloading = tag in downloadingTags
            val isDeleting = tag in deletingTags
            val isDownloaded = tag in downloadedTags
            val failedMessage = failedDownloads[tag]
            val rowStatus = when {
                isDownloading -> "Downloading..."
                isDeleting -> "Deleting..."
                failedMessage != null -> "Failed"
                isDownloaded -> "Downloaded"
                checkingModels -> "Checking..."
                else -> "Not downloaded"
            }
            val action = when {
                isDownloading || isDeleting || checkingModels -> null
                isDownloaded -> "Delete"
                failedMessage != null -> "Retry"
                else -> "Download"
            }
            addModelRow(
                group = modelsGroup,
                label = choice.label,
                status = rowStatus,
                progressVisible = isDownloading || isDeleting,
                actionLabel = action,
            ) {
                if (tag in downloadedTags) {
                    deleteModel(choice)
                } else {
                    startModelDownload(choice)
                }
            }
        }
    }

    fun refreshDownloadedModels() {
        checkingModels = true
        renderModelRows()
        scope.launch {
            when (val outcome = recognitionService.downloadedModels(RecognitionLanguages.tags)) {
                is RecognitionModelsOutcome.Ready -> {
                    downloadedTags.clear()
                    downloadedTags.addAll(outcome.languageTags)
                    checkingModels = false
                    status.text = ""
                    renderModelRows()
                }
                is RecognitionModelsOutcome.Failure -> {
                    checkingModels = false
                    status.text = outcome.message
                    renderModelRows()
                }
            }
        }
    }

    startModelDownload = { choice ->
        if (choice.tag in downloadedTags) {
            failedDownloads.remove(choice.tag)
            status.text = "${choice.label} is already downloaded."
            renderModelRows()
        } else {
            failedDownloads.remove(choice.tag)
            downloadingTags.add(choice.tag)
            renderModelRows()
            status.text = "Downloading ${choice.label}..."
            scope.launch {
                when (val outcome = recognitionService.ensureModel(choice.tag)) {
                    ModelDownloadOutcome.Ready -> {
                        downloadingTags.remove(choice.tag)
                        downloadedTags.add(choice.tag)
                        failedDownloads.remove(choice.tag)
                        status.text = "${choice.label} downloaded."
                    }
                    is ModelDownloadOutcome.Failure -> {
                        downloadingTags.remove(choice.tag)
                        failedDownloads[choice.tag] = outcome.message
                        status.text = outcome.message
                    }
                }
                renderModelRows()
            }
        }
    }

    deleteModel = { choice ->
        deletingTags.add(choice.tag)
        failedDownloads.remove(choice.tag)
        renderModelRows()
        status.text = "Deleting ${choice.label}..."
        scope.launch {
            when (val outcome = recognitionService.deleteModel(choice.tag)) {
                ModelDeleteOutcome.Deleted -> {
                    deletingTags.remove(choice.tag)
                    downloadedTags.remove(choice.tag)
                    status.text = "${choice.label} deleted."
                }
                is ModelDeleteOutcome.Failure -> {
                    deletingTags.remove(choice.tag)
                    status.text = outcome.message
                }
            }
            renderModelRows()
        }
    }

    refreshDownloadedModels()
    panel.addGap(16)
    panel.addView(status, fullWidth())
    panel.addGap(36)
    return panelScrollView(panel)
}
