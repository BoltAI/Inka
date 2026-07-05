package com.inkwell.diary.page

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import android.view.MotionEvent
import android.view.View

class PageCanvasView(context: Context) : View(context) {
    private var pageBitmap: Bitmap? = null
    private val bitmapPaint = Paint(Paint.ANTI_ALIAS_FLAG)

    init {
        setWillNotDraw(false)
        setBackgroundColor(android.graphics.Color.TRANSPARENT)
        isClickable = false
        isFocusable = false
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
    }

    fun present(bitmap: Bitmap, dirtyRect: Rect?) {
        pageBitmap = bitmap
        if (dirtyRect != null) {
            invalidate(dirtyRect)
        } else {
            invalidate()
        }
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        pageBitmap?.let { bitmap ->
            canvas.drawBitmap(bitmap, 0f, 0f, bitmapPaint)
        }
    }

    override fun onTouchEvent(event: MotionEvent): Boolean = false
}
