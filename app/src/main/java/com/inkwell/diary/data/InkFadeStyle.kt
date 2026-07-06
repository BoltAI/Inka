package com.inkwell.diary.data

enum class InkFadeStyle(val label: String) {
    TurnsToDust("Turns to dust"),
    SimplyFades("Simply fades"),
    ;

    companion object {
        val default: InkFadeStyle = TurnsToDust

        fun fromStoredName(value: String?): InkFadeStyle {
            return entries.firstOrNull { it.name == value } ?: default
        }
    }
}
