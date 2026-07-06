package com.inkwell.diary.data

enum class ReplyStyle(
    val label: String,
) {
    Writing("Writing"),
    Drawing("Drawing"),
    ;

    companion object {
        val default = Writing

        fun fromStoredName(value: String?): ReplyStyle {
            return entries.firstOrNull { it.name == value } ?: default
        }
    }
}
