package co.podzim.inka.handwriting

import co.podzim.inka.data.InkPoint
import co.podzim.inka.data.InkStroke
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.Call
import okhttp3.Callback
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import java.io.IOException
import java.io.InterruptedIOException
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume

@Serializable
data class HandwritingSynthesisRequest(
    val text: String,
    val pageWidth: Int,
    val pageHeight: Int,
    val left: Int,
    val top: Int,
    val maxWidth: Int,
    // Wire name kept for existing servers; value is the target text size in page pixels.
    val fontSizeSp: Float,
    val strokeWidthMm: Float = DEFAULT_SYNTHESIZED_HANDWRITING_STROKE_WIDTH_MM,
    val style: String = DEFAULT_STYLE,
    val seed: Long? = null,
)

fun handwritingSynthesisFontSizePx(fontSizeSp: Float, scaledDensity: Float): Float {
    val safeSize = fontSizeSp.takeIf { it.isFinite() && it > 0f } ?: DEFAULT_SYNTHESIS_FONT_SIZE_SP
    val safeDensity = scaledDensity.takeIf { it.isFinite() && it > 0f } ?: 1f
    return (safeSize * safeDensity).coerceIn(
        MIN_SYNTHESIS_FONT_SIZE_PX,
        MAX_SYNTHESIS_FONT_SIZE_PX,
    )
}

sealed class HandwritingSynthesisResult {
    data class Success(val strokes: List<InkStroke>) : HandwritingSynthesisResult()
    data class Failure(val message: String) : HandwritingSynthesisResult()
}

interface HandwritingSynthesisClient {
    suspend fun synthesize(
        serverUrl: String,
        request: HandwritingSynthesisRequest,
    ): HandwritingSynthesisResult
}

class OkHttpHandwritingSynthesisClient(
    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(5, TimeUnit.SECONDS)
        .readTimeout(90, TimeUnit.SECONDS)
        .writeTimeout(10, TimeUnit.SECONDS)
        .build(),
    private val json: Json = Json {
        ignoreUnknownKeys = true
        explicitNulls = false
    },
) : HandwritingSynthesisClient {
    override suspend fun synthesize(
        serverUrl: String,
        request: HandwritingSynthesisRequest,
    ): HandwritingSynthesisResult {
        val endpoint = endpointFor(serverUrl)
            ?: return HandwritingSynthesisResult.Failure("Server URL is missing or invalid")
        val body = json.encodeToString(HandwritingSynthesisRequest.serializer(), request)
            .toRequestBody(JSON_MEDIA_TYPE)
        val httpRequest = Request.Builder()
            .url(endpoint)
            .header("content-type", "application/json")
            .post(body)
            .build()

        return suspendCancellableCoroutine { continuation ->
            val call = client.newCall(httpRequest)
            continuation.invokeOnCancellation {
                call.cancel()
            }
            call.enqueue(
                object : Callback {
                    override fun onFailure(call: Call, error: IOException) {
                        if (!continuation.isActive) return
                        continuation.resume(error.toFailure())
                    }

                    override fun onResponse(call: Call, response: Response) {
                        val result = try {
                            response.use { decodeResponse(it, request) }
                        } catch (error: InterruptedIOException) {
                            error.toFailure()
                        } catch (error: IOException) {
                            error.toFailure()
                        } catch (error: Exception) {
                            error.toFailure()
                        }
                        if (continuation.isActive) {
                            continuation.resume(result)
                        }
                    }
                },
            )
        }
    }

    private fun decodeResponse(
        response: Response,
        request: HandwritingSynthesisRequest,
    ): HandwritingSynthesisResult {
        val responseText = response.body?.string().orEmpty()
        if (!response.isSuccessful) {
            return HandwritingSynthesisResult.Failure("HTTP ${response.code}")
        }
        val decoded = json.decodeFromString(HandwritingSynthesisResponse.serializer(), responseText)
        val strokeWidthMm = request.strokeWidthMm
            .takeIf { it.isFinite() }
            ?.coerceIn(MIN_SYNTHESIZED_HANDWRITING_STROKE_WIDTH_MM, MAX_SYNTHESIZED_HANDWRITING_STROKE_WIDTH_MM)
            ?: DEFAULT_SYNTHESIZED_HANDWRITING_STROKE_WIDTH_MM
        val strokes = decoded.strokes.mapNotNull {
            it.toInkStroke(
                pageWidth = request.pageWidth,
                pageHeight = request.pageHeight,
                strokeWidthMm = strokeWidthMm,
            )
        }
        return if (strokes.isEmpty()) {
            HandwritingSynthesisResult.Failure("Server returned no strokes")
        } else {
            HandwritingSynthesisResult.Success(strokes)
        }
    }

    private fun Exception.toFailure(): HandwritingSynthesisResult.Failure {
        return HandwritingSynthesisResult.Failure(this::class.java.simpleName)
    }

    private fun endpointFor(serverUrl: String): String? {
        val cleaned = serverUrl.trim()
        if (cleaned.isBlank()) return null
        val withScheme = if ("://" in cleaned) cleaned else "http://$cleaned"
        val base = withScheme.trimEnd('/')
        return if (base.endsWith(HANDWRITING_PATH)) base else "$base$HANDWRITING_PATH"
    }

    private fun HandwritingStrokePayload.toInkStroke(
        pageWidth: Int,
        pageHeight: Int,
        strokeWidthMm: Float,
    ): InkStroke? {
        val converted = points.mapNotNull { point ->
            if (!point.x.isFinite() || !point.y.isFinite()) return@mapNotNull null
            InkPoint(
                x = point.x.coerceIn(0f, pageWidth.toFloat()),
                y = point.y.coerceIn(0f, pageHeight.toFloat()),
                pressure = point.pressure.takeIf { it.isFinite() }?.coerceIn(0.1f, 1f) ?: DEFAULT_PRESSURE,
                timestampMs = point.timestampMs.coerceAtLeast(0L),
            )
        }
        return if (converted.isEmpty()) {
            null
        } else {
            InkStroke(converted, strokeWidthMm = strokeWidthMm)
        }
    }

    private companion object {
        private const val HANDWRITING_PATH = "/handwriting"
        private const val DEFAULT_PRESSURE = 0.55f
        private val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()
    }
}

@Serializable
private data class HandwritingSynthesisResponse(
    val strokes: List<HandwritingStrokePayload> = emptyList(),
)

@Serializable
private data class HandwritingStrokePayload(
    val points: List<HandwritingPointPayload> = emptyList(),
)

@Serializable
private data class HandwritingPointPayload(
    val x: Float,
    val y: Float,
    val pressure: Float = 0.55f,
    @SerialName("t")
    val timestampMs: Long = 0L,
)

private const val DEFAULT_STYLE = "default"
private const val DEFAULT_SYNTHESIS_FONT_SIZE_SP = 40f
private const val MIN_SYNTHESIS_FONT_SIZE_PX = 28f
private const val MAX_SYNTHESIS_FONT_SIZE_PX = 110f

const val DEFAULT_SYNTHESIZED_HANDWRITING_STROKE_WIDTH_MM = 0.30f
const val MIN_SYNTHESIZED_HANDWRITING_STROKE_WIDTH_MM = 0.12f
const val MAX_SYNTHESIZED_HANDWRITING_STROKE_WIDTH_MM = 0.80f
