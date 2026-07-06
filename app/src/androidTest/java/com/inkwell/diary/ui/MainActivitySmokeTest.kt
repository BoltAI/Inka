package com.inkwell.diary.ui

import android.content.Intent
import android.os.SystemClock
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.test.platform.app.InstrumentationRegistry
import com.inkwell.diary.data.DEFAULT_NOTEBOOK_ID
import com.inkwell.diary.data.DEFAULT_NOTEBOOK_TITLE
import com.inkwell.diary.data.Exchange
import com.inkwell.diary.data.InkPoint
import com.inkwell.diary.data.InkStroke
import com.inkwell.diary.data.Notebook
import com.inkwell.diary.data.NotebookInk
import com.inkwell.diary.data.NotebookLoadResult
import com.inkwell.diary.data.NotebookStore
import com.inkwell.diary.data.Persona
import com.inkwell.diary.data.Prefs
import com.inkwell.diary.data.ReplyStyle
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MainActivitySmokeTest {
    @Test
    fun launchesIntoOnboardingWhenIncomplete() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val prefs = Prefs(context)
        val previousOnboardingState = prefs.onboardingComplete
        prefs.onboardingComplete = false
        val launchIntent = Intent().setClassName(context.packageName, MainActivity::class.java.name)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        var activity: MainActivity? = null

        try {
            activity = instrumentation.startActivitySync(launchIntent) as MainActivity
            assertTrue(
                "Expected onboarding headline to render",
                waitUntil(timeoutMs = 10_000L) {
                    activity?.containsVisibleText("A diary that writes back.") == true
                },
            )
        } finally {
            activity?.finish()
            instrumentation.waitForIdleSync()
            prefs.onboardingComplete = previousOnboardingState
        }
    }

    @Test
    fun denseActiveNotebookLaunchesWithoutBlockingMainThread() = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val prefs = Prefs(context)
        val store = NotebookStore(context.filesDir)
        val notebookFile = store.fileFor(DEFAULT_NOTEBOOK_ID)
        val originalNotebook = notebookFile.takeIf { it.exists() }?.readBytes()
        val previousOnboardingState = prefs.onboardingComplete
        val previousPersona = prefs.persona
        val previousActiveNotebookId = prefs.activeNotebookId
        val launchIntent = Intent().setClassName(context.packageName, MainActivity::class.java.name)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        var activity: MainActivity? = null

        try {
            prefs.onboardingComplete = true
            prefs.persona = Persona.default
            prefs.activeNotebookId = DEFAULT_NOTEBOOK_ID
            store.save(denseNotebook(Persona.default))

            activity = instrumentation.startActivitySync(launchIntent) as MainActivity
            SystemClock.sleep(1_000L)
            instrumentation.waitForIdleSync()

            assertTrue(activity.containsShownContentDescription("Read notebook"))
            val loaded = (store.load(DEFAULT_NOTEBOOK_ID, Persona.default) as NotebookLoadResult.Ready).notebook
            val ink = loaded.exchanges.single().ink!!
            assertEquals(DENSE_STROKES * DENSE_POINTS_PER_STROKE, ink.strokes.sumOf { it.points.size })
            assertFalse(activity.isFinishing)
        } finally {
            activity?.finish()
            instrumentation.waitForIdleSync()
            prefs.onboardingComplete = previousOnboardingState
            prefs.persona = previousPersona
            prefs.activeNotebookId = previousActiveNotebookId
            if (originalNotebook != null) {
                notebookFile.parentFile?.mkdirs()
                notebookFile.writeBytes(originalNotebook)
            } else {
                notebookFile.delete()
            }
        }
    }

    @Test
    fun settingsScreenHidesMainToolbarActions() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val prefs = Prefs(context)
        val previousOnboardingState = prefs.onboardingComplete
        val previousToolbarLogButton = prefs.showToolbarLogButton
        val launchIntent = Intent().setClassName(context.packageName, MainActivity::class.java.name)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        var activity: MainActivity? = null

        try {
            prefs.onboardingComplete = true
            prefs.showToolbarLogButton = true

            activity = instrumentation.startActivitySync(launchIntent) as MainActivity
            assertTrue(
                "Expected the main toolbar settings action to render",
                waitUntil(timeoutMs = 10_000L) {
                    activity?.containsShownContentDescription("Settings") == true
                },
            )

            assertTrue(activity.performClickOnShownContentDescription("Settings"))
            assertTrue(
                "Expected settings screen to render",
                waitUntil(timeoutMs = 10_000L) {
                    activity?.containsVisibleText("Settings") == true &&
                        activity?.containsShownContentDescription("Back") == true
                },
            )
            assertFalse(activity?.containsShownContentDescription("Erase page") == true)
            assertFalse(activity?.containsShownContentDescription("Read notebook") == true)
            assertFalse(activity?.containsShownContentDescription("AI log") == true)
        } finally {
            activity?.finish()
            instrumentation.waitForIdleSync()
            prefs.onboardingComplete = previousOnboardingState
            prefs.showToolbarLogButton = previousToolbarLogButton
        }
    }

    @Test
    fun generalSettingsExposeReplyStyleChoice() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val prefs = Prefs(context)
        val previousOnboardingState = prefs.onboardingComplete
        val previousReplyStyle = prefs.replyStyle
        val launchIntent = Intent().setClassName(context.packageName, MainActivity::class.java.name)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        var activity: MainActivity? = null

        try {
            prefs.onboardingComplete = true
            prefs.replyStyle = ReplyStyle.Writing

            activity = instrumentation.startActivitySync(launchIntent) as MainActivity
            assertTrue(
                "Expected the main toolbar settings action to render",
                waitUntil(timeoutMs = 10_000L) {
                    activity?.containsShownContentDescription("Settings") == true
                },
            )

            assertTrue(activity.performClickOnShownContentDescription("Settings"))
            assertTrue(
                "Expected Settings home to render",
                waitUntil(timeoutMs = 10_000L) {
                    activity?.containsVisibleText("General") == true
                },
            )
            assertTrue(activity.performClickOnVisibleText("General"))
            assertTrue(
                "Expected General reply style row to render",
                waitUntil(timeoutMs = 10_000L) {
                    activity?.containsVisibleText("The diary replies by") == true &&
                        activity?.containsVisibleText("Writing") == true
                },
            )
        } finally {
            activity?.finish()
            instrumentation.waitForIdleSync()
            prefs.onboardingComplete = previousOnboardingState
            prefs.replyStyle = previousReplyStyle
        }
    }

    @Test
    fun historyScreenShowsBackTitleAndBurnOnly() = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val prefs = Prefs(context)
        val store = NotebookStore(context.filesDir)
        val notebookFile = store.fileFor(DEFAULT_NOTEBOOK_ID)
        val originalNotebook = notebookFile.takeIf { it.exists() }?.readBytes()
        val previousOnboardingState = prefs.onboardingComplete
        val previousPersona = prefs.persona
        val previousActiveNotebookId = prefs.activeNotebookId
        val previousToolbarLogButton = prefs.showToolbarLogButton
        val launchIntent = Intent().setClassName(context.packageName, MainActivity::class.java.name)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        var activity: MainActivity? = null

        try {
            prefs.onboardingComplete = true
            prefs.persona = Persona.default
            prefs.activeNotebookId = DEFAULT_NOTEBOOK_ID
            prefs.showToolbarLogButton = true
            store.save(denseNotebook(Persona.default))

            activity = instrumentation.startActivitySync(launchIntent) as MainActivity
            assertTrue(
                "Expected the read notebook action to render",
                waitUntil(timeoutMs = 10_000L) {
                    activity?.containsShownContentDescription("Read notebook") == true
                },
            )

            assertTrue(activity.performClickOnShownContentDescription("Read notebook"))
            assertTrue(
                "Expected History screen chrome to render",
                waitUntil(timeoutMs = 10_000L) {
                    activity?.containsVisibleText("History") == true &&
                        activity?.containsShownContentDescription("Back") == true &&
                        activity?.containsShownContentDescription("Burn notebook") == true
                },
            )
            assertFalse(activity?.containsShownContentDescription("Erase page") == true)
            assertFalse(activity?.containsShownContentDescription("Read notebook") == true)
            assertFalse(activity?.containsShownContentDescription("Settings") == true)
            assertFalse(activity?.containsShownContentDescription("AI log") == true)
        } finally {
            activity?.finish()
            instrumentation.waitForIdleSync()
            prefs.onboardingComplete = previousOnboardingState
            prefs.persona = previousPersona
            prefs.activeNotebookId = previousActiveNotebookId
            prefs.showToolbarLogButton = previousToolbarLogButton
            if (originalNotebook != null) {
                notebookFile.parentFile?.mkdirs()
                notebookFile.writeBytes(originalNotebook)
            } else {
                notebookFile.delete()
            }
        }
    }

    private fun denseNotebook(persona: Persona): Notebook {
        val strokes = (0 until DENSE_STROKES).map { strokeIndex ->
            InkStroke(
                points = (0 until DENSE_POINTS_PER_STROKE).map { pointIndex ->
                    InkPoint(
                        x = 120f + pointIndex * 2.4f,
                        y = 180f + strokeIndex * 28f + ((pointIndex % 17) * 0.7f),
                        pressure = 0.72f,
                        timestampMs = (strokeIndex * DENSE_POINTS_PER_STROKE + pointIndex).toLong(),
                    )
                },
            )
        }
        return Notebook(
            id = DEFAULT_NOTEBOOK_ID,
            title = DEFAULT_NOTEBOOK_TITLE,
            personaId = persona.name,
            createdAt = 10L,
            updatedAt = 11L,
            exchanges = listOf(
                Exchange(
                    id = "exchange-11",
                    committedAt = 11L,
                    ink = NotebookInk(
                        strokes = strokes,
                        recognizedText = "dense generated smoke",
                    ),
                    reply = null,
                ),
            ),
        )
    }

    private fun waitUntil(timeoutMs: Long, condition: () -> Boolean): Boolean {
        val deadline = SystemClock.elapsedRealtime() + timeoutMs
        while (SystemClock.elapsedRealtime() < deadline) {
            if (condition()) return true
            SystemClock.sleep(250L)
        }
        return condition()
    }

    private fun MainActivity.containsVisibleText(expected: String): Boolean {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        var found = false
        instrumentation.runOnMainSync {
            found = window.decorView.containsVisibleText(expected)
        }
        return found
    }

    private fun View.containsVisibleText(expected: String): Boolean {
        if (visibility != View.VISIBLE) return false
        if (this is TextView && text?.toString() == expected) return true
        if (this !is ViewGroup) return false
        for (index in 0 until childCount) {
            if (getChildAt(index).containsVisibleText(expected)) return true
        }
        return false
    }

    private fun MainActivity.containsShownContentDescription(expected: String): Boolean {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        var found = false
        instrumentation.runOnMainSync {
            found = window.decorView.findShownContentDescription(expected) != null
        }
        return found
    }

    private fun MainActivity.performClickOnShownContentDescription(expected: String): Boolean {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        var clicked = false
        instrumentation.runOnMainSync {
            clicked = window.decorView.findShownContentDescription(expected)?.performClick() == true
        }
        instrumentation.waitForIdleSync()
        return clicked
    }

    private fun MainActivity.performClickOnVisibleText(expected: String): Boolean {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        var clicked = false
        instrumentation.runOnMainSync {
            clicked = window.decorView.findVisibleTextView(expected)
                ?.clickableSelfOrAncestor()
                ?.performClick() == true
        }
        instrumentation.waitForIdleSync()
        return clicked
    }

    private fun View.findShownContentDescription(expected: String): View? {
        if (!isShown) return null
        if (contentDescription?.toString() == expected) return this
        if (this !is ViewGroup) return null
        for (index in 0 until childCount) {
            val match = getChildAt(index).findShownContentDescription(expected)
            if (match != null) return match
        }
        return null
    }

    private fun View.findVisibleTextView(expected: String): View? {
        if (!isShown) return null
        if (this is TextView && text?.toString() == expected) return this
        if (this !is ViewGroup) return null
        for (index in 0 until childCount) {
            val match = getChildAt(index).findVisibleTextView(expected)
            if (match != null) return match
        }
        return null
    }

    private fun View.clickableSelfOrAncestor(): View? {
        var current: View? = this
        while (current != null) {
            if (current.isClickable && current.isEnabled) return current
            current = current.parent as? View
        }
        return null
    }

    private companion object {
        private const val DENSE_STROKES = 12
        private const val DENSE_POINTS_PER_STROKE = 500
    }
}
