package com.inkwell.diary.data

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

class Prefs(context: Context) {
    private val appContext = context.applicationContext
    private val plain: SharedPreferences =
        appContext.getSharedPreferences("inkwell_prefs", Context.MODE_PRIVATE)

    init {
        migrateHandwritingFontWeight()
        migrateDissolveDefaults()
    }

    private val secure: SharedPreferences by lazy {
        runCatching {
            val masterKey = MasterKey.Builder(appContext)
                .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
                .build()
            EncryptedSharedPreferences.create(
                appContext,
                "inkwell_secure",
                masterKey,
                EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
            )
        }.getOrElse {
            plain
        }
    }

    var onboardingComplete: Boolean
        get() = plain.getBoolean(KEY_ONBOARDING_COMPLETE, false)
        set(value) = plain.edit { putBoolean(KEY_ONBOARDING_COMPLETE, value) }

    var provider: AiProvider
        get() = AiProvider.fromName(plain.getString(KEY_PROVIDER, AiProvider.Anthropic.name))
        set(value) = plain.edit { putString(KEY_PROVIDER, value.name) }

    var apiKey: String
        get() = apiKey(provider)
        set(value) = setApiKey(provider, value)

    var model: String
        get() = model(provider)
        set(value) = setModel(provider, value)

    var reasoningEffort: ReasoningEffort
        get() = reasoningEffort(provider)
        set(value) = setReasoningEffort(provider, value)

    var persona: Persona
        get() = Persona.fromStoredName(plain.getString(KEY_PERSONA, Persona.default.name))
        set(value) = plain.edit { putString(KEY_PERSONA, value.name) }

    var activeNotebookId: String
        get() = plain.getString(KEY_ACTIVE_NOTEBOOK_ID, "").orEmpty()
        set(value) = plain.edit { putString(KEY_ACTIVE_NOTEBOOK_ID, value.trim()) }

    var hasSeenFadeDisclosure: Boolean
        get() = plain.getBoolean(KEY_SEEN_FADE_DISCLOSURE, false)
        set(value) = plain.edit { putBoolean(KEY_SEEN_FADE_DISCLOSURE, value) }

    var hasNormalizedBlankCustomPersona: Boolean
        get() = plain.getBoolean(KEY_NORMALIZED_BLANK_CUSTOM_PERSONA, false)
        set(value) = plain.edit { putBoolean(KEY_NORMALIZED_BLANK_CUSTOM_PERSONA, value) }

    var inkFadeStyle: InkFadeStyle
        get() = InkFadeStyle.fromStoredName(plain.getString(KEY_INK_FADE_STYLE, InkFadeStyle.default.name))
        set(value) = plain.edit { putString(KEY_INK_FADE_STYLE, value.name) }

    var replyStyle: ReplyStyle
        get() = ReplyStyle.fromStoredName(plain.getString(KEY_REPLY_STYLE, ReplyStyle.default.name))
        set(value) = plain.edit { putString(KEY_REPLY_STYLE, value.name) }

    var hasSeenDrawingModeHint: Boolean
        get() = plain.getBoolean(KEY_SEEN_DRAWING_MODE_HINT, false)
        set(value) = plain.edit { putBoolean(KEY_SEEN_DRAWING_MODE_HINT, value) }

    var customPrompt: String
        get() = plain.getString(KEY_CUSTOM_PROMPT, "").orEmpty()
        set(value) = plain.edit { putString(KEY_CUSTOM_PROMPT, value) }

    var recognitionLanguage: String
        get() = plain.getString(KEY_LANGUAGE, DEFAULT_LANGUAGE).orEmpty().ifBlank { DEFAULT_LANGUAGE }
        set(value) = plain.edit { putString(KEY_LANGUAGE, value.ifBlank { DEFAULT_LANGUAGE }) }

    var commitDelayMillis: Long
        get() = when (replyStyle) {
            ReplyStyle.Writing -> writingCommitDelayMillis
            ReplyStyle.Drawing -> drawingCommitDelayMillis
        }
        set(value) {
            when (replyStyle) {
                ReplyStyle.Writing -> writingCommitDelayMillis = value
                ReplyStyle.Drawing -> drawingCommitDelayMillis = value
            }
        }

