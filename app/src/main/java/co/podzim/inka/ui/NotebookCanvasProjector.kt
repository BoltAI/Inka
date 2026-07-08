package co.podzim.inka.ui

import co.podzim.inka.data.InkElement
import co.podzim.inka.data.InkStroke
import co.podzim.inka.data.Notebook
import co.podzim.inka.data.NotebookElement
import co.podzim.inka.data.ReplyElement
import co.podzim.inka.data.SketchElement

internal object NotebookCanvasProjector {
    fun latestCanvasId(notebook: Notebook): String? {
        return notebook.exchanges.sortedBy { it.committedAt }.lastOrNull { it.canvasId != null }?.canvasId
    }

    fun canvasElements(notebook: Notebook, canvasId: String): List<NotebookElement> {
        return notebook.exchanges
            .asSequence()
            .filter { it.canvasId == canvasId }
            .sortedBy { it.committedAt }
            .flatMap { exchange ->
                sequence {
                    exchange.ink?.let { ink ->
                        yield(
                            InkElement(
                                strokes = ink.strokes,
                                committedAt = exchange.committedAt,
                                recognizedText = ink.recognizedText,
                            ),
                        )
                    }
                    exchange.reply?.sketch?.let { sketch ->
                        yield(
                            SketchElement(
                                strokes = sketch.strokes,
                                personaId = exchange.reply.personaId,
                                createdAt = exchange.reply.createdAt,
                            ),
                        )
                    }
                    exchange.reply?.text?.takeIf { exchange.reply.displayText && it.isNotBlank() }?.let { text ->
                        yield(
                            ReplyElement(
                                text = text,
                                personaId = exchange.reply.personaId,
                                createdAt = exchange.reply.createdAt,
                            ),
                        )
                    }
                }
            }
            .toList()
    }

    fun currentInkElement(strokes: List<InkStroke>, committedAt: Long): InkElement {
        return InkElement(
            strokes = strokes,
            committedAt = committedAt,
            recognizedText = "",
        )
    }
}
