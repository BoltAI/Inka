package com.inkwell.diary.brain

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.Transient
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

@Serializable
data class AnthropicRequestBody(
    val model: String,
    @SerialName("max_tokens") val maxTokens: Int,
    val system: String,
    val messages: List<AnthropicMessage>,
    @SerialName("output_config") val outputConfig: AnthropicOutputConfig? = null,
    @Transient val openAiReasoningEffort: String? = null,
    val stream: Boolean = false,
)

@Serializable
data class AnthropicOutputConfig(
    val effort: String,
)

@Serializable
data class AnthropicMessage(
    val role: String,
    val content: String,
)

@Serializable
private data class AnthropicResponseBody(
    val content: List<AnthropicContent> = emptyList(),
)

@Serializable
private data class AnthropicContent(
    val type: String = "text",
    val text: String = "",
    val name: String = "",
    val input: JsonObject? = null,
)

sealed class AnthropicResult {
    data class Success(val text: String) : AnthropicResult()
    data class Failure(val kind: BrainErrorKind, val detail: String? = null) : AnthropicResult()
}

sealed class AnthropicToolResult {
    data class Success(val input: JsonObject) : AnthropicToolResult()
    data class Failure(val kind: BrainErrorKind, val detail: String? = null) : AnthropicToolResult()
}

sealed class AnthropicToolStreamResult {
    data class Success(val input: JsonObject) : AnthropicToolStreamResult()
    data class Failure(val kind: BrainErrorKind, val detail: String? = null) : AnthropicToolStreamResult()
}

interface AnthropicTransport {
    suspend fun complete(apiKey: String, requestBody: AnthropicRequestBody): AnthropicResult

    suspend fun completeRaw(apiKey: String, requestBody: JsonObject): AnthropicResult {
        return AnthropicResult.Failure(BrainErrorKind.BadRequest, "Raw content is not supported by this transport")
    }

    suspend fun draw(apiKey: String, requestBody: JsonObject): AnthropicToolResult {
        return AnthropicToolResult.Failure(BrainErrorKind.BadRequest, "Tool use is not supported by this transport")
    }

    suspend fun streamDraw(
        apiKey: String,
        requestBody: JsonObject,
        onToolJsonDelta: suspend (String) -> Unit,
    ): AnthropicToolStreamResult {
        return AnthropicToolStreamResult.Failure(BrainErrorKind.BadRequest, "Tool streaming is not supported by this transport")
    }

    suspend fun stream(
        apiKey: String,
        requestBody: AnthropicRequestBody,
        onTextDelta: suspend (String) -> Unit,
    ): AnthropicResult {
        val result = complete(apiKey, requestBody)
        if (result is AnthropicResult.Success) {
            onTextDelta(result.text)
        }
        return result
    }
}

