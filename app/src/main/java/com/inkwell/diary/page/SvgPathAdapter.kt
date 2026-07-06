package com.inkwell.diary.page

import com.inkwell.diary.data.InkPoint
import com.inkwell.diary.data.InkStroke
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.max

data class SvgPathConversionResult(
    val strokes: List<InkStroke>,
    val rejectedPaths: Int,
    val truncated: Boolean,
)

object SvgPathAdapter {
    fun convert(
        paths: List<String>,
        imageWidth: Int,
        imageHeight: Int,
        pageWidth: Int,
        pageHeight: Int,
        pointCap: Int = DEFAULT_POINT_CAP,
        tolerancePx: Float = DEFAULT_TOLERANCE_PX,
    ): SvgPathConversionResult {
        if (imageWidth <= 0 || imageHeight <= 0 || pageWidth <= 0 || pageHeight <= 0) {
            return SvgPathConversionResult(emptyList(), paths.size, truncated = false)
        }
        val strokes = mutableListOf<InkStroke>()
        var rejected = 0
        var totalPoints = 0
        var truncated = false
        val scaleX = pageWidth.toFloat() / imageWidth.toFloat()
        val scaleY = pageHeight.toFloat() / imageHeight.toFloat()
        val maxX = pageWidth.toFloat()
        val maxY = pageHeight.toFloat()

        for (path in paths.take(MAX_PATHS)) {
            val imagePoints = try {
                SvgPathParser(path).parse().flatten(tolerancePx)
            } catch (_: SvgPathException) {
                rejected += 1
                continue
            }
            if (imagePoints.isEmpty()) continue
            val remaining = pointCap - totalPoints
            if (remaining <= 0) {
                truncated = true
                break
            }
            val scaled = imagePoints.take(remaining).map { point ->
                InkPoint(
                    x = (point.x * scaleX).coerceIn(0f, maxX),
                    y = (point.y * scaleY).coerceIn(0f, maxY),
                    pressure = REPLY_PRESSURE,
                    timestampMs = totalPoints.toLong(),
                )
            }
            strokes.add(InkStroke(scaled))
            totalPoints += scaled.size
            if (scaled.size < imagePoints.size) {
                truncated = true
                break
            }
        }
        if (paths.size > MAX_PATHS) truncated = true
        return SvgPathConversionResult(strokes, rejected, truncated)
    }

    fun convertOne(
        path: String,
        imageWidth: Int,
        imageHeight: Int,
        pageWidth: Int,
        pageHeight: Int,
        pointCap: Int = DEFAULT_POINT_CAP,
        tolerancePx: Float = DEFAULT_TOLERANCE_PX,
    ): SvgPathConversionResult {
        return convert(
            paths = listOf(path),
            imageWidth = imageWidth,
            imageHeight = imageHeight,
            pageWidth = pageWidth,
            pageHeight = pageHeight,
            pointCap = pointCap,
            tolerancePx = tolerancePx,
        )
    }

    private const val MAX_PATHS = 60
    private const val DEFAULT_POINT_CAP = 20_000
    private const val DEFAULT_TOLERANCE_PX = 1.5f
    private const val REPLY_PRESSURE = 0.55f
}

