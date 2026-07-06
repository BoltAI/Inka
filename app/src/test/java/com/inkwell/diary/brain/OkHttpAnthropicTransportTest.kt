package com.inkwell.diary.brain

import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.coroutines.test.runTest
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class OkHttpAnthropicTransportTest {
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
    fun `posts Anthropic body with required headers`() = runTest {
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setBody("""{"content":[{"type":"text","text":"hello"}]}"""),
        )
        val transport = OkHttpAnthropicTransport(baseUrl = server.url("/v1/messages").toString())

        val result = transport.complete(
            apiKey = "test-key",
            requestBody = AnthropicRequestBody(
                model = "model",
                maxTokens = 300,
                system = "system",
                messages = listOf(AnthropicMessage("user", "hi")),
            ),
        )

        assertEquals(AnthropicResult.Success("hello"), result)
        val recorded = server.takeRequest()
        assertEquals("test-key", recorded.getHeader("x-api-key"))
        assertEquals("2023-06-01", recorded.getHeader("anthropic-version"))
        val body = recorded.body.readUtf8()
        assertTrue(body.contains(""""model":"model""""))
        assertTrue(body.contains(""""max_tokens":300"""))
        assertTrue(body.contains(""""content":"hi""""))
    }

    @Test
    fun `retries once on server error`() = runTest {
        server.enqueue(MockResponse().setResponseCode(500).setBody("{}"))
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setBody("""{"content":[{"type":"text","text":"after retry"}]}"""),
        )
        val transport = OkHttpAnthropicTransport(baseUrl = server.url("/v1/messages").toString())

        val result = transport.complete(
            apiKey = "test-key",
            requestBody = AnthropicRequestBody(
                model = "model",
                maxTokens = 300,
                system = "system",
                messages = listOf(AnthropicMessage("user", "hi")),
            ),
        )

        assertEquals(AnthropicResult.Success("after retry"), result)
        assertEquals(2, server.requestCount)
    }

    @Test
    fun `streams text deltas from Anthropic events`() = runTest {
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setHeader("content-type", "text/event-stream")
                .setBody(
                    """
                    event: message_start
                    data: {"type":"message_start","message":{"id":"msg","type":"message","role":"assistant","content":[]}}

                    event: content_block_delta
                    data: {"type":"content_block_delta","index":0,"delta":{"type":"text_delta","text":"Hel"}}

                    event: content_block_delta
                    data: {"type":"content_block_delta","index":0,"delta":{"type":"text_delta","text":"lo"}}

                    event: message_stop
                    data: {"type":"message_stop"}

                    """.trimIndent(),
                ),
        )
        val transport = OkHttpAnthropicTransport(baseUrl = server.url("/v1/messages").toString())
        val chunks = mutableListOf<String>()

        val result = transport.stream(
            apiKey = "test-key",
            requestBody = AnthropicRequestBody(
                model = "model",
                maxTokens = 300,
                system = "system",
                messages = listOf(AnthropicMessage("user", "hi")),
            ),
        ) { delta ->
            chunks.add(delta)
        }

        assertEquals(AnthropicResult.Success("Hello"), result)
        assertEquals(listOf("Hel", "lo"), chunks)
        val body = server.takeRequest().body.readUtf8()
        assertTrue(body.contains(""""stream":true"""))
    }

    @Test
    fun `streams tool input json deltas from Anthropic events`() = runTest {
        val first = """{"paths":[{"d":"M 0 0 L 1 1"}"""
        val second = """,{"d":"M 2 2 L 3 3"}],"page_text_transcript":"draw"}"""
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setHeader("content-type", "text/event-stream")
                .setBody(
                    """
                    event: content_block_start
                    data: {"type":"content_block_start","index":0,"content_block":{"type":"tool_use","id":"toolu","name":"draw","input":{}}}

                    event: content_block_delta
                    ${inputJsonDelta(first)}

                    event: content_block_delta
                    ${inputJsonDelta(second)}

                    event: content_block_stop
                    data: {"type":"content_block_stop","index":0}

                    event: message_stop
                    data: {"type":"message_stop"}

                    """.trimIndent(),
                ),
        )
        val transport = OkHttpAnthropicTransport(baseUrl = server.url("/v1/messages").toString())
        val chunks = mutableListOf<String>()

        val result = transport.streamDraw(
            apiKey = "test-key",
            requestBody = ConversationEngine.buildDrawingRequestBody(
                model = "model",
                systemPrompt = "system",
                history = emptyList(),
                snapshot = PageSnapshot("png", 10, 10, 20, 20),
                forceTool = true,
            ),
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

    private fun inputJsonDelta(partialJson: String): String {
        return """data: {"type":"content_block_delta","index":0,"delta":{"type":"input_json_delta","partial_json":${JsonPrimitive(partialJson)}}}"""
    }
}
