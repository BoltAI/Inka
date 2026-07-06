package com.inkwell.diary.page

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.os.SystemClock
import com.inkwell.diary.data.InkStroke
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.util.Arrays
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.roundToInt
import kotlin.math.sin

interface InkFadeAnimator {
    val watchdogMs: Long
    suspend fun fade(strokes: List<InkStroke>, target: InkFadeTarget, options: InkFadeOptions)
}

internal suspend fun runFadeSafely(
    animator: InkFadeAnimator,
    strokes: List<InkStroke>,
    target: InkFadeTarget,
    options: InkFadeOptions,
): Boolean {
    return try {
        withTimeoutOrNull(animator.watchdogMs) {
            animator.fade(strokes, target, options)
            true
        } == true
    } catch (exception: CancellationException) {
        throw exception
    } catch (_: Throwable) {
        false
    }
}

data class InkFadeOptions(
    val includeFullOpacityFrame: Boolean = true,
)

data class DissolveConfig(
    val cellSizePx: Int = 8,
    val maxCells: Int = 1200,
    val sweepMs: Long = 1100L,
    val cellLifeMs: Long = 700L,
    val frameMs: Long = 110L,
    val driftMinPx: Float = 76f,
    val driftMaxPx: Float = 200f,
    val driftAngleDegrees: Float = -18f,
    val driftJitterDegrees: Float = 14f,
    val delayJitterMs: Long = 140L,
    val windShearPx: Float = 300f,
    val windFlutterPx: Float = 14f,
    val windTailPx: Float = 22f,
    val windTailSteps: Int = 2,
    val windTailDensity: Float = 0.34f,
    val maxTotalMs: Long = 3200L,
    val boundsPaddingPx: Int = 16,
    val terminalHoldMs: Long = 180L,
) {
    val compressedSweepMs: Long
        get() = sweepMs.coerceAtMost((maxTotalMs - cellLifeMs).coerceAtLeast(0L))

    val maxDisplacementPx: Int
        get() = ceil(driftMaxPx + windShearPx + windFlutterPx + windTailPx).toInt() + 2
}

class InkFadeTarget(
    val pageBitmap: Bitmap,
    val pageCanvas: Canvas,
    val inkPaint: Paint,
    val drawStrokes: (Canvas, List<InkStroke>, Paint) -> Unit,
    val clearPage: () -> Unit,
    val clearRect: (Rect) -> Unit,
    val render: (full: Boolean, dirtyRect: Rect?) -> Unit,
    val drawFrame: (Bitmap, Int, Int, Rect) -> Unit,
    val registerCancelCleanup: (((() -> Unit)?) -> Unit),
)

class SteppedFadeAnimator : InkFadeAnimator {
    override val watchdogMs: Long = 3000L

    override suspend fun fade(strokes: List<InkStroke>, target: InkFadeTarget, options: InkFadeOptions) {
        val alphas = if (options.includeFullOpacityFrame) {
            listOf(255, 170, 96, 32, 0)
        } else {
            listOf(170, 96, 32, 0)
        }
        alphas.forEachIndexed { index, alpha ->
            target.clearPage()
            if (alpha > 0) {
                target.inkPaint.alpha = alpha
                target.drawStrokes(target.pageCanvas, strokes, target.inkPaint)
                target.inkPaint.alpha = 255
            }
            target.render(false, null)
            if (index < alphas.lastIndex) {
                delay(STROKE_FADE_STEP_MS)
            }
        }
        target.clearPage()
        target.render(false, null)
    }
}

