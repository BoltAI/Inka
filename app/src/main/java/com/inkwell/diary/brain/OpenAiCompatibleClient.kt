package com.inkwell.diary.brain

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import java.io.IOException
import java.io.InterruptedIOException
import java.util.concurrent.TimeUnit

class OkHttpOpenAiCompatibleTransport(
    private val baseUrl: String,
    private val modelsUrl: String = defaultModelsUrl(baseUrl),
    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .build(),
    private val json: Json = Json {
        ignoreUnknownKeys = true
        explicitNulls = false
    },
) : AnthropicTransport {
    override suspend fun complete(
        apiKey: String,
        requestBody: AnthropicRequestBody,
    ): AnthropicResult = withContext(Dispatchers.IO) {
        var lastFailure: AnthropicResult.Failure? = null
        repeat(2) { attempt ->
            when (val result = executeOnce(apiKey, requestBody.copy(stream = false))) {
                is AnthropicResult.Success -> return@withContext result
                is AnthropicResult.Failure -> {
                    lastFailure = result
                    val retryable = result.kind == BrainErrorKind.Network || result.kind == BrainErrorKind.Server
                    if (!retryable || attempt == 1) {
                        return@withContext result
                    }
                }
            }
        }
        lastFailure ?: AnthropicResult.Failure(BrainErrorKind.Unknown)
    }

    override suspend fun validateKey(
        apiKey: String,
        requestBody: AnthropicRequestBody,
    ): AnthropicResult = withContext(Dispatchers.IO) {
        val request = Request.Builder()
            .url(modelsUrl)
            .header("Authorization", "Bearer $apiKey")
            .get()
            .build()
        try {
            client.newCall(request).execute().use { response ->
                val responseText = response.body?.string().orEmpty()
                if (response.isSuccessful) {
                    AnthropicResult.Success("OK")
                } else {
                    response.toFailure(responseText)
                }
            }
        } catch (e: InterruptedIOException) {
            AnthropicResult.Failure(BrainErrorKind.Network, e::class.java.simpleName)
        } catch (e: IOException) {
            AnthropicResult.Failure(BrainErrorKind.Network, e::class.java.simpleName)
        } catch (e: Exception) {
            AnthropicResult.Failure(BrainErrorKind.Unknown, e::class.java.simpleName)
        }
    }

    override suspend fun stream(
        apiKey: String,
        requestBody: AnthropicRequestBody,
        onTextDelta: suspend (String) -> Unit,
    ): AnthropicResult = withContext(Dispatchers.IO) {
        executeStreamOnce(apiKey, requestBody.copy(stream = true), onTextDelta)
    }

    override suspend fun draw(
        apiKey: String,
        requestBody: JsonObject,
    ): AnthropicToolResult = withContext(Dispatchers.IO) {
        executeToolOnce(apiKey, requestBody)
    }

    override suspend fun streamDraw(
        apiKey: String,
        requestBody: JsonObject,
        onToolJsonDelta: suspend (String) -> Unit,
    ): AnthropicToolStreamResult = withContext(Dispatchers.IO) {
        executeToolStreamOnce(apiKey, requestBody.withStream(), onToolJsonDelta)
    }

    private fun executeOnce(apiKey: String, requestBody: AnthropicRequestBody): AnthropicResult {
        val request = buildRequest(apiKey, requestBody)
        return try {
            client.newCall(request).execute().use { response ->
                val responseText = response.body?.string().orEmpty()
                when {
                    response.isSuccessful -> {
                        val decoded = json.decodeFromString(
                            ChatCompletionResponse.serializer(),
                            responseText,
                        )
                        val text = decoded.choices.firstOrNull()?.message?.content.orEmpty()
                        AnthropicResult.Success(text.ifBlank { BLANK_REPLY })
                    }
                    else -> response.toFailure(responseText)
                }
            }
        } catch (e: InterruptedIOException) {
            AnthropicResult.Failure(BrainErrorKind.Network, e::class.java.simpleName)
        } catch (e: IOException) {
            AnthropicResult.Failure(BrainErrorKind.Network, e::class.java.simpleName)
        } catch (e: Exception) {
            AnthropicResult.Failure(BrainErrorKind.Unknown, e::class.java.simpleName)
        }
    }

    private suspend fun executeStreamOnce(
        apiKey: String,
        requestBody: AnthropicRequestBody,
        onTextDelta: suspend (String) -> Unit,
    ): AnthropicResult {
        val request = buildRequest(apiKey, requestBody)
        return try {
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    return@use response.toFailure(response.body?.string().orEmpty())
                }

                val source = response.body?.source()
                    ?: return@use AnthropicResult.Success(BLANK_REPLY)
                val reply = StringBuilder()
                val data = StringBuilder()

                while (true) {
                    val line = source.readUtf8Line() ?: break
                    if (line.isEmpty()) {
                        val failure = consumeStreamData(data.toString(), reply, onTextDelta)
                        data.setLength(0)
                        if (failure != null) return@use failure
                    } else if (line.startsWith("data:")) {
                        if (data.isNotEmpty()) data.append('\n')
                        data.append(line.removePrefix("data:").trimStart())
                    }
                }

                val failure = consumeStreamData(data.toString(), reply, onTextDelta)
                if (failure != null) {
                    failure
                } else {
                    AnthropicResult.Success(reply.toString().ifBlank { BLANK_REPLY })
                }
            }
        } catch (e: InterruptedIOException) {
            AnthropicResult.Failure(BrainErrorKind.Network, e::class.java.simpleName)
        } catch (e: IOException) {
            AnthropicResult.Failure(BrainErrorKind.Network, e::class.java.simpleName)
        } catch (e: Exception) {
            AnthropicResult.Failure(BrainErrorKind.Unknown, e::class.java.simpleName)
        }
    }

    private fun executeToolOnce(apiKey: String, requestBody: JsonObject): AnthropicToolResult {
        val request = buildRawRequest(apiKey, requestBody)
        return try {
            client.newCall(request).execute().use { response ->
                val responseText = response.body?.string().orEmpty()
                when {
                    response.isSuccessful -> {
                        val decoded = json.decodeFromString(
                            ChatCompletionResponse.serializer(),
                            responseText,
                        )
                        val toolCall = decoded.choices
                            .firstOrNull()
                            ?.message
                            ?.toolCalls
                            ?.preferredDrawToolCall()
                            ?: return@use AnthropicToolResult.Failure(BrainErrorKind.BadRequest, "Expected draw tool, got no tool call")
                        val input = toolCall.function?.arguments?.parseToolInput()
                            ?: return@use AnthropicToolResult.Failure(BrainErrorKind.BadRequest, "Invalid draw tool arguments")
                        AnthropicToolResult.Success(input)
                    }
                    else -> response.toFailure(responseText).toToolFailure()
                }
            }
        } catch (e: InterruptedIOException) {
            AnthropicToolResult.Failure(BrainErrorKind.Network, e::class.java.simpleName)
        } catch (e: IOException) {
            AnthropicToolResult.Failure(BrainErrorKind.Network, e::class.java.simpleName)
        } catch (e: Exception) {
            AnthropicToolResult.Failure(BrainErrorKind.Unknown, e::class.java.simpleName)
        }
    }

    private suspend fun executeToolStreamOnce(
        apiKey: String,
        requestBody: JsonObject,
        onToolJsonDelta: suspend (String) -> Unit,
    ): AnthropicToolStreamResult {
        val request = buildRawRequest(apiKey, requestBody)
        return try {
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    return@use response.toFailure(response.body?.string().orEmpty()).toToolStreamFailure()
                }

                val source = response.body?.source()
                    ?: return@use AnthropicToolStreamResult.Failure(BrainErrorKind.BadRequest, "Empty tool stream")
                val toolArguments = linkedMapOf<Int, StringBuilder>()
                val toolNames = mutableMapOf<Int, String>()
                val data = StringBuilder()

                while (true) {
                    val line = source.readUtf8Line() ?: break
                    if (line.isEmpty()) {
                        val failure = consumeToolStreamData(data.toString(), toolArguments, toolNames, onToolJsonDelta)
                        data.setLength(0)
                        if (failure != null) return@use failure
                    } else if (line.startsWith("data:")) {
                        if (data.isNotEmpty()) data.append('\n')
                        data.append(line.removePrefix("data:").trimStart())
                    }
                }

                val failure = consumeToolStreamData(data.toString(), toolArguments, toolNames, onToolJsonDelta)
                if (failure != null) {
                    failure
                } else {
                    val drawIndex = toolNames.entries.firstOrNull { it.value == DRAW_TOOL_NAME }?.key
                        ?: toolArguments.keys.firstOrNull()
                    val input = drawIndex
                        ?.let { toolArguments[it]?.toString() }
                        ?.parseToolInput()
                    if (input == null) {
                        AnthropicToolStreamResult.Failure(BrainErrorKind.BadRequest, "Invalid streamed draw tool arguments")
                    } else {
                        AnthropicToolStreamResult.Success(input)
                    }
                }
            }
        } catch (e: InterruptedIOException) {
            AnthropicToolStreamResult.Failure(BrainErrorKind.Network, e::class.java.simpleName)
        } catch (e: IOException) {
            AnthropicToolStreamResult.Failure(BrainErrorKind.Network, e::class.java.simpleName)
        } catch (e: Exception) {
            AnthropicToolStreamResult.Failure(BrainErrorKind.Unknown, e::class.java.simpleName)
        }
    }

    private fun buildRequest(apiKey: String, requestBody: AnthropicRequestBody): Request {
        val body = json.encodeToString(
            ChatCompletionRequest.serializer(),
            requestBody.toChatCompletionRequest(),
        ).toRequestBody(JSON_MEDIA_TYPE)

        return Request.Builder()
            .url(baseUrl)
            .header("Authorization", "Bearer $apiKey")
            .header("content-type", "application/json")
            .post(body)
            .build()
    }

    private fun buildRawRequest(apiKey: String, requestBody: JsonObject): Request {
        val body = json.encodeToString(JsonObject.serializer(), requestBody)
            .toRequestBody(JSON_MEDIA_TYPE)

        return Request.Builder()
            .url(baseUrl)
            .header("Authorization", "Bearer $apiKey")
            .header("content-type", "application/json")
            .post(body)
            .build()
    }

    private suspend fun consumeStreamData(
        data: String,
        reply: StringBuilder,
        onTextDelta: suspend (String) -> Unit,
    ): AnthropicResult.Failure? {
        val clean = data.trim()
        if (clean.isBlank() || clean == "[DONE]") return null
        val payload = runCatching { json.parseToJsonElement(clean) as? JsonObject }.getOrNull() ?: return null
        payload["error"]?.let {
            return payload.toChatFailure()
        }

        val delta = runCatching {
            json.decodeFromString(ChatCompletionChunk.serializer(), clean)
        }.getOrNull()?.choices?.firstOrNull()?.delta?.content.orEmpty()

        if (delta.isNotEmpty()) {
            reply.append(delta)
            onTextDelta(delta)
        }
        return null
    }

    private suspend fun consumeToolStreamData(
        data: String,
        toolArguments: MutableMap<Int, StringBuilder>,
        toolNames: MutableMap<Int, String>,
        onToolJsonDelta: suspend (String) -> Unit,
    ): AnthropicToolStreamResult.Failure? {
        val clean = data.trim()
        if (clean.isBlank() || clean == "[DONE]") return null
        val payload = runCatching { json.parseToJsonElement(clean) as? JsonObject }.getOrNull() ?: return null
        payload["error"]?.let {
            return payload.toChatFailure().toToolStreamFailure()
        }

        val chunk = runCatching {
            json.decodeFromString(ChatCompletionChunk.serializer(), clean)
        }.getOrNull() ?: return null

        chunk.choices.forEach { choice ->
            choice.delta.toolCalls.forEach { toolCall ->
                val index = toolCall.index
                val function = toolCall.function
                val name = function?.name.orEmpty()
                if (name.isNotBlank()) {
                    toolNames[index] = name
                }
                val arguments = function?.arguments.orEmpty()
                if (arguments.isNotEmpty()) {
                    toolArguments.getOrPut(index) { StringBuilder() }.append(arguments)
                    onToolJsonDelta(arguments)
                }
            }
        }
        return null
    }

    private fun AnthropicRequestBody.toChatCompletionRequest(): ChatCompletionRequest {
        val chatMessages = buildList {
            if (system.isNotBlank()) {
                add(ChatMessage(role = "system", content = system))
            }
            messages.forEach { message ->
                add(ChatMessage(role = message.role, content = message.content))
            }
        }
        return ChatCompletionRequest(
            model = model,
            messages = chatMessages,
            maxCompletionTokens = maxTokens,
            reasoningEffort = openAiReasoningEffort,
            stream = stream,
        )
    }

    private fun JsonObject.toChatFailure(): AnthropicResult.Failure {
        val error = this["error"] as? JsonObject
        val type = error?.string("type")
        val code = error?.string("code")
        val message = error?.string("message")
        val kind = when {
            type == "authentication_error" || type == "permission_error" -> BrainErrorKind.InvalidKey
            code == "invalid_api_key" || code == "incorrect_api_key" -> BrainErrorKind.InvalidKey
            type == "server_error" || type == "api_error" -> BrainErrorKind.Server
            type == "invalid_request_error" || type == "rate_limit_error" -> BrainErrorKind.BadRequest
            else -> BrainErrorKind.Unknown
        }
        return AnthropicResult.Failure(
            kind = kind,
            detail = listOfNotNull(type, code, message).joinToString(": ").ifBlank { null },
        )
    }

    private fun AnthropicResult.Failure.toToolFailure(): AnthropicToolResult.Failure {
        return AnthropicToolResult.Failure(kind, detail)
    }

    private fun AnthropicResult.Failure.toToolStreamFailure(): AnthropicToolStreamResult.Failure {
        return AnthropicToolStreamResult.Failure(kind, detail)
    }

    private fun Response.toFailure(responseText: String): AnthropicResult.Failure {
        val parsed = runCatching {
            json.parseToJsonElement(responseText) as? JsonObject
        }.getOrNull()
        parsed?.get("error")?.let {
            val failure = parsed.toChatFailure()
            if (failure.kind != BrainErrorKind.Unknown) return failure
        }

        return when {
            code == 401 || code == 403 -> AnthropicResult.Failure(
                BrainErrorKind.InvalidKey,
                detail = httpDetail(responseText),
            )
            code in 500..599 -> AnthropicResult.Failure(
                BrainErrorKind.Server,
                detail = httpDetail(responseText),
            )
            code in 400..499 -> AnthropicResult.Failure(
                BrainErrorKind.BadRequest,
                detail = httpDetail(responseText),
            )
            else -> AnthropicResult.Failure(
                BrainErrorKind.Unknown,
                detail = httpDetail(responseText),
            )
        }
    }

    private fun Response.httpDetail(responseText: String): String {
        val body = responseText.replace('\n', ' ').trim()
        return if (body.isBlank()) {
            "HTTP $code"
        } else {
            "HTTP $code: ${body.take(240)}"
        }
    }

    private fun JsonObject.string(key: String): String? = this[key]?.jsonPrimitive?.contentOrNull

    private fun List<ChatCompletionToolCall>.preferredDrawToolCall(): ChatCompletionToolCall? {
        return firstOrNull { it.function?.name == DRAW_TOOL_NAME } ?: firstOrNull()
    }

    private fun String.parseToolInput(): JsonObject? {
        return runCatching { json.parseToJsonElement(this) as? JsonObject }.getOrNull()
    }

    private fun JsonObject.withStream(): JsonObject = buildJsonObject {
        this@withStream.forEach { (key, value) -> put(key, value) }
        put("stream", true)
    }

    companion object {
        private const val BLANK_REPLY = "I heard you, but the ink came back blank."
        private const val DRAW_TOOL_NAME = "draw"
        private val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()

        private fun defaultModelsUrl(baseUrl: String): String {
            return if (baseUrl.endsWith("/chat/completions")) {
                baseUrl.removeSuffix("/chat/completions") + "/models"
            } else {
                baseUrl
            }
        }
    }
}

