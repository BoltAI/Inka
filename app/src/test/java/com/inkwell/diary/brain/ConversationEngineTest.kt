package com.inkwell.diary.brain

import com.inkwell.diary.data.AiProvider
import com.inkwell.diary.data.Persona
import com.inkwell.diary.data.PersonaPrompts
import kotlinx.serialization.json.add
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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

    @Test
    fun `drawing request forces draw tool and decodes tool payload`() = runTest {
        val transport = FakeDrawingTransport()
        val engine = ConversationEngine(mapOf(AiProvider.Anthropic to transport))
        val snapshot = PageSnapshot(
            pngBase64 = "png-data",
            width = 768,
            height = 512,
            pageWidth = 1800,
            pageHeight = 1200,
        )

        val result = engine.requestDrawing(
            settings = ConversationSettings(
                apiKey = "key",
                model = "",
                systemPrompt = "base",
                provider = AiProvider.Anthropic,
            ),
            snapshot = snapshot,
            latestUserText = "draw a cat",
        )

        assertEquals(
            DrawingReplyResult.Success(
                paths = listOf("M 0 0 L 10 10"),
                pageTextTranscript = "draw a cat",
            ),
            result,
        )
        val body = transport.drawRequests.single()
        assertEquals(AiProvider.Anthropic.defaultModel, body["model"]?.jsonPrimitive?.contentOrNull)
        assertEquals("tool", body["tool_choice"]?.jsonObject?.get("type")?.jsonPrimitive?.contentOrNull)
        assertEquals("draw", body["tool_choice"]?.jsonObject?.get("name")?.jsonPrimitive?.contentOrNull)
        val tool = body["tools"]!!.jsonArray.single().jsonObject
        assertEquals(true, tool["eager_input_streaming"]?.jsonPrimitive?.booleanOrNull)
        assertFalse(tool["input_schema"]!!.jsonObject["properties"]!!.jsonObject.containsKey("caption"))
        val userContent = body["messages"]!!.jsonArray.last().jsonObject["content"]!!.jsonArray
        assertEquals("image", userContent.first().jsonObject["type"]?.jsonPrimitive?.contentOrNull)
        assertEquals("text", userContent.last().jsonObject["type"]?.jsonPrimitive?.contentOrNull)
        val userText = userContent.last().jsonObject["text"]?.jsonPrimitive?.contentOrNull.orEmpty()
        assertTrue(userText.contains("Latest recognized text"))
        assertTrue(userText.contains("draw a cat"))
        assertTrue(userText.contains("current user turn"))
    }

    @Test
    fun `drawing request fails instead of writing when tool paths are missing`() = runTest {
        val transport = FakeEmptyDrawingTransport()
        val engine = ConversationEngine(mapOf(AiProvider.Anthropic to transport))

        val result = engine.requestDrawing(
            settings = ConversationSettings("key", "", "base", AiProvider.Anthropic),
            snapshot = PageSnapshot("png", 10, 10, 20, 20),
        )

        assertEquals(DrawingReplyResult.Failure(BrainErrorKind.BadRequest, "Draw tool returned no paths"), result)
        assertEquals(1, transport.drawRequests)
        assertEquals(0, transport.rawRequests)
    }

    @Test
    fun `drawing request truncates oversized tool path list`() = runTest {
        val transport = FakeManyPathsTransport()
        val engine = ConversationEngine(mapOf(AiProvider.Anthropic to transport))

        val result = engine.requestDrawing(
            settings = ConversationSettings("key", "", "base", AiProvider.Anthropic),
            snapshot = PageSnapshot("png", 10, 10, 20, 20),
        )

        val success = result as DrawingReplyResult.Success
        assertEquals(60, success.paths.size)
        assertEquals("M 0 0 L 59 59", success.paths.last())
    }

    @Test
    fun `stream drawing emits paths before final tool result`() = runTest {
        val transport = FakeStreamingDrawingTransport()
        val engine = ConversationEngine(mapOf(AiProvider.Anthropic to transport))
        val callbackBeforeCompletion = mutableListOf<Boolean>()
        val deltas = mutableListOf<String>()

        val result = engine.streamDrawing(
            settings = ConversationSettings("key", "", "base", AiProvider.Anthropic),
            snapshot = PageSnapshot("png", 10, 10, 20, 20),
            latestUserText = "draw two lines",
            onPath = {
                callbackBeforeCompletion.add(!transport.completed)
            },
            onToolJsonDelta = {
                deltas.add(it)
            },
        )

        assertEquals(
            DrawingReplyResult.Success(
                paths = listOf("M 0 0 L 1 1", "M 2 2 L 3 3"),
                pageTextTranscript = "draw two lines",
            ),
            result,
        )
        assertEquals(listOf(true, true), callbackBeforeCompletion)
        assertTrue(deltas.size >= 2)
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

private class FakeDrawingTransport : AnthropicTransport {
    val drawRequests = mutableListOf<kotlinx.serialization.json.JsonObject>()

    override suspend fun complete(apiKey: String, requestBody: AnthropicRequestBody): AnthropicResult {
        error("complete should not be used by requestDrawing")
    }

    override suspend fun draw(
        apiKey: String,
        requestBody: kotlinx.serialization.json.JsonObject,
    ): AnthropicToolResult {
        drawRequests.add(requestBody)
        return AnthropicToolResult.Success(
            buildJsonObject {
                putJsonArray("paths") {
                    add(
                        buildJsonObject {
                            put("d", "M 0 0 L 10 10")
                        },
                    )
                }
                put("page_text_transcript", "draw a cat")
            },
        )
    }
}

private class FakeEmptyDrawingTransport : AnthropicTransport {
    var drawRequests = 0
    var rawRequests = 0

    override suspend fun complete(apiKey: String, requestBody: AnthropicRequestBody): AnthropicResult {
        error("complete should not be used")
    }

    override suspend fun draw(
        apiKey: String,
        requestBody: kotlinx.serialization.json.JsonObject,
    ): AnthropicToolResult {
        drawRequests += 1
        return AnthropicToolResult.Success(
            buildJsonObject {
                put("page_text_transcript", "single dot")
            },
        )
    }

    override suspend fun completeRaw(
        apiKey: String,
        requestBody: kotlinx.serialization.json.JsonObject,
    ): AnthropicResult {
        rawRequests += 1
        return AnthropicResult.Success("fallback words")
    }
}

private class FakeManyPathsTransport : AnthropicTransport {
    override suspend fun complete(apiKey: String, requestBody: AnthropicRequestBody): AnthropicResult {
        error("complete should not be used")
    }

    override suspend fun draw(
        apiKey: String,
        requestBody: kotlinx.serialization.json.JsonObject,
    ): AnthropicToolResult {
        return AnthropicToolResult.Success(
            buildJsonObject {
                putJsonArray("paths") {
                    repeat(72) { index ->
                        add(
                            buildJsonObject {
                                put("d", "M 0 0 L $index $index")
                            },
                        )
                    }
                }
                put("page_text_transcript", "")
            },
        )
    }
}

private class FakeStreamingDrawingTransport : AnthropicTransport {
    var completed = false

    override suspend fun complete(apiKey: String, requestBody: AnthropicRequestBody): AnthropicResult {
        error("complete should not be used")
    }

    override suspend fun streamDraw(
        apiKey: String,
        requestBody: kotlinx.serialization.json.JsonObject,
        onToolJsonDelta: suspend (String) -> Unit,
    ): AnthropicToolStreamResult {
        onToolJsonDelta("""{"paths":[{"d":"M 0 0 L 1 1"}""")
        onToolJsonDelta(""",{"d":"M 2 2 L 3 3"}],"page_text_transcript":"draw two lines"}""")
        completed = true
        return AnthropicToolStreamResult.Success(
            buildJsonObject {
                putJsonArray("paths") {
                    add(buildJsonObject { put("d", "M 0 0 L 1 1") })
                    add(buildJsonObject { put("d", "M 2 2 L 3 3") })
                }
                put("page_text_transcript", "draw two lines")
            },
        )
    }
}
