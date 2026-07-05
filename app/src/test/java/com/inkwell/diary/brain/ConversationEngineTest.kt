package com.inkwell.diary.brain

import com.inkwell.diary.data.AiProvider
import com.inkwell.diary.data.Persona
import com.inkwell.diary.data.PersonaPrompts
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ConversationEngineTest {
    @Test
    fun `request body keeps system prompt history order and max tokens`() {
        val body = ConversationEngine.buildRequestBody(
            model = "claude-sonnet-4-6",
            systemPrompt = PersonaPrompts.forPersona(Persona.default, ""),
            history = listOf(
                AnthropicMessage("user", "first"),
                AnthropicMessage("assistant", "reply"),
            ),
            userText = "second",
            maxTokens = 300,
        )

        assertEquals("claude-sonnet-4-6", body.model)
        assertEquals(300, body.maxTokens)
        assertEquals(PersonaPrompts.forPersona(Persona.default, ""), body.system)
        assertEquals(listOf("first", "reply", "second"), body.messages.map { it.content })
        assertEquals(listOf("user", "assistant", "user"), body.messages.map { it.role })
    }

    @Test
    fun `history caps at last twenty turns`() = runTest {
        val transport = FakeTransport()
        val engine = ConversationEngine(transport)
        val settings = ConversationSettings("key", "model", "system")

        repeat(25) { index ->
            engine.sendMessage(settings, "u$index")
        }

        val history = engine.historySnapshot()
        assertEquals(40, history.size)
        assertEquals("u5", history.first().content)
        assertEquals("reply-24", history.last().content)
        assertTrue(transport.requests.last().messages.last().content == "u24")
    }

    @Test
    fun `stream message emits deltas and records final assistant reply`() = runTest {
        val transport = FakeStreamingTransport()
        val engine = ConversationEngine(transport)
        val chunks = mutableListOf<String>()

        val result = engine.streamMessage(
            settings = ConversationSettings("key", "model", "system"),
            userText = "hello",
        ) { delta ->
            chunks.add(delta)
        }

        assertEquals(AnthropicResult.Success("streamed reply"), result)
        assertEquals(listOf("streamed ", "reply"), chunks)
        assertEquals(listOf("hello", "streamed reply"), engine.historySnapshot().map { it.content })
    }

    @Test
    fun `replace history caps to configured turn count`() {
        val engine = ConversationEngine(FakeTransport(), maxTurns = 2)
        val messages = (0 until 8).map { index ->
            AnthropicMessage(role = if (index % 2 == 0) "user" else "assistant", content = "message $index")
        }

        engine.replaceHistory(messages)

        assertEquals(listOf("message 4", "message 5", "message 6", "message 7"), engine.historySnapshot().map { it.content })
    }

    @Test
    fun `routes requests to selected provider transport`() = runTest {
        val anthropic = FakeTransport()
        val groq = FakeTransport()
        val engine = ConversationEngine(
            transports = mapOf(
                AiProvider.Anthropic to anthropic,
                AiProvider.Groq to groq,
            ),
        )

        val result = engine.sendMessage(
            settings = ConversationSettings(
                apiKey = "groq-key",
                model = "",
                systemPrompt = "system",
                provider = AiProvider.Groq,
            ),
            userText = "hello",
        )

        assertEquals(AnthropicResult.Success("reply-0"), result)
        assertEquals(0, anthropic.requests.size)
        assertEquals(1, groq.requests.size)
        assertEquals(AiProvider.Groq.defaultModel, groq.requests.single().model)
    }
}

private class FakeTransport : AnthropicTransport {
    val requests = mutableListOf<AnthropicRequestBody>()

    override suspend fun complete(apiKey: String, requestBody: AnthropicRequestBody): AnthropicResult {
        requests.add(requestBody)
        return AnthropicResult.Success("reply-${requests.lastIndex}")
    }
}

private class FakeStreamingTransport : AnthropicTransport {
    override suspend fun complete(apiKey: String, requestBody: AnthropicRequestBody): AnthropicResult {
        error("complete should not be used by streamMessage")
    }

    override suspend fun stream(
        apiKey: String,
        requestBody: AnthropicRequestBody,
        onTextDelta: suspend (String) -> Unit,
    ): AnthropicResult {
        onTextDelta("streamed ")
        onTextDelta("reply")
        return AnthropicResult.Success("streamed reply")
    }
}
