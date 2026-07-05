package com.inkwell.diary.data

import com.inkwell.diary.brain.AnthropicMessage
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class Notebook(
    val id: String,
    val personaId: String,
    val createdAt: Long,
    val updatedAt: Long,
    val schemaVersion: Int = CURRENT_SCHEMA_VERSION,
    val pages: List<NotebookPage> = listOf(NotebookPage(index = 0)),
) {
    fun lastPage(): NotebookPage = pages.maxByOrNull { it.index } ?: NotebookPage(index = 0)

    fun withPage(page: NotebookPage, updatedAt: Long): Notebook {
        val replaced = pages.filterNot { it.index == page.index } + page
        return copy(
            updatedAt = updatedAt,
            pages = replaced.sortedBy { it.index }.ifEmpty { listOf(NotebookPage(index = 0)) },
        )
    }

    fun withFreshPage(updatedAt: Long): Notebook {
        val nextIndex = (pages.maxOfOrNull { it.index } ?: -1) + 1
        return copy(
            updatedAt = updatedAt,
            pages = pages + NotebookPage(index = nextIndex),
        )
    }

    fun withoutRedundantTrailingBlankPages(): Notebook {
        val sorted = pages.sortedBy { it.index }.ifEmpty { listOf(NotebookPage(index = 0)) }
        var keepCount = sorted.size
        while (
            keepCount > 1 &&
            sorted[keepCount - 1].elements.isEmpty() &&
            sorted[keepCount - 2].elements.isEmpty()
        ) {
            keepCount -= 1
        }
        val trimmed = sorted.take(keepCount)
        return if (trimmed == pages) this else copy(pages = trimmed)
    }
}

@Serializable
data class NotebookPage(
    val index: Int,
    val elements: List<NotebookElement> = emptyList(),
) {
    fun addElement(element: NotebookElement): NotebookPage = copy(elements = elements + element)
}

@Serializable
sealed class NotebookElement {
    abstract val createdAt: Long
}

@Serializable
@SerialName("ink")
data class InkElement(
    val strokes: List<InkStroke>,
    val committedAt: Long,
    val recognizedText: String,
) : NotebookElement() {
    override val createdAt: Long = committedAt
}

@Serializable
@SerialName("reply")
data class ReplyElement(
    val text: String,
    val personaId: String,
    override val createdAt: Long,
) : NotebookElement()

fun Notebook.rebuildApiHistory(maxTurns: Int = MAX_API_TURNS): List<AnthropicMessage> {
    val messages = mutableListOf<AnthropicMessage>()
    pages.sortedBy { it.index }
        .flatMap { it.elements }
        .forEach { element ->
            val next = when (element) {
                is InkElement -> element.recognizedText
                    .takeIf { it.isNotBlank() }
                    ?.let { AnthropicMessage(role = "user", content = it) }
                is ReplyElement -> element.text
                    .takeIf { it.isNotBlank() }
                    ?.let { AnthropicMessage(role = "assistant", content = it) }
            }
            if (next != null) {
                val last = messages.lastOrNull()
                if (last?.role == next.role) {
                    messages[messages.lastIndex] = last.copy(
                        content = joinAdjacentContent(last.content, next.content, next.role),
                    )
                } else {
                    messages.add(next)
                }
            }
        }
    return messages.takeLast(maxTurns.coerceAtLeast(1) * 2)
}

private fun joinAdjacentContent(left: String, right: String, role: String): String {
    if (left.isBlank()) return right
    if (right.isBlank()) return left
    val separator = when {
        role == "assistant" -> ""
        left.last().isWhitespace() || right.first().isWhitespace() -> ""
        else -> " "
    }
    return "$left$separator$right"
}

const val CURRENT_SCHEMA_VERSION = 1
const val MAX_API_TURNS = 20
