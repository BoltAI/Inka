package co.podzim.inka.page

import android.graphics.Rect
import android.view.View
import com.onyx.android.sdk.api.device.epd.EpdController
import com.onyx.android.sdk.api.device.epd.UpdateMode

class EinkRefresher {
    fun configureNewSurfaces() {
        runCatching {
            EpdController.useGCForNewSurface(true)
        }
    }

    fun <T> withFastRefresh(view: View, block: () -> T): T {
        runCatching {
            EpdController.setViewDefaultUpdateMode(view, UpdateMode.HAND_WRITING_REPAINT_MODE)
        }
        return try {
            block()
        } finally {
            runCatching {
                EpdController.resetViewUpdateMode(view)
            }
        }
    }

    fun requestFullRefresh(view: View) {
        val refreshed = runCatching {
            EpdController.refreshScreen(view, UpdateMode.GC)
            true
        }.getOrDefault(false)

        if (!refreshed) {
            view.invalidate()
        }
    }

    fun requestFastPartialRefresh(view: View, area: Rect) {
        runCatching {
            EpdController.invalidate(view, area.left, area.top, area.right, area.bottom, UpdateMode.DU)
        }
    }

    fun requestDeepRefresh(view: View) {
        val refreshed = runCatching {
            EpdController.refreshScreen(view, UpdateMode.DEEP_GC)
            true
        }.getOrDefault(false)

        if (!refreshed) {
            view.invalidate()
        }
    }
}
