package com.inkwell.diary.ui

import android.app.AlertDialog
import android.view.View
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.TextView
import com.inkwell.diary.data.InkFadeStyle
import com.inkwell.diary.data.Persona
import com.inkwell.diary.data.Prefs
import com.inkwell.diary.recognize.ModelDownloadOutcome
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

    addValueRow(
        notebookGroup,
        "Storage",
        "Everything you write is stored on this device until you burn the notebook.",
    )

    lateinit var fadeRow: ChoiceRowHandle
    fadeRow = addChoiceRow(notebookGroup, "How the ink fades", prefs.inkFadeStyle.label) {
        val styles = InkFadeStyle.entries.toList()
        showChoiceDialog(
            title = "How the ink fades",
            choices = styles.map { it.label },
            selectedIndex = styles.indexOf(prefs.inkFadeStyle).coerceAtLeast(0),
        ) { index ->
            val style = styles.getOrElse(index) { InkFadeStyle.default }
            prefs.inkFadeStyle = style
            fadeRow.valueText.text = style.label
            callbacks.onInkFadeStyleChanged()
            status.text = "Ink fade saved: ${style.label}."
        }
    }

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
    val group = groupedList()
    panel.addView(group, fullWidth())

    lateinit var languageRow: ChoiceRowHandle
    languageRow = addChoiceRow(group, "Language", prefs.recognitionLanguage) {
        showChoiceDialog(
            title = "Recognition Language",
            choices = languages,
            selectedIndex = languages.indexOf(prefs.recognitionLanguage).coerceAtLeast(0),
        ) { index ->
            val language = languages.getOrElse(index) { Prefs.DEFAULT_LANGUAGE }
            status.text = "Downloading $language model..."
            scope.launch {
                when (val outcome = recognitionService.ensureModel(language)) {
                    ModelDownloadOutcome.Ready -> {
                        prefs.recognitionLanguage = language
                        languageRow.valueText.text = language
                        status.text = "Language saved: $language."
                    }
                    is ModelDownloadOutcome.Failure -> {
                        status.text = outcome.message
                    }
                }
            }
        }
    }
    panel.addGap(16)
    panel.addView(status, fullWidth())
    panel.addGap(36)
    return panelScrollView(panel)
}
