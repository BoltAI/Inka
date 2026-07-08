package co.podzim.inka.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URI
import java.net.URL
import java.net.URLEncoder
import java.nio.charset.StandardCharsets

class ApiKeySetupServerTest {
    @Test
    fun servesSetupPageAndAcceptsSubmittedKey() {
        var submitted = ""
        val server = ApiKeySetupServer(
            providerLabel = "OpenAI",
            model = "gpt-test",
            guideUrl = "https://platform.openai.com/api-keys",
            onApiKeySubmitted = { submitted = it },
        )

        try {
            val start = server.start(advertisedHost = "127.0.0.1")
            require(start is ApiKeySetupServerStart.Ready)

            val setupResponse = URL(start.url).readText()
            assertTrue(setupResponse.contains("Set up Inka"))
            assertTrue(setupResponse.contains("OpenAI API key"))

            val submitUrl = URI(start.url).resolve("/submit?${URI(start.url).rawQuery}").toURL()
            val response = submitForm(submitUrl, "apiKey" to "sk-test-123")

            assertEquals(200, response.code)
            assertTrue(response.body.contains("Sent to Inka"))
            assertEquals("sk-test-123", submitted)
        } finally {
            server.close()
        }
    }

    @Test
    fun rejectsInvalidTokenWithoutSubmittingKey() {
        var submitted = ""
        val server = ApiKeySetupServer(
            providerLabel = "OpenAI",
            model = "gpt-test",
            guideUrl = "https://platform.openai.com/api-keys",
            onApiKeySubmitted = { submitted = it },
        )

        try {
            val start = server.start(advertisedHost = "127.0.0.1")
            require(start is ApiKeySetupServerStart.Ready)

            val base = URI(start.url)
            val submitUrl = base.resolve("/submit?token=wrong").toURL()
            val response = submitForm(submitUrl, "apiKey" to "sk-test-123")

            assertEquals(404, response.code)
            assertEquals("", submitted)
        } finally {
            server.close()
        }
    }

    private fun submitForm(url: URL, field: Pair<String, String>): HttpResponse {
        val body = "${field.first}=${URLEncoder.encode(field.second, StandardCharsets.UTF_8.name())}"
        val connection = (url.openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            doOutput = true
            setRequestProperty("content-type", "application/x-www-form-urlencoded")
            setRequestProperty("content-length", body.toByteArray(StandardCharsets.UTF_8).size.toString())
        }
        OutputStreamWriter(connection.outputStream, StandardCharsets.UTF_8).use { it.write(body) }
        val code = connection.responseCode
        val stream = if (code in 200..399) connection.inputStream else connection.errorStream
        val responseBody = stream.bufferedReader(StandardCharsets.UTF_8).use { it.readText() }
        return HttpResponse(code, responseBody)
    }

    private data class HttpResponse(
        val code: Int,
        val body: String,
    )
}
