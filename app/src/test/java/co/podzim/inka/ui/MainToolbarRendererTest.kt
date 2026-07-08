package co.podzim.inka.ui

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
class MainToolbarRendererTest {
    @Test
    fun `full main toolbar shows brand and main actions`() {
        val toolbar = renderer().create(
            MainToolbarState(
                historyOpen = false,
                immersive = false,
                showLogButton = true,
            ),
        )

        assertTrue(toolbar.hasContentDescription("Toggle immersive mode"))
        assertTrue(toolbar.hasText("Inka"))
        assertTrue(toolbar.hasContentDescription("Erase page"))
        assertTrue(toolbar.hasContentDescription("Read notebook"))
        assertTrue(toolbar.hasContentDescription("AI log"))
        assertTrue(toolbar.hasContentDescription("Settings"))
        assertFalse(toolbar.hasContentDescription("Back"))
        assertFalse(toolbar.hasContentDescription("Burn notebook"))
    }

    @Test
    fun `immersive main toolbar hides app name and actions`() {
        val toolbar = renderer().create(
            MainToolbarState(
                historyOpen = false,
                immersive = true,
                showLogButton = true,
            ),
        )

        assertTrue(toolbar.hasContentDescription("Toggle immersive mode"))
        assertFalse(toolbar.hasText("Inka"))
        assertFalse(toolbar.hasContentDescription("Erase page"))
        assertFalse(toolbar.hasContentDescription("Read notebook"))
        assertFalse(toolbar.hasContentDescription("AI log"))
        assertFalse(toolbar.hasContentDescription("Settings"))
    }

    @Test
    fun `history toolbar shows back title and burn only`() {
        val toolbar = renderer().create(
            MainToolbarState(
                historyOpen = true,
                immersive = false,
                showLogButton = true,
            ),
        )

        assertTrue(toolbar.hasContentDescription("Back"))
        assertTrue(toolbar.hasText("History"))
        assertTrue(toolbar.hasContentDescription("Burn notebook"))
        assertFalse(toolbar.hasContentDescription("Erase page"))
        assertFalse(toolbar.hasContentDescription("Read notebook"))
        assertFalse(toolbar.hasContentDescription("AI log"))
        assertFalse(toolbar.hasContentDescription("Settings"))
    }

    @Test
    fun `main toolbar invokes configured callbacks`() {
        val callbacks = RecordingCallbacks()
        val toolbar = renderer(callbacks).create(
            MainToolbarState(
                historyOpen = false,
                immersive = false,
                showLogButton = true,
            ),
        )

        toolbar.findContentDescription("Erase page")!!.performClick()
        toolbar.findContentDescription("Read notebook")!!.performClick()
        toolbar.findContentDescription("AI log")!!.performClick()
        toolbar.findContentDescription("Settings")!!.performClick()

        assertEquals(listOf("icon", "erase", "icon", "icon", "read", "icon", "icon", "log", "icon", "icon", "settings", "icon"), callbacks.events)
    }

    private fun renderer(callbacks: MainToolbarRenderer.Callbacks = RecordingCallbacks()): MainToolbarRenderer {
        return MainToolbarRenderer(
            context = RuntimeEnvironment.getApplication(),
            callbacks = callbacks,
            appName = "Inka",
        )
    }

    private fun View.hasContentDescription(expected: String): Boolean {
        return findContentDescription(expected) != null
    }

    private fun View.findContentDescription(expected: String): View? {
        if (contentDescription?.toString() == expected) return this
        if (this !is ViewGroup) return null
        for (index in 0 until childCount) {
            val match = getChildAt(index).findContentDescription(expected)
            if (match != null) return match
        }
        return null
    }

    private fun View.hasText(expected: String): Boolean {
        if (this is TextView && text?.toString() == expected) return true
        if (this !is ViewGroup) return false
        for (index in 0 until childCount) {
            if (getChildAt(index).hasText(expected)) return true
        }
        return false
    }

    private class RecordingCallbacks : MainToolbarRenderer.Callbacks {
        val events = mutableListOf<String>()

        override fun onToggleImmersive() {
            events += "immersive"
        }

        override fun onErase() {
            events += "erase"
        }

        override fun onRead() {
            events += "read"
        }

        override fun onToggleLog() {
            events += "log"
        }

        override fun onSettings() {
            events += "settings"
        }

        override fun onCloseHistory() {
            events += "back"
        }

        override fun onBurnNotebook() {
            events += "burn"
        }

        override fun onIconInteraction() {
            events += "icon"
        }
    }
}
