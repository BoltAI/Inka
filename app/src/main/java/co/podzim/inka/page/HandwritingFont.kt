package co.podzim.inka.page

import android.content.Context
import android.graphics.Typeface
import android.os.Build
import androidx.core.content.res.ResourcesCompat
import co.podzim.inka.R

enum class HandwritingFont(
    val key: String,
    val label: String,
    val licenseName: String,
    val licensePath: String,
    private val regularResId: Int? = null,
    private val mediumResId: Int? = null,
    private val semiBoldResId: Int? = null,
    private val boldResId: Int? = null,
    private val systemTypeface: Typeface = Typeface.SERIF,
) {
    DancingScript(
        key = "dancing_script",
        label = "Dancing Script",
        licenseName = "SIL Open Font License 1.1",
        licensePath = "licenses/DANCING-SCRIPT-OFL.txt",
        regularResId = R.font.dancing_script_regular,
        mediumResId = R.font.dancing_script_medium,
        semiBoldResId = R.font.dancing_script_semibold,
        boldResId = R.font.dancing_script_bold,
    ),
    SystemSerif(
        key = "system_serif",
        label = "System Serif",
        licenseName = "Android system font",
        licensePath = "Provided by the operating system",
        systemTypeface = Typeface.SERIF,
    );

    fun loadTypeface(context: Context): Typeface {
        return loadTypeface(context, HandwritingFontWeight.Regular)
    }

    fun loadTypeface(context: Context, weight: HandwritingFontWeight): Typeface {
        weightResId(weight)?.let { fontResId ->
            runCatching { ResourcesCompat.getFont(context, fontResId) }.getOrNull()?.let { return it }
        }
        val base = systemTypeface
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            Typeface.create(base, weight.value, false)
        } else {
            Typeface.create(base, weight.fallbackTypefaceStyle)
        }
    }

    fun shouldFakeBold(weight: HandwritingFontWeight): Boolean {
        return weightResId(weight) == null && Build.VERSION.SDK_INT < Build.VERSION_CODES.P && weight.syntheticBold
    }

    private fun weightResId(weight: HandwritingFontWeight): Int? {
        return when (weight) {
            HandwritingFontWeight.Regular -> regularResId
            HandwritingFontWeight.Medium -> mediumResId
            HandwritingFontWeight.SemiBold -> semiBoldResId
            HandwritingFontWeight.Bold -> boldResId
        }
    }

    companion object {
        val default: HandwritingFont = DancingScript

        fun fromKey(key: String): HandwritingFont {
            return entries.firstOrNull { it.key == key } ?: default
        }
    }
}

enum class HandwritingFontWeight(
    val value: Int,
    val label: String,
    val fallbackTypefaceStyle: Int,
    val syntheticBold: Boolean,
) {
    Regular(400, "Regular", Typeface.NORMAL, syntheticBold = false),
    Medium(500, "Medium", Typeface.NORMAL, syntheticBold = false),
    SemiBold(600, "SemiBold", Typeface.BOLD, syntheticBold = true),
    Bold(700, "Bold", Typeface.BOLD, syntheticBold = true);

    companion object {
        val default: HandwritingFontWeight = Regular

        fun fromValue(value: Int): HandwritingFontWeight {
            return entries.firstOrNull { it.value == value } ?: default
        }
    }
}