    var writingCommitDelayMillis: Long
        get() = plain.getLong(KEY_COMMIT_DELAY, DEFAULT_COMMIT_DELAY_MILLIS)
            .coerceIn(MIN_COMMIT_DELAY_MILLIS, MAX_COMMIT_DELAY_MILLIS)
        set(value) = plain.edit {
            putLong(KEY_COMMIT_DELAY, value.coerceIn(MIN_COMMIT_DELAY_MILLIS, MAX_COMMIT_DELAY_MILLIS))
        }

    var drawingCommitDelayMillis: Long
        get() = plain.getLong(KEY_DRAWING_COMMIT_DELAY, DEFAULT_DRAWING_COMMIT_DELAY_MILLIS)
            .coerceIn(MIN_COMMIT_DELAY_MILLIS, MAX_COMMIT_DELAY_MILLIS)
        set(value) = plain.edit {
            putLong(KEY_DRAWING_COMMIT_DELAY, value.coerceIn(MIN_COMMIT_DELAY_MILLIS, MAX_COMMIT_DELAY_MILLIS))
        }

    var handwritingFontKey: String
        get() = plain.getString(KEY_HANDWRITING_FONT, DEFAULT_HANDWRITING_FONT).orEmpty()
            .ifBlank { DEFAULT_HANDWRITING_FONT }
        set(value) = plain.edit { putString(KEY_HANDWRITING_FONT, value.ifBlank { DEFAULT_HANDWRITING_FONT }) }

    var handwritingFontSizeSp: Float
        get() = plain.getFloat(KEY_HANDWRITING_FONT_SIZE_SP, DEFAULT_HANDWRITING_FONT_SIZE_SP)
            .coerceIn(MIN_HANDWRITING_FONT_SIZE_SP, MAX_HANDWRITING_FONT_SIZE_SP)
        set(value) = plain.edit {
            putFloat(
                KEY_HANDWRITING_FONT_SIZE_SP,
                value.coerceIn(MIN_HANDWRITING_FONT_SIZE_SP, MAX_HANDWRITING_FONT_SIZE_SP),
            )
        }

    var handwritingFontWeight: Int
        get() = normalizeHandwritingFontWeight(
            plain.getInt(KEY_HANDWRITING_FONT_WEIGHT, DEFAULT_HANDWRITING_FONT_WEIGHT),
        )
        set(value) = plain.edit { putInt(KEY_HANDWRITING_FONT_WEIGHT, normalizeHandwritingFontWeight(value)) }

    var autoReplyPaused: Boolean
        get() = plain.getBoolean(KEY_AUTO_REPLY_PAUSED, false)
        set(value) = plain.edit { putBoolean(KEY_AUTO_REPLY_PAUSED, value) }

    var showToolbarLogButton: Boolean
        get() = plain.getBoolean(KEY_SHOW_TOOLBAR_LOG_BUTTON, false)
        set(value) = plain.edit { putBoolean(KEY_SHOW_TOOLBAR_LOG_BUTTON, value) }

    var dissolveCellSizePx: Int
        get() = plain.getInt(KEY_DISSOLVE_CELL_SIZE_PX, DEFAULT_DISSOLVE_CELL_SIZE_PX)
            .coerceIn(MIN_DISSOLVE_CELL_SIZE_PX, MAX_DISSOLVE_CELL_SIZE_PX)
        set(value) = plain.edit {
            putInt(
                KEY_DISSOLVE_CELL_SIZE_PX,
                value.coerceIn(MIN_DISSOLVE_CELL_SIZE_PX, MAX_DISSOLVE_CELL_SIZE_PX),
            )
        }

    var dissolveSweepMs: Long
        get() = plain.getLong(KEY_DISSOLVE_SWEEP_MS, DEFAULT_DISSOLVE_SWEEP_MS)
            .coerceIn(MIN_DISSOLVE_SWEEP_MS, MAX_DISSOLVE_SWEEP_MS)
        set(value) = plain.edit {
            putLong(
                KEY_DISSOLVE_SWEEP_MS,
                value.coerceIn(MIN_DISSOLVE_SWEEP_MS, MAX_DISSOLVE_SWEEP_MS),
            )
        }

