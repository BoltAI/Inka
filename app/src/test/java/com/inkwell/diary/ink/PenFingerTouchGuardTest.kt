package com.inkwell.diary.ink

import android.graphics.Rect
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class PenFingerTouchGuardTest {
    @Test
    fun `default guard keeps finger touch available during pen strokes`() {
        val disableCalls = mutableListOf<Array<Rect>>()
        var resetCalls = 0
        val guard = PenFingerTouchGuard(
            ctpController = object : PenFingerTouchGuard.CtpController {
                override fun disable(regions: Array<Rect>) {
                    disableCalls.add(regions)
                }

                override fun reset() {
                    resetCalls += 1
                }
            },
            disableDuringStroke = false,
            disableRegionsProvider = { arrayOf(Rect(0, 0, 100, 100)) },
        )

        guard.onPenDown()
        guard.onPenUp()

        assertTrue(disableCalls.isEmpty())
        assertTrue(resetCalls == 0)
    }

    @Test
    fun `enabled guard ignores empty regions`() {
        val disableCalls = mutableListOf<Array<Rect>>()
        var resetCalls = 0
        val guard = PenFingerTouchGuard(
            ctpController = object : PenFingerTouchGuard.CtpController {
                override fun disable(regions: Array<Rect>) {
                    disableCalls.add(regions)
                }

                override fun reset() {
                    resetCalls += 1
                }
            },
            disableDuringStroke = true,
            disableRegionsProvider = { arrayOf(Rect(0, 0, 0, 100)) },
        )

        guard.onPenDown()
        guard.onPenUp()

        assertTrue(disableCalls.isEmpty())
        assertTrue(resetCalls == 0)
    }

    @Test
    fun `enabled guard disables valid regions until pen up`() {
        val disableCalls = mutableListOf<Array<Rect>>()
        var resetCalls = 0
        val guard = PenFingerTouchGuard(
            ctpController = object : PenFingerTouchGuard.CtpController {
                override fun disable(regions: Array<Rect>) {
                    disableCalls.add(regions)
                }

                override fun reset() {
                    resetCalls += 1
                }
            },
            disableDuringStroke = true,
            disableRegionsProvider = { arrayOf(Rect(0, 0, 100, 100)) },
        )

        guard.onPenDown()
        guard.onPenUp()

        val disabledRegion = disableCalls.single().single()
        assertTrue(disabledRegion.left == 0)
        assertTrue(disabledRegion.top == 0)
        assertTrue(disabledRegion.right == 100)
        assertTrue(disabledRegion.bottom == 100)
        assertTrue(resetCalls == 1)
    }
}
