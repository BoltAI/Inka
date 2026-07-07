package com.inkwell.diary.page

import com.inkwell.diary.data.InkElement
import com.inkwell.diary.data.Notebook
import com.inkwell.diary.data.NotebookElement
import com.inkwell.diary.data.NotebookPage
import com.inkwell.diary.data.ReplyElement

internal class NotebookHistoryPaginator(
    private val fittingReplyChunk: (List<NotebookElement>, String) -> String,
) {
    fun pagesFor(notebook: Notebook): List<NotebookPage> {
        val pages = mutableListOf<NotebookPage>()
        val sorted = notebook.exchanges.sortedBy { it.committedAt }
        val handledCanvasIds = mutableSetOf<String>()
        sorted.forEach { exchange ->
            val canvasId = exchange.canvasId
            if (canvasId != null && handledCanvasIds.add(canvasId)) {
                pages.add(
                    NotebookPage(
                        index = pages.size,
                        elements = sorted.filter { it.canvasId == canvasId }
                            .flatMap { it.notebookElements(notebook.personaId) },
                    ),
                )
                return@forEach
            } else if (canvasId != null) {
                return@forEach
            }
            val baseElements = exchange.notebookElements(notebook.personaId).filterIsInstance<InkElement>()
            val reply = exchange.reply
            val replyText = reply?.text.orEmpty()
            if (replyText.isBlank()) {
                pages.add(NotebookPage(index = pages.size, elements = baseElements))
                return@forEach
            }

            var remaining = replyText
            var pageBase = baseElements
            while (remaining.isNotBlank()) {
                val chunk = fittingReplyChunk(pageBase, remaining)
                if (chunk.isBlank()) {
                    if (pageBase.isEmpty()) {
                        val forced = remaining.take(FORCED_REPLY_CHUNK_CHARS)
                        pages.add(
                            NotebookPage(
                                index = pages.size,
                                elements = listOf(
                                    ReplyElement(
                                        text = forced,
                                        personaId = reply?.personaId ?: notebook.personaId,
                                        createdAt = reply?.createdAt ?: exchange.committedAt,
                                    ),
                                ),
                            ),
                        )
                        remaining = remaining.drop(forced.length).trimStart()
                        continue
                    }
                    pages.add(NotebookPage(index = pages.size, elements = pageBase))
                    pageBase = emptyList()
                    continue
                }
                pages.add(
                    NotebookPage(
                        index = pages.size,
                        elements = pageBase + ReplyElement(
                            text = chunk,
                            personaId = reply?.personaId ?: notebook.personaId,
                            createdAt = reply?.createdAt ?: exchange.committedAt,
                        ),
                    ),
                )
                remaining = remaining.removePrefix(chunk).trimStart()
                pageBase = emptyList()
            }
        }
        return pages.ifEmpty { listOf(NotebookPage(index = 0)) }
    }

    private companion object {
        private const val FORCED_REPLY_CHUNK_CHARS = 480
    }
}
