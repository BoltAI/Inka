package co.podzim.inka.page

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.view.View
import co.podzim.inka.data.DEFAULT_NOTEBOOK_ID
import co.podzim.inka.data.DEFAULT_NOTEBOOK_TITLE
import co.podzim.inka.data.Exchange
import co.podzim.inka.data.InkElement
import co.podzim.inka.data.InkPoint
import co.podzim.inka.data.InkStroke
import co.podzim.inka.data.Notebook
import co.podzim.inka.data.NotebookInk
import co.podzim.inka.data.NotebookPage
import co.podzim.inka.data.NotebookReply
import co.podzim.inka.data.NotebookSketch
import co.podzim.inka.data.Persona
import co.podzim.inka.data.ReplyElement
import co.podzim.inka.data.SketchElement
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
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
                    reply = NotebookReply(
                        text = reply,
                        personaId = Persona.Storyteller.name,
                        createdAt = 11L,
                    ),
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

    @Test
    fun `history pages composite exchanges with the same canvas id`() {
        val renderer = PageRenderer(RuntimeEnvironment.getApplication())
        val userStroke = InkStroke(
            listOf(
                InkPoint(x = 10f, y = 10f, pressure = 1f, timestampMs = 1L),
                InkPoint(x = 20f, y = 20f, pressure = 1f, timestampMs = 2L),
            ),
        )
        val aiStroke = InkStroke(
            listOf(
                InkPoint(x = 30f, y = 30f, pressure = 1f, timestampMs = 3L),
                InkPoint(x = 40f, y = 40f, pressure = 1f, timestampMs = 4L),
            ),
        )
        val notebook = notebook(
            exchanges = listOf(
                Exchange(
                    id = "exchange-10",
                    committedAt = 10L,
                    canvasId = "canvas-1",
                    ink = NotebookInk(listOf(userStroke), "finish this"),
                    reply = NotebookReply(
                        text = "Done.",
                        sketch = NotebookSketch(listOf(aiStroke)),
                        personaId = Persona.Wit.name,
                        createdAt = 11L,
                    ),
                ),
                Exchange(
                    id = "exchange-20",
                    committedAt = 20L,
                    canvasId = "canvas-1",
                    ink = NotebookInk(listOf(userStroke), "add a hat"),
                    reply = NotebookReply(
                        sketch = NotebookSketch(listOf(aiStroke)),
                        personaId = Persona.Wit.name,
                        createdAt = 21L,
                    ),
                ),
            ),
        )

        val pages = renderer.historyPagesFor(notebook)

        assertEquals(1, pages.size)
        assertEquals(2, pages.single().elements.filterIsInstance<InkElement>().size)
        assertEquals(2, pages.single().elements.filterIsInstance<SketchElement>().size)
        assertEquals(1, pages.single().elements.filterIsInstance<ReplyElement>().size)
    }

    @Test
    fun `history pages render generated handwriting strokes without duplicate reply text`() {
        val renderer = PageRenderer(RuntimeEnvironment.getApplication())
        val userStroke = InkStroke(
            listOf(
                InkPoint(x = 10f, y = 10f, pressure = 1f, timestampMs = 1L),
                InkPoint(x = 20f, y = 20f, pressure = 1f, timestampMs = 2L),
            ),
        )
        val aiStroke = InkStroke(
            listOf(
                InkPoint(x = 30f, y = 30f, pressure = 1f, timestampMs = 3L),
                InkPoint(x = 40f, y = 40f, pressure = 1f, timestampMs = 4L),
            ),
        )
        val notebook = notebook(
            exchanges = listOf(
                Exchange(
                    id = "exchange-10",
                    committedAt = 10L,
                    ink = NotebookInk(listOf(userStroke), "say hello"),
                    reply = NotebookReply(
                        text = "hello",
                        sketch = NotebookSketch(listOf(aiStroke)),
                        displayText = false,
                        personaId = Persona.Wit.name,
                        createdAt = 11L,
                    ),
                ),
            ),
        )

        val pages = renderer.historyPagesFor(notebook)

        assertEquals(1, pages.size)
        assertEquals(1, pages.single().elements.filterIsInstance<InkElement>().size)
        val sketch = pages.single().elements.filterIsInstance<SketchElement>().single()
        assertTrue(sketch.flowAfterPrevious)
        assertEquals(0, pages.single().elements.filterIsInstance<ReplyElement>().size)
    }

    @Test
    fun `history pages flow generated handwriting below prompt ink`() {
        val context = RuntimeEnvironment.getApplication()
        val view = PageCanvasView(context).apply {
            measure(
                View.MeasureSpec.makeMeasureSpec(420, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(320, View.MeasureSpec.EXACTLY),
            )
            layout(0, 0, 420, 320)
        }
        val renderer = PageRenderer(context).apply {
            attach(view, width = 420, height = 320)
        }
        val userStroke = InkStroke(
            listOf(
                InkPoint(x = 60f, y = 70f, pressure = 1f, timestampMs = 1L),
                InkPoint(x = 360f, y = 90f, pressure = 1f, timestampMs = 2L),
            ),
        )
        val generatedStroke = InkStroke(
            listOf(
                InkPoint(x = 60f, y = 40f, pressure = 1f, timestampMs = 3L),
                InkPoint(x = 360f, y = 50f, pressure = 1f, timestampMs = 4L),
            ),
        )
        val exchange = Exchange(
            id = "exchange-10",
            committedAt = 10L,
            ink = NotebookInk(listOf(userStroke), "again"),
            reply = NotebookReply(
                text = "try this",
                sketch = NotebookSketch(listOf(generatedStroke)),
                displayText = false,
                personaId = Persona.Wit.name,
                createdAt = 11L,
            ),
        )

        renderer.renderNotebookPage(
            page = NotebookPage(index = 0, elements = exchange.notebookElements(Persona.Wit.name)),
            pageIndex = 0,
            pageCount = 1,
            showPageStatus = false,
        )

        val rendered = view.presentedBitmapCopy()
        assertNotNull(rendered)
        assertTrue(rendered!!.hasVisibleInkInBand(65, 100))
        assertTrue(rendered.hasVisibleInkInBand(110, 150))
        assertTrue(!rendered.hasVisibleInkInBand(30, 60))
    }

    @Test
    fun `reply writing area starts below current prompt ink`() {
        val context = RuntimeEnvironment.getApplication()
        val view = PageCanvasView(context).apply {
            measure(
                View.MeasureSpec.makeMeasureSpec(420, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(320, View.MeasureSpec.EXACTLY),
            )
            layout(0, 0, 420, 320)
        }
        val renderer = PageRenderer(context).apply {
            attach(view, width = 420, height = 320)
        }
        val promptStroke = InkStroke(
            listOf(
                InkPoint(x = 60f, y = 150f, pressure = 1f, timestampMs = 1L),
                InkPoint(x = 360f, y = 180f, pressure = 1f, timestampMs = 2L),
            ),
        )

        val area = renderer.replyWritingArea(afterStrokes = listOf(promptStroke))

        assertNotNull(area)
        assertTrue(area!!.top > 180)
    }

    @Test
    fun `generated handwriting replay paints long synthesized strokes`() = runTest {
        val context = RuntimeEnvironment.getApplication()
        val view = PageCanvasView(context).apply {
            measure(
                View.MeasureSpec.makeMeasureSpec(420, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(320, View.MeasureSpec.EXACTLY),
            )
            layout(0, 0, 420, 320)
        }
        val renderer = PageRenderer(context).apply {
            attach(view, width = 420, height = 320)
        }
        val stroke = InkStroke(
            points = List(64) { index ->
                InkPoint(
                    x = 40f + index * 4f,
                    y = 150f + if (index % 2 == 0) 0f else 2f,
                    pressure = 0.55f,
                    timestampMs = index.toLong(),
                )
            },
        )

        renderer.beginSketchReply()
        renderer.revealGeneratedHandwritingStrokes(listOf(stroke))

        val rendered = view.presentedBitmapCopy()
        assertNotNull(rendered)
        assertTrue(rendered!!.hasNonTransparentInk())
    }

    @Test
    fun `boox renderer fallback renders visible persisted ink when native replay fails`() {
        val context = RuntimeEnvironment.getApplication()
        val view = PageCanvasView(context).apply {
            measure(
                View.MeasureSpec.makeMeasureSpec(240, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(240, View.MeasureSpec.EXACTLY),
            )
            layout(0, 0, 240, 240)
        }
        var replayCalls = 0
        val renderer = PageRenderer(
            context = context,
            inkReplayRenderer = object : InkReplayRenderer {
                override fun draw(canvas: Canvas, strokes: List<InkStroke>, paint: Paint): Boolean {
                    replayCalls += 1
                    return false
                }
            },
        ).apply {
            attach(view, width = 240, height = 240)
        }
        val page = NotebookPage(
            index = 0,
            elements = listOf(
                InkElement(
                    strokes = listOf(
                        InkStroke(
                            points = listOf(
                                InkPoint(x = 24f, y = 24f, pressure = 0.8f, timestampMs = 1L),
                                InkPoint(x = 216f, y = 216f, pressure = 0.8f, timestampMs = 2L),
                            ),
                        ),
                    ),
                    committedAt = 1L,
                    recognizedText = "visible ink",
                ),
            ),
        )

        renderer.renderNotebookPage(
            page = page,
            pageIndex = 0,
            pageCount = 1,
            showPageStatus = false,
        )

        assertEquals(1, replayCalls)
        val rendered = view.presentedBitmapCopy()
        assertNotNull(rendered)
        assertTrue(rendered!!.hasVisibleInk())
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

    private fun Bitmap.hasVisibleInk(): Boolean {
        for (y in 0 until height) {
            for (x in 0 until width) {
                if (getPixel(x, y) == Color.BLACK) return true
            }
        }
        return false
    }

    private fun Bitmap.hasNonTransparentInk(): Boolean {
        for (y in 0 until height) {
            for (x in 0 until width) {
                if (Color.alpha(getPixel(x, y)) > 0) return true
            }
        }
        return false
    }

    private fun Bitmap.hasVisibleInkInBand(yStart: Int, yEnd: Int): Boolean {
        val start = yStart.coerceIn(0, height)
        val end = yEnd.coerceIn(0, height)
        for (y in start until end) {
            for (x in 0 until width) {
                if (getPixel(x, y) == Color.BLACK) return true
            }
        }
        return false
    }
}
