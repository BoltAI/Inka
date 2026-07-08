package com.inkwell.diary.ui

import com.inkwell.diary.data.InkStroke
import com.inkwell.diary.data.InkFadeStyle
import com.inkwell.diary.data.Prefs
import com.inkwell.diary.data.ReplyStyle
import com.inkwell.diary.ink.InkCaptureController
import com.inkwell.diary.page.PageRenderer
import com.inkwell.diary.page.dissolveConfig
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

internal class MainFadeController(
    private val prefs: Prefs,
    private val renderer: PageRenderer,
    private val scope: CoroutineScope,
    private val captureController: () -> InkCaptureController?,
    private val addDebug: (String) -> Unit,
) {
    private var promptFadeJob: Job? = null
    private var fadeDisclosureJob: Job? = null

    fun cancelPromptFade() {
        promptFadeJob?.cancel()
        promptFadeJob = null
    }

    fun schedulePromptFade(strokes: List<InkStroke>): Job {
        cancelPromptFade()
        addDebug("prompt ink hide scheduled immediately")
        val job = scope.launch {
            fadePrompt(strokes)
        }
        promptFadeJob = job
        return job
    }

    fun cancelFadeDisclosure(clearPageLayer: Boolean = false) {
        val hadDisclosure = fadeDisclosureJob != null
        fadeDisclosureJob?.cancel()
        fadeDisclosureJob = null
        if (clearPageLayer && hadDisclosure) {
            renderer.clear()
        }
    }

    fun showDrawingModeHintIfNeeded() {
        if (prefs.replyStyle != ReplyStyle.Drawing || prefs.hasSeenDrawingModeHint) return
        prefs.hasSeenDrawingModeHint = true
        renderer.showBottomLine(DRAWING_MODE_HINT)
        addDebug("drawing hint shown")
    }

    private suspend fun fadePrompt(strokes: List<InkStroke>) {
        addDebug("prompt ink hold started, strokes=${strokes.size}")
        delay(PROMPT_INK_HOLD_MS)

        if (prefs.inkFadeStyle == InkFadeStyle.TurnsToDust) {
            captureController()?.hideRawInkLayer()
            renderer.setInkFadeStyle(prefs.inkFadeStyle)
            renderer.setDissolveConfig(prefs.dissolveConfig())
            renderer.fadeStrokes(strokes, includeFullOpacityFrame = false)
            addDebug("prompt ink burned")
        } else {
            val capture = captureController()
            if (capture?.isRawDrawingActive() == true) {
                capture.clearRawInkLayer()
            } else {
                renderer.hideCapturedStrokes()
            }
            addDebug("prompt ink hidden")
        }
        showFadeDisclosureOnce()
    }

    private fun showFadeDisclosureOnce() {
        if (prefs.hasSeenFadeDisclosure) return
        prefs.hasSeenFadeDisclosure = true
        cancelFadeDisclosure()
        fadeDisclosureJob = scope.launch {
            renderer.showBottomLine(FADE_DISCLOSURE)
            delay(FADE_DISCLOSURE_VISIBLE_MS)
            renderer.fadeBottomLine(FADE_DISCLOSURE)
            fadeDisclosureJob = null
        }
    }

    private companion object {
        private const val FADE_DISCLOSURE = "The ink leaves the page, but the diary keeps every word. Flip back anytime."
        private const val DRAWING_MODE_HINT = "Draw or write, then tap twice when it's my turn."
        private const val PROMPT_INK_HOLD_MS = 900L
        private const val FADE_DISCLOSURE_VISIBLE_MS = 5_000L
    }
}
