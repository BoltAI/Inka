package com.inkwell.diary.ui

import com.inkwell.diary.data.InkElement
import com.inkwell.diary.data.InkStroke
import com.inkwell.diary.data.Notebook
import com.inkwell.diary.data.NotebookElement
import com.inkwell.diary.data.ReplyElement
import com.inkwell.diary.data.SketchElement

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
