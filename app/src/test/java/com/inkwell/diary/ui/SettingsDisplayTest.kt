package com.inkwell.diary.ui

import com.inkwell.diary.data.AiProvider
import com.inkwell.diary.data.Prefs
import com.inkwell.diary.data.ReasoningEffort
import com.inkwell.diary.page.HandwritingFontWeight
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SettingsDisplayTest {
    @Test
    fun `commit delay label preserves existing clamping and seconds format`() {
        assertEquals("1.0 seconds", SettingsDisplay.commitDelayLabel(Prefs.MIN_COMMIT_DELAY_MILLIS - 100L))
        assertEquals("2.5 seconds", SettingsDisplay.commitDelayLabel(2500L))
        assertEquals("10.0 seconds", SettingsDisplay.commitDelayLabel(Prefs.MAX_COMMIT_DELAY_MILLIS + 100L))
    }

    @Test
    fun `writing labels match current settings text`() {
        assertEquals("42 sp", SettingsDisplay.fontSizeLabel(42.9f))
        assertEquals(HandwritingFontWeight.Bold.label, SettingsDisplay.fontWeightLabel(HandwritingFontWeight.Bold.value))
        assertEquals("Not set", SettingsDisplay.customPromptStatus(""))
        assertEquals("Configured", SettingsDisplay.customPromptStatus("reply like fog"))
    }

    @Test
    fun `model choices keep unknown saved model before provider defaults`() {
        val choices = SettingsDisplay.modelChoices(AiProvider.Anthropic, "claude-local-test")

        assertEquals("claude-local-test", choices.first())
        AiProvider.Anthropic.modelOptions.forEach { model ->
            assertTrue(choices.contains(model))
        }
    }

    @Test
    fun `reasoning choices come from provider support matrix`() {
        assertEquals(ReasoningEffort.choicesFor(AiProvider.OpenAI), SettingsDisplay.reasoningChoices(AiProvider.OpenAI))
        assertEquals(ReasoningEffort.choicesFor(AiProvider.Groq), SettingsDisplay.reasoningChoices(AiProvider.Groq))
    }
}