class DissolveFadeAnimator(
    private val config: DissolveConfig = DissolveConfig(),
) : InkFadeAnimator {
    override val watchdogMs: Long = config.maxTotalMs + 1000L

    override suspend fun fade(strokes: List<InkStroke>, target: InkFadeTarget, options: InkFadeOptions) {
        target.clearPage()
        target.drawStrokes(target.pageCanvas, strokes, target.inkPaint)
        target.render(false, null)
        val prepared = withContext(Dispatchers.Default) {
            prepare(strokes, target)
        } ?: run {
            target.clearPage()
            target.render(true, null)
            return
        }
        target.registerCancelCleanup {
            target.clearRect(prepared.outputBounds)
        }
        try {
            runDissolveFrames(prepared, target, config)
        } finally {
            target.registerCancelCleanup(null)
            prepared.recycle()
        }
    }

    private fun prepare(strokes: List<InkStroke>, target: InkFadeTarget): PreparedDissolve? {
        val strokeBounds = strokesBounds(strokes) ?: return null
        val baseBounds = Rect(
            strokeBounds.left - config.boundsPaddingPx,
            strokeBounds.top - config.boundsPaddingPx,
            strokeBounds.right + config.boundsPaddingPx,
            strokeBounds.bottom + config.boundsPaddingPx,
        ).boundedTo(target.pageBitmap.width, target.pageBitmap.height) ?: return null
        val mask = rasterizeMask(strokes, baseBounds, target.inkPaint, target)
        val cells = DissolvePlanner.cellsForMask(
            mask = mask.ink,
            maskWidth = mask.width,
            maskHeight = mask.height,
            config = config,
        )
        if (cells.isEmpty()) {
            mask.recycle()
            return null
        }
        val maxDrift = config.maxDisplacementPx
        val outputBounds = Rect(
            baseBounds.left - maxDrift,
            baseBounds.top - maxDrift,
            baseBounds.right + maxDrift,
            baseBounds.bottom + maxDrift,
        ).boundedTo(target.pageBitmap.width, target.pageBitmap.height) ?: baseBounds
        val frameBitmap = Bitmap.createBitmap(outputBounds.width(), outputBounds.height(), Bitmap.Config.ARGB_8888)
        val framePixels = IntArray(outputBounds.width() * outputBounds.height())
        return PreparedDissolve(
            baseBounds = baseBounds,
            outputBounds = outputBounds,
            maskWidth = mask.width,
            cells = cells,
            frameBitmap = frameBitmap,
            framePixels = framePixels,
        ).also {
            mask.recycle()
        }
    }

    private fun rasterizeMask(strokes: List<InkStroke>, bounds: Rect, paint: Paint, target: InkFadeTarget): InkMask {
        val bitmap = Bitmap.createBitmap(bounds.width(), bounds.height(), Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val shiftedPaint = Paint(paint).apply {
            alpha = 255
            color = Color.BLACK
            isAntiAlias = true
        }
        canvas.translate(-bounds.left.toFloat(), -bounds.top.toFloat())
        target.drawStrokes(canvas, strokes, shiftedPaint)
        val pixels = IntArray(bounds.width() * bounds.height())
        bitmap.getPixels(pixels, 0, bounds.width(), 0, 0, bounds.width(), bounds.height())
        val ink = BooleanArray(pixels.size) { index -> Color.alpha(pixels[index]) > 0 }
        bitmap.recycle()
        return InkMask(bounds.width(), bounds.height(), ink)
    }

    private fun strokesBounds(strokes: List<InkStroke>): Rect? {
        var left = Float.POSITIVE_INFINITY
        var top = Float.POSITIVE_INFINITY
        var right = Float.NEGATIVE_INFINITY
        var bottom = Float.NEGATIVE_INFINITY
        strokes.forEach { stroke ->
            stroke.points.forEach { point ->
                left = kotlin.math.min(left, point.x)
                top = kotlin.math.min(top, point.y)
                right = kotlin.math.max(right, point.x)
                bottom = kotlin.math.max(bottom, point.y)
            }
        }
        if (!left.isFinite() || !top.isFinite() || !right.isFinite() || !bottom.isFinite()) return null
        return Rect(left.toInt(), top.toInt(), right.toInt() + 1, bottom.toInt() + 1)
    }
}

internal suspend fun runBitmapDissolveSafely(
    animator: BitmapDissolveAnimator,
    source: Bitmap,
    target: InkFadeTarget,
): Boolean {
    return try {
        withTimeoutOrNull(animator.watchdogMs) {
            animator.fade(source, target)
            true
        } == true
    } catch (exception: CancellationException) {
        throw exception
    } catch (_: Throwable) {
        false
    }
}

class BitmapDissolveAnimator(
    private val config: DissolveConfig = DissolveConfig(),
) {
    val watchdogMs: Long = config.maxTotalMs + 1000L

    suspend fun fade(source: Bitmap, target: InkFadeTarget) {
        target.clearPage()
        target.pageCanvas.drawBitmap(source, 0f, 0f, Paint(Paint.ANTI_ALIAS_FLAG))
        target.render(false, null)
        val prepared = withContext(Dispatchers.Default) {
            prepare(source, target)
        } ?: run {
            target.clearPage()
            target.render(true, null)
            return
        }
        target.registerCancelCleanup {
            target.clearRect(prepared.outputBounds)
        }
        try {
            runDissolveFrames(prepared, target, config)
        } finally {
            target.registerCancelCleanup(null)
            prepared.recycle()
        }
    }

    private fun prepare(source: Bitmap, target: InkFadeTarget): PreparedDissolve? {
        val contentBounds = visibleBounds(source) ?: return null
        val baseBounds = Rect(
            contentBounds.left - config.boundsPaddingPx,
            contentBounds.top - config.boundsPaddingPx,
            contentBounds.right + config.boundsPaddingPx,
            contentBounds.bottom + config.boundsPaddingPx,
        ).boundedTo(target.pageBitmap.width, target.pageBitmap.height) ?: return null
        val sourcePixels = IntArray(baseBounds.width() * baseBounds.height())
        source.getPixels(
            sourcePixels,
            0,
            baseBounds.width(),
            baseBounds.left,
            baseBounds.top,
            baseBounds.width(),
            baseBounds.height(),
        )
        val mask = BooleanArray(sourcePixels.size) { index -> Color.alpha(sourcePixels[index]) > 0 }
        val cells = DissolvePlanner.cellsForMask(
            mask = mask,
            maskWidth = baseBounds.width(),
            maskHeight = baseBounds.height(),
            config = config,
        )
        if (cells.isEmpty()) return null
        val maxDrift = config.maxDisplacementPx
        val outputBounds = Rect(
            baseBounds.left - maxDrift,
            baseBounds.top - maxDrift,
            baseBounds.right + maxDrift,
            baseBounds.bottom + maxDrift,
        ).boundedTo(target.pageBitmap.width, target.pageBitmap.height) ?: baseBounds
        val frameBitmap = Bitmap.createBitmap(outputBounds.width(), outputBounds.height(), Bitmap.Config.ARGB_8888)
        val framePixels = IntArray(outputBounds.width() * outputBounds.height())
        return PreparedDissolve(
            baseBounds = baseBounds,
            outputBounds = outputBounds,
            maskWidth = baseBounds.width(),
            cells = cells,
            frameBitmap = frameBitmap,
            framePixels = framePixels,
            sourcePixels = sourcePixels,
        )
    }

    private fun visibleBounds(source: Bitmap): Rect? {
        val width = source.width
        val height = source.height
        if (width <= 0 || height <= 0) return null
        val row = IntArray(width)
        var left = width
        var top = height
        var right = -1
        var bottom = -1
        for (y in 0 until height) {
            source.getPixels(row, 0, width, 0, y, width, 1)
            for (x in 0 until width) {
                if (Color.alpha(row[x]) > 0) {
                    if (x < left) left = x
                    if (x > right) right = x
                    if (y < top) top = y
                    if (y > bottom) bottom = y
                }
            }
        }
        return if (right >= left && bottom >= top) {
            Rect(left, top, right + 1, bottom + 1)
        } else {
            null
        }
    }
}

private suspend fun runDissolveFrames(
    prepared: PreparedDissolve,
    target: InkFadeTarget,
    config: DissolveConfig,
) {
    val startedAt = SystemClock.elapsedRealtime()
    val totalMs = config.compressedSweepMs + config.cellLifeMs
    while (true) {
        val elapsed = SystemClock.elapsedRealtime() - startedAt
        if (elapsed >= totalMs) break
        withContext(Dispatchers.Default) {
            prepared.renderFrame(elapsed, config)
        }
        withContext(Dispatchers.Main) {
            target.drawFrame(prepared.frameBitmap, prepared.outputBounds.left, prepared.outputBounds.top, prepared.outputBounds)
        }
        val nextDelay = (config.frameMs - (SystemClock.elapsedRealtime() - startedAt - elapsed)).coerceAtLeast(0L)
        delay(nextDelay)
    }
    withContext(Dispatchers.Main) {
        target.clearRect(prepared.outputBounds)
        target.render(false, prepared.outputBounds)
    }
    delay(config.terminalHoldMs)
    withContext(Dispatchers.Main) {
        target.clearRect(prepared.outputBounds)
        target.render(true, prepared.outputBounds)
    }
}

object DissolvePlanner {
    fun cellsForMask(
        mask: BooleanArray,
        maskWidth: Int,
        maskHeight: Int,
        config: DissolveConfig,
    ): List<DissolveCell> {
        require(maskWidth >= 0 && maskHeight >= 0)
        require(mask.size == maskWidth * maskHeight)
        var cellSize = config.cellSizePx.coerceAtLeast(1)
        var cells: List<DissolveCell>
        while (true) {
            cells = buildCells(mask, maskWidth, maskHeight, cellSize, config)
            if (cells.size <= config.maxCells || cellSize >= max(maskWidth, maskHeight).coerceAtLeast(1)) {
                return cells
            }
            cellSize = ceil(cellSize * 1.5f).toInt().coerceAtLeast(cellSize + 1)
        }
    }

    fun survivorIndices(cell: DissolveCell, progress: Float): IntArray {
        val erosion = easeInQuad(progress.coerceIn(0f, 1f))
        return cell.inkPixelIndices.filter { pixelIndex ->
            hashUnit(cell.seed, pixelIndex) >= erosion
        }.toIntArray()
    }

    fun survives(seed: Int, pixelIndex: Int, progress: Float): Boolean {
        return hashUnit(seed, pixelIndex) >= easeInQuad(progress.coerceIn(0f, 1f))
    }

    fun compressedSweepMs(config: DissolveConfig): Long = config.compressedSweepMs

    private fun buildCells(
        mask: BooleanArray,
        maskWidth: Int,
        maskHeight: Int,
        cellSize: Int,
        config: DissolveConfig,
    ): List<DissolveCell> {
        if (maskWidth == 0 || maskHeight == 0) return emptyList()
        val cells = mutableListOf<DissolveCell>()
        val sweepMs = config.compressedSweepMs
        var y = 0
        while (y < maskHeight) {
            var x = 0
            while (x < maskWidth) {
                val width = minOf(cellSize, maskWidth - x)
                val height = minOf(cellSize, maskHeight - y)
                val inkPixels = mutableListOf<Int>()
                for (localY in 0 until height) {
                    val rowOffset = (y + localY) * maskWidth
                    for (localX in 0 until width) {
                        val index = rowOffset + x + localX
                        if (mask[index]) inkPixels.add(index)
                    }
                }
                if (inkPixels.isNotEmpty()) {
                    val seed = stableSeed(x, y, cellSize)
                    val centerX = x + width / 2f
                    val xProgress = if (maskWidth <= 1) 0f else centerX / maskWidth
                    val jitter = ((hashUnit(seed, -1) * 2f) - 1f) * config.delayJitterMs
                    val angleJitter = ((hashUnit(seed, -2) * 2f) - 1f) * config.driftJitterDegrees
                    val driftUnit = hashUnit(seed, -3)
                    val windPhase = hashUnit(seed, -4) * WIND_FLUTTER_RADIANS
                    cells.add(
                        DissolveCell(
                            cellX = x,
                            cellY = y,
                            width = width,
                            height = height,
                            inkPixelIndices = inkPixels.toIntArray(),
                            seed = seed,
                            delayMs = (xProgress * sweepMs + jitter).roundToInt().toLong().coerceAtLeast(0L),
                            driftAngleRad = Math.toRadians((config.driftAngleDegrees + angleJitter).toDouble()).toFloat(),
                            driftDistPx = config.driftMinPx + (config.driftMaxPx - config.driftMinPx) * driftUnit,
                            windPhaseRad = windPhase,
                        ),
                    )
                }
                x += cellSize
            }
            y += cellSize
        }
        return cells.sortedWith(compareBy<DissolveCell> { it.cellX }.thenBy { it.cellY })
    }

    private fun stableSeed(x: Int, y: Int, cellSize: Int): Int {
        var value = 0x45d9f3b
        value = value xor (x * 0x1f123bb5)
        value = value xor (y * 0x05491333)
        value = value xor (cellSize * 0x27d4eb2d)
        return value
    }

    fun hashUnit(seed: Int, index: Int): Float {
        var value = seed xor (index * 0x9e3779b9.toInt())
        value = value xor (value ushr 16)
        value *= 0x7feb352d
        value = value xor (value ushr 15)
        value *= 0x846ca68b.toInt()
        value = value xor (value ushr 16)
        return (value ushr 1).toFloat() / Int.MAX_VALUE.toFloat()
    }

    fun offsetFor(cell: DissolveCell, progress: Float, config: DissolveConfig): DissolveOffset {
        val p = progress.coerceIn(0f, 1f)
        val drift = easeOutCubic(p) * cell.driftDistPx
        val shear = easeInQuad(p) * config.windShearPx
        val flutter = sin(cell.windPhaseRad + p * WIND_FLUTTER_RADIANS) * config.windFlutterPx * p * (1f - p)
        val x = (cos(cell.driftAngleRad) * drift + shear).roundToInt()
        val y = (sin(cell.driftAngleRad) * drift + flutter).roundToInt()
        val tail = easeOutQuad(p) * config.windTailPx
        val tailX = (cos(cell.driftAngleRad) * tail + shear * 0.25f).roundToInt()
        val tailY = (sin(cell.driftAngleRad) * tail).roundToInt()
        return DissolveOffset(x, y, tailX, tailY)
    }

    private fun easeInQuad(progress: Float): Float = progress * progress

    private fun easeOutQuad(progress: Float): Float {
        return 1f - (1f - progress) * (1f - progress)
    }

    private fun easeOutCubic(progress: Float): Float {
        val inverse = 1f - progress
        return 1f - inverse * inverse * inverse
    }
}

data class DissolveCell(
    val cellX: Int,
    val cellY: Int,
    val width: Int,
    val height: Int,
    val inkPixelIndices: IntArray,
    val seed: Int,
    val delayMs: Long,
    val driftAngleRad: Float,
    val driftDistPx: Float,
    val windPhaseRad: Float,
)

data class DissolveOffset(
    val x: Int,
    val y: Int,
    val tailX: Int,
    val tailY: Int,
)

internal class PreparedDissolve(
    val baseBounds: Rect,
    val outputBounds: Rect,
    val maskWidth: Int,
    val cells: List<DissolveCell>,
    val frameBitmap: Bitmap,
    val framePixels: IntArray,
    val sourcePixels: IntArray? = null,
) {
    fun renderFrame(elapsedMs: Long, config: DissolveConfig) {
        Arrays.fill(framePixels, Color.TRANSPARENT)
        val outputWidth = outputBounds.width()
        val outputHeight = outputBounds.height()
        val baseOffsetX = baseBounds.left - outputBounds.left
        val baseOffsetY = baseBounds.top - outputBounds.top
        cells.forEach { cell ->
            val localElapsed = elapsedMs - cell.delayMs
            if (localElapsed < 0L) {
                drawCellPixels(cell, baseOffsetX, baseOffsetY, outputWidth, outputHeight)
                return@forEach
            }
            if (localElapsed >= config.cellLifeMs) return@forEach
            val progress = (localElapsed.toFloat() / config.cellLifeMs).coerceIn(0f, 1f)
            val offset = DissolvePlanner.offsetFor(cell, progress, config)
            cell.inkPixelIndices.forEach { pixelIndex ->
                if (!DissolvePlanner.survives(cell.seed, pixelIndex, progress)) return@forEach
                val color = colorFor(pixelIndex)
                val maskX = pixelIndex % maskWidth
                val maskY = pixelIndex / maskWidth
                val x = baseOffsetX + maskX + offset.x
                val y = baseOffsetY + maskY + offset.y
                putPixel(outputWidth, outputHeight, x, y, color)
                if (
                    progress > WIND_TAIL_START_PROGRESS &&
                    offset.tailX != 0 &&
                    config.windTailSteps > 0 &&
                    DissolvePlanner.hashUnit(cell.seed, pixelIndex xor WIND_TAIL_HASH_SALT) < config.windTailDensity
                ) {
                    for (step in 1..config.windTailSteps) {
                        val ratio = step.toFloat() / (config.windTailSteps + 1)
                        putPixel(
                            outputWidth = outputWidth,
                            outputHeight = outputHeight,
                            x = x - (offset.tailX * ratio).roundToInt(),
                            y = y - (offset.tailY * ratio).roundToInt(),
                            color = color,
                        )
                    }
                }
            }
        }
        frameBitmap.setPixels(framePixels, 0, outputWidth, 0, 0, outputWidth, outputHeight)
    }

    private fun drawCellPixels(
        cell: DissolveCell,
        baseOffsetX: Int,
        baseOffsetY: Int,
        outputWidth: Int,
        outputHeight: Int,
    ) {
        cell.inkPixelIndices.forEach { pixelIndex ->
            val maskX = pixelIndex % maskWidth
            val maskY = pixelIndex / maskWidth
            putPixel(
                outputWidth = outputWidth,
                outputHeight = outputHeight,
                x = baseOffsetX + maskX,
                y = baseOffsetY + maskY,
                color = colorFor(pixelIndex),
            )
        }
    }

    fun recycle() {
        frameBitmap.recycle()
    }

    private fun colorFor(pixelIndex: Int): Int {
        val color = sourcePixels?.getOrNull(pixelIndex) ?: Color.BLACK
        return if (Color.alpha(color) > 0) color else Color.BLACK
    }

    private fun putPixel(outputWidth: Int, outputHeight: Int, x: Int, y: Int, color: Int) {
        if (x in 0 until outputWidth && y in 0 until outputHeight) {
            framePixels[y * outputWidth + x] = color
        }
    }
}

private data class InkMask(
    val width: Int,
    val height: Int,
    val ink: BooleanArray,
) {
    fun recycle() = Unit
}

private fun Rect.boundedTo(width: Int, height: Int): Rect? {
    val bounded = Rect(
        left.coerceAtLeast(0),
        top.coerceAtLeast(0),
        right.coerceAtMost(width),
        bottom.coerceAtMost(height),
    )
    return if (bounded.width() > 0 && bounded.height() > 0) bounded else null
}

private const val STROKE_FADE_STEP_MS = 150L
private const val WIND_FLUTTER_RADIANS = (Math.PI.toFloat() * 2f)
private const val WIND_TAIL_HASH_SALT = -0x4a3f21
private const val WIND_TAIL_START_PROGRESS = 0.12f