private class SvgPathParser(
    private val raw: String,
) {
    private val tokens = tokenize(raw)
    private var index = 0

    fun parse(): List<PathCommand> {
        val commands = mutableListOf<PathCommand>()
        var currentCommand: Char? = null
        while (index < tokens.size) {
            val token = tokens[index]
            if (token.isCommand()) {
                currentCommand = token.command
                index += 1
            } else if (currentCommand == null) {
                throw SvgPathException("Expected command")
            }

            val command = currentCommand ?: throw SvgPathException("Expected command")
            if (!command.isAllowedSvgCommand()) {
                throw SvgPathException("Unsupported command $command")
            }
            when (command.uppercaseChar()) {
                'M' -> parseMove(command, commands).also { currentCommand = if (command.isLowerCase()) 'l' else 'L' }
                'L' -> parseLine(command, commands)
                'C' -> parseCubic(command, commands)
                'Q' -> parseQuad(command, commands)
                'Z' -> commands.add(PathCommand.Close).also { currentCommand = null }
                else -> throw SvgPathException("Unsupported command $command")
            }
        }
        return commands
    }

    private fun parseMove(command: Char, commands: MutableList<PathCommand>) {
        val first = readPair() ?: throw SvgPathException("Move missing coordinates")
        commands.add(PathCommand.Move(first.x, first.y, command.isRelativeSvgCommand()))
        while (peekNumber()) {
            val point = readPair() ?: break
            commands.add(PathCommand.Line(point.x, point.y, command.isRelativeSvgCommand()))
        }
    }

    private fun parseLine(command: Char, commands: MutableList<PathCommand>) {
        var parsed = false
        while (peekNumber()) {
            val point = readPair() ?: break
            commands.add(PathCommand.Line(point.x, point.y, command.isRelativeSvgCommand()))
            parsed = true
        }
        if (!parsed) throw SvgPathException("Line missing coordinates")
    }

    private fun parseCubic(command: Char, commands: MutableList<PathCommand>) {
        var parsed = false
        while (peekNumber()) {
            val c1 = readPair() ?: break
            val c2 = readPair() ?: throw SvgPathException("Cubic missing second control")
            val end = readPair() ?: throw SvgPathException("Cubic missing end")
            commands.add(PathCommand.Cubic(c1.x, c1.y, c2.x, c2.y, end.x, end.y, command.isRelativeSvgCommand()))
            parsed = true
        }
        if (!parsed) throw SvgPathException("Cubic missing coordinates")
    }

    private fun parseQuad(command: Char, commands: MutableList<PathCommand>) {
        var parsed = false
        while (peekNumber()) {
            val c = readPair() ?: break
            val end = readPair() ?: throw SvgPathException("Quad missing end")
            commands.add(PathCommand.Quad(c.x, c.y, end.x, end.y, command.isRelativeSvgCommand()))
            parsed = true
        }
        if (!parsed) throw SvgPathException("Quad missing coordinates")
    }

    private fun readPair(): Point? {
        val x = readNumber() ?: return null
        val y = readNumber() ?: throw SvgPathException("Expected y coordinate")
        return Point(x, y)
    }

    private fun readNumber(): Float? {
        if (!peekNumber()) return null
        return tokens[index++].number
    }

    private fun peekNumber(): Boolean = index < tokens.size && tokens[index].number != null

    private fun Token.isCommand(): Boolean = command != null

    companion object {
        private fun tokenize(raw: String): List<Token> {
            val tokens = mutableListOf<Token>()
            var index = 0
            while (index < raw.length) {
                val char = raw[index]
                when {
                    char.isWhitespace() || char == ',' -> index += 1
                    char.isLetter() -> {
                        if (!char.isAllowedSvgCommand()) throw SvgPathException("Unsupported command $char")
                        tokens.add(Token(command = char))
                        index += 1
                    }
                    char == '+' || char == '-' || char == '.' || char.isDigit() -> {
                        val match = NUMBER_REGEX.find(raw, index)
                        if (match == null || match.range.first != index) {
                            throw SvgPathException("Malformed number")
                        }
                        tokens.add(Token(number = match.value.toFloat()))
                        index = match.range.last + 1
                    }
                    else -> throw SvgPathException("Unexpected character $char")
                }
            }
            return tokens
        }

        private val NUMBER_REGEX = Regex("""[+-]?(?:(?:\d+\.\d*)|(?:\.\d+)|(?:\d+))(?:[eE][+-]?\d+)?""")
    }
}

private sealed class PathCommand {
    data class Move(val x: Float, val y: Float, val relative: Boolean) : PathCommand()
    data class Line(val x: Float, val y: Float, val relative: Boolean) : PathCommand()
    data class Cubic(
        val x1: Float,
        val y1: Float,
        val x2: Float,
        val y2: Float,
        val x: Float,
        val y: Float,
        val relative: Boolean,
    ) : PathCommand()

    data class Quad(
        val x1: Float,
        val y1: Float,
        val x: Float,
        val y: Float,
        val relative: Boolean,
    ) : PathCommand()

    data object Close : PathCommand()
}

