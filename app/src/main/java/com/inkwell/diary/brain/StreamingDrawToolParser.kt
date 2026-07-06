package com.inkwell.diary.brain

class StreamingDrawToolParser {
    private val raw = StringBuilder()
    private var emittedPathCount = 0

    fun append(partialJson: String): List<String> {
        if (partialJson.isEmpty()) return emptyList()
        raw.append(partialJson)
        val paths = completedPathValues(raw.toString())
        if (paths.size <= emittedPathCount) return emptyList()
        val newPaths = paths.drop(emittedPathCount)
        emittedPathCount = paths.size
        return newPaths
    }

    fun text(): String = raw.toString()

    private fun completedPathValues(input: String): List<String> {
        val start = pathsArrayStart(input) ?: return emptyList()
        val paths = mutableListOf<String>()
        var index = start
        while (index < input.length) {
            when (input[index]) {
                '"' -> {
                    val key = readJsonString(input, index) ?: return paths
                    index = key.nextIndex
                    if (key.value == "d") {
                        index = skipWhitespace(input, index)
                        if (index < input.length && input[index] == ':') {
                            index = skipWhitespace(input, index + 1)
                            if (index >= input.length || input[index] != '"') return paths
                            val value = readJsonString(input, index) ?: return paths
                            paths.add(value.value)
                            index = value.nextIndex
                        }
                    }
                }
                ']' -> return paths
                else -> index += 1
            }
        }
        return paths
    }

    private fun pathsArrayStart(input: String): Int? {
        var index = 0
        while (index < input.length) {
            if (input[index] != '"') {
                index += 1
                continue
            }
            val key = readJsonString(input, index) ?: return null
            index = key.nextIndex
            if (key.value != "paths") continue
            index = skipWhitespace(input, index)
            if (index >= input.length || input[index] != ':') continue
            index = skipWhitespace(input, index + 1)
            return if (index < input.length && input[index] == '[') index + 1 else null
        }
        return null
    }

    private fun skipWhitespace(input: String, start: Int): Int {
        var index = start
        while (index < input.length && input[index].isWhitespace()) index += 1
        return index
    }

    private data class JsonString(val value: String, val nextIndex: Int)

    private fun readJsonString(input: String, start: Int): JsonString? {
        if (start >= input.length || input[start] != '"') return null
        val value = StringBuilder()
        var index = start + 1
        while (index < input.length) {
            val char = input[index]
            when (char) {
                '"' -> return JsonString(value.toString(), index + 1)
                '\\' -> {
                    if (index + 1 >= input.length) return null
                    when (val escaped = input[index + 1]) {
                        '"', '\\', '/' -> {
                            value.append(escaped)
                            index += 2
                        }
                        'b' -> {
                            value.append('\b')
                            index += 2
                        }
                        'f' -> {
                            value.append('\u000C')
                            index += 2
                        }
                        'n' -> {
                            value.append('\n')
                            index += 2
                        }
                        'r' -> {
                            value.append('\r')
                            index += 2
                        }
                        't' -> {
                            value.append('\t')
                            index += 2
                        }
                        'u' -> {
                            if (index + 6 > input.length) return null
                            val hex = input.substring(index + 2, index + 6)
                            val code = hex.toIntOrNull(16) ?: return null
                            value.append(code.toChar())
                            index += 6
                        }
                        else -> return null
                    }
                }
                else -> {
                    value.append(char)
                    index += 1
                }
            }
        }
        return null
    }
}
