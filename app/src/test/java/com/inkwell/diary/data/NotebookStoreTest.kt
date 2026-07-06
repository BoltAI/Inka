package com.inkwell.diary.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class NotebookStoreTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun `round trips the active v2 notebook`() = runTest {
        val store = NotebookStore(temporaryFolder.root) { 1000L }
        val notebook = (store.loadOrCreateActive("", Persona.Muse) as NotebookLoadResult.Ready).notebook
        val saved = notebook.withExchange(
            Exchange(
                id = "exchange-1100",
                committedAt = 1100L,
                ink = NotebookInk(
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
                    recognizedText = "a small door",
                ),
                reply = NotebookReply(
                    text = "It opened inward.",
                    personaId = Persona.Muse.name,
                    createdAt = 1200L,
                ),
            ),
            updatedAt = 1200L,
        )

        store.save(saved)

        val file = store.fileFor(DEFAULT_NOTEBOOK_ID)
        assertTrue(file.exists())
        assertTrue(file.readText().contains("\"schemaVersion\": 2"))
        assertTrue(file.readText().contains("\"tiltX\": 12"))

        val loaded = (store.load(DEFAULT_NOTEBOOK_ID, Persona.Whisper) as NotebookLoadResult.Ready).notebook
        assertEquals(saved, loaded)
    }

    @Test
    fun `round trips large stroke exchanges`() = runTest {
        val store = NotebookStore(temporaryFolder.root) { 2000L }
        val points = (0 until 10_000).map { index ->
            InkPoint(x = index.toFloat(), y = (index % 100).toFloat(), pressure = 1f, timestampMs = index.toLong())
        }
        val notebook = (store.loadOrCreateActive("", Persona.Scholar) as NotebookLoadResult.Ready).notebook
        val saved = notebook.withExchange(
            Exchange(
                id = "exchange-2100",
                committedAt = 2100L,
                ink = NotebookInk(
                    strokes = listOf(InkStroke(points)),
                    recognizedText = "large page",
                ),
            ),
            updatedAt = 2100L,
        )

        store.save(saved)

        val loaded = (store.load(DEFAULT_NOTEBOOK_ID, Persona.Scholar) as NotebookLoadResult.Ready).notebook
        val ink = loaded.exchanges.single().ink!!
        assertEquals(10_000, ink.strokes.single().points.size)
        assertEquals(9999f, ink.strokes.single().points.last().x)
    }

    @Test
    fun `concurrent saves do not collide on temp files`() = runTest {
        val jobs = (0 until 24).map { index ->
            launch(Dispatchers.Default) {
                val store = NotebookStore(temporaryFolder.root) { index.toLong() }
                val notebook = Notebook(
                    id = DEFAULT_NOTEBOOK_ID,
                    title = DEFAULT_NOTEBOOK_TITLE,
                    personaId = Persona.Whisper.name,
                    createdAt = 1L,
                    updatedAt = index.toLong(),
                    exchanges = listOf(exchange(index, "question $index", "answer $index")),
                )
                store.save(notebook)
            }
        }
        jobs.joinAll()

        val store = NotebookStore(temporaryFolder.root)
        val loaded = (store.load(DEFAULT_NOTEBOOK_ID, Persona.Whisper) as NotebookLoadResult.Ready).notebook
        val leftovers = File(temporaryFolder.root, "notebooks").listFiles { file ->
            file.name.endsWith(".tmp")
        }.orEmpty()

        assertEquals(DEFAULT_NOTEBOOK_ID, loaded.id)
        assertEquals(1, loaded.exchanges.size)
        assertTrue(loaded.updatedAt in 0L..23L)
        assertTrue(leftovers.isEmpty())
    }

    @Test
    fun `unsupported schema is renamed damaged and recovered as fresh notebook`() {
        val store = NotebookStore(temporaryFolder.root) { 3000L }
        val file = store.fileFor("unsupported")
        file.parentFile?.mkdirs()
        file.writeText(
            """
            {
              "id": "unsupported",
              "title": "Unsupported",
              "personaId": "Storyteller",
              "createdAt": 1,
              "updatedAt": 1,
              "schemaVersion": 99,
              "exchanges": []
            }
            """.trimIndent(),
        )

        val result = store.load("unsupported", Persona.Storyteller)

        assertTrue(result is NotebookLoadResult.Recovered)
        val recovered = (result as NotebookLoadResult.Recovered).notebook
        assertEquals(DEFAULT_NOTEBOOK_ID, recovered.id)
        assertEquals(Persona.Storyteller.name, recovered.personaId)
        assertFalse(file.exists())
        assertTrue(File(file.parentFile, "${file.name}.damaged").exists())
    }

    @Test
    fun `corrupt active notebook is renamed damaged and recovered`() {
        val store = NotebookStore(temporaryFolder.root) { 4000L }
        val file = store.fileFor(DEFAULT_NOTEBOOK_ID)
        file.parentFile?.mkdirs()
        file.writeText("{ not json")

        val result = store.loadOrCreateActive(DEFAULT_NOTEBOOK_ID, Persona.Wit)

        assertTrue(result is NotebookLoadResult.Recovered)
        assertFalse(file.exists())
        assertTrue(File(file.parentFile, "${file.name}.damaged").exists())
        assertEquals(4000L, (result as NotebookLoadResult.Recovered).notebook.createdAt)
    }

    @Test
    fun `burn deletes active notebook and returns a fresh default notebook`() = runTest {
        val store = NotebookStore(temporaryFolder.root) { 5000L }
        val notebook = (store.loadOrCreateActive("", Persona.Wit) as NotebookLoadResult.Ready).notebook
        store.save(notebook)
        assertTrue(store.fileFor(DEFAULT_NOTEBOOK_ID).exists())

        val fresh = store.burn(DEFAULT_NOTEBOOK_ID, Persona.Wit)

        assertFalse(store.fileFor(DEFAULT_NOTEBOOK_ID).exists())
        assertEquals(DEFAULT_NOTEBOOK_ID, fresh.id)
        assertEquals(DEFAULT_NOTEBOOK_TITLE, fresh.title)
        assertEquals(Persona.Wit.name, fresh.personaId)
        assertEquals(5000L, fresh.createdAt)
        assertTrue(fresh.exchanges.isEmpty())
    }

    @Test
    fun `migrates latest v1 persona notebook without deleting old files`() {
        val store = NotebookStore(temporaryFolder.root) { 6000L }
        val notebookDir = File(temporaryFolder.root, "notebooks").apply { mkdirs() }
        val older = File(notebookDir, "Muse.json").apply {
            writeText(v1NotebookJson("Muse", updatedAt = 100L, recognizedText = "older question"))
        }
        val newer = File(notebookDir, "Scholar.json").apply {
            writeText(v1NotebookJson("Scholar", updatedAt = 200L, recognizedText = "newer question"))
        }

        val migrated = (store.loadOrCreateActive("", Persona.Whisper) as NotebookLoadResult.Ready).notebook

        assertEquals(DEFAULT_NOTEBOOK_ID, migrated.id)
        assertEquals(DEFAULT_NOTEBOOK_TITLE, migrated.title)
        assertEquals(Persona.Scholar.name, migrated.personaId)
        assertEquals(1, migrated.exchanges.size)
        assertEquals("newer question", migrated.exchanges.single().ink?.recognizedText)
        assertEquals("old reply", migrated.exchanges.single().reply?.text)
        assertTrue(older.exists())
        assertTrue(newer.exists())
        assertTrue(store.fileFor(DEFAULT_NOTEBOOK_ID).exists())
    }

    @Test
    fun `migration is skipped when active notebook id is already set`() {
        val store = NotebookStore(temporaryFolder.root) { 7000L }
        val notebookDir = File(temporaryFolder.root, "notebooks").apply { mkdirs() }
        File(notebookDir, "Muse.json").writeText(v1NotebookJson("Muse", updatedAt = 100L, recognizedText = "legacy"))

        val active = (store.loadOrCreateActive("my-active", Persona.Confidant) as NotebookLoadResult.Ready).notebook

        assertEquals("my-active", active.id)
        assertEquals(Persona.Confidant.name, active.personaId)
        assertTrue(active.exchanges.isEmpty())
        assertFalse(store.fileFor(DEFAULT_NOTEBOOK_ID).exists())
    }

    @Test
    fun `persona switch changes metadata but keeps the same exchanges`() {
        val exchange = exchange(10, "question", "answer")
        val notebook = Notebook(
            id = DEFAULT_NOTEBOOK_ID,
            title = DEFAULT_NOTEBOOK_TITLE,
            personaId = Persona.Whisper.name,
            createdAt = 1L,
            updatedAt = 2L,
            exchanges = listOf(exchange),
        )

        val switched = notebook.withPersona(Persona.Muse, updatedAt = 3L)

        assertEquals(DEFAULT_NOTEBOOK_ID, switched.id)
        assertEquals(Persona.Muse.name, switched.personaId)
        assertEquals(listOf(exchange), switched.exchanges)
    }

    @Test
    fun `api history rebuild keeps last twenty answered turns`() {
        val notebook = Notebook(
            id = DEFAULT_NOTEBOOK_ID,
            title = DEFAULT_NOTEBOOK_TITLE,
            personaId = Persona.Confidant.name,
            createdAt = 1L,
            updatedAt = 2L,
            exchanges = (0 until 25).map { index ->
                exchange(index, "question $index", "answer $index")
            },
        )

        val history = notebook.rebuildApiHistory()

        assertEquals(40, history.size)
        assertEquals("question 5", history.first().content)
        assertEquals("answer 24", history.last().content)
    }

    @Test
    fun `api history includes unanswered user turns and skips missing assistant turns`() {
        val notebook = Notebook(
            id = DEFAULT_NOTEBOOK_ID,
            title = DEFAULT_NOTEBOOK_TITLE,
            personaId = Persona.Muse.name,
            createdAt = 1L,
            updatedAt = 2L,
            exchanges = listOf(
                Exchange(
                    id = "exchange-10",
                    committedAt = 10L,
                    ink = NotebookInk(emptyList(), "first unfinished thought"),
                    reply = null,
                ),
                exchange(20, "second line", "second answer"),
            ),
        )

        val history = notebook.rebuildApiHistory()

        assertEquals(listOf("user", "user", "assistant"), history.map { it.role })
        assertEquals(listOf("first unfinished thought", "second line", "second answer"), history.map { it.content })
    }

    @Test
    fun `api history caps unanswered exchanges at last twenty turns`() {
        val notebook = Notebook(
            id = DEFAULT_NOTEBOOK_ID,
            title = DEFAULT_NOTEBOOK_TITLE,
            personaId = Persona.Muse.name,
            createdAt = 1L,
            updatedAt = 2L,
            exchanges = (0 until 25).map { index ->
                Exchange(
                    id = "exchange-$index",
                    committedAt = index.toLong(),
                    ink = NotebookInk(emptyList(), "question $index"),
                    reply = null,
                )
            },
        )

        val history = notebook.rebuildApiHistory()

        assertEquals(20, history.size)
        assertEquals("question 5", history.first().content)
        assertEquals("question 24", history.last().content)
    }

    private fun exchange(index: Int, question: String, answer: String): Exchange {
        val committedAt = index * 2L
        return Exchange(
            id = "exchange-$committedAt",
            committedAt = committedAt,
            ink = NotebookInk(emptyList(), question),
            reply = NotebookReply(
                text = answer,
                personaId = Persona.Confidant.name,
                createdAt = committedAt + 1L,
            ),
        )
    }

    private fun v1NotebookJson(personaId: String, updatedAt: Long, recognizedText: String): String {
        return """
            {
              "id": "$personaId",
              "personaId": "$personaId",
              "createdAt": 1,
              "updatedAt": $updatedAt,
              "schemaVersion": 1,
              "pages": [
                {
                  "index": 0,
                  "elements": [
                    {
                      "type": "ink",
                      "strokes": [
                        {
                          "points": [
                            {
                              "x": 1.0,
                              "y": 2.0,
                              "pressure": 0.5,
                              "t": 10,
                              "size": 1.0,
                              "tiltX": 0,
                              "tiltY": 0
                            }
                          ]
                        }
                      ],
                      "committedAt": 10,
                      "recognizedText": "$recognizedText"
                    },
                    {
                      "type": "reply",
                      "text": "old reply",
                      "personaId": "$personaId",
                      "createdAt": 11
                    }
                  ]
                }
              ]
            }
        """.trimIndent()
    }
}