class OkHttpAnthropicTransport(
    private val baseUrl: String = "https://api.anthropic.com/v1/messages",
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
            when (val result = executeOnce(apiKey, requestBody)) {
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

    override suspend fun stream(
        apiKey: String,
        requestBody: AnthropicRequestBody,
        onTextDelta: suspend (String) -> Unit,
    ): AnthropicResult = withContext(Dispatchers.IO) {
        executeStreamOnce(apiKey, requestBody.copy(stream = true), onTextDelta)
    }

    override suspend fun completeRaw(
        apiKey: String,
        requestBody: JsonObject,
    ): AnthropicResult = withContext(Dispatchers.IO) {
        when (val result = executeRawOnce(apiKey, requestBody)) {
            is RawAnthropicResult.Text -> AnthropicResult.Success(result.text.ifBlank { "I heard you, but the ink came back blank." })
            is RawAnthropicResult.Tool -> AnthropicResult.Success("I drew something, but the ink came back as a tool.")
            is RawAnthropicResult.Failure -> AnthropicResult.Failure(result.kind, result.detail)
        }
    }

    override suspend fun draw(
        apiKey: String,
        requestBody: JsonObject,
    ): AnthropicToolResult = withContext(Dispatchers.IO) {
        when (val result = executeRawOnce(apiKey, requestBody)) {
            is RawAnthropicResult.Tool -> AnthropicToolResult.Success(result.input)
            is RawAnthropicResult.Text -> AnthropicToolResult.Failure(BrainErrorKind.BadRequest, "Expected draw tool, got text")
            is RawAnthropicResult.Failure -> AnthropicToolResult.Failure(result.kind, result.detail)
        }
    }

    override suspend fun streamDraw(
        apiKey: String,
        requestBody: JsonObject,
        onToolJsonDelta: suspend (String) -> Unit,
    ): AnthropicToolStreamResult = withContext(Dispatchers.IO) {
        executeToolStreamOnce(apiKey, requestBody.withStream(), onToolJsonDelta)
    }

    private fun executeOnce(apiKey: String, requestBody: AnthropicRequestBody): AnthropicResult {
        val body = json.encodeToString(AnthropicRequestBody.serializer(), requestBody)
            .toRequestBody(JSON_MEDIA_TYPE)
        val request = Request.Builder()
            .url(baseUrl)
            .header("x-api-key", apiKey)
            .header("anthropic-version", "2023-06-01")
            .header("content-type", "application/json")
            .post(body)
            .build()

        return try {
            client.newCall(request).execute().use { response ->
                val responseText = response.body?.string().orEmpty()
                when {
                    response.isSuccessful -> {
                        val decoded = json.decodeFromString(
                            AnthropicResponseBody.serializer(),
                            responseText,
                        )
                        val text = decoded.content.firstOrNull { it.type == "text" }?.text.orEmpty()
                        AnthropicResult.Success(text.ifBlank { "I heard you, but the ink came back blank." })
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

    private fun executeRawOnce(apiKey: String, requestBody: JsonObject): RawAnthropicResult {
        val body = json.encodeToString(JsonObject.serializer(), requestBody)
            .toRequestBody(JSON_MEDIA_TYPE)
        val request = Request.Builder()
            .url(baseUrl)
            .header("x-api-key", apiKey)
            .header("anthropic-version", "2023-06-01")
            .header("content-type", "application/json")
            .post(body)
            .build()

        return try {
            client.newCall(request).execute().use { response ->
                val responseText = response.body?.string().orEmpty()
                when {
                    response.isSuccessful -> {
                        val decoded = json.decodeFromString(
                            AnthropicResponseBody.serializer(),
                            responseText,
                        )
                        val tool = decoded.content.firstOrNull { it.type == "tool_use" && it.name == DRAW_TOOL_NAME }
                        if (tool?.input != null) {
                            RawAnthropicResult.Tool(tool.input)
                        } else {
                            val text = decoded.content.firstOrNull { it.type == "text" }?.text.orEmpty()
                            RawAnthropicResult.Text(text.ifBlank { "I heard you, but the ink came back blank." })
                        }
                    }
                    else -> response.toFailure(responseText).toRawFailure()
                }
            }
        } catch (e: InterruptedIOException) {
            RawAnthropicResult.Failure(BrainErrorKind.Network, e::class.java.simpleName)
        } catch (e: IOException) {
            RawAnthropicResult.Failure(BrainErrorKind.Network, e::class.java.simpleName)
        } catch (e: Exception) {
            RawAnthropicResult.Failure(BrainErrorKind.Unknown, e::class.java.simpleName)
        }
    }

    private suspend fun executeStreamOnce(
        apiKey: String,
        requestBody: AnthropicRequestBody,
        onTextDelta: suspend (String) -> Unit,
    ): AnthropicResult {
        val body = json.encodeToString(AnthropicRequestBody.serializer(), requestBody)
            .toRequestBody(JSON_MEDIA_TYPE)
        val request = Request.Builder()
            .url(baseUrl)
            .header("x-api-key", apiKey)
            .header("anthropic-version", "2023-06-01")
            .header("content-type", "application/json")
            .header("accept", "text/event-stream")
            .post(body)
            .build()

        return try {
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    return@use response.toFailure(response.body?.string().orEmpty())
                }

                val source = response.body?.source()
                    ?: return@use AnthropicResult.Success("I heard you, but the ink came back blank.")
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
                    AnthropicResult.Success(reply.toString().ifBlank { "I heard you, but the ink came back blank." })
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

    private suspend fun executeToolStreamOnce(
        apiKey: String,
        requestBody: JsonObject,
        onToolJsonDelta: suspend (String) -> Unit,
    ): AnthropicToolStreamResult {
        val body = json.encodeToString(JsonObject.serializer(), requestBody)
            .toRequestBody(JSON_MEDIA_TYPE)
        val request = Request.Builder()
            .url(baseUrl)
            .header("x-api-key", apiKey)
            .header("anthropic-version", "2023-06-01")
            .header("content-type", "application/json")
            .header("accept", "text/event-stream")
            .post(body)
            .build()

        return try {
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    return@use response.toFailure(response.body?.string().orEmpty()).toToolStreamFailure()
                }

                val source = response.body?.source()
                    ?: return@use AnthropicToolStreamResult.Failure(BrainErrorKind.BadRequest, "Empty tool stream")
                val toolInput = StringBuilder()
                val data = StringBuilder()

                while (true) {
                    val line = source.readUtf8Line() ?: break
                    if (line.isEmpty()) {
                        val failure = consumeToolStreamData(data.toString(), toolInput, onToolJsonDelta)
                        data.setLength(0)
                        if (failure != null) return@use failure
                    } else if (line.startsWith("data:")) {
                        if (data.isNotEmpty()) data.append('\n')
                        data.append(line.removePrefix("data:").trimStart())
                    }
                }

                val failure = consumeToolStreamData(data.toString(), toolInput, onToolJsonDelta)
                if (failure != null) {
                    failure
                } else {
                    val input = runCatching { json.parseToJsonElement(toolInput.toString()) as? JsonObject }.getOrNull()
                    if (input == null) {
                        AnthropicToolStreamResult.Failure(BrainErrorKind.BadRequest, "Invalid streamed tool input")
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

    private suspend fun consumeStreamData(
        data: String,
        reply: StringBuilder,
        onTextDelta: suspend (String) -> Unit,
    ): AnthropicResult.Failure? {
        if (data.isBlank()) return null
        val payload = runCatching { json.parseToJsonElement(data) as? JsonObject }.getOrNull() ?: return null
        return when (payload.string("type")) {
            "content_block_delta" -> {
                val delta = payload["delta"] as? JsonObject
                if (delta?.string("type") == "text_delta") {
                    val text = delta.string("text").orEmpty()
                    if (text.isNotEmpty()) {
                        reply.append(text)
                        onTextDelta(text)
                    }
                }
                null
            }
            "error" -> payload.toStreamFailure()
            else -> null
        }
    }

    private suspend fun consumeToolStreamData(
        data: String,
        toolInput: StringBuilder,
        onToolJsonDelta: suspend (String) -> Unit,
    ): AnthropicToolStreamResult.Failure? {
        if (data.isBlank()) return null
        val payload = runCatching { json.parseToJsonElement(data) as? JsonObject }.getOrNull() ?: return null
        return when (payload.string("type")) {
            "content_block_delta" -> {
                val delta = payload["delta"] as? JsonObject
                if (delta?.string("type") == "input_json_delta") {
                    val partial = delta.string("partial_json").orEmpty()
                    if (partial.isNotEmpty()) {
                        toolInput.append(partial)
                        onToolJsonDelta(partial)
                    }
                }
                null
            }
            "error" -> payload.toToolStreamFailure()
            else -> null
        }
    }

    private fun JsonObject.toStreamFailure(): AnthropicResult.Failure {
        val error = this["error"] as? JsonObject
        val type = error?.string("type")
        val message = error?.string("message")
        val kind = when (type) {
            "authentication_error", "permission_error" -> BrainErrorKind.InvalidKey
            "overloaded_error", "api_error" -> BrainErrorKind.Server
            "invalid_request_error", "not_found_error", "rate_limit_error" -> BrainErrorKind.BadRequest
            else -> BrainErrorKind.Unknown
        }
        return AnthropicResult.Failure(
            kind = kind,
            detail = listOfNotNull(type, message).joinToString(": ").ifBlank { null },
        )
    }

    private fun Response.toFailure(responseText: String): AnthropicResult.Failure {
        return when {
            code == 401 -> AnthropicResult.Failure(
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

    private fun okhttp3.Response.httpDetail(responseText: String): String {
        val body = responseText.replace('\n', ' ').trim()
        return if (body.isBlank()) {
            "HTTP $code"
        } else {
            "HTTP $code: ${body.take(240)}"
        }
    }

    private fun JsonObject.string(key: String): String? = this[key]?.jsonPrimitive?.contentOrNull

    private fun AnthropicResult.Failure.toRawFailure(): RawAnthropicResult.Failure {
        return RawAnthropicResult.Failure(kind, detail)
    }

    private fun AnthropicResult.Failure.toToolStreamFailure(): AnthropicToolStreamResult.Failure {
        return AnthropicToolStreamResult.Failure(kind, detail)
    }

    private fun JsonObject.toToolStreamFailure(): AnthropicToolStreamResult.Failure {
        val failure = toStreamFailure()
        return AnthropicToolStreamResult.Failure(failure.kind, failure.detail)
    }

    private fun JsonObject.withStream(): JsonObject = buildJsonObject {
        this@withStream.forEach { (key, value) -> put(key, value) }
        put("stream", true)
    }

    companion object {
        private const val DRAW_TOOL_NAME = "draw"
        private val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()
    }
}

private sealed class RawAnthropicResult {
    data class Text(val text: String) : RawAnthropicResult()
    data class Tool(val input: JsonObject) : RawAnthropicResult()
    data class Failure(val kind: BrainErrorKind, val detail: String? = null) : RawAnthropicResult()
}
