package co.podzim.inka.ui

import co.podzim.inka.data.AiProvider

internal fun AiProvider.apiKeyGuideUrl(): String {
    return when (this) {
        AiProvider.Anthropic -> "https://console.anthropic.com/settings/keys"
        AiProvider.OpenAI -> "https://platform.openai.com/api-keys"
        AiProvider.Groq -> "https://console.groq.com/keys"
    }
}