    var dissolveCellLifeMs: Long
        get() = plain.getLong(KEY_DISSOLVE_CELL_LIFE_MS, DEFAULT_DISSOLVE_CELL_LIFE_MS)
            .coerceIn(MIN_DISSOLVE_CELL_LIFE_MS, MAX_DISSOLVE_CELL_LIFE_MS)
        set(value) = plain.edit {
            putLong(
                KEY_DISSOLVE_CELL_LIFE_MS,
                value.coerceIn(MIN_DISSOLVE_CELL_LIFE_MS, MAX_DISSOLVE_CELL_LIFE_MS),
            )
        }

    var dissolveWindShearPx: Int
        get() = plain.getInt(KEY_DISSOLVE_WIND_SHEAR_PX, DEFAULT_DISSOLVE_WIND_SHEAR_PX)
            .coerceAtLeast(MIN_DISSOLVE_WIND_SHEAR_PX)
        set(value) = plain.edit {
            putInt(
                KEY_DISSOLVE_WIND_SHEAR_PX,
                value.coerceAtLeast(MIN_DISSOLVE_WIND_SHEAR_PX),
            )
        }

    var dissolveDriftMaxPx: Int
        get() = plain.getInt(KEY_DISSOLVE_DRIFT_MAX_PX, DEFAULT_DISSOLVE_DRIFT_MAX_PX)
            .coerceIn(MIN_DISSOLVE_DRIFT_MAX_PX, MAX_DISSOLVE_DRIFT_MAX_PX)
        set(value) = plain.edit {
            putInt(
                KEY_DISSOLVE_DRIFT_MAX_PX,
                value.coerceIn(MIN_DISSOLVE_DRIFT_MAX_PX, MAX_DISSOLVE_DRIFT_MAX_PX),
            )
        }

    fun resetDissolveConfig() {
        plain.edit {
            remove(KEY_DISSOLVE_CELL_SIZE_PX)
            remove(KEY_DISSOLVE_SWEEP_MS)
            remove(KEY_DISSOLVE_CELL_LIFE_MS)
            remove(KEY_DISSOLVE_WIND_SHEAR_PX)
            remove(KEY_DISSOLVE_DRIFT_MAX_PX)
        }
    }

    private fun migrateHandwritingFontWeight() {
        if (plain.contains(KEY_HANDWRITING_FONT_WEIGHT)) return
        if (!plain.getBoolean(KEY_HANDWRITING_FONT_BOLD, false)) return

        plain.edit(commit = true) {
            putInt(KEY_HANDWRITING_FONT_WEIGHT, HANDWRITING_FONT_WEIGHT_BOLD)
        }
    }

    private fun migrateDissolveDefaults() {
        if (plain.getInt(KEY_DISSOLVE_DEFAULTS_VERSION, 0) >= DISSOLVE_DEFAULTS_VERSION) return

        plain.edit(commit = true) {
            if (plain.getInt(KEY_DISSOLVE_CELL_SIZE_PX, OLD_DEFAULT_DISSOLVE_CELL_SIZE_PX) == OLD_DEFAULT_DISSOLVE_CELL_SIZE_PX) {
                remove(KEY_DISSOLVE_CELL_SIZE_PX)
            }
            if (plain.getLong(KEY_DISSOLVE_SWEEP_MS, OLD_DEFAULT_DISSOLVE_SWEEP_MS) == OLD_DEFAULT_DISSOLVE_SWEEP_MS) {
                remove(KEY_DISSOLVE_SWEEP_MS)
            }
            if (plain.getLong(KEY_DISSOLVE_CELL_LIFE_MS, OLD_DEFAULT_DISSOLVE_CELL_LIFE_MS) == OLD_DEFAULT_DISSOLVE_CELL_LIFE_MS) {
                remove(KEY_DISSOLVE_CELL_LIFE_MS)
            }
            if (plain.getInt(KEY_DISSOLVE_WIND_SHEAR_PX, OLD_DEFAULT_DISSOLVE_WIND_SHEAR_PX) == OLD_DEFAULT_DISSOLVE_WIND_SHEAR_PX) {
                remove(KEY_DISSOLVE_WIND_SHEAR_PX)
            }
            putInt(KEY_DISSOLVE_DEFAULTS_VERSION, DISSOLVE_DEFAULTS_VERSION)
        }
    }

