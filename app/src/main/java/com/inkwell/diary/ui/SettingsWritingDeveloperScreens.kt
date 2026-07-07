package com.inkwell.diary.ui

import android.annotation.SuppressLint
import android.app.AlertDialog
import android.content.Intent
import android.view.View
import android.widget.TextView
import com.inkwell.diary.BuildConfig
import com.inkwell.diary.data.Prefs
import com.inkwell.diary.data.ReplyStyle
import com.inkwell.diary.page.HandwritingFont
import com.inkwell.diary.page.HandwritingFontWeight

@SuppressLint("ClickableViewAccessibility")
internal fun SettingsScreenContext.buildWritingScreen(): View {
    val panel = scrollPanel(topPaddingDp = 12, horizontalPaddingDp = 46)
    val status = TextView(context).paperText(16f)
    val group = groupedList()
    panel.addView(group, fullWidth())

    lateinit var delayRow: ChoiceRowHandle
    lateinit var fontRow: ChoiceRowHandle
    lateinit var sizeRow: ChoiceRowHandle
    lateinit var weightRow: ChoiceRowHandle
    lateinit var previewText: TextView

    fun updatePreview() {
        val font = HandwritingFont.fromKey(prefs.handwritingFontKey)
        val weight = HandwritingFontWeight.fromValue(prefs.handwritingFontWeight)
        previewText.textSize = prefs.handwritingFontSizeSp
        previewText.typeface = font.loadTypeface(context, weight)
        previewText.paint.isFakeBoldText = font.shouldFakeBold(weight)
    }

    delayRow = addChoiceRow(group, "Commit Delay", SettingsDisplay.commitDelayLabel(prefs.commitDelayMillis)) {
        showSliderDialog(
            title = "Commit Delay",
            min = Prefs.MIN_COMMIT_DELAY_MILLIS.toInt(),
            max = Prefs.MAX_COMMIT_DELAY_MILLIS.toInt(),
            step = 100,
            current = prefs.commitDelayMillis.toInt(),
            valueLabel = { "Commit Delay: ${SettingsDisplay.commitDelayLabel(it.toLong())}" },
        ) { value ->
            prefs.commitDelayMillis = value.toLong()
            delayRow.valueText.text = SettingsDisplay.commitDelayLabel(prefs.commitDelayMillis)
            status.text = "Commit delay saved: ${SettingsDisplay.commitDelayLabel(prefs.commitDelayMillis)}."
        }
    }
    fontRow = addChoiceRow(group, "Handwriting Font", HandwritingFont.fromKey(prefs.handwritingFontKey).label) {
        val currentFont = HandwritingFont.fromKey(prefs.handwritingFontKey)
        showChoiceDialog(
            title = "Handwriting Font",
            choices = handwritingFonts.map { it.label },
            selectedIndex = handwritingFonts.indexOf(currentFont).coerceAtLeast(0),
        ) { index ->
            val font = handwritingFonts.getOrElse(index) { HandwritingFont.default }
            if (prefs.handwritingFontKey != font.key) {
                prefs.handwritingFontKey = font.key
                fontRow.valueText.text = font.label
                updatePreview()
                callbacks.onHandwritingStyleChanged()
                status.text = "Font saved: ${font.label}."
            }
        }
    }
    sizeRow = addChoiceRow(group, "Font Size", SettingsDisplay.fontSizeLabel(prefs.handwritingFontSizeSp)) {
        showSliderDialog(
            title = "Font Size",
            min = Prefs.MIN_HANDWRITING_FONT_SIZE_SP.toInt(),
            max = Prefs.MAX_HANDWRITING_FONT_SIZE_SP.toInt(),
            step = 1,
            current = prefs.handwritingFontSizeSp.toInt(),
            valueLabel = { "Font Size: $it" },
        ) { value ->
            prefs.handwritingFontSizeSp = value.toFloat()
            sizeRow.valueText.text = SettingsDisplay.fontSizeLabel(prefs.handwritingFontSizeSp)
            updatePreview()
            callbacks.onHandwritingStyleChanged()
            status.text = "Font size saved: ${SettingsDisplay.fontSizeLabel(prefs.handwritingFontSizeSp)}."
        }
    }
    weightRow = addChoiceRow(group, "Font Weight", SettingsDisplay.fontWeightLabel(prefs.handwritingFontWeight)) {
        showChoiceDialog(
            title = "Font Weight",
            choices = handwritingFontWeights.map { it.label },
            selectedIndex = handwritingFontWeights.indexOf(
                HandwritingFontWeight.fromValue(prefs.handwritingFontWeight),
            ).coerceAtLeast(0),
        ) { index ->
            val weight = handwritingFontWeights.getOrElse(index) { HandwritingFontWeight.default }
            if (prefs.handwritingFontWeight != weight.value) {
                prefs.handwritingFontWeight = weight.value
                weightRow.valueText.text = weight.label
                updatePreview()
                callbacks.onHandwritingStyleChanged()
                status.text = "Font weight saved: ${weight.label}."
            }
        }
    }
    panel.addGap(18)
    previewText = addPreviewBlock(panel, "Preview", "The page was listening.")
    updatePreview()

    panel.addGap(16)
    panel.addView(status, fullWidth())
    panel.addGap(36)
    return panelScrollView(panel)
}

