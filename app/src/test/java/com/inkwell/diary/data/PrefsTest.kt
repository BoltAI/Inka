package com.inkwell.diary.data

import com.inkwell.diary.page.HandwritingFont
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class PrefsTest {
    @Before
    fun clearPrefs() {
        val app = RuntimeEnvironment.getApplication()
        app.getSharedPreferences("inkwell_prefs", android.content.Context.MODE_PRIVATE)
            .edit()
            .clear()
            .commit()
        app.getSharedPreferences("inkwell_secure", android.content.Context.MODE_PRIVATE)
            .edit()
            .clear()
            .commit()
    }

    @Test
    fun `handwriting style preferences persist and clamp size`() {
        val prefs = Prefs(RuntimeEnvironment.getApplication())

        assertEquals(HandwritingFont.MsMadi.key, prefs.handwritingFontKey)
        assertEquals(Prefs.DEFAULT_HANDWRITING_FONT_SIZE_SP, prefs.handwritingFontSizeSp, 0.01f)
        assertFalse(prefs.handwritingFontBold)

        prefs.handwritingFontSizeSp = 72f
        prefs.handwritingFontBold = true

        assertEquals(Prefs.MAX_HANDWRITING_FONT_SIZE_SP, prefs.handwritingFontSizeSp, 0.01f)
        assertTrue(prefs.handwritingFontBold)

        prefs.handwritingFontSizeSp = 10f

        assertEquals(Prefs.MIN_HANDWRITING_FONT_SIZE_SP, prefs.handwritingFontSizeSp, 0.01f)
    }

    @Test
    fun `developer auto reply pause persists`() {
        val prefs = Prefs(RuntimeEnvironment.getApplication())

        assertFalse(prefs.autoReplyPaused)

        prefs.autoReplyPaused = true

        assertTrue(Prefs(RuntimeEnvironment.getApplication()).autoReplyPaused)
    }

    @Test
    fun `developer toolbar log button defaults off and persists`() {
        val prefs = Prefs(RuntimeEnvironment.getApplication())

        assertFalse(prefs.showToolbarLogButton)

        prefs.showToolbarLogButton = true

        assertTrue(Prefs(RuntimeEnvironment.getApplication()).showToolbarLogButton)
    }

    @Test
    fun `provider stores separate keys and models`() {
        val prefs = Prefs(RuntimeEnvironment.getApplication())

        assertEquals(AiProvider.Anthropic, prefs.provider)
        assertEquals(AiProvider.Anthropic.defaultModel, prefs.model)

        prefs.setApiKey(AiProvider.Anthropic, "anthropic-key")
        prefs.setModel(AiProvider.Anthropic, "claude-custom")
        prefs.setApiKey(AiProvider.OpenAI, "openai-key")
        prefs.setModel(AiProvider.OpenAI, "openai-custom")
        prefs.setApiKey(AiProvider.Groq, "groq-key")
        prefs.setModel(AiProvider.Groq, "groq-custom")

        prefs.provider = AiProvider.OpenAI
        assertEquals("openai-key", prefs.apiKey)
        assertEquals("openai-custom", prefs.model)

        prefs.provider = AiProvider.Groq
        assertEquals("groq-key", prefs.apiKey)
        assertEquals("groq-custom", prefs.model)

        prefs.provider = AiProvider.Anthropic
        assertEquals("anthropic-key", prefs.apiKey)
        assertEquals("claude-custom", prefs.model)
    }

    @Test
    fun `provider model options include defaults`() {
        AiProvider.entries.forEach { provider ->
            assertTrue(provider.modelOptions.contains(provider.defaultModel))
        }
    }

    @Test
    fun `persona defaults to whisper and maps legacy stored values`() {
        val app = RuntimeEnvironment.getApplication()
        val prefs = Prefs(app)

        assertEquals(Persona.Whisper, prefs.persona)
        assertTrue(prefs.systemPrompt().contains(PersonaPrompts.WHISPER))

        app.getSharedPreferences("inkwell_prefs", android.content.Context.MODE_PRIVATE)
            .edit()
            .putString("persona", "Socratic")
            .commit()

        assertEquals(Persona.Socrates, Prefs(app).persona)
    }

    @Test
    fun `diary mode defaults are per persona and persist overrides`() {
        val prefs = Prefs(RuntimeEnvironment.getApplication())

        assertEquals(DiaryMode.Fade, prefs.diaryModeFor(Persona.Whisper))
        assertEquals(DiaryMode.Manuscript, prefs.diaryModeFor(Persona.Confidant))
        assertEquals(DiaryMode.Manuscript, prefs.diaryModeFor(Persona.Custom))

        prefs.setDiaryMode(Persona.Whisper, DiaryMode.Manuscript)

        assertEquals(DiaryMode.Manuscript, Prefs(RuntimeEnvironment.getApplication()).diaryModeFor(Persona.Whisper))
        assertEquals(DiaryMode.Manuscript, Prefs(RuntimeEnvironment.getApplication()).diaryModeFor(Persona.Confidant))
    }
}
