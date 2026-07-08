package com.inkwell.diary.data

enum class HandwritingReplyMode(
    val label: String,
) {
    Font("Font renderer"),
    Hosted("Hosted synthesis"),
    ;

    val usesGeneratedStrokes: Boolean
        get() = this != Font

    val requiresServerEndpoint: Boolean
        get() = this == Hosted

    fun applyTo(prefs: Prefs) {
        when (this) {
            Font -> prefs.handwritingReplyRenderer = HandwritingReplyRenderer.Font
            Hosted -> {
                prefs.handwritingReplyRenderer = HandwritingReplyRenderer.GeneratedStrokes
                prefs.handwritingSynthesisSource = HandwritingSynthesisSource.Server
            }
        }
    }

    companion object {
        val default = Font

        fun fromPrefs(prefs: Prefs): HandwritingReplyMode {
            return when (prefs.handwritingReplyRenderer) {
                HandwritingReplyRenderer.Font -> Font
                HandwritingReplyRenderer.GeneratedStrokes -> when (prefs.handwritingSynthesisSource) {
                    HandwritingSynthesisSource.Server -> Hosted
                }
            }
        }
    }
}
