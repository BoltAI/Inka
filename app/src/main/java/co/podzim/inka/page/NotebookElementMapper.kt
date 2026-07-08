package co.podzim.inka.page

import co.podzim.inka.data.Exchange
import co.podzim.inka.data.InkElement
import co.podzim.inka.data.NotebookElement
import co.podzim.inka.data.ReplyElement
import co.podzim.inka.data.SketchElement

internal fun Exchange.notebookElements(fallbackPersonaId: String): List<NotebookElement> {
    return buildList {
        ink?.let { ink ->
            add(
                InkElement(
                    strokes = ink.strokes,
                    committedAt = committedAt,
                    recognizedText = ink.recognizedText,
                ),
            )
        }
        reply?.sketch?.let { sketch ->
            add(
                SketchElement(
                    strokes = sketch.strokes,
                    personaId = reply?.personaId ?: fallbackPersonaId,
                    createdAt = reply?.createdAt ?: committedAt,
                    flowAfterPrevious = reply?.displayText == false && reply?.text?.isNotBlank() == true,
                ),
            )
        }
        reply?.text?.takeIf { reply?.displayText != false && it.isNotBlank() }?.let { text ->
            add(
                ReplyElement(
                    text = text,
                    personaId = reply?.personaId ?: fallbackPersonaId,
                    createdAt = reply?.createdAt ?: committedAt,
                ),
            )
        }
    }
}
