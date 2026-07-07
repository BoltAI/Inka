package com.inkwell.diary.page

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Paint
import android.graphics.Rect
import android.util.Base64
import com.inkwell.diary.brain.PageSnapshot
import java.io.ByteArrayOutputStream
import kotlin.math.max
import kotlin.math.min

internal object PageSnapshotRenderer {
    fun snapshotForVision(
        source: Bitmap,
        maxLongEdge: Int,
        drawSource: (Canvas) -> Unit,
    ): PageSnapshot {
        val sourceBitmap = Bitmap.createBitmap(source.width, source.height, Bitmap.Config.ARGB_8888)
        val sourceCanvas = Canvas(sourceBitmap)
        sourceCanvas.drawColor(Color.WHITE)
        drawSource(sourceCanvas)

        val longEdge = max(sourceBitmap.width, sourceBitmap.height).coerceAtLeast(1)
        val scale = min(1f, maxLongEdge.toFloat() / longEdge.toFloat())
        val scaledWidth = max(1, (sourceBitmap.width * scale).toInt())
        val scaledHeight = max(1, (sourceBitmap.height * scale).toInt())
        val scaled = Bitmap.createBitmap(scaledWidth, scaledHeight, Bitmap.Config.ARGB_8888)
        val grayscalePaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG).apply {
            colorFilter = ColorMatrixColorFilter(
                ColorMatrix().apply { setSaturation(0f) },
            )
        }
        Canvas(scaled).drawBitmap(
            sourceBitmap,
            null,
            Rect(0, 0, scaledWidth, scaledHeight),
            grayscalePaint,
        )
        val bytes = ByteArrayOutputStream()
        scaled.compress(Bitmap.CompressFormat.PNG, 100, bytes)
        val encoded = Base64.encodeToString(bytes.toByteArray(), Base64.NO_WRAP)
        scaled.recycle()
        sourceBitmap.recycle()
        return PageSnapshot(
            pngBase64 = encoded,
            width = scaledWidth,
            height = scaledHeight,
            pageWidth = source.width,
            pageHeight = source.height,
        )
    }
}
