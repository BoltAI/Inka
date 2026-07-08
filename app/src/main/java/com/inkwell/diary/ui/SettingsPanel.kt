package com.inkwell.diary.ui

import android.content.Context
import android.graphics.Color
import android.widget.FrameLayout
import com.inkwell.diary.brain.ConversationEngine
import com.inkwell.diary.data.Persona
import com.inkwell.diary.data.Prefs
import com.inkwell.diary.recognize.RecognitionService
import kotlinx.coroutines.CoroutineScope

class SettingsPanel(
    context: Context,
    prefs: Prefs,
    engine: ConversationEngine,
    recognitionService: RecognitionService,
    scope: CoroutineScope,
    private val callbacks: Callbacks,
) : FrameLayout(context) {
    interface Callbacks {
        fun onCloseSettings()
        fun onClearConversation()
        fun onHandwritingStyleChanged()
        fun onToolbarSettingsChanged()
        fun onReplyStyleChanged()
        fun currentNotebookTitle(): String
        fun currentNotebookPersona(): Persona
        fun onNotebookTitleChanged(title: String)
        fun onNotebookPersonaChanged(persona: Persona)
        fun onBurnNotebook()
    }

    private val backStack = mutableListOf<SettingsRoute>()
    private val chrome = SettingsChrome(context) { goBack() }
    private val screenContext = SettingsScreenContext(
        context = context,
        prefs = prefs,
        engine = engine,
        recognitionService = recognitionService,
        scope = scope,
        callbacks = callbacks,
        navigate = { navigate(it) },
    )
    private var currentScreen = SettingsRoute.Home

    init {
        setBackgroundColor(Color.WHITE)
        isClickable = true
        isFocusable = true
        addView(
            chrome.view,
            LayoutParams(
                LayoutParams.MATCH_PARENT,
                LayoutParams.MATCH_PARENT,
            ),
        )
        render(SettingsRoute.Home)
    }

    fun handleBack(): Boolean {
        if (backStack.isEmpty()) return false
        currentScreen = backStack.removeAt(backStack.lastIndex)
        render(currentScreen)
        return true
    }

    private fun goBack() {
        if (!handleBack()) {
            callbacks.onCloseSettings()
        }
    }

    private fun navigate(screen: SettingsRoute) {
        if (screen == currentScreen) return
        backStack.add(currentScreen)
        currentScreen = screen
        render(screen)
    }

    private fun render(screen: SettingsRoute) {
        currentScreen = screen
        chrome.setTitle(screen.title)
        chrome.setContent(
            when (screen) {
                SettingsRoute.Home -> screenContext.buildHomeScreen()
                SettingsRoute.Ai -> screenContext.buildAiScreen()
                SettingsRoute.Notebook -> screenContext.buildNotebookScreen()
                SettingsRoute.Persona -> screenContext.buildPersonaScreen()
                SettingsRoute.Recognition -> screenContext.buildRecognitionScreen()
                SettingsRoute.Writing -> screenContext.buildWritingScreen()
                SettingsRoute.Developer -> screenContext.buildDeveloperScreen()
                SettingsRoute.Conversation -> screenContext.buildConversationScreen()
                SettingsRoute.About -> screenContext.buildAboutScreen()
            },
        )
    }
}
