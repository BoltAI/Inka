package com.inkwell.diary.ui

import org.junit.Assert.assertEquals
import org.junit.Test

class CaptureInputPolicyTest {
    @Test
    fun `settings disables capture even when history is open`() {
        assertEquals(
            CaptureInputMode.Disabled,
            captureInputMode(settingsPanelOpen = true, busy = false, historyOpen = true),
        )
    }

    @Test
    fun `busy disables capture`() {
        assertEquals(
            CaptureInputMode.Disabled,
            captureInputMode(settingsPanelOpen = false, busy = true, historyOpen = false),
        )
    }

    @Test
    fun `history uses read-only capture`() {
        assertEquals(
            CaptureInputMode.ReadOnly,
            captureInputMode(settingsPanelOpen = false, busy = false, historyOpen = true),
        )
    }

    @Test
    fun `normal page remains writable`() {
        assertEquals(
            CaptureInputMode.Writable,
            captureInputMode(settingsPanelOpen = false, busy = false, historyOpen = false),
        )
    }
}
