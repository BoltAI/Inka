package com.inkwell.diary.ink

import android.content.Context
import android.graphics.Rect
import com.onyx.android.sdk.api.device.epd.EpdController

internal class PenFingerTouchGuard(
    private val ctpController: CtpController,
    private val disableDuringStroke: Boolean = false,
    private val disableRegionsProvider: () -> Array<Rect> = { emptyArray() },
) {
    private var disabled = false

    interface CtpController {
        fun disable(regions: Array<Rect>)
        fun reset()
    }

    fun onPenDown() {
        if (!disableDuringStroke) return
        val regions = disableRegionsProvider()
            .filter { it.right > it.left && it.bottom > it.top }
            .toTypedArray()
        if (regions.isEmpty()) return
        runCatching {
            ctpController.disable(regions)
            disabled = true
        }
    }

    fun onPenUp() {
        if (!disabled) return
        disabled = false
        runCatching { ctpController.reset() }
    }

    companion object {
        fun onyx(
            context: Context,
            disableDuringStroke: Boolean = false,
            disableRegionsProvider: () -> Array<Rect> = { emptyArray() },
        ): PenFingerTouchGuard {
            val appContext = context.applicationContext
            return PenFingerTouchGuard(
                ctpController = object : CtpController {
                    override fun disable(regions: Array<Rect>) {
                        EpdController.setAppCTPDisableRegion(appContext, regions)
                    }

                    override fun reset() {
                        EpdController.appResetCTPDisableRegion(appContext)
                    }
                },
                disableDuringStroke = disableDuringStroke,
                disableRegionsProvider = disableRegionsProvider,
            )
        }
    }
}
