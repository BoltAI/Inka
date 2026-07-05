package com.inkwell.diary.page

import com.inkwell.diary.data.DEFAULT_NOTEBOOK_ID
import com.inkwell.diary.data.DEFAULT_NOTEBOOK_TITLE
import com.inkwell.diary.data.Exchange
import com.inkwell.diary.data.InkElement
import com.inkwell.diary.data.InkPoint
import com.inkwell.diary.data.InkStroke
import com.inkwell.diary.data.Notebook
import com.inkwell.diary.data.NotebookInk
import com.inkwell.diary.data.NotebookReply
import com.inkwell.diary.data.Persona
import com.inkwell.diary.data.ReplyElement
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class PageRendererTest {
    @Test
    fun `history pages split long replies without losing text`() {
        val context = RuntimeEnvironment.getApplication()
        val renderer = PageRenderer(context).apply {
            attach(PageCanvasView(context).apply { layout(0, 0, 420, 260) }, width = 420, height = 260)
        }
        val reply = List(2_000) { "word$it" }.joinToString(" ")
        val notebook = notebook(
            exchanges = listOf(
                Exchange(
                    id = "exchange-10",
                    committedAt = 10L,
                    ink = NotebookInk(
                        strokes = listOf(
                            InkStroke(
                                listOf(
                                    InkPoint(x = 120f, y = 180f, pressure = 0.8f, timestampMs = 1L),
                                    InkPoint(x = 320f, y = 220f, pressure = 0.8f, timestampMs = 2L),
                                ),
                            ),
                        ),
                        recognizedText = "tell me a long story",
                    ),
                    reply = NotebookReply(reply, Persona.Storyteller.name, createdAt = 11L),
                ),
            ),
        )

        val pages = renderer.historyPagesFor(notebook)
        val replyChunks = pages.flatMap { page -> page.elements.filterIsInstance<ReplyElement>() }
            .joinToString(" ") { it.text }
            .replace(Regex("\\s+"), " ")
            .trim()

        assertTrue(pages.size > 1)
        assertTrue(pages.first().elements.any { it is InkElement })
        assertEquals(reply, replyChunks)
    }

    @Test
    fun `history pages keep unanswered exchanges readable`() {
        val renderer = PageRenderer(RuntimeEnvironment.getApplication())
        val notebook = notebook(
            exchanges = listOf(
                Exchange(
                    id = "exchange-10",
                    committedAt = 10L,
                    ink = NotebookInk(emptyList(), "will this be remembered"),
                    reply = null,
                ),
            ),
        )

        val pages = renderer.historyPagesFor(notebook)

        assertEquals(1, pages.size)
        val ink = pages.single().elements.single() as InkElement
        assertEquals("will this be remembered", ink.recognizedText)
    }

    private fun notebook(exchanges: List<Exchange>): Notebook {
        return Notebook(
            id = DEFAULT_NOTEBOOK_ID,
            title = DEFAULT_NOTEBOOK_TITLE,
            personaId = Persona.Storyteller.name,
            createdAt = 1L,
            updatedAt = 2L,
            exchanges = exchanges,
        )
    }
}
