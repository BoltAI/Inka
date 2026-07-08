package co.podzim.inka.ui

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NotebookLoadRenderPolicyTest {
    @Test
    fun `renders loaded notebook only when page is idle and visible`() {
        assertTrue(
            shouldRenderAfterNotebookLoad(
                renderOnComplete = true,
                rendererInitialized = true,
                pageSurfaceInitialized = true,
                settingsPanelOpen = false,
                historyOpen = false,
                strokeStoreEmpty = true,
                busy = false,
            ),
        )
    }

    @Test
    fun `does not render loaded notebook over active user ink`() {
        assertFalse(
            shouldRenderAfterNotebookLoad(
                renderOnComplete = true,
                rendererInitialized = true,
                pageSurfaceInitialized = true,
                settingsPanelOpen = false,
                historyOpen = false,
                strokeStoreEmpty = false,
                busy = false,
            ),
        )
    }

    @Test
    fun `does not render loaded notebook over active commit work`() {
        assertFalse(
            shouldRenderAfterNotebookLoad(
                renderOnComplete = true,
                rendererInitialized = true,
                pageSurfaceInitialized = true,
                settingsPanelOpen = false,
                historyOpen = false,
                strokeStoreEmpty = true,
                busy = true,
            ),
        )
    }

    @Test
    fun `does not render loaded notebook behind other screens`() {
        assertFalse(
            shouldRenderAfterNotebookLoad(
                renderOnComplete = true,
                rendererInitialized = true,
                pageSurfaceInitialized = true,
                settingsPanelOpen = true,
                historyOpen = false,
                strokeStoreEmpty = true,
                busy = false,
            ),
        )
        assertFalse(
            shouldRenderAfterNotebookLoad(
                renderOnComplete = true,
                rendererInitialized = true,
                pageSurfaceInitialized = true,
                settingsPanelOpen = false,
                historyOpen = true,
                strokeStoreEmpty = true,
                busy = false,
            ),
        )
    }
}
