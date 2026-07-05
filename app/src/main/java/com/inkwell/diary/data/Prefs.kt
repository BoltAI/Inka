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

    var persona: Persona
        get() = Persona.fromStoredName(plain.getString(KEY_PERSONA, Persona.default.name))
        set(value) = plain.edit { putString(KEY_PERSONA, value.name) }

    var customPrompt: String
        get() = plain.getString(KEY_CUSTOM_PROMPT, "").orEmpty()
        set(value) = plain.edit { putString(KEY_CUSTOM_PROMPT, value) }

    var recognitionLanguage: String
        get() = plain.getString(KEY_LANGUAGE, DEFAULT_LANGUAGE).orEmpty().ifBlank { DEFAULT_LANGUAGE }
        set(value) = plain.edit { putString(KEY_LANGUAGE, value.ifBlank { DEFAULT_LANGUAGE }) }

    var commitDelayMillis: Long
        get() = plain.getLong(KEY_COMMIT_DELAY, DEFAULT_COMMIT_DELAY_MILLIS)
        set(value) = plain.edit { putLong(KEY_COMMIT_DELAY, value.coerceIn(1000L, 4000L)) }

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

    var handwritingFontBold: Boolean
        get() = plain.getBoolean(KEY_HANDWRITING_FONT_BOLD, DEFAULT_HANDWRITING_FONT_BOLD)
        set(value) = plain.edit { putBoolean(KEY_HANDWRITING_FONT_BOLD, value) }

    var autoReplyPaused: Boolean
        get() = plain.getBoolean(KEY_AUTO_REPLY_PAUSED, false)
        set(value) = plain.edit { putBoolean(KEY_AUTO_REPLY_PAUSED, value) }

    var showToolbarLogButton: Boolean
        get() = plain.getBoolean(KEY_SHOW_TOOLBAR_LOG_BUTTON, false)
        set(value) = plain.edit { putBoolean(KEY_SHOW_TOOLBAR_LOG_BUTTON, value) }

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

    fun systemPrompt(): String = PersonaPrompts.forPersona(persona, customPrompt)

    fun diaryModeFor(persona: Persona): DiaryMode {
        val fallback = DiaryMode.defaultFor(persona)
        return DiaryMode.fromStoredName(plain.getString(diaryModeKey(persona), null), fallback)
    }

    fun setDiaryMode(persona: Persona, mode: DiaryMode) {
        plain.edit { putString(diaryModeKey(persona), mode.name) }
    }

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

    private fun diaryModeKey(persona: Persona): String = "${KEY_DIARY_MODE_PREFIX}${persona.name}"

    companion object {
        const val DEFAULT_MODEL = "claude-sonnet-4-6"
        const val DEFAULT_LANGUAGE = "en-US"
        const val DEFAULT_COMMIT_DELAY_MILLIS = 2000L
        const val DEFAULT_HANDWRITING_FONT = "ms_madi"
        const val MIN_HANDWRITING_FONT_SIZE_SP = 28f
        const val MAX_HANDWRITING_FONT_SIZE_SP = 56f
        const val DEFAULT_HANDWRITING_FONT_SIZE_SP = 40f
        const val DEFAULT_HANDWRITING_FONT_BOLD = false

        private const val KEY_ONBOARDING_COMPLETE = "onboarding_complete"
        private const val KEY_PROVIDER = "provider"
        private const val KEY_API_KEY = "api_key"
        private const val KEY_OPENAI_API_KEY = "openai_api_key"
        private const val KEY_GROQ_API_KEY = "groq_api_key"
        private const val KEY_MODEL = "model"
        private const val KEY_OPENAI_MODEL = "openai_model"
        private const val KEY_GROQ_MODEL = "groq_model"
        private const val KEY_PERSONA = "persona"
        private const val KEY_CUSTOM_PROMPT = "custom_prompt"
        private const val KEY_DIARY_MODE_PREFIX = "diary_mode_"
        private const val KEY_LANGUAGE = "language"
        private const val KEY_COMMIT_DELAY = "commit_delay"
        private const val KEY_HANDWRITING_FONT = "handwriting_font"
        private const val KEY_HANDWRITING_FONT_SIZE_SP = "handwriting_font_size_sp"
        private const val KEY_HANDWRITING_FONT_BOLD = "handwriting_font_bold"
        private const val KEY_AUTO_REPLY_PAUSED = "auto_reply_paused"
        private const val KEY_SHOW_TOOLBAR_LOG_BUTTON = "show_toolbar_log_button"
    }
}
