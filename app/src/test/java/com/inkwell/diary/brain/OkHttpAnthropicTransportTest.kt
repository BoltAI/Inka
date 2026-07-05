package com.inkwell.diary.brain

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
}
