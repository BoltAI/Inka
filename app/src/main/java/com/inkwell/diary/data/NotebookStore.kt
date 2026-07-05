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
    data class UnsupportedSchema(val version: Int) : NotebookLoadResult()
    data class Corrupt(val detail: String? = null) : NotebookLoadResult()
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

    fun fileFor(persona: Persona): File = File(notebookDir, "${persona.name}.json")

    fun load(persona: Persona): NotebookLoadResult {
        val file = fileFor(persona)
        if (!file.exists()) {
            return NotebookLoadResult.Ready(newNotebook(persona))
        }

        return try {
            val raw = file.readText()
            val version = json.parseToJsonElement(raw)
                .jsonObject["schemaVersion"]
                ?.jsonPrimitive
                ?.intOrNull
                ?: CURRENT_SCHEMA_VERSION
            if (version != CURRENT_SCHEMA_VERSION) {
                NotebookLoadResult.UnsupportedSchema(version)
            } else {
                NotebookLoadResult.Ready(
                    json.decodeFromString(Notebook.serializer(), raw)
                        .withoutRedundantTrailingBlankPages(),
                )
            }
        } catch (e: IOException) {
            NotebookLoadResult.Corrupt(e::class.java.simpleName)
        } catch (e: IllegalArgumentException) {
            NotebookLoadResult.Corrupt(e::class.java.simpleName)
        } catch (e: SerializationException) {
            NotebookLoadResult.Corrupt(e::class.java.simpleName)
        }
    }

    fun loadOrCreate(persona: Persona): Notebook {
        return when (val result = load(persona)) {
            is NotebookLoadResult.Ready -> result.notebook
            is NotebookLoadResult.UnsupportedSchema -> newNotebook(persona)
            is NotebookLoadResult.Corrupt -> newNotebook(persona)
        }
    }

    suspend fun save(notebook: Notebook) = withContext(Dispatchers.IO) {
        notebookDir.mkdirs()
        val destination = File(notebookDir, "${notebook.personaId}.json")
        val tmp = File(notebookDir, "${notebook.personaId}.json.tmp")
        tmp.writeText(json.encodeToString(notebook))
        if (!tmp.renameTo(destination)) {
            tmp.copyTo(destination, overwrite = true)
            tmp.delete()
        }
    }

    suspend fun burn(persona: Persona): Notebook = withContext(Dispatchers.IO) {
        val file = fileFor(persona)
        if (file.exists()) {
            file.delete()
        }
        newNotebook(persona)
    }

    private fun newNotebook(persona: Persona): Notebook {
        val now = clock()
        return Notebook(
            id = persona.name,
            personaId = persona.name,
            createdAt = now,
            updatedAt = now,
            pages = listOf(NotebookPage(index = 0)),
        )
    }
}
