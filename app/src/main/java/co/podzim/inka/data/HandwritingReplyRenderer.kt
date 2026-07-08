package co.podzim.inka.data

enum class HandwritingReplyRenderer(
    val label: String,
) {
    Font("Font"),
    GeneratedStrokes("Generated strokes"),
    ;

    companion object {
        val default = Font

        fun fromStoredName(value: String?): HandwritingReplyRenderer {
            return entries.firstOrNull { it.name == value } ?: default
        }
    }
}
