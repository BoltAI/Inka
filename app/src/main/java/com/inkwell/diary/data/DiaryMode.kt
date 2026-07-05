package com.inkwell.diary.data

enum class DiaryMode(
    val label: String,
) {
    Fade("The ink fades"),
    Manuscript("The ink remains");

    companion object {
        fun defaultFor(persona: Persona): DiaryMode {
            return if (persona == Persona.Whisper) Fade else Manuscript
        }

        fun fromStoredName(value: String?, fallback: DiaryMode): DiaryMode {
            return entries.firstOrNull { it.name == value } ?: fallback
        }
    }
}
