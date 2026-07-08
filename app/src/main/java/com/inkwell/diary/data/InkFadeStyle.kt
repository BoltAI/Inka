package com.inkwell.diary.data

enum class InkFadeStyle(val label: String) {
    TurnsToDust("Burn"),
    SimplyFades("Fade"),
    ;

    companion object {
        val default: InkFadeStyle = SimplyFades

        fun fromStoredName(value: String?): InkFadeStyle {
            return entries.firstOrNull { it.name == value } ?: default
        }
    }
}
