package com.inkwell.diary.ui

import android.content.Context
import android.graphics.Color
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.ScrollView
import android.widget.TextView
import com.inkwell.diary.brain.ConversationEngine
import com.inkwell.diary.data.AiProvider
import com.inkwell.diary.data.Persona
import com.inkwell.diary.data.Prefs
import com.inkwell.diary.data.SecureStorageUnavailableException
import com.inkwell.diary.page.HandwritingFont
import com.inkwell.diary.page.HandwritingFontWeight
import com.inkwell.diary.recognize.RecognitionService
import kotlinx.coroutines.CoroutineScope

internal class SettingsScreenContext(
    val context: Context,
    val prefs: Prefs,
    val engine: ConversationEngine,
    val recognitionService: RecognitionService,
    val scope: CoroutineScope,
    val callbacks: SettingsPanel.Callbacks,
    val navigate: (SettingsRoute) -> Unit,
) {
    val languages = RecognitionLanguages.tags
    val aiProviders = AiProvider.entries.toList()
    val handwritingFonts = HandwritingFont.entries.toList()
    val handwritingFontWeights = HandwritingFontWeight.entries.toList()
    private val rows = SettingsRows(context)
    private val dialogs = SettingsDialogs(context, handwritingFonts)

    fun scrollPanel(
        topPaddingDp: Int = 20,
        horizontalPaddingDp: Int = 42,
    ): LinearLayout {
        val scroll = ScrollView(context).apply {
            setBackgroundColor(Color.WHITE)
        }
        val panel = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.NO_GRAVITY
            setPadding(
                context.dp(horizontalPaddingDp),
                context.dp(topPaddingDp),
                context.dp(horizontalPaddingDp),
                context.dp(36),
            )
        }
        scroll.addView(
            panel,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.WRAP_CONTENT,
            ),
        )
        return panel
    }

    fun groupedList(): LinearLayout {
        return rows.groupedList()
    }

    fun addPersonaRow(
        group: LinearLayout,
        persona: Persona,
        onClick: () -> Unit,
    ): RadioButton {
        return rows.addPersonaRow(group, persona, onClick)
    }

    fun addChoiceRow(
        group: LinearLayout,
        label: String,
        value: String,
        warningVisible: Boolean = false,
        onClick: () -> Unit,
    ): ChoiceRowHandle {
        return rows.addChoiceRow(group, label, value, warningVisible, onClick)
    }

    fun showChoiceDialog(
        title: String,
        choices: List<String>,
        selectedIndex: Int,
        onSelected: (Int) -> Unit,
    ) {
        dialogs.showChoice(title, choices, selectedIndex, onSelected)
    }

    fun showTextInputDialog(
        title: String,
        hint: String,
        initialValue: String,
        masked: Boolean,
        multiLine: Boolean,
        onSaved: (String) -> Unit,
    ) {
        dialogs.showTextInput(title, hint, initialValue, masked, multiLine, onSaved)
    }

    fun showSliderDialog(
        title: String,
        min: Int,
        max: Int,
        step: Int,
        current: Int,
        valueLabel: (Int) -> String,
        onSaved: (Int) -> Unit,
    ) {
        dialogs.showSlider(title, min, max, step, current, valueLabel, onSaved)
    }

    fun showFontLicensesDialog() {
        dialogs.showFontLicenses()
    }

    fun apiKeyStatus(provider: AiProvider): String {
        return try {
            if (prefs.apiKey(provider).isBlank()) "Required" else "Configured"
        } catch (_: SecureStorageUnavailableException) {
            "Storage error"
        }
    }

    fun addTopicRow(group: LinearLayout, label: String, onClick: () -> Unit) {
        rows.addTopicRow(group, label, onClick)
    }

    fun addValueRow(group: LinearLayout, label: String, value: String) {
        rows.addValueRow(group, label, value)
    }

    fun addSectionLabel(group: LinearLayout, label: String) {
        rows.addSectionLabel(group, label)
    }

    fun addModelRow(
        group: LinearLayout,
        label: String,
        status: String,
        progressVisible: Boolean,
        actionLabel: String?,
        onAction: () -> Unit,
    ) {
        rows.addModelRow(group, label, status, progressVisible, actionLabel, onAction)
    }

    fun addPreviewBlock(parent: LinearLayout, label: String, value: String): TextView {
        return rows.addPreviewBlock(parent, label, value)
    }

    fun addToggleRow(
        group: LinearLayout,
        label: String,
        explanation: String,
        checked: Boolean,
        onCheckedChange: (Boolean) -> Unit,
    ) {
        rows.addToggleRow(group, label, explanation, checked, onCheckedChange)
    }

    fun fullWidth(): LinearLayout.LayoutParams {
        return rows.fullWidth()
    }

    fun panelScrollView(panel: LinearLayout): ScrollView {
        return panel.parent as ScrollView
    }
}

internal const val SETTINGS_SECURE_STORAGE_ERROR = "Secure storage is unavailable, so API keys cannot be read or saved."