private fun List<PathCommand>.flatten(tolerancePx: Float): List<Point> {
    val points = mutableListOf<Point>()
    var current = Point(0f, 0f)
    var subpathStart = Point(0f, 0f)

    fun absolute(x: Float, y: Float, relative: Boolean): Point {
        return if (relative) Point(current.x + x, current.y + y) else Point(x, y)
    }

    forEach { command ->
        when (command) {
            is PathCommand.Move -> {
                current = absolute(command.x, command.y, command.relative)
                subpathStart = current
                if (points.isEmpty()) points.add(current)
            }
            is PathCommand.Line -> {
                current = absolute(command.x, command.y, command.relative)
                points.add(current)
            }
            is PathCommand.Cubic -> {
                val c1 = absolute(command.x1, command.y1, command.relative)
                val c2 = absolute(command.x2, command.y2, command.relative)
                val end = absolute(command.x, command.y, command.relative)
                flattenCubic(current, c1, c2, end, tolerancePx, points)
                current = end
            }
            is PathCommand.Quad -> {
                val c = absolute(command.x1, command.y1, command.relative)
                val end = absolute(command.x, command.y, command.relative)
                flattenQuad(current, c, end, tolerancePx, points)
                current = end
            }
            PathCommand.Close -> {
                if (current != subpathStart) {
                    current = subpathStart
                    points.add(current)
                }
            }
        }
    }
    return points.distinctConsecutive()
}

private fun flattenQuad(p0: Point, p1: Point, p2: Point, tolerance: Float, out: MutableList<Point>, depth: Int = 0) {
    if (depth >= MAX_SUBDIVISION_DEPTH || distancePointToLine(p1, p0, p2) <= tolerance) {
        out.add(p2)
        return
    }
    val p01 = midpoint(p0, p1)
    val p12 = midpoint(p1, p2)
    val p012 = midpoint(p01, p12)
    flattenQuad(p0, p01, p012, tolerance, out, depth + 1)
    flattenQuad(p012, p12, p2, tolerance, out, depth + 1)
}

private fun flattenCubic(
    p0: Point,
    p1: Point,
    p2: Point,
    p3: Point,
    tolerance: Float,
    out: MutableList<Point>,
    depth: Int = 0,
) {
    val error = max(distancePointToLine(p1, p0, p3), distancePointToLine(p2, p0, p3))
    if (depth >= MAX_SUBDIVISION_DEPTH || error <= tolerance) {
        out.add(p3)
        return
    }
    val p01 = midpoint(p0, p1)
    val p12 = midpoint(p1, p2)
    val p23 = midpoint(p2, p3)
    val p012 = midpoint(p01, p12)
    val p123 = midpoint(p12, p23)
    val p0123 = midpoint(p012, p123)
    flattenCubic(p0, p01, p012, p0123, tolerance, out, depth + 1)
    flattenCubic(p0123, p123, p23, p3, tolerance, out, depth + 1)
}

private fun distancePointToLine(point: Point, start: Point, end: Point): Float {
    val dx = end.x - start.x
    val dy = end.y - start.y
    val length = hypot(dx, dy)
    if (length == 0f) return hypot(point.x - start.x, point.y - start.y)
    return abs(dy * point.x - dx * point.y + end.x * start.y - end.y * start.x) / length
}

private fun midpoint(a: Point, b: Point): Point = Point((a.x + b.x) / 2f, (a.y + b.y) / 2f)

private fun List<Point>.distinctConsecutive(): List<Point> {
    if (isEmpty()) return this
    val result = mutableListOf(first())
    drop(1).forEach { point ->
        if (hypot(point.x - result.last().x, point.y - result.last().y) > MIN_POINT_DISTANCE_PX) {
            result.add(point)
        }
    }
    return result
}

private data class Token(
    val command: Char? = null,
    val number: Float? = null,
)

private data class Point(
    val x: Float,
    val y: Float,
)

private class SvgPathException(message: String) : IllegalArgumentException(message)

private fun Char.isAllowedSvgCommand(): Boolean = uppercaseChar() in setOf('M', 'L', 'C', 'Q', 'Z')

private fun Char.isRelativeSvgCommand(): Boolean = isLowerCase()

private const val MAX_SUBDIVISION_DEPTH = 12
private const val MIN_POINT_DISTANCE_PX = 0.01f
