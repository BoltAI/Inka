package com.inkwell.diary.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
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

    suspend fun loadOrCreateActive(activeNotebookId: String?, persona: Persona): NotebookLoadResult = withContext(Dispatchers.IO) {
        val activeId = activeNotebookId.orEmpty().ifBlank { null }
        if (activeId != null) {
            loadOrRecover(activeId, persona)
        } else {
            NotebookLoadResult.Ready(newNotebook(DEFAULT_NOTEBOOK_ID, persona))
        }
    }

    suspend fun load(id: String, persona: Persona): NotebookLoadResult = withContext(Dispatchers.IO) {
        loadOrRecover(id.ifBlank { DEFAULT_NOTEBOOK_ID }, persona)
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
            when (version) {
                CURRENT_SCHEMA_VERSION -> NotebookLoadResult.Ready(json.decodeFromString(Notebook.serializer(), raw))
                V2_SCHEMA_VERSION -> {
                    val migrated = json.decodeFromString(Notebook.serializer(), raw)
                        .copy(schemaVersion = CURRENT_SCHEMA_VERSION)
                    NotebookLoadResult.Ready(migrated)
                }
                else -> {
                    renameDamaged(file)
                    NotebookLoadResult.Recovered(newNotebook(DEFAULT_NOTEBOOK_ID, persona), "Unsupported schema $version")
                }
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

private const val V2_SCHEMA_VERSION = 2
private const val V1_SCHEMA_VERSION = 1
