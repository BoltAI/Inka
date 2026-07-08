package co.podzim.inka.ui

import java.io.BufferedReader
import java.io.Closeable
import java.io.InputStreamReader
import java.net.Inet4Address
import java.net.NetworkInterface
import java.net.ServerSocket
import java.net.Socket
import java.net.URI
import java.net.URLDecoder
import java.nio.charset.StandardCharsets
import java.security.SecureRandom
import java.util.Base64
import java.util.Locale
import kotlin.concurrent.thread

internal class ApiKeySetupServer(
    private val providerLabel: String,
    private val model: String,
    private val guideUrl: String,
    private val onApiKeySubmitted: (String) -> Unit,
) : Closeable {
    @Volatile
    private var running = false
    private var serverSocket: ServerSocket? = null
    private var serverThread: Thread? = null
    private val token = randomToken()

    fun start(advertisedHost: String? = localIpv4Address()): ApiKeySetupServerStart {
        if (advertisedHost.isNullOrBlank()) return ApiKeySetupServerStart.Unavailable
        if (running) return ApiKeySetupServerStart.Unavailable

        return runCatching {
            val socket = ServerSocket(0)
            socket.soTimeout = ACCEPT_TIMEOUT_MS
            serverSocket = socket
            running = true
            serverThread = thread(
                start = true,
                isDaemon = true,
                name = "InkaApiKeySetupServer",
            ) {
                serve(socket)
            }
            ApiKeySetupServerStart.Ready(
                url = "http://$advertisedHost:${socket.localPort}/?token=$token",
            )
        }.getOrElse {
            close()
            ApiKeySetupServerStart.Unavailable
        }
    }

    override fun close() {
        running = false
        runCatching { serverSocket?.close() }
        serverSocket = null
        serverThread = null
    }

    private fun serve(socket: ServerSocket) {
        while (running) {
            val client = runCatching { socket.accept() }.getOrNull() ?: continue
            runCatching { handle(client) }
            runCatching { client.close() }
        }
    }

    private fun handle(socket: Socket) {
        socket.soTimeout = REQUEST_TIMEOUT_MS
        val reader = BufferedReader(InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8))
        val requestLine = reader.readLine().orEmpty()
        if (requestLine.isBlank()) return

        val parts = requestLine.split(" ")
        if (parts.size < 2) {
            socket.writeHtml(400, errorPage("Bad request."))
            return
        }

        val method = parts[0].uppercase(Locale.US)
        val target = parts[1]
        val headers = readHeaders(reader)
        val uri = runCatching { URI("http://localhost$target") }.getOrNull()
        if (uri == null || uri.queryParam("token") != token) {
            socket.writeHtml(404, errorPage("This setup link is no longer active."))
            return
        }

        when {
            method == "GET" && uri.path == "/" -> socket.writeHtml(200, setupPage())
            method == "POST" && uri.path == "/submit" -> handleSubmit(socket, reader, headers)
            else -> socket.writeHtml(404, errorPage("This setup link is no longer active."))
        }
    }

    private fun handleSubmit(
        socket: Socket,
        reader: BufferedReader,
        headers: Map<String, String>,
    ) {
        val contentLength = headers["content-length"]?.toIntOrNull() ?: 0
        if (contentLength <= 0 || contentLength > MAX_BODY_CHARS) {
            socket.writeHtml(400, errorPage("The submitted key was empty or too large."))
            return
        }

        val buffer = CharArray(contentLength)
        var offset = 0
        while (offset < contentLength) {
            val read = reader.read(buffer, offset, contentLength - offset)
            if (read < 0) break
            offset += read
        }

        val body = String(buffer, 0, offset)
        val apiKey = formFields(body)["apiKey"].orEmpty().trim()
        if (apiKey.isBlank()) {
            socket.writeHtml(200, setupPage(error = "Paste the API key before sending."))
            return
        }

        onApiKeySubmitted(apiKey)
        socket.writeHtml(200, successPage())
    }

    private fun setupPage(error: String = ""): String {
        val errorHtml = if (error.isBlank()) {
            ""
        } else {
            """<p class="error">${error.escapeHtml()}</p>"""
        }
        return page(
            title = "Set up Inka",
            body = """
                <h1>Set up Inka</h1>
                <p>This page sends your ${providerLabel.escapeHtml()} API key directly to your BOOX tablet on this Wi-Fi network.</p>
                <ol>
                  <li>Open <a href="${guideUrl.escapeHtml()}">${guideUrl.escapeHtml()}</a>.</li>
                  <li>Create a new API key.</li>
                  <li>Paste it below and send it to Inka.</li>
                </ol>
                <p class="fine">Inka will save the key on the tablet and verify it with ${providerLabel.escapeHtml()} using ${model.escapeHtml()}.</p>
                $errorHtml
                <form method="post" action="/submit?token=${token.escapeHtml()}">
                  <label for="apiKey">${providerLabel.escapeHtml()} API key</label>
                  <textarea id="apiKey" name="apiKey" rows="4" autocomplete="off" autocapitalize="none" spellcheck="false" autofocus></textarea>
                  <button type="submit">Send to Inka</button>
                </form>
            """.trimIndent(),
        )
    }

    private fun successPage(): String {
        return page(
            title = "Sent to Inka",
            body = """
                <h1>Sent to Inka</h1>
                <p>The key was sent to your BOOX tablet.</p>
                <p>Check Inka now. The tablet will save and verify the key there.</p>
            """.trimIndent(),
        )
    }

    private fun errorPage(message: String): String {
        return page(
            title = "Inka setup",
            body = """
                <h1>Inka setup</h1>
                <p>${message.escapeHtml()}</p>
            """.trimIndent(),
        )
    }

    private fun page(title: String, body: String): String {
        return """
            <!doctype html>
            <html lang="en">
            <head>
              <meta charset="utf-8">
              <meta name="viewport" content="width=device-width, initial-scale=1">
              <title>${title.escapeHtml()}</title>
              <style>
                :root { color-scheme: light; }
                body { font-family: -apple-system, BlinkMacSystemFont, "Segoe UI", sans-serif; margin: 0; padding: 24px; line-height: 1.45; color: #111; background: #fff; }
                main { max-width: 560px; margin: 0 auto; }
                h1 { font-size: 30px; line-height: 1.1; margin: 0 0 16px; }
                p, li, label { font-size: 17px; }
                ol { padding-left: 22px; }
                a { color: #111; font-weight: 700; }
                label { display: block; font-weight: 700; margin: 22px 0 8px; }
                textarea { width: 100%; box-sizing: border-box; border: 2px solid #111; border-radius: 8px; padding: 12px; font: 16px ui-monospace, SFMono-Regular, Menlo, monospace; }
                button { width: 100%; margin-top: 14px; border: 0; border-radius: 8px; padding: 15px 18px; background: #111; color: #fff; font-size: 18px; font-weight: 700; }
                .fine { color: #444; font-size: 15px; }
                .error { color: #9b111e; font-weight: 700; }
              </style>
            </head>
            <body>
              <main>
                $body
              </main>
            </body>
            </html>
        """.trimIndent()
    }

    companion object {
        private const val ACCEPT_TIMEOUT_MS = 500
        private const val REQUEST_TIMEOUT_MS = 5000
        private const val MAX_BODY_CHARS = 16 * 1024
    }
}

