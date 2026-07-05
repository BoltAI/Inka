package com.inkwell.diary.data

enum class AiProvider(
    val label: String,
    val defaultModel: String,
    val modelOptions: List<String>,
) {
    Anthropic(
        label = "Anthropic",
        defaultModel = "claude-sonnet-4-6",
        modelOptions = listOf(
            "claude-sonnet-5",
            "claude-sonnet-4-6",
            "claude-haiku-4-5",
            "claude-opus-4-8",
        ),
    ),
    OpenAI(
        label = "OpenAI",
        defaultModel = "gpt-5.4-mini",
        modelOptions = listOf(
            "gpt-5.5",
            "gpt-5.4-mini",
            "gpt-5.4-nano",
        ),
    ),
    Groq(
        label = "Groq",
        defaultModel = "openai/gpt-oss-20b",
        modelOptions = listOf(
            "openai/gpt-oss-20b",
            "openai/gpt-oss-120b",
        ),
    );

    companion object {
        fun fromName(value: String?): AiProvider {
            return entries.firstOrNull { it.name == value } ?: Anthropic
        }
    }
}
