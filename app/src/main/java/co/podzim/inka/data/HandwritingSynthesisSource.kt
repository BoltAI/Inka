package co.podzim.inka.data

enum class HandwritingSynthesisSource(
    val label: String,
) {
    Server("Hosted synthesis"),
    ;

    companion object {
        val default = Server

        fun fromStoredName(value: String?): HandwritingSynthesisSource {
            return entries.firstOrNull { it.name == value } ?: default
        }
    }
}