internal fun SettingsScreenContext.buildConversationScreen(): View {
    val panel = scrollPanel(topPaddingDp = 12, horizontalPaddingDp = 46)
    val status = TextView(context).paperText(16f)
    val group = groupedList()
    panel.addView(group, fullWidth())

    addChoiceRow(group, "Clear Live Page", "Clear") {
        AlertDialog.Builder(context)
            .setTitle("Clear Live Page")
            .setMessage("Clear the current live page. Stored notebook history is unchanged.")
            .setPositiveButton("Clear") { _, _ ->
                callbacks.onClearConversation()
                status.text = "Live page cleared."
            }
            .setNegativeButton("Cancel", null)
            .show()
    }
    panel.addGap(16)
    panel.addView(status, fullWidth())
    panel.addGap(36)
    return panelScrollView(panel)
}

internal fun SettingsScreenContext.buildDeveloperScreen(): View {
    val panel = scrollPanel(topPaddingDp = 12, horizontalPaddingDp = 46)
    val group = groupedList()
    panel.addView(group, fullWidth())
    val status = TextView(context).paperText(16f)

    lateinit var replyStyleRow: ChoiceRowHandle
    replyStyleRow = addChoiceRow(group, "AI answer mode", prefs.replyStyle.label) {
        val styles = ReplyStyle.entries.toList()
        showChoiceDialog(
            title = "AI answer mode",
            choices = styles.map { it.label },
            selectedIndex = styles.indexOf(prefs.replyStyle).coerceAtLeast(0),
        ) { index ->
            val style = styles.getOrElse(index) { ReplyStyle.default }
            prefs.replyStyle = style
            replyStyleRow.valueText.text = style.label
            callbacks.onReplyStyleChanged()
            status.text = "AI answer mode saved: ${style.label}."
        }
    }

    addToggleRow(
        group = group,
        label = "Pause auto reply",
        explanation = "Keep ink on the page and skip recognition plus AI after pen-up.",
        checked = prefs.autoReplyPaused,
    ) { checked ->
        prefs.autoReplyPaused = checked
    }
    addToggleRow(
        group = group,
        label = "Show log button",
        explanation = "Show the AI log shortcut in the top toolbar while debugging.",
        checked = prefs.showToolbarLogButton,
    ) { checked ->
        prefs.showToolbarLogButton = checked
        callbacks.onToolbarSettingsChanged()
    }
    addToggleRow(
        group = group,
        label = "Use Onyx alpha fade replay",
        explanation = "Only applies to Simply fades. Turns to dust always uses the dissolve animation.",
        checked = prefs.useOnyxFadeReplay,
    ) { checked ->
        prefs.useOnyxFadeReplay = checked
    }
    if (BuildConfig.DEBUG) {
        addChoiceRow(group, "Dissolve Lab", "Open") {
            context.startActivity(Intent(context, DissolveLabActivity::class.java))
        }
        addChoiceRow(group, "SVG Fidelity Lab", "Open") {
            context.startActivity(Intent(context, SvgFidelityLabActivity::class.java))
        }
        addChoiceRow(group, "Ink Replay Lab", "Open") {
            context.startActivity(Intent(context, InkReplayLabActivity::class.java))
        }
    }

    panel.addGap(16)
    panel.addView(status, fullWidth())
    panel.addGap(36)
    return panelScrollView(panel)
}

internal fun SettingsScreenContext.buildAboutScreen(): View {
    val panel = scrollPanel(topPaddingDp = 12, horizontalPaddingDp = 46)
    val group = groupedList()
    panel.addView(group, fullWidth())

    addValueRow(group, "Version", BuildConfig.VERSION_NAME)
    addValueRow(group, "Onyx SDK", "onyxsdk-pen")
    addValueRow(group, "Recognition", "ML Kit Digital Ink")
    addChoiceRow(group, "Fonts", "Licenses") {
        showFontLicensesDialog()
    }
    addValueRow(group, "Privacy", "Local only")
    panel.addGap(36)
    return panelScrollView(panel)
}
