package com.inkwell.diary.ui

import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RuntimeEnvironment
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class DebugPanelControllerTest {
    @Test
    fun `starts hidden and toggles visibility`() {
        val controller = controller()

        assertFalse(controller.visible)
        assertEquals(View.GONE, controller.view.visibility)

        assertTrue(controller.toggle())
        assertTrue(controller.visible)
        assertEquals(View.VISIBLE, controller.view.visibility)

        assertFalse(controller.toggle())
        assertFalse(controller.visible)
        assertEquals(View.GONE, controller.view.visibility)
    }

    @Test
    fun `hide returns whether visible state changed`() {
        val controller = controller()

        assertFalse(controller.hide())

        controller.toggle()

        assertTrue(controller.hide())
        assertFalse(controller.visible)
        assertEquals(View.GONE, controller.view.visibility)
    }

    @Test
    fun `append stores formatted log text in panel`() {
        var uptime = 1_234L
        val controller = controller(maxLines = 2) { uptime }

        val first = controller.append("ready")
        uptime = 2_345L
        controller.append("loaded")

        assertEquals("1.234  ready", first)
        assertEquals(
            """
            1.234  ready
            2.345  loaded
            """.trimIndent(),
            controller.view.findTextViewText(),
        )
    }

    private fun controller(
        maxLines: Int = 10,
        uptimeMillis: () -> Long = { 0L },
    ): DebugPanelController {
        return DebugPanelController(
            context = RuntimeEnvironment.getApplication(),
            maxLines = maxLines,
            uptimeMillis = uptimeMillis,
        )
    }

    private fun View.findTextViewText(): String {
        if (this is TextView) return text.toString()
        if (this is ViewGroup) {
            for (index in 0 until childCount) {
                val text = getChildAt(index).findTextViewText()
                if (text.isNotEmpty()) return text
            }
        }
        return ""
    }
}
