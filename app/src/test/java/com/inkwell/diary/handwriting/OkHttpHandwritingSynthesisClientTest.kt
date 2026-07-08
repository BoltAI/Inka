package com.inkwell.diary.handwriting

import kotlinx.coroutines.test.runTest
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class OkHttpHandwritingSynthesisClientTest {
    private lateinit var server: MockWebServer

    @Test
    fun `converts display sp to synthesis page pixels`() {
        assertEquals(56f, handwritingSynthesisFontSizePx(fontSizeSp = 40f, scaledDensity = 1.4f), 0.01f)
        assertEquals(40f, handwritingSynthesisFontSizePx(fontSizeSp = 40f, scaledDensity = 1f), 0.01f)
        assertEquals(110f, handwritingSynthesisFontSizePx(fontSizeSp = 90f, scaledDensity = 2f), 0.01f)
    }

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
    fun `posts synthesis request and parses ink strokes`() = runTest {
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setBody("""{"strokes":[{"points":[{"x":10.5,"y":20.5,"pressure":0.6,"t":12}]}]}"""),
        )
        val client = OkHttpHandwritingSynthesisClient()

        val result = client.synthesize(
            serverUrl = server.url("/").toString(),
            request = request(),
        )

        val success = result as HandwritingSynthesisResult.Success
        assertEquals(1, success.strokes.size)
        assertEquals(10.5f, success.strokes.single().points.single().x, 0.01f)
        assertEquals(20.5f, success.strokes.single().points.single().y, 0.01f)
        assertEquals(0.6f, success.strokes.single().points.single().pressure, 0.01f)
        assertEquals(12L, success.strokes.single().points.single().timestampMs)
        assertEquals(0.42f, success.strokes.single().strokeWidthMm, 0.01f)

        val recorded = server.takeRequest()
        assertEquals("/handwriting", recorded.path)
        val body = recorded.body.readUtf8()
        assertTrue(body.contains(""""text":"The page heard you.""""))
        assertTrue(body.contains(""""pageWidth":1404"""))
        assertTrue(body.contains(""""fontSizeSp":52.0"""))
        assertTrue(body.contains(""""strokeWidthMm":0.42"""))
        assertTrue(body.contains(""""style":"lab-123456""""))
        assertTrue(body.contains(""""seed":123456"""))
    }

    @Test
    fun `appends endpoint when server URL omits scheme`() = runTest {
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setBody("""{"strokes":[{"points":[{"x":1,"y":2,"t":3}]}]}"""),
        )
        val client = OkHttpHandwritingSynthesisClient()

        val result = client.synthesize(
            serverUrl = "${server.hostName}:${server.port}",
            request = request(),
        )

        assertTrue(result is HandwritingSynthesisResult.Success)
        assertEquals("/handwriting", server.takeRequest().path)
    }

    @Test
    fun `returns failure for empty stroke response`() = runTest {
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setBody("""{"strokes":[]}"""),
        )
        val client = OkHttpHandwritingSynthesisClient()

        val result = client.synthesize(
            serverUrl = server.url("/").toString(),
            request = request(),
        )

        assertTrue(result is HandwritingSynthesisResult.Failure)
    }

    private fun request(): HandwritingSynthesisRequest {
        return HandwritingSynthesisRequest(
            text = "The page heard you.",
            pageWidth = 1404,
            pageHeight = 1872,
            left = 96,
            top = 180,
            maxWidth = 1212,
            fontSizeSp = 52f,
            strokeWidthMm = 0.42f,
            style = "lab-123456",
            seed = 123456L,
        )
    }
}
