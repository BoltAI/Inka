package com.inkwell.diary.brain

import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.coroutines.test.runTest
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class OkHttpOpenAiCompatibleTransportTest {
    private lateinit var server: MockWebServer

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    @Test
    fun `posts OpenAI compatible chat body with bearer auth`() = runTest {
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setBody("""{"choices":[{"message":{"content":"hello"}}]}"""),
        )
        val transport = OkHttpOpenAiCompatibleTransport(
            baseUrl = server.url("/openai/v1/chat/completions").toString(),
        )

        val result = transport.complete(
            apiKey = "test-key",
            requestBody = requestBody(stream = false, reasoningEffort = "high"),
        )

        assertEquals(AnthropicResult.Success("hello"), result)
        val recorded = server.takeRequest()
        assertEquals("Bearer test-key", recorded.getHeader("Authorization"))
        val body = recorded.body.readUtf8()
        assertTrue(body.contains(""""model":"openai/gpt-oss-20b""""))
        assertTrue(body.contains(""""role":"system""""))
        assertTrue(body.contains(""""content":"system""""))
        assertTrue(body.contains(""""role":"user""""))
        assertTrue(body.contains(""""content":"hi""""))
        assertTrue(body.contains(""""max_completion_tokens":300"""))
        assertTrue(body.contains(""""reasoning_effort":"high""""))
    }

    @Test
    fun `validates OpenAI compatible key with models endpoint`() = runTest {
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setBody("""{"object":"list","data":[{"id":"gpt-5.4-mini"}]}"""),
        )
        val transport = OkHttpOpenAiCompatibleTransport(
            baseUrl = server.url("/openai/v1/chat/completions").toString(),
        )

        val result = transport.validateKey(
            apiKey = "test-key",
            requestBody = requestBody(stream = false),
        )

        assertEquals(AnthropicResult.Success("OK"), result)
        val recorded = server.takeRequest()
        assertEquals("/openai/v1/models", recorded.path)
        assertEquals("GET", recorded.method)
        assertEquals("Bearer test-key", recorded.getHeader("Authorization"))
    }

    @Test
    fun `maps OpenAI compatible validation auth errors to invalid key`() = runTest {
        server.enqueue(
            MockResponse()
                .setResponseCode(401)
                .setBody("""{"error":{"type":"authentication_error","message":"bad key"}}"""),
        )
        val transport = OkHttpOpenAiCompatibleTransport(
            baseUrl = server.url("/openai/v1/chat/completions").toString(),
        )

        val result = transport.validateKey(
            apiKey = "test-key",
            requestBody = requestBody(stream = false),
        )

        assertTrue(result is AnthropicResult.Failure)
        assertEquals(BrainErrorKind.InvalidKey, (result as AnthropicResult.Failure).kind)
    }

    @Test
    fun `streams OpenAI compatible text deltas`() = runTest {
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setHeader("content-type", "text/event-stream")
                .setBody(
                    """
                    data: {"choices":[{"delta":{"content":"Hel"}}]}

                    data: {"choices":[{"delta":{"content":"lo"}}]}

                    data: [DONE]

                    """.trimIndent(),
                ),
        )
        val transport = OkHttpOpenAiCompatibleTransport(
            baseUrl = server.url("/openai/v1/chat/completions").toString(),
        )
        val chunks = mutableListOf<String>()

        val result = transport.stream(
            apiKey = "test-key",
            requestBody = requestBody(stream = false),
        ) { delta ->
            chunks.add(delta)
        }

        assertEquals(AnthropicResult.Success("Hello"), result)
        assertEquals(listOf("Hel", "lo"), chunks)
        val body = server.takeRequest().body.readUtf8()
        assertTrue(body.contains(""""stream":true"""))
    }

    @Test
    fun `parses OpenAI compatible draw tool call`() = runTest {
        val arguments = """{"paths":[{"d":"M 0 0 L 1 1"}],"page_text_transcript":"draw"}"""
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setBody(
                    """
                    {
                      "choices": [
                        {
                          "message": {
                            "tool_calls": [
                              {
                                "type": "function",
                                "function": {
                                  "name": "draw",
                                  "arguments": ${JsonPrimitive(arguments)}
                                }
                              }
                            ]
                          }
                        }
                      ]
                    }
                    """.trimIndent(),
                ),
        )
        val transport = OkHttpOpenAiCompatibleTransport(
            baseUrl = server.url("/openai/v1/chat/completions").toString(),
        )

        val result = transport.draw(
            apiKey = "test-key",
            requestBody = openAiDrawingBody(stream = false),
        )

        val success = result as AnthropicToolResult.Success
        assertEquals("M 0 0 L 1 1", success.input["paths"]!!.jsonArray[0].jsonObject["d"]?.jsonPrimitive?.contentOrNull)
        assertEquals("draw", success.input["page_text_transcript"]?.jsonPrimitive?.contentOrNull)
        val body = server.takeRequest().body.readUtf8()
        assertTrue(body.contains(""""tool_choice":{"type":"function","function":{"name":"draw"}}"""))
    }

    @Test
    fun `streams OpenAI compatible draw tool argument deltas`() = runTest {
        val first = """{"paths":[{"d":"M 0 0 L 1 1"}"""
        val second = """,{"d":"M 2 2 L 3 3"}],"page_text_transcript":"draw"}"""
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setHeader("content-type", "text/event-stream")
                .setBody(
                    """
                    ${toolDelta(first, name = "draw")}

                    ${toolDelta(second)}

                    data: [DONE]

                    """.trimIndent(),
                ),
        )
        val transport = OkHttpOpenAiCompatibleTransport(
            baseUrl = server.url("/openai/v1/chat/completions").toString(),
        )
        val chunks = mutableListOf<String>()

        val result = transport.streamDraw(
            apiKey = "test-key",
            requestBody = openAiDrawingBody(stream = false),
        ) { delta ->
            chunks.add(delta)
        }

        val success = result as AnthropicToolStreamResult.Success
        assertEquals(listOf(first, second), chunks)
        assertEquals("M 0 0 L 1 1", success.input["paths"]!!.jsonArray[0].jsonObject["d"]?.jsonPrimitive?.contentOrNull)
        assertEquals("M 2 2 L 3 3", success.input["paths"]!!.jsonArray[1].jsonObject["d"]?.jsonPrimitive?.contentOrNull)
        val body = server.takeRequest().body.readUtf8()
        assertTrue(body.contains(""""stream":true"""))
    }

    @Test
    fun `maps auth errors to invalid key`() = runTest {
        server.enqueue(
            MockResponse()
                .setResponseCode(401)
                .setBody("""{"error":{"type":"authentication_error","message":"bad key"}}"""),
        )
        val transport = OkHttpOpenAiCompatibleTransport(
            baseUrl = server.url("/openai/v1/chat/completions").toString(),
        )

        val result = transport.complete(
            apiKey = "test-key",
            requestBody = requestBody(stream = false),
        )

        assertTrue(result is AnthropicResult.Failure)
        assertEquals(BrainErrorKind.InvalidKey, (result as AnthropicResult.Failure).kind)
    }

    private fun requestBody(stream: Boolean, reasoningEffort: String? = null): AnthropicRequestBody {
        return AnthropicRequestBody(
            model = "openai/gpt-oss-20b",
            maxTokens = 300,
            system = "system",
            messages = listOf(AnthropicMessage("user", "hi")),
            openAiReasoningEffort = reasoningEffort,
            stream = stream,
        )
    }

    private fun openAiDrawingBody(stream: Boolean) = buildJsonObject {
        put("model", "gpt-5.4-mini")
        put("max_completion_tokens", 2000)
        putJsonArray("messages") {
            add(
                buildJsonObject {
                    put("role", "user")
                    putJsonArray("content") {
                        add(buildJsonObject { put("type", "text"); put("text", "draw") })
                        add(
                            buildJsonObject {
                                put("type", "image_url")
                                put(
                                    "image_url",
                                    buildJsonObject {
                                        put("url", "data:image/png;base64,png")
                                    },
                                )
                            },
                        )
                    }
                },
            )
        }
        putJsonArray("tools") {
            add(
                buildJsonObject {
                    put("type", "function")
                    put(
                        "function",
                        buildJsonObject {
                            put("name", "draw")
                            put("parameters", buildJsonObject { put("type", "object") })
                        },
                    )
                },
            )
        }
        put(
            "tool_choice",
            buildJsonObject {
                put("type", "function")
                put("function", buildJsonObject { put("name", "draw") })
            },
        )
        put("stream", stream)
    }

    private fun toolDelta(arguments: String, name: String? = null): String {
        val function = buildJsonObject {
            name?.let { put("name", it) }
            put("arguments", arguments)
        }
        val payload = buildJsonObject {
            putJsonArray("choices") {
                add(
                    buildJsonObject {
                        put(
                            "delta",
                            buildJsonObject {
                                putJsonArray("tool_calls") {
                                    add(
                                        buildJsonObject {
                                            put("index", 0)
                                            put("type", "function")
                                            put("function", function)
                                        },
                                    )
                                }
                            },
                        )
                    },
                )
            }
        }
        return "data: $payload"
    }
}
