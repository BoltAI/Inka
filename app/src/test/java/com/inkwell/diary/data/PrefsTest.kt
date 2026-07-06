package com.inkwell.diary.data

import com.inkwell.diary.page.HandwritingFont
import com.inkwell.diary.page.HandwritingFontWeight
import com.inkwell.diary.page.dissolveConfig
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

        assertEquals(HandwritingFont.DancingScript.key, prefs.handwritingFontKey)
        assertEquals(Prefs.DEFAULT_HANDWRITING_FONT_SIZE_SP, prefs.handwritingFontSizeSp, 0.01f)
        assertEquals(HandwritingFontWeight.Regular.value, prefs.handwritingFontWeight)

        prefs.handwritingFontSizeSp = 72f
        prefs.handwritingFontWeight = HandwritingFontWeight.Medium.value

        assertEquals(Prefs.MAX_HANDWRITING_FONT_SIZE_SP, prefs.handwritingFontSizeSp, 0.01f)
        assertEquals(HandwritingFontWeight.Medium.value, prefs.handwritingFontWeight)

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
    fun `ink fade style defaults to dust and persists simple fade fallback`() {
        val prefs = Prefs(RuntimeEnvironment.getApplication())

        assertEquals(InkFadeStyle.TurnsToDust, prefs.inkFadeStyle)

        prefs.inkFadeStyle = InkFadeStyle.SimplyFades

        assertEquals(InkFadeStyle.SimplyFades, Prefs(RuntimeEnvironment.getApplication()).inkFadeStyle)
    }

    @Test
    fun `reply style defaults to writing and uses separate commit delays`() {
        val prefs = Prefs(RuntimeEnvironment.getApplication())

        assertEquals(ReplyStyle.Writing, prefs.replyStyle)
        assertEquals(Prefs.DEFAULT_COMMIT_DELAY_MILLIS, prefs.commitDelayMillis)

        prefs.commitDelayMillis = 2500L
        prefs.replyStyle = ReplyStyle.Drawing

        assertEquals(Prefs.DEFAULT_DRAWING_COMMIT_DELAY_MILLIS, prefs.commitDelayMillis)

        prefs.commitDelayMillis = 6500L

        val persisted = Prefs(RuntimeEnvironment.getApplication())
        assertEquals(ReplyStyle.Drawing, persisted.replyStyle)
        assertEquals(6500L, persisted.commitDelayMillis)

        persisted.replyStyle = ReplyStyle.Writing
        assertEquals(2500L, persisted.commitDelayMillis)
    }

    @Test
    fun `dissolve tuning defaults persist clamp bounded values and reset`() {
        val prefs = Prefs(RuntimeEnvironment.getApplication())

        assertEquals(Prefs.DEFAULT_DISSOLVE_CELL_SIZE_PX, prefs.dissolveCellSizePx)
        assertEquals(Prefs.DEFAULT_DISSOLVE_SWEEP_MS, prefs.dissolveSweepMs)
        assertEquals(Prefs.DEFAULT_DISSOLVE_CELL_LIFE_MS, prefs.dissolveCellLifeMs)
        assertEquals(Prefs.DEFAULT_DISSOLVE_WIND_SHEAR_PX, prefs.dissolveWindShearPx)
        assertEquals(Prefs.DEFAULT_DISSOLVE_DRIFT_MAX_PX, prefs.dissolveDriftMaxPx)

        prefs.dissolveCellSizePx = 99
        prefs.dissolveSweepMs = 99_000L
        prefs.dissolveCellLifeMs = 99_000L
        prefs.dissolveWindShearPx = 99_000
        prefs.dissolveDriftMaxPx = 99_000

        val persisted = Prefs(RuntimeEnvironment.getApplication())
        assertEquals(Prefs.MAX_DISSOLVE_CELL_SIZE_PX, persisted.dissolveCellSizePx)
        assertEquals(Prefs.MAX_DISSOLVE_SWEEP_MS, persisted.dissolveSweepMs)
        assertEquals(Prefs.MAX_DISSOLVE_CELL_LIFE_MS, persisted.dissolveCellLifeMs)
        assertEquals(99_000, persisted.dissolveWindShearPx)
        assertEquals(Prefs.MAX_DISSOLVE_DRIFT_MAX_PX, persisted.dissolveDriftMaxPx)

        persisted.resetDissolveConfig()

        assertEquals(Prefs.DEFAULT_DISSOLVE_CELL_SIZE_PX, Prefs(RuntimeEnvironment.getApplication()).dissolveCellSizePx)
    }

    @Test
    fun `dissolve config defaults match dust lab baseline`() {
        val config = Prefs(RuntimeEnvironment.getApplication()).dissolveConfig()

        assertEquals(8, config.cellSizePx)
        assertEquals(1100L, config.sweepMs)
        assertEquals(700L, config.cellLifeMs)
        assertEquals(300f, config.windShearPx, 0.01f)
    }

    @Test
    fun `legacy handwriting bold preference migrates to bold weight`() {
        val app = RuntimeEnvironment.getApplication()
        app.getSharedPreferences("inkwell_prefs", android.content.Context.MODE_PRIVATE)
            .edit()
            .putBoolean("handwriting_font_bold", true)
            .commit()

        val prefs = Prefs(app)

        assertEquals(HandwritingFontWeight.Bold.value, prefs.handwritingFontWeight)
    }

    @Test
    fun `old auto saved dissolve defaults migrate to new baseline`() {
        val app = RuntimeEnvironment.getApplication()
        app.getSharedPreferences("inkwell_prefs", android.content.Context.MODE_PRIVATE)
            .edit()
            .putInt("dissolve_cell_size_px", 4)
            .putLong("dissolve_sweep_ms", 1500L)
            .putLong("dissolve_cell_life_ms", 950L)
            .putInt("dissolve_wind_shear_px", 200)
            .commit()

        val prefs = Prefs(app)

        assertEquals(8, prefs.dissolveCellSizePx)
        assertEquals(1100L, prefs.dissolveSweepMs)
        assertEquals(700L, prefs.dissolveCellLifeMs)
        assertEquals(300, prefs.dissolveWindShearPx)
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
    fun `provider stores separate thinking effort`() {
        val prefs = Prefs(RuntimeEnvironment.getApplication())

        assertEquals(ReasoningEffort.Default, prefs.reasoningEffort)

        prefs.setReasoningEffort(AiProvider.Anthropic, ReasoningEffort.Max)
        prefs.setReasoningEffort(AiProvider.OpenAI, ReasoningEffort.XHigh)
        prefs.setReasoningEffort(AiProvider.Groq, ReasoningEffort.High)

        prefs.provider = AiProvider.OpenAI
        assertEquals(ReasoningEffort.XHigh, prefs.reasoningEffort)

        prefs.provider = AiProvider.Groq
        assertEquals(ReasoningEffort.High, prefs.reasoningEffort)

        prefs.provider = AiProvider.Anthropic
        assertEquals(ReasoningEffort.Max, prefs.reasoningEffort)
    }

    @Test
    fun `provider rejects unsupported thinking effort`() {
        val prefs = Prefs(RuntimeEnvironment.getApplication())

        prefs.setReasoningEffort(AiProvider.Groq, ReasoningEffort.Max)

        assertEquals(ReasoningEffort.Default, prefs.reasoningEffort(AiProvider.Groq))
    }

    @Test
    fun `provider model options include defaults`() {
        AiProvider.entries.forEach { provider ->
            assertTrue(provider.modelOptions.contains(provider.defaultModel))
        }
    }

    @Test
    fun `anthropic model options include fable`() {
        assertTrue(AiProvider.Anthropic.modelOptions.contains("claude-fable-5"))
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
    fun `active notebook and fade disclosure preferences persist`() {
        val prefs = Prefs(RuntimeEnvironment.getApplication())

        assertEquals("", prefs.activeNotebookId)
        assertFalse(prefs.hasSeenFadeDisclosure)
        assertFalse(prefs.hasNormalizedBlankCustomPersona)

        prefs.activeNotebookId = "default"
        prefs.hasSeenFadeDisclosure = true
        prefs.hasNormalizedBlankCustomPersona = true

        val reloaded = Prefs(RuntimeEnvironment.getApplication())
        assertEquals("default", reloaded.activeNotebookId)
        assertTrue(reloaded.hasSeenFadeDisclosure)
        assertTrue(reloaded.hasNormalizedBlankCustomPersona)
    }
}
