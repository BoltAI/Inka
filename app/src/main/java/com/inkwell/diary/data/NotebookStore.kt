package com.inkwell.diary.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.File
import java.io.IOException

sealed class NotebookLoadResult {
    data class Ready(val notebook: Notebook) : NotebookLoadResult()
    data class Recovered(val notebook: Notebook, val detail: String? = null) : NotebookLoadResult()
}

class NotebookStore(
    filesDir: File,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private val notebookDir = File(filesDir, "notebooks")

    private val json = Json {
        classDiscriminator = "type"
        encodeDefaults = true
        ignoreUnknownKeys = true
        prettyPrint = true
    }

    fun fileFor(id: String): File = File(notebookDir, "${id.ifBlank { DEFAULT_NOTEBOOK_ID }}.json")

    fun loadOrCreateActive(activeNotebookId: String?, persona: Persona): NotebookLoadResult {
        val activeId = activeNotebookId.orEmpty().ifBlank { null }
        if (activeId != null) {
            return loadOrRecover(activeId, persona)
        }
        val migrated = migrateLatestV1Notebook(persona)
        return if (migrated != null) {
            NotebookLoadResult.Ready(migrated)
        } else {
            NotebookLoadResult.Ready(newNotebook(DEFAULT_NOTEBOOK_ID, persona))
        }
    }

    fun load(id: String, persona: Persona): NotebookLoadResult {
        return loadOrRecover(id.ifBlank { DEFAULT_NOTEBOOK_ID }, persona)
    }

    suspend fun save(notebook: Notebook) = withContext(Dispatchers.IO) {
        notebookDir.mkdirs()
        val destination = fileFor(notebook.id)
        val safeId = notebook.id.ifBlank { DEFAULT_NOTEBOOK_ID }
        val tmp = File.createTempFile("$safeId.", ".json.tmp", notebookDir)
        try {
            tmp.writeText(json.encodeToString(notebook))
            if (!tmp.renameTo(destination)) {
                tmp.copyTo(destination, overwrite = true)
            }
        } finally {
            if (tmp.exists()) {
                tmp.delete()
            }
        }
    }

    suspend fun burn(id: String, persona: Persona): Notebook = withContext(Dispatchers.IO) {
        val file = fileFor(id)
        if (file.exists()) {
            file.delete()
        }
        newNotebook(DEFAULT_NOTEBOOK_ID, persona)
    }

    private fun loadOrRecover(id: String, persona: Persona): NotebookLoadResult {
        val file = fileFor(id)
        if (!file.exists()) {
            return NotebookLoadResult.Ready(newNotebook(id, persona))
        }
        return try {
            val raw = file.readText()
            val version = schemaVersion(raw)
            if (version != CURRENT_SCHEMA_VERSION) {
                renameDamaged(file)
                NotebookLoadResult.Recovered(newNotebook(DEFAULT_NOTEBOOK_ID, persona), "Unsupported schema $version")
            } else {
                NotebookLoadResult.Ready(json.decodeFromString(Notebook.serializer(), raw))
            }
        } catch (e: IOException) {
            renameDamaged(file)
            NotebookLoadResult.Recovered(newNotebook(DEFAULT_NOTEBOOK_ID, persona), e::class.java.simpleName)
        } catch (e: IllegalArgumentException) {
            renameDamaged(file)
            NotebookLoadResult.Recovered(newNotebook(DEFAULT_NOTEBOOK_ID, persona), e::class.java.simpleName)
        } catch (e: SerializationException) {
            renameDamaged(file)
            NotebookLoadResult.Recovered(newNotebook(DEFAULT_NOTEBOOK_ID, persona), e::class.java.simpleName)
        }
    }

    private fun migrateLatestV1Notebook(persona: Persona): Notebook? {
        if (!notebookDir.exists()) return null
        val candidates = notebookDir.listFiles { file ->
            file.isFile && file.extension == "json" && runCatching {
                schemaVersion(file.readText()) == V1_SCHEMA_VERSION
            }.getOrDefault(false)
        }.orEmpty()
        val source = candidates.maxWithOrNull(compareBy<File> { runCatching { v1UpdatedAt(it) }.getOrDefault(0L) }.thenBy { it.lastModified() })
            ?: return null
        val migrated = runCatching {
            val v1 = json.decodeFromString(V1Notebook.serializer(), source.readText())
            v1.toV2Notebook(persona)
        }.getOrNull() ?: return null
        runCatching {
            notebookDir.mkdirs()
            val destination = fileFor(migrated.id)
            if (!destination.exists()) {
                destination.writeText(json.encodeToString(migrated))
            }
        }
        return migrated
    }

    private fun v1UpdatedAt(file: File): Long {
        return json.decodeFromString(V1Notebook.serializer(), file.readText()).updatedAt
    }

    private fun schemaVersion(raw: String): Int {
        return json.parseToJsonElement(raw)
            .jsonObject["schemaVersion"]
            ?.jsonPrimitive
            ?.intOrNull
            ?: V1_SCHEMA_VERSION
    }

    private fun renameDamaged(file: File) {
        if (!file.exists()) return
        val damaged = File(file.parentFile, "${file.name}.damaged")
        if (!file.renameTo(damaged)) {
            file.copyTo(damaged, overwrite = true)
            file.delete()
        }
    }

    private fun newNotebook(id: String, persona: Persona): Notebook {
        val now = clock()
        return Notebook(
            id = id.ifBlank { DEFAULT_NOTEBOOK_ID },
            title = DEFAULT_NOTEBOOK_TITLE,
            personaId = persona.name,
            createdAt = now,
            updatedAt = now,
        )
    }
}

