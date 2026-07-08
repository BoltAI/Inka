package co.podzim.inka.page

import android.view.View
import com.onyx.android.sdk.api.device.epd.EpdController
import com.onyx.android.sdk.api.device.epd.UpdateOption
import com.onyx.android.sdk.api.device.epd.UpdateMode

class EinkRefresher {
    fun configureAppRefreshMode() {
        runCatching {
            EpdController.setAppScopeRefreshMode(UpdateOption.NORMAL)
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
