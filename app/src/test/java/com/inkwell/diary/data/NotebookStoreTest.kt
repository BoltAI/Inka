package com.inkwell.diary.data

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class NotebookStoreTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun `round trips notebook as one json file per persona`() = runTest {
        val store = NotebookStore(temporaryFolder.root) { 1000L }
        val notebook = store.loadOrCreate(Persona.Muse)
        val ink = InkElement(
            strokes = listOf(
                InkStroke(
                    listOf(
                        InkPoint(x = 1f, y = 2f, pressure = 0.5f, timestampMs = 10L),
                        InkPoint(
                            x = 3f,
                            y = 4f,
                            pressure = 0.7f,
                            timestampMs = 20L,
                            size = 0.8f,
                            tiltX = 12,
                            tiltY = -4,
                        ),
                    ),
                ),
            ),
            committedAt = 1100L,
            recognizedText = "a small door",
        )
        val saved = notebook.withPage(
            notebook.lastPage()
                .addElement(ink)
                .addElement(ReplyElement(text = "It opened inward.", personaId = Persona.Muse.name, createdAt = 1200L)),
            updatedAt = 1200L,
        )

        store.save(saved)

        val file = store.fileFor(Persona.Muse)
        assertTrue(file.exists())
        assertTrue(file.readText().contains("\"personaId\": \"Muse\""))
        assertTrue(file.readText().contains("\"tiltX\": 12"))

        val loaded = (store.load(Persona.Muse) as NotebookLoadResult.Ready).notebook
        assertEquals(saved, loaded)
    }

    @Test
    fun `round trips large stroke pages`() = runTest {
        val store = NotebookStore(temporaryFolder.root) { 2000L }
        val points = (0 until 10_000).map { index ->
            InkPoint(x = index.toFloat(), y = (index % 100).toFloat(), pressure = 1f, timestampMs = index.toLong())
        }
        val notebook = store.loadOrCreate(Persona.Scholar)
        val saved = notebook.withPage(
            notebook.lastPage().addElement(
                InkElement(
                    strokes = listOf(InkStroke(points)),
                    committedAt = 2100L,
                    recognizedText = "large page",
                ),
            ),
            updatedAt = 2100L,
        )

        store.save(saved)

        val loaded = (store.load(Persona.Scholar) as NotebookLoadResult.Ready).notebook
        val element = loaded.lastPage().elements.single() as InkElement
        assertEquals(10_000, element.strokes.single().points.size)
        assertEquals(9999f, element.strokes.single().points.last().x)
    }

    @Test
    fun `unknown schema is refused without deleting the notebook`() {
        val store = NotebookStore(temporaryFolder.root)
        val file = store.fileFor(Persona.Storyteller)
        file.parentFile?.mkdirs()
        file.writeText(
            """
            {
              "id": "Storyteller",
              "personaId": "Storyteller",
              "createdAt": 1,
              "updatedAt": 1,
              "schemaVersion": 99,
              "pages": []
            }
            """.trimIndent(),
        )

        val result = store.load(Persona.Storyteller)

        assertEquals(NotebookLoadResult.UnsupportedSchema(99), result)
        assertTrue(file.exists())
    }

    @Test
    fun `burn deletes the persona notebook and returns a fresh notebook`() = runTest {
        val store = NotebookStore(temporaryFolder.root) { 3000L }
        store.save(store.loadOrCreate(Persona.Wit))
        assertTrue(store.fileFor(Persona.Wit).exists())

        val fresh = store.burn(Persona.Wit)

        assertFalse(store.fileFor(Persona.Wit).exists())
        assertEquals(Persona.Wit.name, fresh.personaId)
        assertEquals(3000L, fresh.createdAt)
        assertEquals(1, fresh.pages.size)
        assertTrue(fresh.lastPage().elements.isEmpty())
    }

    @Test
    fun `load trims redundant blank trailing pages but keeps one writable page`() = runTest {
        val store = NotebookStore(temporaryFolder.root) { 4000L }
        val notebook = Notebook(
            id = Persona.Storyteller.name,
            personaId = Persona.Storyteller.name,
            createdAt = 1L,
            updatedAt = 2L,
            pages = listOf(
                NotebookPage(index = 0).addElement(
                    ReplyElement(
                        text = "Once there was a page.",
                        personaId = Persona.Storyteller.name,
                        createdAt = 3L,
                    ),
                ),
                NotebookPage(index = 1),
                NotebookPage(index = 2),
            ),
        )
        store.save(notebook)

        val loaded = (store.load(Persona.Storyteller) as NotebookLoadResult.Ready).notebook

        assertEquals(listOf(0, 1), loaded.pages.map { it.index })
        assertTrue(loaded.lastPage().elements.isEmpty())
    }

    @Test
    fun `api history rebuild keeps the last twenty turns`() {
        val pages = (0 until 25).map { index ->
            NotebookPage(index = index).addElement(
                InkElement(
                    strokes = emptyList(),
                    committedAt = index * 2L,
                    recognizedText = "question $index",
                ),
            ).addElement(
                ReplyElement(
                    text = "answer $index",
                    personaId = Persona.Confidant.name,
                    createdAt = index * 2L + 1L,
                ),
            )
        }
        val notebook = Notebook(
            id = Persona.Confidant.name,
            personaId = Persona.Confidant.name,
            createdAt = 1L,
            updatedAt = 2L,
            pages = pages,
        )

        val history = notebook.rebuildApiHistory()

        assertEquals(40, history.size)
        assertEquals("question 5", history.first().content)
        assertEquals("answer 24", history.last().content)
    }

    @Test
    fun `api history rebuild merges reply chunks split across pages`() {
        val notebook = Notebook(
            id = Persona.Storyteller.name,
            personaId = Persona.Storyteller.name,
            createdAt = 1L,
            updatedAt = 2L,
            pages = listOf(
                NotebookPage(index = 0)
                    .addElement(
                        InkElement(
                            strokes = emptyList(),
                            committedAt = 10L,
                            recognizedText = "Continue the tale",
                        ),
                    )
                    .addElement(
                        ReplyElement(
                            text = "The door opened",
                            personaId = Persona.Storyteller.name,
                            createdAt = 11L,
                        ),
                    ),
                NotebookPage(index = 1)
                    .addElement(
                        ReplyElement(
                            text = " into moonlight.",
                            personaId = Persona.Storyteller.name,
                            createdAt = 12L,
                        ),
                    ),
            ),
        )

        val history = notebook.rebuildApiHistory()

        assertEquals(listOf("user", "assistant"), history.map { it.role })
        assertEquals("The door opened into moonlight.", history.last().content)
    }

    @Test
    fun `api history rebuild separates consecutive user turns without replies`() {
        val notebook = Notebook(
            id = Persona.Muse.name,
            personaId = Persona.Muse.name,
            createdAt = 1L,
            updatedAt = 2L,
            pages = listOf(
                NotebookPage(index = 0)
                    .addElement(
                        InkElement(
                            strokes = emptyList(),
                            committedAt = 10L,
                            recognizedText = "first unfinished thought",
                        ),
                    )
                    .addElement(
                        InkElement(
                            strokes = emptyList(),
                            committedAt = 11L,
                            recognizedText = "second line after a failed reply",
                        ),
                    ),
            ),
        )

        val history = notebook.rebuildApiHistory()

        assertEquals(listOf("user"), history.map { it.role })
        assertEquals("first unfinished thought second line after a failed reply", history.single().content)
    }

    @Test
    fun `api history rebuild joins cross-page user segments in page order`() {
        val notebook = Notebook(
            id = Persona.Scholar.name,
            personaId = Persona.Scholar.name,
            createdAt = 1L,
            updatedAt = 2L,
            pages = listOf(
                NotebookPage(index = 1).addElement(
                    InkElement(
                        strokes = emptyList(),
                        committedAt = 11L,
                        recognizedText = "continued on page two",
                    ),
                ),
                NotebookPage(index = 0).addElement(
                    InkElement(
                        strokes = emptyList(),
                        committedAt = 10L,
                        recognizedText = "started on page one",
                    ),
                ),
            ),
        )

        val history = notebook.rebuildApiHistory()

        assertEquals(listOf("user"), history.map { it.role })
        assertEquals("started on page one continued on page two", history.single().content)
    }

}