@Serializable
private data class V1Notebook(
    val id: String,
    val personaId: String,
    val createdAt: Long,
    val updatedAt: Long,
    val schemaVersion: Int = V1_SCHEMA_VERSION,
    val pages: List<V1NotebookPage> = emptyList(),
)

@Serializable
private data class V1NotebookPage(
    val index: Int,
    val elements: List<V1NotebookElement> = emptyList(),
)

@Serializable
private sealed class V1NotebookElement {
    abstract val createdAt: Long
}

@Serializable
@SerialName("ink")
private data class V1InkElement(
    val strokes: List<InkStroke>,
    val committedAt: Long,
    val recognizedText: String,
) : V1NotebookElement() {
    override val createdAt: Long = committedAt
}

@Serializable
@SerialName("reply")
private data class V1ReplyElement(
    val text: String,
    val personaId: String,
    override val createdAt: Long,
) : V1NotebookElement()

private fun V1Notebook.toV2Notebook(fallbackPersona: Persona): Notebook {
    val exchanges = mutableListOf<Exchange>()
    val elements = pages.sortedBy { it.index }
        .flatMap { it.elements }
        .sortedBy { it.createdAt }
    var index = 0
    while (index < elements.size) {
        val element = elements[index]
        when (element) {
            is V1InkElement -> {
                val nextReply = elements.getOrNull(index + 1) as? V1ReplyElement
                exchanges.add(
                    Exchange(
                        id = newExchangeId(element.committedAt),
                        committedAt = element.committedAt,
                        ink = NotebookInk(element.strokes, element.recognizedText),
                        reply = nextReply?.let {
                            NotebookReply(
                                text = it.text,
                                personaId = it.personaId,
                                createdAt = it.createdAt,
                            )
                        },
                    ),
                )
                index += if (nextReply != null) 2 else 1
            }
            is V1ReplyElement -> {
                exchanges.add(
                    Exchange(
                        id = newExchangeId(element.createdAt),
                        committedAt = element.createdAt,
                        reply = NotebookReply(
                            text = element.text,
                            personaId = element.personaId,
                            createdAt = element.createdAt,
                        ),
                    ),
                )
                index += 1
            }
        }
    }
    return Notebook(
        id = DEFAULT_NOTEBOOK_ID,
        title = DEFAULT_NOTEBOOK_TITLE,
        personaId = personaId.ifBlank { fallbackPersona.name },
        createdAt = createdAt,
        updatedAt = updatedAt,
        exchanges = exchanges,
    )
}

private const val V1_SCHEMA_VERSION = 1
