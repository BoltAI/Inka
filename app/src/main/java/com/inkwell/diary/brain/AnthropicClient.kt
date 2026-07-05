package com.inkwell.diary.brain

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
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
    val stream: Boolean = false,
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
)

sealed class AnthropicResult {
    data class Success(val text: String) : AnthropicResult()
    data class Failure(val kind: BrainErrorKind, val detail: String? = null) : AnthropicResult()
}

interface AnthropicTransport {
    suspend fun complete(apiKey: String, requestBody: AnthropicRequestBody): AnthropicResult

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

    companion object {
        private val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()
    }
}
