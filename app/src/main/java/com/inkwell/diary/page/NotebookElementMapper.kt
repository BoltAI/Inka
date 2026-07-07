package com.inkwell.diary.page

import com.inkwell.diary.data.Exchange
import com.inkwell.diary.data.InkElement
import com.inkwell.diary.data.NotebookElement
import com.inkwell.diary.data.ReplyElement
import com.inkwell.diary.data.SketchElement

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
                ),
            )
        }
        reply?.text?.takeIf { it.isNotBlank() }?.let { text ->
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
