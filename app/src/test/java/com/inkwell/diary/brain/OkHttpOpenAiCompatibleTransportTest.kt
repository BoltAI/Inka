package com.inkwell.diary.brain

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
            requestBody = requestBody(stream = false),
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

    private fun requestBody(stream: Boolean): AnthropicRequestBody {
        return AnthropicRequestBody(
            model = "openai/gpt-oss-20b",
            maxTokens = 300,
            system = "system",
            messages = listOf(AnthropicMessage("user", "hi")),
            stream = stream,
        )
    }
}
