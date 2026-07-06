package com.inkwell.diary.page

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DissolvePlannerTest {
    @Test
    fun `cell decomposition only emits ink-bearing cells`() {
        val mask = BooleanArray(16 * 16)
        mask[1 * 16 + 1] = true
        mask[12 * 16 + 12] = true

        val cells = DissolvePlanner.cellsForMask(
            mask = mask,
            maskWidth = 16,
            maskHeight = 16,
            config = DissolveConfig(cellSizePx = 4, maxCells = 20, delayJitterMs = 0L),
        )

        assertEquals(2, cells.size)
        assertTrue(cells.all { it.inkPixelIndices.isNotEmpty() })
        assertEquals(listOf(0, 12), cells.map { it.cellX })
    }

    @Test
    fun `adaptive cell size caps dense masks`() {
        val mask = BooleanArray(120 * 120) { true }

        val cells = DissolvePlanner.cellsForMask(
            mask = mask,
            maskWidth = 120,
            maskHeight = 120,
            config = DissolveConfig(cellSizePx = 3, maxCells = 32),
        )

        assertTrue(cells.size <= 32)
        assertTrue(cells.any { it.width > 3 || it.height > 3 })
    }

    @Test
    fun `same seed and progress produce identical survivors`() {
        val cell = dissolveCell()

        val first = DissolvePlanner.survivorIndices(cell, progress = 0.42f).toList()
        val second = DissolvePlanner.survivorIndices(cell, progress = 0.42f).toList()

        assertEquals(first, second)
    }

    @Test
    fun `erosion is monotonic`() {
        val cell = dissolveCell()

        val early = DissolvePlanner.survivorIndices(cell, progress = 0.3f).toSet()
        val late = DissolvePlanner.survivorIndices(cell, progress = 0.7f).toSet()

        assertTrue(early.containsAll(late))
    }

    @Test
    fun `cell delays sweep left to right when jitter is disabled`() {
        val mask = BooleanArray(30 * 4)
        for (x in listOf(1, 11, 21)) {
            mask[1 * 30 + x] = true
        }

        val cells = DissolvePlanner.cellsForMask(
            mask = mask,
            maskWidth = 30,
            maskHeight = 4,
            config = DissolveConfig(cellSizePx = 10, maxCells = 10, delayJitterMs = 0L),
        )

        val delays = cells.map { it.delayMs }
        assertEquals(delays.sorted(), delays)
    }

    @Test
    fun `sweep compresses to stay inside max total duration`() {
        val config = DissolveConfig(sweepMs = 5_000L, cellLifeMs = 450L, maxTotalMs = 2_000L)

        assertEquals(1_550L, DissolvePlanner.compressedSweepMs(config))
    }

    @Test
    fun `default dissolve carries particles like wind`() {
        val cell = dissolveCell().copy(
            driftAngleRad = Math.toRadians(-18.0).toFloat(),
            driftDistPx = 120f,
            windPhaseRad = 0f,
        )

        val early = DissolvePlanner.offsetFor(cell, progress = 0.2f, config = DissolveConfig())
        val late = DissolvePlanner.offsetFor(cell, progress = 0.85f, config = DissolveConfig())

        assertTrue(late.x > early.x + 60)
        assertTrue(late.y < early.y)
        assertTrue(late.tailX > 0)
    }

    @Test
    fun `wind offset starts exactly at original ink position`() {
        val offset = DissolvePlanner.offsetFor(dissolveCell(), progress = 0f, config = DissolveConfig())

        assertEquals(0, offset.x)
        assertEquals(0, offset.y)
        assertEquals(0, offset.tailX)
        assertEquals(0, offset.tailY)
    }

    private fun dissolveCell(): DissolveCell {
        return DissolveCell(
            cellX = 0,
            cellY = 0,
            width = 10,
            height = 10,
            inkPixelIndices = IntArray(100) { it },
            seed = 123456,
            delayMs = 0L,
            driftAngleRad = 0f,
            driftDistPx = 32f,
            windPhaseRad = 0f,
        )
    }
}
