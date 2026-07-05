package com.inkwell.diary.page

import android.content.Context
import android.graphics.Typeface
import androidx.annotation.FontRes
import androidx.core.content.res.ResourcesCompat
import com.inkwell.diary.R

enum class HandwritingFont(
    val key: String,
    val label: String,
    @FontRes val resId: Int,
    val licenseName: String,
    val licensePath: String,
) {
    Caveat(
        key = "caveat",
        label = "Caveat",
        resId = R.font.caveat,
        licenseName = "SIL Open Font License 1.1",
        licensePath = "licenses/CAVEAT-OFL.txt",
    ),
    HomemadeApple(
        key = "homemade_apple",
        label = "Homemade Apple",
        resId = R.font.homemade_apple,
        licenseName = "Apache License 2.0",
        licensePath = "licenses/HOMEMADE-APPLE-LICENSE.txt",
    ),
    MsMadi(
        key = "ms_madi",
        label = "Ms Madi",
        resId = R.font.ms_madi,
        licenseName = "SIL Open Font License 1.1",
        licensePath = "licenses/MS-MADI-OFL.txt",
    );

    fun loadTypeface(context: Context): Typeface {
        return runCatching {
            ResourcesCompat.getFont(context, resId)
        }.getOrNull() ?: Typeface.create(Typeface.SERIF, Typeface.NORMAL)
    }

    companion object {
        val default: HandwritingFont = MsMadi

        fun fromKey(key: String): HandwritingFont {
            return entries.firstOrNull { it.key == key } ?: default
        }
    }
}
