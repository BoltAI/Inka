package com.inkwell.diary.brain

import com.inkwell.diary.data.AiProvider
import com.inkwell.diary.data.Persona
import com.inkwell.diary.data.PersonaPrompts
import com.inkwell.diary.data.Prefs

data class ConversationSettings(
    val apiKey: String,
    val model: String,
    val systemPrompt: String,
    val provider: AiProvider = AiProvider.Anthropic,
)

class ConversationEngine(
    private val transports: Map<AiProvider, AnthropicTransport> = defaultTransports(),
    private val maxTurns: Int = 20,
) {
    constructor(
        transport: AnthropicTransport,
        maxTurns: Int = 20,
    ) : this(mapOf(AiProvider.Anthropic to transport), maxTurns)

    private val history = mutableListOf<AnthropicMessage>()

    fun historySnapshot(): List<AnthropicMessage> = history.toList()

    fun clearHistory() {
        history.clear()
    }

    suspend fun sendMessage(settings: ConversationSettings, userText: String): AnthropicResult {
        if (settings.apiKey.isBlank()) {
            return AnthropicResult.Failure(BrainErrorKind.InvalidKey)
        }
        val requestBody = buildRequestBody(
            model = settings.model,
            systemPrompt = settings.systemPrompt,
            history = history,
            userText = userText,
            maxTokens = 300,
            defaultModel = settings.provider.defaultModel,
        )
        val transport = transports[settings.provider]
            ?: return AnthropicResult.Failure(BrainErrorKind.BadRequest, "No transport for ${settings.provider.label}")
        return when (val result = transport.complete(settings.apiKey, requestBody)) {
            is AnthropicResult.Success -> {
                history.add(AnthropicMessage("user", userText))
                history.add(AnthropicMessage("assistant", result.text))
                capHistory()
                result
            }
            is AnthropicResult.Failure -> result
        }
    }

    suspend fun streamMessage(
        settings: ConversationSettings,
        userText: String,
        onTextDelta: suspend (String) -> Unit,
    ): AnthropicResult {
        if (settings.apiKey.isBlank()) {
            return AnthropicResult.Failure(BrainErrorKind.InvalidKey)
        }
        val requestBody = buildRequestBody(
            model = settings.model,
            systemPrompt = settings.systemPrompt,
            history = history,
            userText = userText,
            maxTokens = 300,
            defaultModel = settings.provider.defaultModel,
        )
        val transport = transports[settings.provider]
            ?: return AnthropicResult.Failure(BrainErrorKind.BadRequest, "No transport for ${settings.provider.label}")
        return when (val result = transport.stream(settings.apiKey, requestBody, onTextDelta)) {
            is AnthropicResult.Success -> {
                history.add(AnthropicMessage("user", userText))
                history.add(AnthropicMessage("assistant", result.text))
                capHistory()
                result
            }
            is AnthropicResult.Failure -> result
        }
    }

    suspend fun validateKey(apiKey: String, model: String): AnthropicResult {
        return validateKey(AiProvider.Anthropic, apiKey, model)
    }

    suspend fun validateKey(provider: AiProvider, apiKey: String, model: String): AnthropicResult {
        if (apiKey.isBlank()) {
            return AnthropicResult.Failure(BrainErrorKind.InvalidKey)
        }
        val body = buildRequestBody(
            model = model.ifBlank { provider.defaultModel },
            systemPrompt = PersonaPrompts.forPersona(Persona.default, ""),
            history = emptyList(),
            userText = "Reply with OK.",
            maxTokens = 1,
            defaultModel = provider.defaultModel,
        )
        val transport = transports[provider]
            ?: return AnthropicResult.Failure(BrainErrorKind.BadRequest, "No transport for ${provider.label}")
        return transport.complete(apiKey, body)
    }

    private fun capHistory() {
        val maxMessages = maxTurns * 2
        while (history.size > maxMessages) {
            history.removeAt(0)
        }
    }

    companion object {
        fun buildRequestBody(
            model: String,
            systemPrompt: String,
            history: List<AnthropicMessage>,
            userText: String,
            maxTokens: Int,
            defaultModel: String = Prefs.DEFAULT_MODEL,
        ): AnthropicRequestBody {
            return AnthropicRequestBody(
                model = model.ifBlank { defaultModel },
                maxTokens = maxTokens,
                system = systemPrompt,
                messages = history + AnthropicMessage("user", userText),
            )
        }

        fun defaultTransports(): Map<AiProvider, AnthropicTransport> {
            return mapOf(
                AiProvider.Anthropic to OkHttpAnthropicTransport(),
                AiProvider.OpenAI to OkHttpOpenAiCompatibleTransport(
                    baseUrl = "https://api.openai.com/v1/chat/completions",
                ),
                AiProvider.Groq to OkHttpOpenAiCompatibleTransport(
                    baseUrl = "https://api.groq.com/openai/v1/chat/completions",
                ),
            )
        }
    }
}
