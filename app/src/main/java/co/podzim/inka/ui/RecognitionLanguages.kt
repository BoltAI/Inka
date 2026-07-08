package co.podzim.inka.ui

internal data class RecognitionLanguageChoice(
    val label: String,
    val tag: String,
)

internal object RecognitionLanguages {
    val choices = listOf(
        RecognitionLanguageChoice("English", "en-US"),
        RecognitionLanguageChoice("English UK", "en-GB"),
        RecognitionLanguageChoice("Spanish", "es-ES"),
        RecognitionLanguageChoice("Spanish Mexico", "es-MX"),
        RecognitionLanguageChoice("French", "fr-FR"),
        RecognitionLanguageChoice("German", "de-DE"),
        RecognitionLanguageChoice("Italian", "it-IT"),
        RecognitionLanguageChoice("Portuguese Brazil", "pt-BR"),
        RecognitionLanguageChoice("Portuguese Portugal", "pt-PT"),
        RecognitionLanguageChoice("Dutch", "nl-NL"),
        RecognitionLanguageChoice("Vietnamese", "vi-VN"),
        RecognitionLanguageChoice("Japanese", "ja-JP"),
        RecognitionLanguageChoice("Korean", "ko-KR"),
        RecognitionLanguageChoice("Chinese Simplified", "zh-Hans"),
        RecognitionLanguageChoice("Chinese Traditional", "zh-Hant"),
        RecognitionLanguageChoice("Hindi", "hi-IN"),
        RecognitionLanguageChoice("Arabic", "ar"),
        RecognitionLanguageChoice("Russian", "ru-RU"),
        RecognitionLanguageChoice("Polish", "pl-PL"),
        RecognitionLanguageChoice("Turkish", "tr-TR"),
        RecognitionLanguageChoice("Indonesian", "id-ID"),
        RecognitionLanguageChoice("Thai", "th-TH"),
    )

    val tags: List<String> = choices.map { it.tag }

    fun choiceFor(tag: String): RecognitionLanguageChoice {
        return choices.firstOrNull { it.tag == tag } ?: choices.first()
    }
}
