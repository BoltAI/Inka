package com.inkwell.diary.ui

import com.inkwell.diary.data.AiProvider
import com.inkwell.diary.data.Prefs
import com.inkwell.diary.data.ReasoningEffort
import com.inkwell.diary.page.HandwritingFontWeight

internal object SettingsDisplay {
    fun commitDelayLabel(delayMillis: Long): String {
        return "${"%.1f".format(delayMillis.coerceIn(Prefs.MIN_COMMIT_DELAY_MILLIS, Prefs.MAX_COMMIT_DELAY_MILLIS) / 1000f)} seconds"
    }

    fun fontSizeLabel(sizeSp: Float): String {
        return "${sizeSp.toInt()} sp"
    }

    fun fontWeightLabel(weightValue: Int): String {
        return HandwritingFontWeight.fromValue(weightValue).label
    }

    fun customPromptStatus(customPrompt: String): String {
        return if (customPrompt.isBlank()) "Not set" else "Configured"
    }

    fun modelChoices(provider: AiProvider, savedModel: String): List<String> {
        return if (savedModel in provider.modelOptions) {
            provider.modelOptions
        } else {
            listOf(savedModel) + provider.modelOptions
        }
    }

    fun reasoningChoices(provider: AiProvider): List<ReasoningEffort> {
        return ReasoningEffort.choicesFor(provider)
    }
}