    private fun normalizeHandwritingFontWeight(value: Int): Int {
        return when (value) {
            HANDWRITING_FONT_WEIGHT_REGULAR,
            HANDWRITING_FONT_WEIGHT_MEDIUM,
            HANDWRITING_FONT_WEIGHT_SEMIBOLD,
            HANDWRITING_FONT_WEIGHT_BOLD -> value
            else -> DEFAULT_HANDWRITING_FONT_WEIGHT
        }
    }

    fun apiKey(provider: AiProvider): String {
        return secure.getString(apiKeyKey(provider), "").orEmpty()
    }

    fun setApiKey(provider: AiProvider, value: String) {
        secure.edit { putString(apiKeyKey(provider), value.trim()) }
    }

    fun model(provider: AiProvider): String {
        return plain.getString(modelKey(provider), provider.defaultModel).orEmpty()
            .ifBlank { provider.defaultModel }
    }

    fun setModel(provider: AiProvider, value: String) {
        plain.edit { putString(modelKey(provider), value.trim().ifBlank { provider.defaultModel }) }
    }

    fun reasoningEffort(provider: AiProvider): ReasoningEffort {
        return ReasoningEffort.fromStoredName(
            plain.getString(reasoningEffortKey(provider), ReasoningEffort.Default.name),
            provider,
        )
    }

    fun setReasoningEffort(provider: AiProvider, value: ReasoningEffort) {
        val normalized = if (value in ReasoningEffort.choicesFor(provider)) value else ReasoningEffort.Default
        plain.edit { putString(reasoningEffortKey(provider), normalized.name) }
    }

    fun systemPrompt(): String = PersonaPrompts.forPersona(persona, customPrompt)

    private fun apiKeyKey(provider: AiProvider): String {
        return when (provider) {
            AiProvider.Anthropic -> KEY_API_KEY
            AiProvider.OpenAI -> KEY_OPENAI_API_KEY
            AiProvider.Groq -> KEY_GROQ_API_KEY
        }
    }

    private fun modelKey(provider: AiProvider): String {
        return when (provider) {
            AiProvider.Anthropic -> KEY_MODEL
            AiProvider.OpenAI -> KEY_OPENAI_MODEL
            AiProvider.Groq -> KEY_GROQ_MODEL
        }
    }

    private fun reasoningEffortKey(provider: AiProvider): String {
        return when (provider) {
            AiProvider.Anthropic -> KEY_REASONING_EFFORT
            AiProvider.OpenAI -> KEY_OPENAI_REASONING_EFFORT
            AiProvider.Groq -> KEY_GROQ_REASONING_EFFORT
        }
    }

