package com.inkwell.diary.ui

import android.content.Intent
import android.os.SystemClock
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.assertion.ViewAssertions.matches
import androidx.test.espresso.matcher.ViewMatchers.withText
import androidx.test.platform.app.InstrumentationRegistry
import com.inkwell.diary.data.DiaryMode
import com.inkwell.diary.data.InkElement
import com.inkwell.diary.data.InkPoint
import com.inkwell.diary.data.InkStroke
import com.inkwell.diary.data.Notebook
import com.inkwell.diary.data.NotebookLoadResult
import com.inkwell.diary.data.NotebookPage
import com.inkwell.diary.data.NotebookStore
import com.inkwell.diary.data.Persona
import com.inkwell.diary.data.Prefs
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertFalse
import org.junit.Assert.assertEquals
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
            onView(withText("A diary that writes back.")).check(matches(withText("A diary that writes back.")))
        } finally {
            activity?.finish()
            instrumentation.waitForIdleSync()
            prefs.onboardingComplete = previousOnboardingState
        }
    }

    @Test
    fun debugReplyIntentWarnsWhenManuscriptReplyDoesNotFitPage() = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val prefs = Prefs(context)
        val store = NotebookStore(context.filesDir)
        val testPersona = Persona.Custom
        val notebookFile = store.fileFor(testPersona)
        val originalNotebook = notebookFile.takeIf { it.exists() }?.readBytes()
        val previousOnboardingState = prefs.onboardingComplete
        val previousPersona = prefs.persona
        val previousMode = prefs.diaryModeFor(testPersona)
        val longReply = List(70) { index ->
            "This injected manuscript line $index keeps flowing across the paper so the debug smoke can prove page overflow without touching the network."
        }.joinToString(" ")
        val launchIntent = Intent(DEBUG_REPLY_ACTION)
            .setClassName(context.packageName, MainActivity::class.java.name)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            .putExtra(DEBUG_REPLY_EXTRA, longReply)
        var activity: MainActivity? = null

        try {
            notebookFile.delete()
            prefs.onboardingComplete = true
            prefs.persona = testPersona
            prefs.setDiaryMode(testPersona, DiaryMode.Manuscript)
            activity = instrumentation.startActivitySync(launchIntent) as MainActivity
            assertTrue(
                "Expected the debug manuscript reply to show a page-fit warning",
                waitUntil(timeoutMs = 15_000L) {
                    runCatching {
                        onView(withText("Reply does not fit")).check(matches(withText("Reply does not fit")))
                    }.isSuccess
                },
            )
            val result = store.load(testPersona)
            assertTrue(result is NotebookLoadResult.Ready)
            val notebook = (result as NotebookLoadResult.Ready).notebook
            assertEquals(listOf(0), notebook.pages.map { it.index })
            assertTrue(notebook.rebuildDebugText().isBlank())
            assertFalse(notebook.rebuildDebugText().contains("injected manuscript line"))
            assertTrue(
                "Expected the overflow debug reply not to create extra pages",
                waitUntil(timeoutMs = 1_000L) {
                    val result = store.load(testPersona)
                    result is NotebookLoadResult.Ready &&
                        result.notebook.pages.size == 1 &&
                        result.notebook.rebuildDebugText().isBlank()
                },
            )
        } finally {
            activity?.finish()
            instrumentation.waitForIdleSync()
            prefs.onboardingComplete = previousOnboardingState
            prefs.persona = previousPersona
            prefs.setDiaryMode(testPersona, previousMode)
            if (originalNotebook != null) {
                notebookFile.parentFile?.mkdirs()
                notebookFile.writeBytes(originalNotebook)
            } else {
                notebookFile.delete()
            }
        }
    }

    @Test
    fun denseSavedInkNotebookLaunchesWithoutBlockingMainThread() = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val prefs = Prefs(context)
        val store = NotebookStore(context.filesDir)
        val testPersona = Persona.Custom
        val notebookFile = store.fileFor(testPersona)
        val originalNotebook = notebookFile.takeIf { it.exists() }?.readBytes()
        val previousOnboardingState = prefs.onboardingComplete
        val previousPersona = prefs.persona
        val previousMode = prefs.diaryModeFor(testPersona)
        val launchIntent = Intent().setClassName(context.packageName, MainActivity::class.java.name)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        var activity: MainActivity? = null

        try {
            prefs.onboardingComplete = true
            prefs.persona = testPersona
            prefs.setDiaryMode(testPersona, DiaryMode.Manuscript)
            store.save(denseNotebook(testPersona))

            activity = instrumentation.startActivitySync(launchIntent) as MainActivity
            SystemClock.sleep(1_000L)
            instrumentation.waitForIdleSync()

            val loaded = (store.load(testPersona) as NotebookLoadResult.Ready).notebook
            val ink = loaded.pages.single().elements.single() as InkElement
            assertEquals(DENSE_STROKES * DENSE_POINTS_PER_STROKE, ink.strokes.sumOf { it.points.size })
            assertFalse(activity.isFinishing)
        } finally {
            activity?.finish()
            instrumentation.waitForIdleSync()
            prefs.onboardingComplete = previousOnboardingState
            prefs.persona = previousPersona
            prefs.setDiaryMode(testPersona, previousMode)
            if (originalNotebook != null) {
                notebookFile.parentFile?.mkdirs()
                notebookFile.writeBytes(originalNotebook)
            } else {
                notebookFile.delete()
            }
        }
    }

    @Test
    fun debugReplyIntentKeepsPersonaNotebooksSeparate() = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val prefs = Prefs(context)
        val store = NotebookStore(context.filesDir)
        val personas = listOf(Persona.Custom, Persona.Muse)
        val originalNotebooks = personas.associateWith { persona ->
            store.fileFor(persona).takeIf { it.exists() }?.readBytes()
        }
        val previousOnboardingState = prefs.onboardingComplete
        val previousPersona = prefs.persona
        val previousModes = personas.associateWith { prefs.diaryModeFor(it) }
        val customReply = "custom persona isolation smoke"
        val museReply = "muse persona isolation smoke"
        var activity: MainActivity? = null

        try {
            prefs.onboardingComplete = true
            personas.forEach { persona ->
                store.fileFor(persona).delete()
                prefs.setDiaryMode(persona, DiaryMode.Manuscript)
            }

            activity = instrumentation.startActivitySync(
                debugReplyIntent(context.packageName, Persona.Custom, customReply),
            ) as MainActivity
            assertTrue(
                "Expected Custom notebook to receive only its debug reply",
                waitUntil(timeoutMs = 10_000L) {
                    store.debugTextFor(Persona.Custom).contains(customReply)
                },
            )
            activity.finish()
            instrumentation.waitForIdleSync()
            activity = null

            activity = instrumentation.startActivitySync(
                debugReplyIntent(context.packageName, Persona.Muse, museReply),
            ) as MainActivity
            assertTrue(
                "Expected Muse notebook to receive only its debug reply",
                waitUntil(timeoutMs = 10_000L) {
                    store.debugTextFor(Persona.Muse).contains(museReply)
                },
            )

            val customText = store.debugTextFor(Persona.Custom)
            val museText = store.debugTextFor(Persona.Muse)
            assertTrue(customText.contains(customReply))
            assertFalse(customText.contains(museReply))
            assertTrue(museText.contains(museReply))
            assertFalse(museText.contains(customReply))
        } finally {
            activity?.finish()
            instrumentation.waitForIdleSync()
            prefs.onboardingComplete = previousOnboardingState
            prefs.persona = previousPersona
            previousModes.forEach { (persona, mode) ->
                prefs.setDiaryMode(persona, mode)
            }
            originalNotebooks.forEach { (persona, bytes) ->
                restoreNotebook(store, persona, bytes)
            }
        }
    }

    private fun waitUntil(timeoutMs: Long, condition: () -> Boolean): Boolean {
        val deadline = SystemClock.elapsedRealtime() + timeoutMs
        while (SystemClock.elapsedRealtime() < deadline) {
            if (condition()) return true
            SystemClock.sleep(250L)
        }
        return condition()
    }

    private fun debugReplyIntent(packageName: String, persona: Persona, reply: String): Intent {
        return Intent(DEBUG_REPLY_ACTION)
            .setClassName(packageName, MainActivity::class.java.name)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            .putExtra(DEBUG_REPLY_EXTRA, reply)
            .putExtra(DEBUG_PERSONA_EXTRA, persona.name)
            .putExtra(DEBUG_MODE_EXTRA, DiaryMode.Manuscript.name)
    }

    private fun NotebookStore.debugTextFor(persona: Persona): String {
        val result = load(persona)
        return if (result is NotebookLoadResult.Ready) {
            result.notebook.rebuildDebugText()
        } else {
            ""
        }
    }

    private fun restoreNotebook(store: NotebookStore, persona: Persona, bytes: ByteArray?) {
        val file = store.fileFor(persona)
        if (bytes != null) {
            file.parentFile?.mkdirs()
            file.writeBytes(bytes)
        } else {
            file.delete()
        }
    }

    private fun com.inkwell.diary.data.Notebook.rebuildDebugText(): String {
        return pages.sortedBy { it.index }
            .flatMap { it.elements }
            .joinToString("\n") { element ->
                when (element) {
                    is com.inkwell.diary.data.InkElement -> element.recognizedText
                    is com.inkwell.diary.data.ReplyElement -> element.text
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
            id = persona.name,
            personaId = persona.name,
            createdAt = 10L,
            updatedAt = 11L,
            pages = listOf(
                NotebookPage(index = 0).addElement(
                    InkElement(
                        strokes = strokes,
                        committedAt = 11L,
                        recognizedText = "dense generated smoke",
                    ),
                ),
            ),
        )
    }

    private companion object {
        private const val DEBUG_REPLY_ACTION = "com.inkwell.diary.DEBUG_REPLY"
        private const val DEBUG_REPLY_EXTRA = "reply"
        private const val DEBUG_PERSONA_EXTRA = "persona"
        private const val DEBUG_MODE_EXTRA = "mode"
        private const val DENSE_STROKES = 12
        private const val DENSE_POINTS_PER_STROKE = 500
    }
}
