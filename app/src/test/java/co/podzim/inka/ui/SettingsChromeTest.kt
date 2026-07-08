package co.podzim.inka.ui

import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
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
class SettingsChromeTest {
    @Test
    fun `renders title and invokes back callback`() {
        var backCount = 0
        val chrome = SettingsChrome(RuntimeEnvironment.getApplication()) {
            backCount += 1
        }

        chrome.setTitle("Developer")
        chrome.view.findContentDescription("Back")!!.performClick()

        assertTrue(chrome.view.hasText("Developer"))
        assertEquals(1, backCount)
    }

    @Test
    fun `set content replaces prior screen`() {
        val chrome = SettingsChrome(RuntimeEnvironment.getApplication()) {}
        val first = FrameLayout(RuntimeEnvironment.getApplication()).apply {
            contentDescription = "First"
        }
        val second = FrameLayout(RuntimeEnvironment.getApplication()).apply {
            contentDescription = "Second"
        }

        chrome.setContent(first)
        chrome.setContent(second)

        assertFalse(chrome.view.hasContentDescription("First"))
        assertTrue(chrome.view.hasContentDescription("Second"))
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
}
