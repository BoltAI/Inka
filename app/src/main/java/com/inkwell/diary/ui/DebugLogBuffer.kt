package com.inkwell.diary.ui

internal class DebugLogBuffer(
    private val maxLines: Int,
    private val uptimeMillis: () -> Long,
) {
    private val lines = ArrayDeque<String>()

    fun append(line: String): String {
        val uptimeMs = uptimeMillis()
        val timestamp = "${uptimeMs / 1000}.${(uptimeMs % 1000).toString().padStart(3, '0')}"
        val entry = "$timestamp  $line"
        lines.addLast(entry)
        while (lines.size > maxLines) {
            lines.removeFirst()
        }
        return entry
    }

    fun text(): String = lines.joinToString("\n")
}
