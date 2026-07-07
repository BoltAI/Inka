package com.inkwell.diary.ui

import org.junit.Assert.assertEquals
import org.junit.Test

class SettingsSliderRangeTest {
    @Test
    fun `maps progress to stepped values`() {
        val range = SettingsSliderRange(min = 500, max = 3000, step = 100)

        assertEquals(25, range.maxProgress)
        assertEquals(500, range.valueForProgress(-1))
        assertEquals(500, range.valueForProgress(0))
        assertEquals(600, range.valueForProgress(1))
        assertEquals(3000, range.valueForProgress(25))
        assertEquals(3000, range.valueForProgress(26))
    }

    @Test
    fun `maps values to clamped progress`() {
        val range = SettingsSliderRange(min = 10, max = 20, step = 2)

        assertEquals(0, range.progressForValue(0))
        assertEquals(0, range.progressForValue(10))
        assertEquals(1, range.progressForValue(12))
        assertEquals(2, range.progressForValue(15))
        assertEquals(5, range.progressForValue(20))
        assertEquals(5, range.progressForValue(30))
    }

    @Test
    fun `normalizes reversed range and invalid step like the original panel code`() {
        val range = SettingsSliderRange(min = 5, max = 1, step = 0)

        assertEquals(4, range.maxProgress)
        assertEquals(1, range.valueForProgress(0))
        assertEquals(5, range.valueForProgress(99))
        assertEquals(2, range.progressForValue(3))
    }
}
