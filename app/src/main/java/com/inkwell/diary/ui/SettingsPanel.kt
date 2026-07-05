package com.inkwell.diary.ui

import android.annotation.SuppressLint
import android.app.AlertDialog
import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.text.InputType
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.TextView
import com.inkwell.diary.BuildConfig
import com.inkwell.diary.R
import com.inkwell.diary.brain.AnthropicResult
import com.inkwell.diary.brain.ConversationEngine
import com.inkwell.diary.data.AiProvider
import com.inkwell.diary.data.Persona
import com.inkwell.diary.data.Prefs
import com.inkwell.diary.page.HandwritingFont
import com.inkwell.diary.recognize.ModelDownloadOutcome
import com.inkwell.diary.recognize.RecognitionService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

class SettingsPanel(
    context: Context,
    private val prefs: Prefs,
    private val engine: ConversationEngine,
    private val recognitionService: RecognitionService,
    private val scope: CoroutineScope,
    private val callbacks: Callbacks,
) : FrameLayout(context) {
    interface Callbacks {
        fun onCloseSettings()
        fun onClearConversation()
        fun onHandwritingStyleChanged()
        fun onToolbarSettingsChanged()
    }

    private enum class SettingsScreen(val title: String) {
        Home("Settings"),
        Ai("AI Settings"),
        Persona("Persona"),
        Recognition("Recognition Settings"),
        Writing("Writing Settings"),
        Developer("Developer"),
        Conversation("Conversation Data"),
        About("About Inkwell"),
    }

    private val languages = listOf("en-US", "es-ES", "fr-FR", "de-DE", "vi-VN")
    private val aiProviders = AiProvider.entries.toList()
    private val handwritingFonts = HandwritingFont.entries.toList()
    private val backStack = mutableListOf<SettingsScreen>()
    private lateinit var titleText: TextView
    private lateinit var contentHost: FrameLayout
    private var currentScreen = SettingsScreen.Home

    init {
        setBackgroundColor(Color.WHITE)
        buildChrome()
        render(SettingsScreen.Home)
    }

    fun handleBack(): Boolean {
        if (backStack.isEmpty()) return false
        currentScreen = backStack.removeAt(backStack.lastIndex)
        render(currentScreen)
        return true
    }

    private fun buildChrome() {
        val container = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.WHITE)
        }
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
        addView(
            container,
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
                    setOnClickListener { goBack() }
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

    private fun goBack() {
        if (!handleBack()) {
            callbacks.onCloseSettings()
        }
    }

    private fun navigate(screen: SettingsScreen) {
        if (screen == currentScreen) return
        backStack.add(currentScreen)
        currentScreen = screen
        render(screen)
    }

    private fun render(screen: SettingsScreen) {
        currentScreen = screen
        titleText.text = screen.title
        contentHost.removeAllViews()
        val content = when (screen) {
            SettingsScreen.Home -> buildHomeScreen()
            SettingsScreen.Ai -> buildAiScreen()
            SettingsScreen.Persona -> buildPersonaScreen()
            SettingsScreen.Recognition -> buildRecognitionScreen()
            SettingsScreen.Writing -> buildWritingScreen()
            SettingsScreen.Developer -> buildDeveloperScreen()
            SettingsScreen.Conversation -> buildConversationScreen()
            SettingsScreen.About -> buildAboutScreen()
        }
        contentHost.addView(
            content,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT,
            ),
        )
    }

    private fun buildHomeScreen(): View {
        val panel = scrollPanel(topPaddingDp = 12, horizontalPaddingDp = 46)
        val group = groupedList()
        panel.addView(group, fullWidth())

        addTopicRow(group, "AI Settings") { navigate(SettingsScreen.Ai) }
        addTopicRow(group, "Persona") { navigate(SettingsScreen.Persona) }
        addTopicRow(group, "Recognition Settings") { navigate(SettingsScreen.Recognition) }
        addTopicRow(group, "Writing Settings") { navigate(SettingsScreen.Writing) }
        addTopicRow(group, "Conversation Data") { navigate(SettingsScreen.Conversation) }
        addTopicRow(group, "Developer") { navigate(SettingsScreen.Developer) }
        addTopicRow(group, "About Inkwell") { navigate(SettingsScreen.About) }
        addValueRow(group, "Inkwell Version", BuildConfig.VERSION_NAME)

        return panel.parent as ScrollView
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun buildAiScreen(): View {
        val panel = scrollPanel(topPaddingDp = 12, horizontalPaddingDp = 46)
        val status = TextView(context).paperText(16f)
        val group = groupedList()
        panel.addView(group, fullWidth())

        var currentProvider = prefs.provider
        var modelChoices = modelChoicesFor(currentProvider)
        lateinit var providerRow: ChoiceRowHandle
        lateinit var modelRow: ChoiceRowHandle
        lateinit var apiKeyRow: ChoiceRowHandle

        fun hasApiKey(provider: AiProvider): Boolean = prefs.apiKey(provider).isNotBlank()

        fun updateApiKeyRow() {
            apiKeyRow.valueText.text = apiKeyStatus(currentProvider)
            apiKeyRow.warningIcon.visibility = if (hasApiKey(currentProvider)) View.GONE else View.VISIBLE
        }

        fun validateSavedKey(provider: AiProvider, apiKey: String) {
            val cleaned = apiKey.trim()
            if (cleaned.isBlank()) {
                status.text = "${provider.label} API key is required."
                return
            }
            val model = prefs.model(provider)
            status.text = "Validating ${provider.label} key..."
            scope.launch {
                when (engine.validateKey(provider, cleaned, model)) {
                    is AnthropicResult.Success -> {
                        status.text = "${provider.label} key saved and validated."
                    }
                    is AnthropicResult.Failure -> {
                        status.text = "${provider.label} key saved, but validation failed."
                    }
                }
            }
        }

        fun promptForApiKey(provider: AiProvider) {
            showTextInputDialog(
                title = "${provider.label} API Key",
                hint = "${provider.label} API key",
                initialValue = prefs.apiKey(provider),
                masked = true,
                multiLine = false,
            ) { value ->
                prefs.provider = provider
                prefs.setApiKey(provider, value)
                currentProvider = provider
                updateApiKeyRow()
                validateSavedKey(provider, value)
            }
        }

        fun refreshProviderRows() {
            modelChoices = modelChoicesFor(currentProvider)
            providerRow.valueText.text = currentProvider.label
            modelRow.valueText.text = prefs.model(currentProvider)
            updateApiKeyRow()
        }

        providerRow = addChoiceRow(group, "Provider", currentProvider.label) {
            showChoiceDialog(
                title = "Provider",
                choices = aiProviders.map { it.label },
                selectedIndex = aiProviders.indexOf(currentProvider).coerceAtLeast(0),
            ) { index ->
                currentProvider = aiProviders.getOrElse(index) { AiProvider.Anthropic }
                prefs.provider = currentProvider
                refreshProviderRows()
                if (hasApiKey(currentProvider)) {
                    status.text = "${currentProvider.label} selected."
                } else {
                    status.text = "${currentProvider.label} API key is required."
                    post { promptForApiKey(currentProvider) }
                }
            }
        }
        apiKeyRow = addChoiceRow(
            group = group,
            label = "API Key",
            value = apiKeyStatus(currentProvider),
            warningVisible = !hasApiKey(currentProvider),
        ) {
            promptForApiKey(currentProvider)
        }
        modelRow = addChoiceRow(group, "Model", prefs.model(currentProvider)) {
            modelChoices = modelChoicesFor(currentProvider)
            showChoiceDialog(
                title = "Model",
                choices = modelChoices,
                selectedIndex = modelChoices.indexOf(prefs.model(currentProvider)).coerceAtLeast(0),
            ) { index ->
                val model = modelChoices.getOrElse(index) { currentProvider.defaultModel }
                prefs.provider = currentProvider
                prefs.setModel(currentProvider, model)
                modelRow.valueText.text = model
                status.text = "${currentProvider.label} model saved: $model"
            }
        }
        status.text = if (hasApiKey(currentProvider)) "" else "${currentProvider.label} API key is required."
        panel.addGap(16)
        panel.addView(status, fullWidth())
        panel.addGap(36)
        return panel.parent as ScrollView
    }

    private fun buildPersonaScreen(): View {
        val panel = scrollPanel(topPaddingDp = 12, horizontalPaddingDp = 46)
        val status = TextView(context).paperText(16f)
        val group = groupedList()
        panel.addView(group, fullWidth())

        val radioButtons = linkedMapOf<Persona, RadioButton>()
        val customGap = View(context).apply {
            visibility = if (prefs.persona == Persona.Custom) View.VISIBLE else View.GONE
        }
        val customGroup = groupedList().apply {
            visibility = if (prefs.persona == Persona.Custom) View.VISIBLE else View.GONE
        }

        fun renderSelection() {
            val selected = prefs.persona
            radioButtons.forEach { (persona, radioButton) ->
                radioButton.isChecked = persona == selected
            }
            val customVisibility = if (selected == Persona.Custom) View.VISIBLE else View.GONE
            customGap.visibility = customVisibility
            customGroup.visibility = customVisibility
        }

        fun selectPersona(persona: Persona) {
            prefs.persona = persona
            renderSelection()
            status.text = if (persona == Persona.Custom && prefs.customPrompt.isBlank()) {
                "Custom persona selected. Add a custom prompt below."
            } else {
                "${persona.label} selected."
            }
        }

        Persona.entries.forEach { persona ->
            radioButtons[persona] = addPersonaRow(group, persona) {
                selectPersona(persona)
            }
        }
        renderSelection()

        panel.addView(
            customGap,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                context.dp(18),
            ),
        )
        panel.addView(customGroup, fullWidth())
        lateinit var customPromptRow: ChoiceRowHandle
        customPromptRow = addChoiceRow(customGroup, "Custom Prompt", customPromptStatus()) {
            showTextInputDialog(
                title = "Custom Prompt",
                hint = "Custom system prompt",
                initialValue = prefs.customPrompt,
                masked = false,
                multiLine = true,
            ) { value ->
                prefs.customPrompt = value
                prefs.persona = Persona.Custom
                renderSelection()
                customPromptRow.valueText.text = customPromptStatus()
                status.text = "Custom prompt saved."
            }
        }

        panel.addGap(16)
        panel.addView(status, fullWidth())
        panel.addGap(36)
        return panel.parent as ScrollView
    }

    private fun buildRecognitionScreen(): View {
        val panel = scrollPanel(topPaddingDp = 12, horizontalPaddingDp = 46)
        val status = TextView(context).paperText(16f)
        val group = groupedList()
        panel.addView(group, fullWidth())

        lateinit var languageRow: ChoiceRowHandle
        languageRow = addChoiceRow(group, "Language", prefs.recognitionLanguage) {
            showChoiceDialog(
                title = "Recognition Language",
                choices = languages,
                selectedIndex = languages.indexOf(prefs.recognitionLanguage).coerceAtLeast(0),
            ) { index ->
                val language = languages.getOrElse(index) { Prefs.DEFAULT_LANGUAGE }
                status.text = "Downloading $language model..."
                scope.launch {
                    when (val outcome = recognitionService.ensureModel(language)) {
                        ModelDownloadOutcome.Ready -> {
                            prefs.recognitionLanguage = language
                            languageRow.valueText.text = language
                            status.text = "Language saved: $language."
                        }
                        is ModelDownloadOutcome.Failure -> {
                            status.text = outcome.message
                        }
                    }
                }
            }
        }
        panel.addGap(16)
        panel.addView(status, fullWidth())
        panel.addGap(36)
        return panel.parent as ScrollView
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun buildWritingScreen(): View {
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
            val bold = prefs.handwritingFontBold
            previewText.textSize = prefs.handwritingFontSizeSp
            previewText.typeface = Typeface.create(font.loadTypeface(context), if (bold) Typeface.BOLD else Typeface.NORMAL)
            previewText.paint.isFakeBoldText = bold
        }

        delayRow = addChoiceRow(group, "Commit Delay", formatCommitDelay(prefs.commitDelayMillis)) {
            showSliderDialog(
                title = "Commit Delay",
                min = 1000,
                max = 4000,
                step = 100,
                current = prefs.commitDelayMillis.toInt(),
                valueLabel = { "Commit Delay: ${formatCommitDelay(it.toLong())}" },
            ) { value ->
                prefs.commitDelayMillis = value.toLong()
                delayRow.valueText.text = formatCommitDelay(prefs.commitDelayMillis)
                status.text = "Commit delay saved: ${formatCommitDelay(prefs.commitDelayMillis)}."
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
        sizeRow = addChoiceRow(group, "Font Size", formatFontSize(prefs.handwritingFontSizeSp)) {
            showSliderDialog(
                title = "Font Size",
                min = Prefs.MIN_HANDWRITING_FONT_SIZE_SP.toInt(),
                max = Prefs.MAX_HANDWRITING_FONT_SIZE_SP.toInt(),
                step = 1,
                current = prefs.handwritingFontSizeSp.toInt(),
                valueLabel = { "Font Size: $it" },
            ) { value ->
                prefs.handwritingFontSizeSp = value.toFloat()
                sizeRow.valueText.text = formatFontSize(prefs.handwritingFontSizeSp)
                updatePreview()
                callbacks.onHandwritingStyleChanged()
                status.text = "Font size saved: ${formatFontSize(prefs.handwritingFontSizeSp)}."
            }
        }
        weightRow = addChoiceRow(group, "Font Weight", fontWeightLabel(prefs.handwritingFontBold)) {
            val choices = listOf(false, true)
            showChoiceDialog(
                title = "Font Weight",
                choices = choices.map { fontWeightLabel(it) },
                selectedIndex = choices.indexOf(prefs.handwritingFontBold).coerceAtLeast(0),
            ) { index ->
                val bold = choices.getOrElse(index) { Prefs.DEFAULT_HANDWRITING_FONT_BOLD }
                if (prefs.handwritingFontBold != bold) {
                    prefs.handwritingFontBold = bold
                    weightRow.valueText.text = fontWeightLabel(bold)
                    updatePreview()
                    callbacks.onHandwritingStyleChanged()
                    status.text = "Font weight saved: ${fontWeightLabel(bold)}."
                }
            }
        }
        panel.addGap(18)
        previewText = addPreviewBlock(panel, "Preview", "The page was listening.")
        updatePreview()

        panel.addGap(16)
        panel.addView(status, fullWidth())
        panel.addGap(36)
        return panel.parent as ScrollView
    }

    private fun buildConversationScreen(): View {
        val panel = scrollPanel(topPaddingDp = 12, horizontalPaddingDp = 46)
        val status = TextView(context).paperText(16f)
        val group = groupedList()
        panel.addView(group, fullWidth())

        addChoiceRow(group, "Clear Conversation", "Clear") {
            AlertDialog.Builder(context)
                .setTitle("Clear Conversation")
                .setMessage("Remove the local conversation history used for AI context?")
                .setPositiveButton("Clear") { _, _ ->
                    callbacks.onClearConversation()
                    status.text = "Conversation cleared."
                }
                .setNegativeButton("Cancel", null)
                .show()
        }
        panel.addGap(16)
        panel.addView(status, fullWidth())
        panel.addGap(36)
        return panel.parent as ScrollView
    }

    private fun buildDeveloperScreen(): View {
        val panel = scrollPanel(topPaddingDp = 12, horizontalPaddingDp = 46)
        val group = groupedList()
        panel.addView(group, fullWidth())

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

        panel.addGap(36)
        return panel.parent as ScrollView
    }

    private fun buildAboutScreen(): View {
        val panel = scrollPanel(topPaddingDp = 12, horizontalPaddingDp = 46)
        val group = groupedList()
        panel.addView(group, fullWidth())

        addValueRow(group, "Version", BuildConfig.VERSION_NAME)
        addValueRow(group, "Onyx SDK", "onyxsdk-pen")
        addValueRow(group, "Recognition", "ML Kit Digital Ink")
        addChoiceRow(group, "Fonts", "Licenses") {
            showFontLicensesDialog()
        }
        addValueRow(group, "Privacy", "No analytics or accounts")
        panel.addGap(36)
        return panel.parent as ScrollView
    }

    private fun formatCommitDelay(delayMillis: Long): String {
        return "${"%.1f".format(delayMillis.coerceIn(1000L, 4000L) / 1000f)} seconds"
    }

    private fun formatFontSize(sizeSp: Float): String {
        return "${sizeSp.toInt()} sp"
    }

    private fun fontWeightLabel(bold: Boolean): String {
        return if (bold) "Bold" else "Regular"
    }

    private fun scrollPanel(
        topPaddingDp: Int = 20,
        horizontalPaddingDp: Int = 42,
    ): LinearLayout {
        val scroll = ScrollView(context).apply {
            setBackgroundColor(Color.WHITE)
        }
        val panel = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.NO_GRAVITY
            setPadding(
                context.dp(horizontalPaddingDp),
                context.dp(topPaddingDp),
                context.dp(horizontalPaddingDp),
                context.dp(36),
            )
        }
        scroll.addView(
            panel,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.WRAP_CONTENT,
            ),
        )
        return panel
    }

    private fun groupedList(): LinearLayout {
        return LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            background = GradientDrawable().apply {
                setColor(Color.WHITE)
                cornerRadius = context.dp(12).toFloat()
                setStroke(context.dp(1), Color.rgb(135, 135, 135))
            }
        }
    }

    private fun addPersonaRow(
        group: LinearLayout,
        persona: Persona,
        onClick: () -> Unit,
    ): RadioButton {
        val row = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            isClickable = true
            isFocusable = true
            minimumHeight = context.dp(DESCRIPTION_ROW_HEIGHT_DP)
            setPadding(context.dp(26), context.dp(10), context.dp(24), context.dp(10))
            setOnClickListener { onClick() }
        }
        val textColumn = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_VERTICAL
        }
        textColumn.addView(
            TextView(context).apply {
                text = persona.label
                includeFontPadding = false
                paperText(23f)
            },
            fullWidth(),
        )
        textColumn.addView(
            TextView(context).apply {
                text = persona.pickerDescription
                paperText(17f)
                setTextColor(Color.rgb(45, 45, 45))
            },
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            ).apply {
                topMargin = context.dp(4)
            },
        )
        row.addView(
            textColumn,
            LinearLayout.LayoutParams(
                0,
                LinearLayout.LayoutParams.WRAP_CONTENT,
                1f,
            ),
        )
        val radioButton = RadioButton(context).apply {
            isClickable = false
            isFocusable = false
            buttonTintList = ColorStateList.valueOf(Color.BLACK)
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }
        row.addView(
            radioButton,
            LinearLayout.LayoutParams(
                context.dp(54),
                context.dp(54),
            ).apply {
                leftMargin = context.dp(18)
            },
        )
        group.addView(row, fullWidth())
        return radioButton
    }

    private fun addChoiceRow(
        group: LinearLayout,
        label: String,
        value: String,
        warningVisible: Boolean = false,
        onClick: () -> Unit,
    ): ChoiceRowHandle {
        val row = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            isClickable = true
            isFocusable = true
            minimumHeight = context.dp(SINGLE_LINE_ROW_HEIGHT_DP)
            setPadding(context.dp(26), 0, context.dp(20), 0)
            setOnClickListener { onClick() }
        }
        row.addView(
            TextView(context).apply {
                text = label
                gravity = Gravity.CENTER_VERTICAL
                includeFontPadding = false
                paperText(23f)
            },
            LinearLayout.LayoutParams(
                0,
                LinearLayout.LayoutParams.MATCH_PARENT,
                1f,
            ),
        )
        val valueText = TextView(context).apply {
            text = value
            gravity = Gravity.CENTER_VERTICAL or Gravity.END
            includeFontPadding = false
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
            maxWidth = context.dp(620)
            paperText(20f)
        }
        val warningIcon = TextView(context).apply {
            text = "!"
            gravity = Gravity.CENTER
            includeFontPadding = false
            paperText(15f, bold = true)
            visibility = if (warningVisible) View.VISIBLE else View.GONE
            background = GradientDrawable().apply {
                setColor(Color.WHITE)
                cornerRadius = context.dp(2).toFloat()
                setStroke(context.dp(2), Color.BLACK)
            }
        }
        val valueGroup = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL or Gravity.END
            addView(
                warningIcon,
                LinearLayout.LayoutParams(
                    context.dp(28),
                    context.dp(28),
                ).apply {
                    rightMargin = context.dp(14)
                },
            )
            addView(
                valueText,
                LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.MATCH_PARENT,
                ),
            )
        }
        row.addView(
            valueGroup,
            LinearLayout.LayoutParams(
                0,
                LinearLayout.LayoutParams.MATCH_PARENT,
                1.25f,
            ),
        )
        row.addView(
            ImageView(context).apply {
                importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
                setImageResource(R.drawable.ic_nav_chevron_right)
                scaleType = ImageView.ScaleType.CENTER
            },
            LinearLayout.LayoutParams(
                context.dp(42),
                LinearLayout.LayoutParams.MATCH_PARENT,
            ),
        )
        group.addView(row, fullWidth())
        return ChoiceRowHandle(valueText, warningIcon)
    }

    private fun showChoiceDialog(
        title: String,
        choices: List<String>,
        selectedIndex: Int,
        onSelected: (Int) -> Unit,
    ) {
        AlertDialog.Builder(context)
            .setTitle(title)
            .setSingleChoiceItems(choices.toTypedArray(), selectedIndex) { dialog, which ->
                dialog.dismiss()
                onSelected(which)
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun showTextInputDialog(
        title: String,
        hint: String,
        initialValue: String,
        masked: Boolean,
        multiLine: Boolean,
        onSaved: (String) -> Unit,
    ) {
        val input = context.paperEditText(hint, masked = masked).apply {
            setText(initialValue)
            setSelectAllOnFocus(true)
            setSingleLine(!multiLine)
            minLines = if (multiLine) 5 else 1
            inputType = when {
                masked -> InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
                multiLine -> InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE
                else -> InputType.TYPE_CLASS_TEXT
            }
        }
        AlertDialog.Builder(context)
            .setTitle(title)
            .setView(input)
            .setPositiveButton("Save") { _, _ -> onSaved(input.text.toString()) }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun showSliderDialog(
        title: String,
        min: Int,
        max: Int,
        step: Int,
        current: Int,
        valueLabel: (Int) -> String,
        onSaved: (Int) -> Unit,
    ) {
        val normalizedMin = min.coerceAtMost(max)
        val normalizedMax = max.coerceAtLeast(min)
        val normalizedStep = step.coerceAtLeast(1)
        val maxProgress = ((normalizedMax - normalizedMin) / normalizedStep).coerceAtLeast(0)

        fun valueForProgress(progress: Int): Int {
            return normalizedMin + progress.coerceIn(0, maxProgress) * normalizedStep
        }

        fun progressForValue(value: Int): Int {
            return ((value.coerceIn(normalizedMin, normalizedMax) - normalizedMin) / normalizedStep)
                .coerceIn(0, maxProgress)
        }

        val label = TextView(context).apply {
            gravity = Gravity.CENTER
            includeFontPadding = false
            paperText(21f)
        }
        lateinit var slider: SeekBar
        fun setSliderProgress(progress: Int) {
            slider.progress = progress.coerceIn(0, maxProgress)
            label.text = valueLabel(valueForProgress(slider.progress))
        }

        slider = SeekBar(context).apply {
            this.max = maxProgress
            progress = progressForValue(current)
            progressTintList = ColorStateList.valueOf(Color.BLACK)
            progressBackgroundTintList = ColorStateList.valueOf(Color.rgb(120, 120, 120))
            thumbTintList = ColorStateList.valueOf(Color.BLACK)
            splitTrack = false
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(seekBar: SeekBar, progress: Int, fromUser: Boolean) {
                    label.text = valueLabel(valueForProgress(progress))
                }

                override fun onStartTrackingTouch(seekBar: SeekBar) = Unit
                override fun onStopTrackingTouch(seekBar: SeekBar) = Unit
            })
        }
        label.text = valueLabel(valueForProgress(slider.progress))

        val sliderRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, context.dp(26), 0, context.dp(22))
            addView(
                sliderButton("-") { setSliderProgress(slider.progress - 1) },
                LinearLayout.LayoutParams(context.dp(54), context.dp(54)),
            )
            addView(
                slider,
                LinearLayout.LayoutParams(
                    0,
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    1f,
                ).apply {
                    leftMargin = context.dp(28)
                    rightMargin = context.dp(28)
                },
            )
            addView(
                sliderButton("+") { setSliderProgress(slider.progress + 1) },
                LinearLayout.LayoutParams(context.dp(54), context.dp(54)),
            )
        }

        val content = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            contentDescription = title
            setPadding(context.dp(28), context.dp(28), context.dp(28), 0)
            addView(label, fullWidth())
            addView(sliderRow, fullWidth())
        }

        AlertDialog.Builder(context)
            .setView(content)
            .setPositiveButton("OK") { _, _ -> onSaved(valueForProgress(slider.progress)) }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun sliderButton(label: String, onClick: () -> Unit): TextView {
        return TextView(context).apply {
            text = label
            gravity = Gravity.CENTER
            includeFontPadding = false
            paperText(28f)
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(Color.WHITE)
                setStroke(context.dp(3), Color.BLACK)
            }
            setOnClickListener { onClick() }
        }
    }

    private fun showFontLicensesDialog() {
        AlertDialog.Builder(context)
            .setTitle("Font Licenses")
            .setMessage(
                listOf(
                    "Caveat: SIL Open Font License 1.1",
                    "Homemade Apple: Apache License 2.0",
                    "Ms Madi: SIL Open Font License 1.1",
                    "",
                    "Full license text is included in the project licenses folder.",
                ).joinToString("\n"),
            )
            .setPositiveButton("OK", null)
            .show()
    }

    private fun apiKeyStatus(provider: AiProvider): String {
        return if (prefs.apiKey(provider).isBlank()) "Required" else "Configured"
    }

    private fun customPromptStatus(): String {
        return if (prefs.customPrompt.isBlank()) "Not set" else "Configured"
    }

    private fun addTopicRow(group: LinearLayout, label: String, onClick: () -> Unit) {
        val row = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            isClickable = true
            isFocusable = true
            minimumHeight = context.dp(SINGLE_LINE_ROW_HEIGHT_DP)
            setPadding(context.dp(26), 0, context.dp(20), 0)
            setOnClickListener { onClick() }
        }
        row.addView(
            TextView(context).apply {
                text = label
                gravity = Gravity.CENTER_VERTICAL
                includeFontPadding = false
                paperText(23f)
            },
            LinearLayout.LayoutParams(
                0,
                LinearLayout.LayoutParams.MATCH_PARENT,
                1f,
            ),
        )
        row.addView(
            ImageView(context).apply {
                importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
                setImageResource(R.drawable.ic_nav_chevron_right)
                scaleType = ImageView.ScaleType.CENTER
            },
            LinearLayout.LayoutParams(
                context.dp(44),
                LinearLayout.LayoutParams.MATCH_PARENT,
            ),
        )
        group.addView(row, fullWidth())
    }

    private fun modelChoicesFor(provider: AiProvider): List<String> {
        val savedModel = prefs.model(provider)
        return if (savedModel in provider.modelOptions) {
            provider.modelOptions
        } else {
            listOf(savedModel) + provider.modelOptions
        }
    }

    private fun addValueRow(group: LinearLayout, label: String, value: String) {
        val row = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            minimumHeight = context.dp(SINGLE_LINE_ROW_HEIGHT_DP)
            setPadding(context.dp(26), context.dp(4), context.dp(26), context.dp(4))
        }
        row.addView(
            TextView(context).apply {
                text = label
                gravity = Gravity.CENTER_VERTICAL
                includeFontPadding = false
                paperText(23f)
            },
            LinearLayout.LayoutParams(
                0,
                LinearLayout.LayoutParams.MATCH_PARENT,
                1f,
            ),
        )
        row.addView(
            TextView(context).apply {
                text = value
                gravity = Gravity.CENTER_VERTICAL or Gravity.END
                includeFontPadding = false
                maxLines = 2
                ellipsize = TextUtils.TruncateAt.END
                paperText(19f)
            },
            LinearLayout.LayoutParams(
                0,
                LinearLayout.LayoutParams.MATCH_PARENT,
                1f,
            ),
        )
        group.addView(row, fullWidth())
    }

    private fun addPreviewBlock(parent: LinearLayout, label: String, value: String): TextView {
        val block = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            minimumHeight = context.dp(132)
            setPadding(context.dp(8), context.dp(6), context.dp(8), context.dp(4))
        }
        block.addView(
            TextView(context).apply {
                text = label
                gravity = Gravity.CENTER_VERTICAL or Gravity.START
                includeFontPadding = false
                paperText(20f)
            },
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            ),
        )
        val previewText = TextView(context).apply {
            text = value
            gravity = Gravity.CENTER_VERTICAL or Gravity.START
            includeFontPadding = false
            maxLines = 2
            ellipsize = TextUtils.TruncateAt.END
            setTextColor(Color.BLACK)
        }
        block.addView(
            previewText,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                0,
                1f,
            ).apply {
                topMargin = context.dp(12)
            },
        )
        parent.addView(block, fullWidth())
        return previewText
    }

    private fun addToggleRow(
        group: LinearLayout,
        label: String,
        explanation: String,
        checked: Boolean,
        onCheckedChange: (Boolean) -> Unit,
    ) {
        var isChecked = checked
        val toggle = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }

        fun renderToggle() {
            toggle.removeAllViews()
            toggle.background = GradientDrawable().apply {
                setColor(if (isChecked) Color.BLACK else Color.WHITE)
                cornerRadius = context.dp(2).toFloat()
                setStroke(context.dp(2), Color.BLACK)
            }

            val knob = View(context).apply {
                background = GradientDrawable().apply {
                    setColor(if (isChecked) Color.WHITE else Color.BLACK)
                    cornerRadius = context.dp(1).toFloat()
                }
            }
            val knobParams = LinearLayout.LayoutParams(context.dp(20), context.dp(20)).apply {
                leftMargin = context.dp(5)
                rightMargin = context.dp(5)
            }
            val stateText = TextView(context).apply {
                text = if (isChecked) "ON" else "OFF"
                gravity = Gravity.CENTER
                includeFontPadding = false
                paperText(14f, bold = true)
                setTextColor(if (isChecked) Color.WHITE else Color.BLACK)
            }
            val stateParams = LinearLayout.LayoutParams(context.dp(42), LinearLayout.LayoutParams.MATCH_PARENT)

            if (isChecked) {
                toggle.addView(stateText, stateParams)
                toggle.addView(knob, knobParams)
            } else {
                toggle.addView(knob, knobParams)
                toggle.addView(stateText, stateParams)
            }
        }

        val row = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            isClickable = true
            isFocusable = true
            minimumHeight = context.dp(DESCRIPTION_ROW_HEIGHT_DP)
            setPadding(context.dp(26), context.dp(8), context.dp(26), context.dp(8))
            setOnClickListener {
                isChecked = !isChecked
                renderToggle()
                onCheckedChange(isChecked)
            }
        }
        val textColumn = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_VERTICAL
        }
        textColumn.addView(
            TextView(context).apply {
                text = label
                includeFontPadding = false
                paperText(23f)
            },
            fullWidth(),
        )
        textColumn.addView(
            TextView(context).apply {
                text = explanation
                paperText(17f)
                setTextColor(Color.rgb(45, 45, 45))
            },
            fullWidth(),
        )
        row.addView(
            textColumn,
            LinearLayout.LayoutParams(
                0,
                LinearLayout.LayoutParams.WRAP_CONTENT,
                1f,
            ),
        )
        row.addView(
            toggle,
            LinearLayout.LayoutParams(
                context.dp(82),
                context.dp(38),
            ),
        )
        renderToggle()
        group.addView(row, fullWidth())
    }

    private fun fullWidth(): LinearLayout.LayoutParams {
        return LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT,
        )
    }
}

private data class ChoiceRowHandle(
    val valueText: TextView,
    val warningIcon: TextView,
)

private const val NAV_HEIGHT_DP = 72
private const val SINGLE_LINE_ROW_HEIGHT_DP = 72
private const val DESCRIPTION_ROW_HEIGHT_DP = 96
