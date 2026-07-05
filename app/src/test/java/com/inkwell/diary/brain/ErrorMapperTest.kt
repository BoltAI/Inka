package com.inkwell.diary.brain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class ErrorMapperTest {
    @Test
    fun `maps invalid key to alert copy plus actionable title`() {
        val error = ErrorMapper.from(BrainErrorKind.InvalidKey)

        assertEquals("The selected provider key did not work.", error.diegeticLine)
        assertNotNull(error.banner)
    }

    @Test
    fun `maps network to alert copy without settings title`() {
        val error = ErrorMapper.from(BrainErrorKind.Network)

        assertEquals("No internet connection. Connect to Wi-Fi and try again.", error.diegeticLine)
        assertNull(error.banner)
    }

    @Test
    fun `maps unknown without whimsical fallback copy`() {
        val error = ErrorMapper.from(BrainErrorKind.Unknown)

        assertEquals("The AI request failed. Check settings and try again.", error.diegeticLine)
    }
}