    companion object {
        const val DEFAULT_MODEL = "claude-sonnet-4-6"
        const val DEFAULT_LANGUAGE = "en-US"
        const val DEFAULT_COMMIT_DELAY_MILLIS = 2000L
        const val DEFAULT_DRAWING_COMMIT_DELAY_MILLIS = 6000L
        const val MIN_COMMIT_DELAY_MILLIS = 1000L
        const val MAX_COMMIT_DELAY_MILLIS = 10000L
        const val DEFAULT_HANDWRITING_FONT = "dancing_script"
        const val MIN_HANDWRITING_FONT_SIZE_SP = 28f
        const val MAX_HANDWRITING_FONT_SIZE_SP = 70f
        const val DEFAULT_HANDWRITING_FONT_SIZE_SP = 40f
        const val HANDWRITING_FONT_WEIGHT_REGULAR = 400
        const val HANDWRITING_FONT_WEIGHT_MEDIUM = 500
        const val HANDWRITING_FONT_WEIGHT_SEMIBOLD = 600
        const val HANDWRITING_FONT_WEIGHT_BOLD = 700
        const val DEFAULT_HANDWRITING_FONT_WEIGHT = HANDWRITING_FONT_WEIGHT_REGULAR
        const val MIN_DISSOLVE_CELL_SIZE_PX = 4
        const val MAX_DISSOLVE_CELL_SIZE_PX = 24
        const val DEFAULT_DISSOLVE_CELL_SIZE_PX = 8
        const val MIN_DISSOLVE_SWEEP_MS = 500L
        const val MAX_DISSOLVE_SWEEP_MS = 3000L
        const val DEFAULT_DISSOLVE_SWEEP_MS = 1100L
        const val MIN_DISSOLVE_CELL_LIFE_MS = 450L
        const val MAX_DISSOLVE_CELL_LIFE_MS = 1800L
        const val DEFAULT_DISSOLVE_CELL_LIFE_MS = 700L
        const val MIN_DISSOLVE_WIND_SHEAR_PX = 0
        const val DEFAULT_DISSOLVE_WIND_SHEAR_PX = 300
        const val MIN_DISSOLVE_DRIFT_MAX_PX = 80
        const val MAX_DISSOLVE_DRIFT_MAX_PX = 320
        const val DEFAULT_DISSOLVE_DRIFT_MAX_PX = 200

        private const val DISSOLVE_DEFAULTS_VERSION = 1
        private const val OLD_DEFAULT_DISSOLVE_CELL_SIZE_PX = 4
        private const val OLD_DEFAULT_DISSOLVE_SWEEP_MS = 1500L
        private const val OLD_DEFAULT_DISSOLVE_CELL_LIFE_MS = 950L
        private const val OLD_DEFAULT_DISSOLVE_WIND_SHEAR_PX = 200

        private const val KEY_ONBOARDING_COMPLETE = "onboarding_complete"
        private const val KEY_PROVIDER = "provider"
        private const val KEY_API_KEY = "api_key"
        private const val KEY_OPENAI_API_KEY = "openai_api_key"
        private const val KEY_GROQ_API_KEY = "groq_api_key"
        private const val KEY_MODEL = "model"
        private const val KEY_OPENAI_MODEL = "openai_model"
        private const val KEY_GROQ_MODEL = "groq_model"
        private const val KEY_REASONING_EFFORT = "reasoning_effort"
        private const val KEY_OPENAI_REASONING_EFFORT = "openai_reasoning_effort"
        private const val KEY_GROQ_REASONING_EFFORT = "groq_reasoning_effort"
        private const val KEY_PERSONA = "persona"
        private const val KEY_ACTIVE_NOTEBOOK_ID = "active_notebook_id"
        private const val KEY_SEEN_FADE_DISCLOSURE = "seen_fade_disclosure"
        private const val KEY_SEEN_DRAWING_MODE_HINT = "seen_drawing_mode_hint"
        private const val KEY_NORMALIZED_BLANK_CUSTOM_PERSONA = "normalized_blank_custom_persona"
        private const val KEY_INK_FADE_STYLE = "ink_fade_style"
        private const val KEY_REPLY_STYLE = "reply_style"
        private const val KEY_CUSTOM_PROMPT = "custom_prompt"
        private const val KEY_LANGUAGE = "language"
        private const val KEY_COMMIT_DELAY = "commit_delay"
        private const val KEY_DRAWING_COMMIT_DELAY = "drawing_commit_delay"
        private const val KEY_HANDWRITING_FONT = "handwriting_font"
        private const val KEY_HANDWRITING_FONT_SIZE_SP = "handwriting_font_size_sp"
        private const val KEY_HANDWRITING_FONT_BOLD = "handwriting_font_bold"
        private const val KEY_HANDWRITING_FONT_WEIGHT = "handwriting_font_weight"
        private const val KEY_AUTO_REPLY_PAUSED = "auto_reply_paused"
        private const val KEY_SHOW_TOOLBAR_LOG_BUTTON = "show_toolbar_log_button"
        private const val KEY_DISSOLVE_CELL_SIZE_PX = "dissolve_cell_size_px"
        private const val KEY_DISSOLVE_SWEEP_MS = "dissolve_sweep_ms"
        private const val KEY_DISSOLVE_CELL_LIFE_MS = "dissolve_cell_life_ms"
        private const val KEY_DISSOLVE_WIND_SHEAR_PX = "dissolve_wind_shear_px"
        private const val KEY_DISSOLVE_DRIFT_MAX_PX = "dissolve_drift_max_px"
        private const val KEY_DISSOLVE_DEFAULTS_VERSION = "dissolve_defaults_version"
    }
}