internal sealed interface ApiKeySetupServerStart {
    data class Ready(val url: String) : ApiKeySetupServerStart
    data object Unavailable : ApiKeySetupServerStart
}

private fun readHeaders(reader: BufferedReader): Map<String, String> {
    val headers = linkedMapOf<String, String>()
    while (true) {
        val line = reader.readLine() ?: break
        if (line.isBlank()) break
        val separator = line.indexOf(':')
        if (separator <= 0) continue
        val name = line.substring(0, separator).trim().lowercase(Locale.US)
        val value = line.substring(separator + 1).trim()
        headers[name] = value
    }
    return headers
}

private fun formFields(body: String): Map<String, String> {
    return body.split('&')
        .mapNotNull { pair ->
            val separator = pair.indexOf('=')
            if (separator < 0) return@mapNotNull null
            val key = pair.substring(0, separator).urlDecode()
            val value = pair.substring(separator + 1).urlDecode()
            key to value
        }
        .toMap()
}

private fun String.urlDecode(): String =
    URLDecoder.decode(this, StandardCharsets.UTF_8.name())

private fun URI.queryParam(name: String): String? {
    return rawQuery
        ?.split('&')
        ?.firstNotNullOfOrNull { pair ->
            val separator = pair.indexOf('=')
            val key = if (separator >= 0) pair.substring(0, separator) else pair
            if (key.urlDecode() == name) {
                if (separator >= 0) pair.substring(separator + 1).urlDecode() else ""
            } else {
                null
            }
        }
}

private fun Socket.writeHtml(status: Int, html: String) {
    val reason = when (status) {
        200 -> "OK"
        400 -> "Bad Request"
        404 -> "Not Found"
        else -> "OK"
    }
    val body = html.toByteArray(StandardCharsets.UTF_8)
    val header = "HTTP/1.1 $status $reason\r\n" +
        "Content-Type: text/html; charset=utf-8\r\n" +
        "Cache-Control: no-store\r\n" +
        "Connection: close\r\n" +
        "Content-Length: ${body.size}\r\n" +
        "\r\n"
    getOutputStream().apply {
        write(header.toByteArray(StandardCharsets.US_ASCII))
        write(body)
        flush()
    }
}

private fun String.escapeHtml(): String =
    replace("&", "&amp;")
        .replace("<", "&lt;")
        .replace(">", "&gt;")
        .replace("\"", "&quot;")
        .replace("'", "&#39;")

private fun randomToken(): String {
    val bytes = ByteArray(18)
    SecureRandom().nextBytes(bytes)
    return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
}

private fun localIpv4Address(): String? {
    return runCatching {
        NetworkInterface.getNetworkInterfaces()
            .asSequence()
            .filter { it.isUp && !it.isLoopback }
            .flatMap { networkInterface -> networkInterface.inetAddresses.asSequence() }
            .filterIsInstance<Inet4Address>()
            .firstOrNull { !it.isLoopbackAddress && !it.isLinkLocalAddress }
            ?.hostAddress
    }.getOrNull()
}
