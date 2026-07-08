package co.podzim.inka.ink

import android.util.Log
import com.onyx.android.sdk.pen.data.TouchPointList
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.ObjectInputStream
import java.io.ObjectOutputStream
import java.util.Base64

internal object OnyxTouchPointListCodec {
    fun encode(pointList: TouchPointList): String? {
        return runCatching {
            val stableCopy = TouchPointList(pointList)
            val bytes = ByteArrayOutputStream().use { byteStream ->
                ObjectOutputStream(byteStream).use { objectStream ->
                    objectStream.writeObject(stableCopy)
                }
                byteStream.toByteArray()
            }
            Base64.getEncoder().encodeToString(bytes)
        }.getOrElse { error ->
            Log.i(TAG, "Onyx point list encode failed: ${error::class.java.simpleName}")
            null
        }
    }

    fun decode(encoded: String): TouchPointList? {
        if (encoded.isBlank()) return null
        return runCatching {
            val bytes = Base64.getDecoder().decode(encoded)
            ObjectInputStream(ByteArrayInputStream(bytes)).use { objectStream ->
                objectStream.readObject() as? TouchPointList
            }
        }.getOrElse { error ->
            Log.i(TAG, "Onyx point list decode failed: ${error::class.java.simpleName}")
            null
        }
    }

    private const val TAG = "OnyxPointListCodec"
}
