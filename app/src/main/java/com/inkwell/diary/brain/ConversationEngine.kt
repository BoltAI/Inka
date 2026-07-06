package com.inkwell.diary.brain

import com.inkwell.diary.data.AiProvider
import com.inkwell.diary.data.Persona
import com.inkwell.diary.data.PersonaPrompts
import com.inkwell.diary.data.Prefs
import com.inkwell.diary.data.ReasoningEffort
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

data class ConversationSettings(
    val apiKey: String,
    val model: String,
    val systemPrompt: String,
    val provider: AiProvider = AiProvider.Anthropic,
    val reasoningEffort: ReasoningEffort = ReasoningEffort.Default,
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
    private val json = Json {
        ignoreUnknownKeys = true
        explicitNulls = false
    }

    fun historySnapshot(): List<AnthropicMessage> = history.toList()

    fun clearHistory() {
        history.clear()
    }

    fun replaceHistory(messages: List<AnthropicMessage>) {
        history.clear()
        history.addAll(messages.takeLast(maxTurns * 2))
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
            provider = settings.provider,
            reasoningEffort = settings.reasoningEffort,
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
            provider = settings.provider,
            reasoningEffort = settings.reasoningEffort,
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

    suspend fun requestDrawing(
        settings: ConversationSettings,
        snapshot: PageSnapshot,
        latestUserText: String = "",
    ): DrawingReplyResult {
        if (settings.apiKey.isBlank()) {
            return DrawingReplyResult.Failure(BrainErrorKind.InvalidKey)
        }
        if (!settings.provider.supportsDrawing()) {
            return DrawingReplyResult.Failure(
                BrainErrorKind.BadRequest,
                "Drawing mode currently supports Anthropic and OpenAI.",
            )
        }
        val transport = transports[settings.provider]
            ?: return DrawingReplyResult.Failure(BrainErrorKind.BadRequest, "No transport for ${settings.provider.label}")
        val body = buildProviderDrawingRequestBody(settings, history, snapshot, latestUserText)
        return when (val result = transport.draw(settings.apiKey, body)) {
            is AnthropicToolResult.Success -> {
                val payload = runCatching {
                    json.decodeFromJsonElement(DrawToolPayload.serializer(), result.input)
                }.getOrElse {
                    return DrawingReplyResult.Failure(BrainErrorKind.BadRequest, "Invalid draw tool payload")
                }
                val paths = payload.paths.take(DRAW_TOOL_MAX_PATHS).map { it.d }.filter { it.isNotBlank() }
                if (paths.isEmpty()) {
                    return DrawingReplyResult.Failure(BrainErrorKind.BadRequest, "Draw tool returned no paths")
                }
                DrawingReplyResult.Success(
                    paths = paths,
                    pageTextTranscript = payload.pageTextTranscript.trim(),
                )
            }
            is AnthropicToolResult.Failure -> DrawingReplyResult.Failure(result.kind, result.detail)
        }
    }

    suspend fun streamDrawing(
        settings: ConversationSettings,
        snapshot: PageSnapshot,
        latestUserText: String = "",
        onPath: suspend (String) -> Unit,
        onToolJsonDelta: suspend (String) -> Unit = {},
    ): DrawingReplyResult {
        if (settings.apiKey.isBlank()) {
            return DrawingReplyResult.Failure(BrainErrorKind.InvalidKey)
        }
        if (!settings.provider.supportsDrawing()) {
            return DrawingReplyResult.Failure(
                BrainErrorKind.BadRequest,
                "Drawing mode currently supports Anthropic and OpenAI.",
            )
        }
        val transport = transports[settings.provider]
            ?: return DrawingReplyResult.Failure(BrainErrorKind.BadRequest, "No transport for ${settings.provider.label}")
        val body = buildProviderDrawingRequestBody(settings, history, snapshot, latestUserText)
        val parser = StreamingDrawToolParser()
        var emittedPathCount = 0
        return when (
            val result = transport.streamDraw(settings.apiKey, body) { partialJson ->
                onToolJsonDelta(partialJson)
                parser.append(partialJson).forEach { path ->
                    if (emittedPathCount < DRAW_TOOL_MAX_PATHS) {
                        emittedPathCount += 1
                        onPath(path)
                    }
                }
            }
        ) {
            is AnthropicToolStreamResult.Success -> {
                val payload = runCatching {
                    json.decodeFromJsonElement(DrawToolPayload.serializer(), result.input)
                }.getOrElse {
                    return DrawingReplyResult.Failure(BrainErrorKind.BadRequest, "Invalid streamed draw tool payload")
                }
                val paths = payload.paths.take(DRAW_TOOL_MAX_PATHS).map { it.d }.filter { it.isNotBlank() }
                if (paths.isEmpty()) {
                    return DrawingReplyResult.Failure(BrainErrorKind.BadRequest, "Streamed draw tool returned no paths")
                }
                DrawingReplyResult.Success(
                    paths = paths,
                    pageTextTranscript = payload.pageTextTranscript.trim(),
                )
            }
            is AnthropicToolStreamResult.Failure -> DrawingReplyResult.Failure(result.kind, result.detail)
        }
    }

    private fun capHistory() {
        val maxMessages = maxTurns * 2
        while (history.size > maxMessages) {
            history.removeAt(0)
        }
    }

    companion object {
        private fun AiProvider.supportsDrawing(): Boolean {
            return this == AiProvider.Anthropic || this == AiProvider.OpenAI
        }

        private fun buildProviderDrawingRequestBody(
            settings: ConversationSettings,
            history: List<AnthropicMessage>,
            snapshot: PageSnapshot,
            latestUserText: String,
        ) = when (settings.provider) {
            AiProvider.Anthropic -> buildDrawingRequestBody(
                model = settings.model,
                systemPrompt = settings.systemPrompt,
                history = history,
                snapshot = snapshot,
                latestUserText = latestUserText,
                forceTool = true,
                defaultModel = settings.provider.defaultModel,
                reasoningEffort = settings.reasoningEffort,
            )
            AiProvider.OpenAI -> buildOpenAiDrawingRequestBody(
                model = settings.model,
                systemPrompt = settings.systemPrompt,
                history = history,
                snapshot = snapshot,
                latestUserText = latestUserText,
                defaultModel = settings.provider.defaultModel,
                reasoningEffort = settings.reasoningEffort,
            )
            AiProvider.Groq -> buildJsonObject {}
        }

        fun buildRequestBody(
            model: String,
            systemPrompt: String,
            history: List<AnthropicMessage>,
            userText: String,
            maxTokens: Int,
            defaultModel: String = Prefs.DEFAULT_MODEL,
            provider: AiProvider = AiProvider.Anthropic,
            reasoningEffort: ReasoningEffort = ReasoningEffort.Default,
        ): AnthropicRequestBody {
            val effort = reasoningEffort.apiValue
            return AnthropicRequestBody(
                model = model.ifBlank { defaultModel },
                maxTokens = maxTokens,
                system = systemPrompt,
                messages = history + AnthropicMessage("user", userText),
                outputConfig = if (provider == AiProvider.Anthropic && effort != null) {
                    AnthropicOutputConfig(effort)
                } else {
                    null
                },
                openAiReasoningEffort = if (provider != AiProvider.Anthropic) effort else null,
            )
        }

        fun buildDrawingRequestBody(
            model: String,
            systemPrompt: String,
            history: List<AnthropicMessage>,
            snapshot: PageSnapshot,
            latestUserText: String = "",
            forceTool: Boolean,
            defaultModel: String = Prefs.DEFAULT_MODEL,
            reasoningEffort: ReasoningEffort = ReasoningEffort.Default,
        ) = buildJsonObject {
            put("model", model.ifBlank { defaultModel })
            put("max_tokens", DRAWING_MAX_TOKENS)
            put("system", listOf(systemPrompt, DRAWING_SYSTEM_PROMPT).filter { it.isNotBlank() }.joinToString("\n\n"))
            reasoningEffort.apiValue?.let { effort ->
                putJsonObject("output_config") {
                    put("effort", effort)
                }
            }
            putJsonArray("messages") {
                history.forEach { message ->
                    add(
                        buildJsonObject {
                            put("role", message.role)
                            put("content", message.content)
                        },
                    )
                }
                add(
                    buildJsonObject {
                        put("role", "user")
                        putJsonArray("content") {
                            add(
                                buildJsonObject {
                                    put("type", "image")
                                    putJsonObject("source") {
                                        put("type", "base64")
                                        put("media_type", "image/png")
                                        put("data", snapshot.pngBase64)
                                    }
                                },
                            )
                            add(
                                buildJsonObject {
                                    put("type", "text")
                                    put("text", drawingUserTurnText(snapshot, latestUserText))
                                },
                            )
                        }
                    },
                )
            }
            putJsonArray("tools") {
                add(anthropicDrawToolDefinition())
            }
            if (forceTool) {
                putJsonObject("tool_choice") {
                    put("type", "tool")
                    put("name", DRAW_TOOL_NAME)
                }
            }
        }

        fun buildOpenAiDrawingRequestBody(
            model: String,
            systemPrompt: String,
            history: List<AnthropicMessage>,
            snapshot: PageSnapshot,
            latestUserText: String = "",
            defaultModel: String = AiProvider.OpenAI.defaultModel,
            reasoningEffort: ReasoningEffort = ReasoningEffort.Default,
        ) = buildJsonObject {
            put("model", model.ifBlank { defaultModel })
            put("max_completion_tokens", DRAWING_MAX_TOKENS)
            reasoningEffort.apiValue?.let { put("reasoning_effort", it) }
            putJsonArray("messages") {
                val combinedSystem = listOf(systemPrompt, DRAWING_SYSTEM_PROMPT)
                    .filter { it.isNotBlank() }
                    .joinToString("\n\n")
                if (combinedSystem.isNotBlank()) {
                    add(
                        buildJsonObject {
                            put("role", "system")
                            put("content", combinedSystem)
                        },
                    )
                }
                history.forEach { message ->
                    add(
                        buildJsonObject {
                            put("role", message.role)
                            put("content", message.content)
                        },
                    )
                }
                add(
                    buildJsonObject {
                        put("role", "user")
                        putJsonArray("content") {
                            add(
                                buildJsonObject {
                                    put("type", "text")
                                    put("text", drawingUserTurnText(snapshot, latestUserText))
                                },
                            )
                            add(
                                buildJsonObject {
                                    put("type", "image_url")
                                    putJsonObject("image_url") {
                                        put("url", "data:image/png;base64,${snapshot.pngBase64}")
                                        put("detail", "high")
                                    }
                                },
                            )
                        }
                    },
                )
            }
            putJsonArray("tools") {
                add(openAiDrawToolDefinition())
            }
            putJsonObject("tool_choice") {
                put("type", "function")
                putJsonObject("function") {
                    put("name", DRAW_TOOL_NAME)
                }
            }
        }

        private fun drawingUserTurnText(snapshot: PageSnapshot, latestUserText: String): String {
            val base = DRAWING_USER_TURN_TEMPLATE.format(snapshot.width, snapshot.height)
            val recognized = latestUserText.trim()
            if (recognized.isBlank()) {
                return "$base\n\nLatest recognized text from the writer's new strokes: (unavailable). Use the image to infer the current turn, and treat older visible writing as background context unless it is clearly part of the new marks."
            }
            return "$base\n\nLatest recognized text from the writer's new strokes:\n$recognized\n\nTreat this recognized text as the current user turn. Use the image for visual context, style, placement, and non-text marks. Older visible writing is background context unless it also appears in this latest transcript."
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

        private fun anthropicDrawToolDefinition() = buildJsonObject {
            put("name", DRAW_TOOL_NAME)
            put("description", DRAW_TOOL_DESCRIPTION)
            put("eager_input_streaming", true)
            put("input_schema", drawToolInputSchema())
        }

        private fun openAiDrawToolDefinition() = buildJsonObject {
            put("type", "function")
            putJsonObject("function") {
                put("name", DRAW_TOOL_NAME)
                put("description", DRAW_TOOL_DESCRIPTION)
                put("parameters", drawToolInputSchema())
            }
        }

        private fun drawToolInputSchema() = buildJsonObject {
            put("type", "object")
            putJsonObject("properties") {
                putJsonObject("paths") {
                    put("type", "array")
                    put("maxItems", DRAW_TOOL_MAX_PATHS)
                    putJsonObject("items") {
                        put("type", "object")
                        putJsonObject("properties") {
                            putJsonObject("d") {
                                put("type", "string")
                                put(
                                    "description",
                                    "SVG path data. Allowed commands ONLY: M L C Q Z and their relative forms. No A (arcs), no H/V, no S/T, no transforms, no fills.",
                                )
                            }
                            putJsonObject("role") {
                                put("type", "string")
                                put("description", "Use reply for paths that answer the writer.")
                            }
                        }
                        putJsonArray("required") {
                            add("d")
                        }
                    }
                }
                putJsonObject("page_text_transcript") {
                    put("type", "string")
                    put("description", "Transcribe any handwritten words visible in the user's latest additions, verbatim. Empty string if none.")
                }
            }
            putJsonArray("required") {
                add("paths")
                add("page_text_transcript")
            }
        }

        private const val DRAW_TOOL_NAME = "draw"
        private const val DRAW_TOOL_DESCRIPTION = "Draw your reply onto the shared page as line art. Do not write words, captions, labels, letters, or text. Emit paths first, one complete path object at a time; put page_text_transcript last."
        private const val DRAW_TOOL_MAX_PATHS = 60
        private const val DRAWING_MAX_TOKENS = 2000
    }
}