@Serializable
private data class ChatCompletionRequest(
    val model: String,
    val messages: List<ChatMessage>,
    @SerialName("max_completion_tokens") val maxCompletionTokens: Int,
    @SerialName("reasoning_effort") val reasoningEffort: String? = null,
    val stream: Boolean = false,
)

@Serializable
private data class ChatMessage(
    val role: String,
    val content: String,
)

@Serializable
private data class ChatCompletionResponse(
    val choices: List<ChatCompletionChoice> = emptyList(),
)

@Serializable
private data class ChatCompletionChoice(
    val message: ChatCompletionMessage? = null,
)

@Serializable
private data class ChatCompletionMessage(
    val content: String? = null,
    @SerialName("tool_calls") val toolCalls: List<ChatCompletionToolCall> = emptyList(),
)

@Serializable
private data class ChatCompletionToolCall(
    val type: String = "",
    val function: ChatCompletionFunctionCall? = null,
)

@Serializable
private data class ChatCompletionFunctionCall(
    val name: String = "",
    val arguments: String = "",
)

@Serializable
private data class ChatCompletionChunk(
    val choices: List<ChatCompletionChunkChoice> = emptyList(),
)

@Serializable
private data class ChatCompletionChunkChoice(
    val delta: ChatCompletionDelta = ChatCompletionDelta(),
)

@Serializable
private data class ChatCompletionDelta(
    val content: String? = null,
    @SerialName("tool_calls") val toolCalls: List<ChatCompletionDeltaToolCall> = emptyList(),
)

@Serializable
private data class ChatCompletionDeltaToolCall(
    val index: Int = 0,
    val id: String? = null,
    val type: String? = null,
    val function: ChatCompletionDeltaFunctionCall? = null,
)

@Serializable
private data class ChatCompletionDeltaFunctionCall(
    val name: String? = null,
    val arguments: String? = null,
)
