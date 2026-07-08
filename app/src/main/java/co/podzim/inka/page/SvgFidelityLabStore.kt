package co.podzim.inka.page

import android.content.Context
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File

@Serializable
data class SvgFidelityRecord(
    val paths: List<String>,
    val imageWidth: Int,
    val imageHeight: Int,
    val savedAt: Long,
)

object SvgFidelityLabStore {
    private val json = Json {
        ignoreUnknownKeys = true
        explicitNulls = false
        prettyPrint = true
    }

    fun save(context: Context, paths: List<String>, imageWidth: Int, imageHeight: Int) {
        if (paths.isEmpty() || imageWidth <= 0 || imageHeight <= 0) return
        runCatching {
            file(context).apply {
                parentFile?.mkdirs()
                writeText(
                    json.encodeToString(
                        SvgFidelityRecord.serializer(),
                        SvgFidelityRecord(
                            paths = paths.take(MAX_STORED_PATHS),
                            imageWidth = imageWidth,
                            imageHeight = imageHeight,
                            savedAt = System.currentTimeMillis(),
                        ),
                    ),
                )
            }
        }
    }

    fun load(context: Context): SvgFidelityRecord? {
        return runCatching {
            val source = file(context)
            if (!source.exists()) return null
            json.decodeFromString(SvgFidelityRecord.serializer(), source.readText())
        }.getOrNull()
    }

    private fun file(context: Context): File = File(context.filesDir, "debug/svg_fidelity_last.json")

    private const val MAX_STORED_PATHS = 80
}
