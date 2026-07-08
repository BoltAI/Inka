package co.podzim.inka.page

import android.content.Context
import co.podzim.inka.data.InkStroke
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

object DissolveLabStore {
    private const val FILE_NAME = "dissolve-lab-strokes.json"
    private val json = Json {
        encodeDefaults = true
        ignoreUnknownKeys = true
    }

    suspend fun save(context: Context, strokes: List<InkStroke>) = withContext(Dispatchers.IO) {
        context.cacheDir.resolve(FILE_NAME).writeText(json.encodeToString(strokes))
    }

    fun load(context: Context): List<InkStroke> {
        val file = context.cacheDir.resolve(FILE_NAME)
        if (!file.exists()) return emptyList()
        return runCatching {
            json.decodeFromString<List<InkStroke>>(file.readText())
        }.getOrDefault(emptyList())
    }
}
