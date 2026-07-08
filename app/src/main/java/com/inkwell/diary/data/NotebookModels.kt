package com.inkwell.diary.data

import com.inkwell.diary.brain.AnthropicMessage
import kotlinx.serialization.Serializable

@Serializable
data class Notebook(
    val id: String,
    val title: String,
    val personaId: String,
    val createdAt: Long,
    val updatedAt: Long,
    val schemaVersion: Int = CURRENT_SCHEMA_VERSION,
    val exchanges: List<Exchange> = emptyList(),
) {
    fun withExchange(exchange: Exchange, updatedAt: Long): Notebook {
        val replaced = exchanges.filterNot { it.id == exchange.id } + exchange
        return copy(
            updatedAt = updatedAt,
            exchanges = replaced.sortedBy { it.committedAt },
        )
    }

    fun withPersona(persona: Persona, updatedAt: Long): Notebook {
        return copy(personaId = persona.name, updatedAt = updatedAt)
    }

    fun withTitle(title: String, updatedAt: Long): Notebook {
        return copy(title = title.ifBlank { DEFAULT_NOTEBOOK_TITLE }, updatedAt = updatedAt)
    }
}

@Serializable
data class Exchange(
    val id: String,
    val committedAt: Long,
    val canvasId: String? = null,
    val ink: NotebookInk? = null,
    val reply: NotebookReply? = null,
)

@Serializable
data class NotebookInk(
    val strokes: List<InkStroke>,
    val recognizedText: String,
)

@Serializable
data class NotebookReply(
    val text: String = "",
    val sketch: NotebookSketch? = null,
    val displayText: Boolean = true,
    val personaId: String,
    val createdAt: Long,
)

@Serializable
data class NotebookSketch(
    val strokes: List<InkStroke>,
)

data class NotebookPage(
    val index: Int,
    val elements: List<NotebookElement> = emptyList(),
) {
    fun addElement(element: NotebookElement): NotebookPage = copy(elements = elements + element)
}

sealed class NotebookElement {
    abstract val createdAt: Long
}

data class InkElement(
    val strokes: List<InkStroke>,
    val committedAt: Long,
    val recognizedText: String,
) : NotebookElement() {
    override val createdAt: Long = committedAt
}

data class ReplyElement(
    val text: String,
    val personaId: String,
    override val createdAt: Long,
) : NotebookElement()

data class SketchElement(
    val strokes: List<InkStroke>,
    val personaId: String,
    override val createdAt: Long,
) : NotebookElement()

fun Notebook.rebuildApiHistory(maxTurns: Int = MAX_API_TURNS): List<AnthropicMessage> {
    val messages = mutableListOf<AnthropicMessage>()
    exchanges.sortedBy { it.committedAt }
        .filter { exchange ->
            exchange.ink?.recognizedText?.isNotBlank() == true ||
                exchange.reply?.text?.isNotBlank() == true
        }
        .takeLast(maxTurns.coerceAtLeast(1))
        .forEach { exchange ->
            exchange.ink?.recognizedText
                ?.takeIf { it.isNotBlank() }
                ?.let { messages.add(AnthropicMessage(role = "user", content = it)) }
            exchange.reply?.text
                ?.takeIf { it.isNotBlank() }
                ?.let { messages.add(AnthropicMessage(role = "assistant", content = it)) }
        }
    return messages
}

const val CURRENT_SCHEMA_VERSION = 3
const val MAX_API_TURNS = 20
const val DEFAULT_NOTEBOOK_ID = "default"
const val DEFAULT_NOTEBOOK_TITLE = "Inka's Diary"

fun newExchangeId(committedAt: Long): String = "exchange-$committedAt"

fun newCanvasId(createdAt: Long): String = "canvas-$createdAt"
