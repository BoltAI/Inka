package co.podzim.inka.ui

import co.podzim.inka.data.DEFAULT_NOTEBOOK_ID
import co.podzim.inka.data.DEFAULT_NOTEBOOK_TITLE
import co.podzim.inka.data.Exchange
import co.podzim.inka.data.InkElement
import co.podzim.inka.data.InkPoint
import co.podzim.inka.data.InkStroke
import co.podzim.inka.data.Notebook
import co.podzim.inka.data.NotebookInk
import co.podzim.inka.data.NotebookReply
import co.podzim.inka.data.NotebookSketch
import co.podzim.inka.data.Persona
import co.podzim.inka.data.ReplyElement
import co.podzim.inka.data.SketchElement
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class NotebookCanvasProjectorTest {
    @Test
    fun `latest canvas id comes from the newest committed canvas exchange`() {
        val notebook = notebook(
            listOf(
                exchange(committedAt = 30L, canvasId = "older-canvas"),
                exchange(committedAt = 40L, canvasId = null),
                exchange(committedAt = 20L, canvasId = "oldest-canvas"),
                exchange(committedAt = 50L, canvasId = "newest-canvas"),
            ),
        )

        assertEquals("newest-canvas", NotebookCanvasProjector.latestCanvasId(notebook))
    }

    @Test
    fun `canvas elements preserve existing ink sketch reply ordering`() {
        val userStroke = stroke(10f)
        val sketchStroke = stroke(20f)
        val ignoredStroke = stroke(30f)
        val notebook = notebook(
            listOf(
                exchange(
                    committedAt = 30L,
                    canvasId = "canvas-a",
                    ink = NotebookInk(listOf(userStroke), "second prompt"),
                    reply = NotebookReply(
                        text = "second reply",
                        sketch = NotebookSketch(listOf(sketchStroke)),
                        personaId = Persona.Whisper.name,
                        createdAt = 31L,
                    ),
                ),
                exchange(
                    committedAt = 20L,
                    canvasId = "canvas-b",
                    ink = NotebookInk(listOf(ignoredStroke), "ignored"),
                ),
                exchange(
                    committedAt = 10L,
                    canvasId = "canvas-a",
                    ink = NotebookInk(listOf(userStroke), "first prompt"),
                    reply = NotebookReply(
                        text = "",
                        sketch = NotebookSketch(listOf(sketchStroke)),
                        personaId = Persona.Muse.name,
                        createdAt = 11L,
                    ),
                ),
            ),
        )

        val elements = NotebookCanvasProjector.canvasElements(notebook, "canvas-a")

        assertEquals(5, elements.size)
        assertTrue(elements[0] is InkElement)
        assertEquals("first prompt", (elements[0] as InkElement).recognizedText)
        assertTrue(elements[1] is SketchElement)
        assertEquals(Persona.Muse.name, (elements[1] as SketchElement).personaId)
        assertTrue(elements[2] is InkElement)
        assertEquals("second prompt", (elements[2] as InkElement).recognizedText)
        assertTrue(elements[3] is SketchElement)
        assertEquals(Persona.Whisper.name, (elements[3] as SketchElement).personaId)
        assertTrue(elements[4] is ReplyElement)
        assertEquals("second reply", (elements[4] as ReplyElement).text)
    }

    @Test
    fun `canvas elements can hide stored text when reply is rendered as ink`() {
        val userStroke = stroke(10f)
        val sketchStroke = stroke(20f)
        val notebook = notebook(
            listOf(
                exchange(
                    committedAt = 30L,
                    canvasId = "canvas-a",
                    ink = NotebookInk(listOf(userStroke), "say hello"),
                    reply = NotebookReply(
                        text = "hello",
                        sketch = NotebookSketch(listOf(sketchStroke)),
                        displayText = false,
                        personaId = Persona.Whisper.name,
                        createdAt = 31L,
                    ),
                ),
            ),
        )

        val elements = NotebookCanvasProjector.canvasElements(notebook, "canvas-a")

        assertEquals(2, elements.size)
        assertTrue(elements[0] is InkElement)
        assertTrue(elements[1] is SketchElement)
    }

    @Test
    fun `current ink element uses caller timestamp and blank recognition`() {
        val stroke = stroke(12f)

        val element = NotebookCanvasProjector.currentInkElement(listOf(stroke), committedAt = 1234L)

        assertEquals(1234L, element.committedAt)
        assertEquals("", element.recognizedText)
        assertEquals(listOf(stroke), element.strokes)
    }

    private fun notebook(exchanges: List<Exchange>): Notebook {
        return Notebook(
            id = DEFAULT_NOTEBOOK_ID,
            title = DEFAULT_NOTEBOOK_TITLE,
            personaId = Persona.Whisper.name,
            createdAt = 1L,
            updatedAt = 2L,
            exchanges = exchanges,
        )
    }

    private fun exchange(
        committedAt: Long,
        canvasId: String?,
        ink: NotebookInk? = null,
        reply: NotebookReply? = null,
    ): Exchange {
        return Exchange(
            id = "exchange-$committedAt",
            committedAt = committedAt,
            canvasId = canvasId,
            ink = ink,
            reply = reply,
        )
    }

    private fun stroke(offset: Float): InkStroke {
        return InkStroke(
            listOf(
                InkPoint(x = offset, y = offset, pressure = 1f, timestampMs = offset.toLong()),
                InkPoint(x = offset + 1f, y = offset + 1f, pressure = 1f, timestampMs = offset.toLong() + 1L),
            ),
        )
    }
}
