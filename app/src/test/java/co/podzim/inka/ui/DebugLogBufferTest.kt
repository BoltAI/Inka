package co.podzim.inka.ui

import org.junit.Assert.assertEquals
import org.junit.Test

class DebugLogBufferTest {
    @Test
    fun `append formats uptime timestamp and message`() {
        val buffer = DebugLogBuffer(maxLines = 10) { 12_034L }

        val entry = buffer.append("ready")

        assertEquals("12.034  ready", entry)
        assertEquals("12.034  ready", buffer.text())
    }

    @Test
    fun `buffer keeps newest max lines`() {
        var time = 1_000L
        val buffer = DebugLogBuffer(maxLines = 2) { time }

        buffer.append("one")
        time = 2_000L
        buffer.append("two")
        time = 3_000L
        buffer.append("three")

        assertEquals(
            """
            2.000  two
            3.000  three
            """.trimIndent(),
            buffer.text(),
        )
    }
}
