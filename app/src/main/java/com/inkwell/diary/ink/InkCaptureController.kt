package com.inkwell.diary.ink

import android.content.Context
import android.graphics.Color
import android.graphics.Rect
import android.graphics.RectF
import android.os.SystemClock
import android.util.Log
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.SurfaceView
import com.inkwell.diary.data.InkPoint
import com.inkwell.diary.data.InkStroke
import com.inkwell.diary.data.StrokeStore
import com.onyx.android.sdk.data.note.TouchPoint
import com.onyx.android.sdk.api.device.epd.EpdController
import com.onyx.android.sdk.pen.RawInputCallback
import com.onyx.android.sdk.pen.TouchHelper
import com.onyx.android.sdk.pen.data.TouchPointList

class InkCaptureController(
    context: Context,
    private val surfaceView: SurfaceView,
    private val strokeStore: StrokeStore,
    private val commitTimer: CommitTimer,
    private val pageRectProvider: () -> Rect,
    private val excludeRectsProvider: () -> List<Rect>,
    private val callbacks: Callbacks,
) {
    interface Callbacks {
        fun onPenDown(): Boolean
        fun onPenUp()
        fun onCommitRequested()
        fun onFingerTap(x: Float, y: Float)
        fun onFingerSwipeLeft()
        fun onFingerSwipeRight()
        fun onStrokeCaptured(strokes: List<InkStroke>, dirtyRect: RectF?)
    }

    private val appContext = context.applicationContext

    private val gestureDetector = GestureDetector(
        context,
        object : GestureDetector.SimpleOnGestureListener() {
            override fun onSingleTapUp(e: MotionEvent): Boolean {
                callbacks.onFingerTap(e.x, e.y)
                return true
            }

            override fun onDoubleTap(e: MotionEvent): Boolean {
                commitTimer.commitNow()
                callbacks.onCommitRequested()
                return true
            }

            override fun onFling(
                e1: MotionEvent?,
                e2: MotionEvent,
                velocityX: Float,
                velocityY: Float,
            ): Boolean {
                val start = e1 ?: return false
                val dx = e2.x - start.x
                val dy = e2.y - start.y
                if (kotlin.math.abs(dx) < SWIPE_DISTANCE_PX || kotlin.math.abs(dx) < kotlin.math.abs(dy) * 1.4f) {
                    return false
                }
                if (dx < 0) {
                    callbacks.onFingerSwipeLeft()
                } else {
                    callbacks.onFingerSwipeRight()
                }
                return true
            }
        },
    )

    private var touchHelper: TouchHelper? = null
    private var rawDrawingActive = false
    private var inputEnabled = true
    private var rawStrokeAccepted = false
    private var fallbackStrokeAccepted = false

    private val rawCallback = object : RawInputCallback() {
        override fun onBeginRawDrawing(b: Boolean, touchPoint: TouchPoint) {
            if (!inputEnabled) return
            disableFingerTouchDuringStroke()
            rawStrokeAccepted = callbacks.onPenDown()
            if (!rawStrokeAccepted) {
                clearRejectedRawStroke()
                enableFingerTouchAfterStroke()
                return
            }
            strokeStore.beginStroke(touchPoint.toInkPoint())
            commitTimer.onPenDown()
            Log.d(TAG, "raw pen begin")
        }

        override fun onEndRawDrawing(b: Boolean, touchPoint: TouchPoint) {
            try {
                if (!inputEnabled || !rawStrokeAccepted) return
                strokeStore.addPoint(touchPoint.toInkPoint())
                strokeStore.finishCurrent()
                callbacks.onPenUp()
                commitTimer.onPenUp()
                Log.d(TAG, "raw pen end")
                publishCapturedInk(dirtyRect = null)
            } finally {
                rawStrokeAccepted = false
                enableFingerTouchAfterStroke()
            }
        }

        override fun onRawDrawingTouchPointMoveReceived(touchPoint: TouchPoint) {
            if (!inputEnabled || !rawStrokeAccepted) return
            strokeStore.addPoint(touchPoint.toInkPoint())
            commitTimer.onStrokeMove()
        }

        override fun onRawDrawingTouchPointListReceived(touchPointList: TouchPointList) {
            if (!inputEnabled || !rawStrokeAccepted) return
            strokeStore.replaceCurrentStroke(touchPointList.points.map { it.toInkPoint() })
            commitTimer.onStrokeMove()
            Log.d(TAG, "raw pen point list size=${touchPointList.points.size}")
        }

        override fun onBeginRawErasing(b: Boolean, touchPoint: TouchPoint) = Unit
        override fun onEndRawErasing(b: Boolean, touchPoint: TouchPoint) = Unit
        override fun onRawErasingTouchPointMoveReceived(touchPoint: TouchPoint) = Unit
        override fun onRawErasingTouchPointListReceived(touchPointList: TouchPointList) = Unit

        override fun onPenUpRefresh(refreshRect: RectF) {
            if (!inputEnabled || !rawStrokeAccepted) return
            Log.d(TAG, "raw pen refresh rect=$refreshRect")
            publishCapturedInk(refreshRect)
        }
    }

    fun attach() {
        installFallbackTouchHandler()
        rawDrawingActive = runCatching {
            val helper = TouchHelper.create(surfaceView, rawCallback)
                .setStrokeWidth(strokeWidthPx())
                .setLimitRect(pageRectProvider(), excludeRectsProvider())
                .openRawDrawing()
            applyRawDrawingSettings(helper)
            touchHelper = helper
            Log.i(TAG, "Onyx raw drawing attached, ${rawLimitSummary(resetRawSession = true)}")
            true
        }.getOrElse { error ->
            Log.i(TAG, "Onyx raw drawing unavailable; using fallback surface input: ${error::class.java.simpleName}")
            false
        }
    }

    fun isRawDrawingActive(): Boolean = rawDrawingActive

    fun refreshLimits(resetRawSession: Boolean = false) {
        runCatching {
            val helper = touchHelper ?: return@runCatching
            if (resetRawSession) {
                helper.bindHostView(surfaceView, rawCallback)
            }
            val limit = pageRectProvider()
            val excludes = excludeRectsProvider()
            helper.setLimitRect(limit, excludes)
            if (resetRawSession) {
                helper.openRawDrawing()
                applyRawDrawingSettings(helper)
                helper.setRawDrawingEnabled(inputEnabled)
            }
            Log.i(TAG, "raw drawing limits refreshed, ${rawLimitSummary(resetRawSession, limit, excludes)}")
        }.onFailure { error ->
            Log.i(TAG, "raw limit refresh failed: ${error::class.java.simpleName}")
        }
    }

    fun setInputEnabled(enabled: Boolean, keepRawInkVisible: Boolean = false) {
        inputEnabled = enabled
        runCatching {
            if (enabled) {
                touchHelper?.setRawDrawingRenderEnabled(true)
                touchHelper?.setRawDrawingEnabled(true)
            } else if (keepRawInkVisible) {
                touchHelper?.setRawDrawingRenderEnabled(true)
                touchHelper?.setRawDrawingEnabled(true)
            } else {
                touchHelper?.setRawDrawingRenderEnabled(false)
                touchHelper?.setRawDrawingEnabled(false)
            }
        }
        if (!enabled) {
            enableFingerTouchAfterStroke()
        }
    }

    fun setReadOnlyInputEnabled(enabled: Boolean) {
        inputEnabled = enabled
        rawStrokeAccepted = false
        fallbackStrokeAccepted = false
        runCatching {
            touchHelper?.setRawDrawingRenderEnabled(false)
            touchHelper?.setRawDrawingEnabled(enabled)
        }
        if (!enabled) {
            enableFingerTouchAfterStroke()
        }
    }

    fun hideRawInkLayer() {
        runCatching {
            touchHelper?.setRawDrawingRenderEnabled(false)
        }
    }

    fun clearRawInkLayer() {
        runCatching {
            val helper = touchHelper ?: return@runCatching
            helper.setRawDrawingRenderEnabled(false)
            helper.restartRawDrawing()
            applyRawDrawingSettings(helper)
            helper.setLimitRect(pageRectProvider(), excludeRectsProvider())
            helper.setRawDrawingEnabled(inputEnabled)
        }.onFailure { error ->
            Log.i(TAG, "raw ink clear failed: ${error::class.java.simpleName}")
        }
    }

    fun detach() {
        commitTimer.cancel()
        runCatching {
            touchHelper?.setRawDrawingEnabled(false)
            touchHelper?.closeRawDrawing()
        }
        enableFingerTouchAfterStroke()
        touchHelper = null
        rawDrawingActive = false
    }

    private fun installFallbackTouchHandler() {
        surfaceView.setOnTouchListener { _, event ->
            val toolType = event.getToolType(0)
            val isFinger = toolType == MotionEvent.TOOL_TYPE_FINGER ||
                toolType == MotionEvent.TOOL_TYPE_MOUSE ||
                toolType == MotionEvent.TOOL_TYPE_UNKNOWN
            if (isFinger) {
                gestureDetector.onTouchEvent(event)
            }
            if (rawDrawingActive || !inputEnabled) {
                return@setOnTouchListener isFinger
            }
            handleFallbackInk(event)
            true
        }
    }

    private fun handleFallbackInk(event: MotionEvent) {
        val point = InkPoint(
            x = event.x,
            y = event.y,
            pressure = event.pressure,
            timestampMs = event.eventTime,
            size = event.size,
        )
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                fallbackStrokeAccepted = callbacks.onPenDown()
                if (!fallbackStrokeAccepted) return
                strokeStore.beginStroke(point)
                commitTimer.onPenDown()
            }
            MotionEvent.ACTION_MOVE -> {
                if (!fallbackStrokeAccepted) return
                for (i in 0 until event.historySize) {
                    strokeStore.addPoint(
                        InkPoint(
                            x = event.getHistoricalX(i),
                            y = event.getHistoricalY(i),
                            pressure = event.getHistoricalPressure(i),
                            timestampMs = event.getHistoricalEventTime(i),
                            size = event.getHistoricalSize(i),
                        ),
                    )
                }
                strokeStore.addPoint(point)
                commitTimer.onStrokeMove()
                publishCapturedInk(dirtyRect = null)
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                if (!fallbackStrokeAccepted) return
                strokeStore.addPoint(point)
                strokeStore.finishCurrent()
                callbacks.onPenUp()
                commitTimer.onPenUp()
                publishCapturedInk(dirtyRect = null)
                fallbackStrokeAccepted = false
            }
        }
    }

    private fun publishCapturedInk(dirtyRect: RectF?) {
        val strokes = strokeStore.snapshotStrokes()
        surfaceView.post {
            callbacks.onStrokeCaptured(strokes, dirtyRect)
        }
    }

    private fun applyRawDrawingSettings(helper: TouchHelper) {
        helper.setStrokeStyle(TouchHelper.STROKE_STYLE_FOUNTAIN)
        helper.setStrokeColor(Color.BLACK)
        helper.setStrokeWidth(strokeWidthPx())
        helper.setRawDrawingRenderEnabled(true)
        helper.setPenUpRefreshEnabled(true)
        helper.setFilterRepeatMovePoint(true)
        helper.enableFingerTouch(true)
        helper.setRawDrawingEnabled(true)
    }

    private fun rawLimitSummary(
        resetRawSession: Boolean,
        limit: Rect = pageRectProvider(),
        excludes: List<Rect> = excludeRectsProvider(),
    ): String {
        return "reset=$resetRawSession limit=${limit.flattenToString()} excludes=${excludes.joinToString(prefix = "[", postfix = "]") { it.flattenToString() }}"
    }

    private fun disableFingerTouchDuringStroke() {
        runCatching {
            val metrics = appContext.resources.displayMetrics
            EpdController.setAppCTPDisableRegion(
                appContext,
                arrayOf(Rect(0, 0, metrics.widthPixels, metrics.heightPixels)),
            )
        }
    }

    private fun enableFingerTouchAfterStroke() {
        runCatching {
            EpdController.appResetCTPDisableRegion(appContext)
        }
    }

    private fun clearRejectedRawStroke() {
        runCatching {
            touchHelper?.setRawDrawingRenderEnabled(false)
            touchHelper?.restartRawDrawing()
            touchHelper?.setLimitRect(pageRectProvider(), excludeRectsProvider())
            touchHelper?.setRawDrawingEnabled(inputEnabled)
        }
    }

    private fun strokeWidthPx(): Float {
        return appContext.resources.displayMetrics.xdpi / MILLIMETERS_PER_INCH * STROKE_WIDTH_MM
    }

    private fun TouchPoint.toInkPoint(): InkPoint {
        return InkPoint(
            x = getX(),
            y = getY(),
            pressure = getPressure(),
            timestampMs = getTimestamp().takeIf { it > 0L } ?: SystemClock.uptimeMillis(),
            size = getSize(),
            tiltX = getTiltX(),
            tiltY = getTiltY(),
        )
    }

    companion object {
        private const val TAG = "InkCaptureController"
        private const val MILLIMETERS_PER_INCH = 25.4f
        private const val STROKE_WIDTH_MM = 1.0f
        private const val SWIPE_DISTANCE_PX = 90f
    }
}
