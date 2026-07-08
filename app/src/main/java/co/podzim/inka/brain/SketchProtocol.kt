package co.podzim.inka.brain

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

data class PageSnapshot(
    val pngBase64: String,
    val width: Int,
    val height: Int,
    val pageWidth: Int,
    val pageHeight: Int,
)

@Serializable
data class DrawToolPayload(
    val paths: List<DrawPathPayload> = emptyList(),
    @SerialName("page_text_transcript")
    val pageTextTranscript: String = "",
)

@Serializable
data class DrawPathPayload(
    val d: String = "",
    val role: String = "",
)

sealed class DrawingReplyResult {
    data class Success(
        val paths: List<String>,
        val pageTextTranscript: String,
    ) : DrawingReplyResult()

    data class Failure(val kind: BrainErrorKind, val detail: String? = null) : DrawingReplyResult()
}

const val DRAWING_USER_TURN_TEMPLATE =
    "The image is the current page of the notebook, %dx%d pixels. Your previous drawings, if any, appear in a lighter/different ink. The writer has just added new marks. Reply by calling the draw tool. Draw in image coordinates. Do not redraw or trace over existing marks except where a line must connect to them. Leave the writer's marks untouched."

const val DRAWING_SYSTEM_PROMPT =
    "You reply only by drawing. Do not write words, captions, labels, letters, or text of any kind. Draw simple, confident, single-line ink sketches - the hand of a quick, charming sketch artist, not a printer. Complete, extend, answer, or annotate what the writer drew or asked; match the scale and style of their marks; place your drawing in empty space near theirs. Use between 3 and 40 strokes; prefer a few expressive curves over many small ones. Fills, hatching, and photorealism are not your medium. If the writer only wrote words, draw a visual answer to those words. Emit tool input with paths first, one complete path object at a time, then page_text_transcript last."
