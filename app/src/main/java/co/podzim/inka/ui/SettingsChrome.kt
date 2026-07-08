package co.podzim.inka.ui

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.net.Uri
import android.text.SpannableString
import android.text.Spanned
import android.text.style.StyleSpan
import android.text.style.UnderlineSpan
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import co.podzim.inka.R
import android.graphics.Bitmap
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter

internal class SettingsChrome(
    private val context: Context,
    private val onBack: () -> Unit,
) {
    val view: View
    private lateinit var titleText: TextView
    private lateinit var contentHost: FrameLayout
    private lateinit var makerCredit: View

    init {
        val root = FrameLayout(context).apply {
            setBackgroundColor(Color.WHITE)
        }
        val container = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.WHITE)
        }
        root.addView(
            container,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT,
            ),
        )
        container.addView(
            buildTopNav(),
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                context.dp(NAV_HEIGHT_DP),
            ),
        )

        contentHost = FrameLayout(context).apply {
            setBackgroundColor(Color.WHITE)
        }
        container.addView(
            contentHost,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                0,
                1f,
            ),
        )
        makerCredit = buildMakerCredit()
        root.addView(
            makerCredit,
            FrameLayout.LayoutParams(
                context.dp(MAKER_CREDIT_WIDTH_DP),
                FrameLayout.LayoutParams.WRAP_CONTENT,
                Gravity.BOTTOM or Gravity.END,
            ).apply {
                setMargins(0, 0, context.dp(MAKER_CREDIT_MARGIN_DP), context.dp(MAKER_CREDIT_MARGIN_DP))
            },
        )
        view = root
    }

    fun setTitle(title: String) {
        titleText.text = title
    }

    fun setMakerCreditVisible(visible: Boolean) {
        makerCredit.visibility = if (visible) View.VISIBLE else View.GONE
    }

    fun setContent(content: View) {
        contentHost.removeAllViews()
        contentHost.addView(
            content,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT,
            ),
        )
    }

    private fun buildTopNav(): LinearLayout {
        return LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setBackgroundColor(Color.WHITE)
            setPadding(context.dp(22), 0, context.dp(30), 0)

            addView(
                ImageButton(context).apply {
                    contentDescription = "Back"
                    importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_YES
                    setBackgroundColor(Color.TRANSPARENT)
                    setImageResource(R.drawable.ic_nav_back)
                    scaleType = ImageView.ScaleType.CENTER
                    setPadding(context.dp(10), context.dp(10), context.dp(10), context.dp(10))
                    setOnClickListener { onBack() }
                },
                LinearLayout.LayoutParams(
                    context.dp(54),
                    LinearLayout.LayoutParams.MATCH_PARENT,
                ),
            )
            titleText = TextView(context).apply {
                gravity = Gravity.CENTER_VERTICAL
                includeFontPadding = false
                paperText(25f)
            }
            addView(
                titleText,
                LinearLayout.LayoutParams(
                    0,
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    1f,
                ),
            )
        }
    }

    private fun buildMakerCredit(): LinearLayout {
        return LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            isClickable = true
            isFocusable = true
            contentDescription = "Made by @daniel_nguyenx"
            setOnClickListener { openMakerProfile() }

            val qrSize = context.dp(MAKER_QR_SIZE_DP)
            addView(
                ImageView(context).apply {
                    setImageBitmap(qrBitmap(MAKER_PROFILE_URL, qrSize))
                    scaleType = ImageView.ScaleType.FIT_CENTER
                    contentDescription = "QR code for @daniel_nguyenx"
                },
                LinearLayout.LayoutParams(qrSize, qrSize),
            )
            addView(
                TextView(context).apply {
                    text = makerCreditText()
                    gravity = Gravity.CENTER
                    paperText(13f)
                    setSingleLine(true)
                },
                LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                ).apply {
                    topMargin = context.dp(6)
                },
            )
        }
    }

    private fun makerCreditText(): SpannableString {
        val text = "Made by @daniel_nguyenx"
        val handleStart = text.indexOf('@')
        return SpannableString(text).apply {
            if (handleStart >= 0) {
                setSpan(StyleSpan(Typeface.BOLD), handleStart, text.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                setSpan(UnderlineSpan(), handleStart, text.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            }
        }
    }

    private fun openMakerProfile() {
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(MAKER_PROFILE_URL))
        try {
            context.startActivity(intent)
        } catch (_: ActivityNotFoundException) {
            // BOOX builds without a browser can still use the QR code.
        }
    }
}

private const val NAV_HEIGHT_DP = 72
private const val MAKER_CREDIT_WIDTH_DP = 190
private const val MAKER_QR_SIZE_DP = MAKER_CREDIT_WIDTH_DP
private const val MAKER_CREDIT_MARGIN_DP = 28
internal const val MAKER_PROFILE_URL = "https://x.com/daniel_nguyenx"

private fun qrBitmap(content: String, sizePx: Int): Bitmap {
    val hints = mapOf(EncodeHintType.MARGIN to 1)
    val matrix = QRCodeWriter().encode(content, BarcodeFormat.QR_CODE, sizePx, sizePx, hints)
    val bitmap = Bitmap.createBitmap(sizePx, sizePx, Bitmap.Config.ARGB_8888)
    for (y in 0 until sizePx) {
        for (x in 0 until sizePx) {
            bitmap.setPixel(x, y, if (matrix[x, y]) Color.BLACK else Color.WHITE)
        }
    }
    return bitmap
}
