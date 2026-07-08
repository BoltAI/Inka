package co.podzim.inka.data

enum class ReplyStyle(
    val label: String,
) {
    Writing("Text only"),
    Drawing("Draw"),
    ;

    companion object {
        val default = Writing

        fun fromStoredName(value: String?): ReplyStyle {
            return entries.firstOrNull { it.name == value } ?: default
        }
    }
}
