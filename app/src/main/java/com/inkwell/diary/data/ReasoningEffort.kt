package com.inkwell.diary.data

enum class ReasoningEffort(
    val label: String,
    val apiValue: String?,
) {
    Default("Default", null),
    None("None", "none"),
    Minimal("Minimal", "minimal"),
    Low("Low", "low"),
    Medium("Medium", "medium"),
    High("High", "high"),
    XHigh("XHigh", "xhigh"),
    Max("Max", "max");

    companion object {
        fun choicesFor(provider: AiProvider): List<ReasoningEffort> {
            return when (provider) {
                AiProvider.Anthropic -> listOf(Default, Low, Medium, High, XHigh, Max)
                AiProvider.OpenAI -> listOf(Default, None, Minimal, Low, Medium, High, XHigh)
                AiProvider.Groq -> listOf(Default, Low, Medium, High)
            }
        }

        fun fromStoredName(value: String?, provider: AiProvider): ReasoningEffort {
            val effort = entries.firstOrNull { it.name == value } ?: Default
            return if (effort in choicesFor(provider)) effort else Default
        }
    }
}
