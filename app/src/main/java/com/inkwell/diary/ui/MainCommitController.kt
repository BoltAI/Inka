package com.inkwell.diary.ui

import android.content.Context
import android.os.SystemClock
import android.view.SurfaceView
import com.inkwell.diary.BuildConfig
import com.inkwell.diary.brain.AnthropicResult
import com.inkwell.diary.brain.BrainErrorKind
import com.inkwell.diary.brain.ConversationEngine
import com.inkwell.diary.brain.ConversationSettings
import com.inkwell.diary.brain.DrawingReplyResult
import com.inkwell.diary.brain.ErrorMapper
import com.inkwell.diary.data.AiProvider
import com.inkwell.diary.data.Exchange
import com.inkwell.diary.data.HandwritingReplyMode
import com.inkwell.diary.data.InkStroke
import com.inkwell.diary.data.Notebook
import com.inkwell.diary.data.NotebookInk
import com.inkwell.diary.data.NotebookReply
import com.inkwell.diary.data.NotebookSketch
import com.inkwell.diary.data.NotebookStore
import com.inkwell.diary.data.Prefs
import com.inkwell.diary.data.ReplyStyle
import com.inkwell.diary.data.StrokeStore
import com.inkwell.diary.data.newCanvasId
import com.inkwell.diary.data.newExchangeId
import com.inkwell.diary.data.rebuildApiHistory
import com.inkwell.diary.handwriting.HandwritingSynthesisClient
import com.inkwell.diary.handwriting.HandwritingSynthesisRequest
import com.inkwell.diary.handwriting.HandwritingSynthesisResult
import com.inkwell.diary.handwriting.OkHttpHandwritingSynthesisClient
import com.inkwell.diary.handwriting.handwritingSynthesisFontSizePx
import com.inkwell.diary.ink.InkCaptureController
import com.inkwell.diary.page.DissolveLabStore
import com.inkwell.diary.page.PageRenderer
import com.inkwell.diary.page.ReplyOverlayView
import com.inkwell.diary.page.SvgFidelityLabStore
import com.inkwell.diary.page.SvgPathAdapter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

internal class MainCommitController(
    private val context: Context,
    private val prefs: Prefs,
    private val engine: ConversationEngine,
    private val recognitionService: com.inkwell.diary.recognize.RecognitionService,
    private val notebookStore: NotebookStore,
    private val renderer: PageRenderer,
    private val handwritingSynthesisClient: HandwritingSynthesisClient = OkHttpHandwritingSynthesisClient(),
    private val strokeStore: StrokeStore,
    private val surfaceView: () -> SurfaceView,
    private val replyOverlay: () -> ReplyOverlayView,
    private val captureController: () -> InkCaptureController?,
    private val alertDialogs: MainAlertDialogs,
    private val activeNotebook: () -> Notebook?,
    private val setActiveNotebook: (Notebook) -> Unit,
    private val activeCanvasId: () -> String?,
    private val setActiveCanvasId: (String) -> Unit,
    private val voiceChangedForNextRequest: () -> Boolean,
    private val setVoiceChangedForNextRequest: (Boolean) -> Unit,
    private val lastCommitRequestedElapsedMs: () -> Long?,
    private val setBusy: (Boolean) -> Unit,
    private val schedulePromptFade: (List<InkStroke>) -> Job,
    private val loadActiveNotebook: suspend () -> Notebook,
    private val apiKeyForRequest: (AiProvider) -> String?,
    private val setStatus: (String) -> Unit,
    private val addDebug: (String) -> Unit,
) {
    suspend fun commitPage() {
        when (prefs.replyStyle) {
            ReplyStyle.Writing -> commitFadePage()
            ReplyStyle.Drawing -> commitDrawingPage()
        }
    }

    private suspend fun commitDrawingPage() {
        setBusy(true)
        val message = strokeStore.snapshot(surfaceView().width, surfaceView().height)
        val strokes = message.strokes
        if (strokes.isEmpty()) {
            setBusy(false)
            return
        }
        captureController()?.setInputEnabled(false, keepRawInkVisible = true)
        setStatus("Recognizing")
        val recognitionStartedAt = SystemClock.elapsedRealtime()
        addDebug("drawing recognizing ${strokes.size} stroke(s), language=${prefs.recognitionLanguage}")
        val latestUserText = when (val outcome = recognitionService.recognize(message, prefs.recognitionLanguage)) {
            is com.inkwell.diary.recognize.RecognitionOutcome.Text -> {
                val recognitionMs = SystemClock.elapsedRealtime() - recognitionStartedAt
                val recognized = outcome.value.trim()
                addDebug("drawing recognized in ${recognitionMs}ms: ${recognized.ifBlank { "(blank)" }.shortForDebug()}")
                recognized
            }
            is com.inkwell.diary.recognize.RecognitionOutcome.Failure -> {
                val recognitionMs = SystemClock.elapsedRealtime() - recognitionStartedAt
                addDebug("drawing recognition failed in ${recognitionMs}ms: ${outcome.message.shortForDebug()}")
                ""
            }
        }
        val notebookBeforeRequest = activeNotebook() ?: loadActiveNotebook()
        val canvasId = activeCanvasId() ?: NotebookCanvasProjector.latestCanvasId(notebookBeforeRequest)
            ?: newCanvasId(System.currentTimeMillis()).also { setActiveCanvasId(it) }
        val priorElements = NotebookCanvasProjector.canvasElements(notebookBeforeRequest, canvasId)
        val snapshot = renderer.snapshotForVision(
            elements = priorElements,
            draftStrokes = strokes,
        )
        if (snapshot == null) {
            captureController()?.setInputEnabled(true)
            setBusy(false)
            setStatus("Canvas not ready")
            addDebug("drawing snapshot failed: renderer not ready")
            return
        }

        val committedAt = System.currentTimeMillis()
        val exchangeId = newExchangeId(committedAt)
        var persistedNotebook = notebookBeforeRequest.withExchange(
            Exchange(
                id = exchangeId,
                committedAt = committedAt,
                canvasId = canvasId,
                ink = NotebookInk(
                    strokes = strokes,
                    recognizedText = latestUserText,
                ),
                reply = null,
            ),
            updatedAt = committedAt,
        )
        setActiveNotebook(persistedNotebook)
        notebookStore.save(persistedNotebook)
        addDebug(
            "drawing exchange saved: canvas=$canvasId, strokes=${strokes.size}, snapshot=${snapshot.width}x${snapshot.height}, transcript=${latestUserText.ifBlank { "(blank)" }.shortForDebug()}",
        )

        val provider = prefs.provider
        val apiKey = apiKeyForRequest(provider)
        if (apiKey == null) {
            showCommittedDrawingCanvas(canvasId, strokes)
            finishDrawingCommit("Secure storage error")
            return
        }
        if (apiKey.isBlank()) {
            showCommittedDrawingCanvas(canvasId, strokes)
            finishDrawingCommit("AI setup needed")
            addDebug("drawing ai setup missing: ${provider.label} API key")
            alertDialogs.showMissingApiKeyWarning(provider.label)
            return
        }
        if (provider == AiProvider.Groq) {
            showCommittedDrawingCanvas(canvasId, strokes)
            finishDrawingCommit("Unsupported provider")
            addDebug("drawing mode unsupported provider: ${provider.label}")
            alertDialogs.showWarning(
                title = "Unsupported provider",
                message = "Drawing mode currently supports Anthropic and OpenAI. Switch Provider in Settings > AI Settings.",
            )
            return
        }

        engine.replaceHistory(notebookBeforeRequest.rebuildApiHistory())
        setStatus("Drawing")
        val settings = ConversationSettings(
            apiKey = apiKey,
            model = prefs.model,
            systemPrompt = systemPromptForRequest(),
            provider = provider,
            reasoningEffort = prefs.reasoningEffort,
        )
        showCommittedDrawingCanvas(canvasId, strokes, fullRefresh = false)
        renderer.beginSketchReply()
        val aiStartedAt = SystemClock.elapsedRealtime()
        addDebug("draw stream start: ${provider.label} ${prefs.model}, effort=${prefs.reasoningEffort.label}")
        var firstToolDeltaAt: Long? = null
        var firstPathParsedAt: Long? = null
        var firstStrokeRenderedAt: Long? = null
        val streamedPaths = mutableListOf<String>()
        val streamedReplyStrokes = mutableListOf<InkStroke>()
        val result = engine.streamDrawing(
            settings = settings,
            snapshot = snapshot,
            latestUserText = latestUserText,
            onPath = { path ->
                val pathAt = SystemClock.elapsedRealtime()
                if (firstPathParsedAt == null) {
                    firstPathParsedAt = pathAt
                    addDebug("draw first path parsed: ${pathAt - aiStartedAt}ms")
                }
                streamedPaths.add(path)
                val converted = SvgPathAdapter.convertOne(
                    path = path,
                    imageWidth = snapshot.width,
                    imageHeight = snapshot.height,
                    pageWidth = snapshot.pageWidth,
                    pageHeight = snapshot.pageHeight,
                )
                if (converted.strokes.isEmpty()) {
                    addDebug("draw streamed path rejected")
                } else {
                    withContext(Dispatchers.Main) {
                        converted.strokes.forEach { stroke ->
                            renderer.appendSketchStroke(stroke)
                            streamedReplyStrokes.add(stroke)
                            if (firstStrokeRenderedAt == null) {
                                val strokeAt = SystemClock.elapsedRealtime()
                                firstStrokeRenderedAt = strokeAt
                                addDebug("draw first stroke rendered: ${strokeAt - aiStartedAt}ms")
                            }
                        }
                    }
                }
            },
            onToolJsonDelta = {
                if (firstToolDeltaAt == null) {
                    val deltaAt = SystemClock.elapsedRealtime()
                    firstToolDeltaAt = deltaAt
                    addDebug("draw first tool delta: ${deltaAt - aiStartedAt}ms")
                }
            },
        )
        when (result) {
            is DrawingReplyResult.Success -> {
                val converted = SvgPathAdapter.convert(
                    paths = result.paths,
                    imageWidth = snapshot.width,
                    imageHeight = snapshot.height,
                    pageWidth = snapshot.pageWidth,
                    pageHeight = snapshot.pageHeight,
                )
                addDebug(
                    "draw tool done: paths=${result.paths.size}, valid=${converted.strokes.size}, rejected=${converted.rejectedPaths}, truncated=${converted.truncated}, total=${SystemClock.elapsedRealtime() - aiStartedAt}ms",
                )
                if (converted.strokes.isEmpty()) {
                    val error = ErrorMapper.from(BrainErrorKind.BadRequest)
                    addDebug("draw stream failed: no valid strokes after conversion")
                    alertDialogs.showAiFailureWarning(error)
                    finishDrawingCommit("AI error")
                    return
                }
                val missingPaths = result.paths.drop(streamedPaths.size)
                missingPaths.forEach { path ->
                    val missing = SvgPathAdapter.convertOne(
                        path = path,
                        imageWidth = snapshot.width,
                        imageHeight = snapshot.height,
                        pageWidth = snapshot.pageWidth,
                        pageHeight = snapshot.pageHeight,
                    )
                    missing.strokes.forEach { stroke ->
                        renderer.appendSketchStroke(stroke)
                        streamedReplyStrokes.add(stroke)
                    }
                }
                val replyStrokes = streamedReplyStrokes.takeIf { it.isNotEmpty() }?.toList() ?: converted.strokes
                renderer.endSketchReply(fullRefresh = false)
                addDebug(
                    "draw stream complete: paths=${result.paths.size}, valid=${replyStrokes.size}, rejected=${converted.rejectedPaths}, truncated=${converted.truncated}, total=${SystemClock.elapsedRealtime() - aiStartedAt}ms",
                )
                if (BuildConfig.DEBUG) {
                    SvgFidelityLabStore.save(context, result.paths, snapshot.width, snapshot.height)
                }
                val savedAt = System.currentTimeMillis()
                persistedNotebook = saveDrawingReply(
                    notebook = persistedNotebook,
                    exchangeId = exchangeId,
                    transcript = latestUserText.ifBlank { result.pageTextTranscript },
                    reply = NotebookReply(
                        text = "",
                        sketch = NotebookSketch(replyStrokes),
                        personaId = prefs.persona.name,
                        createdAt = savedAt,
                    ),
                    savedAt = savedAt,
                )
                engine.replaceHistory(persistedNotebook.rebuildApiHistory())
                finishDrawingCommit("Idle")
            }
            is DrawingReplyResult.Failure -> {
                val error = ErrorMapper.from(result.kind)
                addDebug(
                    "draw stream failed: ${provider.label} ${prefs.model}, ${result.kind}, partialStrokes=${streamedReplyStrokes.size}, total=${SystemClock.elapsedRealtime() - aiStartedAt}ms${result.detail?.let { " - ${it.shortForDebug()}" }.orEmpty()}",
                )
                setStatus("AI error: ${result.kind}")
                alertDialogs.showAiFailureWarning(error)
                finishDrawingCommit("AI error")
            }
        }
    }

    private suspend fun saveDrawingReply(
        notebook: Notebook,
        exchangeId: String,
        transcript: String,
        reply: NotebookReply,
        savedAt: Long,
    ): Notebook {
        val exchange = notebook.exchanges.firstOrNull { it.id == exchangeId } ?: return notebook
        val updatedInk = exchange.ink?.copy(recognizedText = transcript)
        val updated = notebook.withExchange(
            exchange.copy(
                ink = updatedInk,
                reply = reply,
            ),
            updatedAt = savedAt,
        )
        setActiveNotebook(updated)
        notebookStore.save(updated)
        addDebug("drawing reply saved: exchanges=${updated.exchanges.size}, transcript=${transcript.ifBlank { "(blank)" }.shortForDebug()}")
        return updated
    }

    private suspend fun showCommittedDrawingCanvas(
        canvasId: String,
        newStrokes: List<InkStroke>,
        fullRefresh: Boolean = true,
    ) {
        val notebook = activeNotebook() ?: loadActiveNotebook()
        val elements = NotebookCanvasProjector.canvasElements(notebook, canvasId).ifEmpty {
            listOf(NotebookCanvasProjector.currentInkElement(newStrokes, System.currentTimeMillis()))
        }
        captureController()?.hideRawInkLayer()
        renderer.showNotebookElements(elements, fullRefresh = fullRefresh)
    }

    private fun finishDrawingCommit(status: String) {
        strokeStore.clear()
        captureController()?.clearRawInkLayer()
        captureController()?.setInputEnabled(true)
        setBusy(false)
        setStatus(status)
    }

    private suspend fun commitFadePage() {
        setBusy(true)
        val message = strokeStore.snapshot(surfaceView().width, surfaceView().height)
        val strokes = message.strokes
        if (BuildConfig.DEBUG) {
            runCatching { DissolveLabStore.save(context, strokes) }
        }
        captureController()?.setInputEnabled(false, keepRawInkVisible = true)
        val promptFade = schedulePromptFade(strokes)
        val recognitionStartedAt = SystemClock.elapsedRealtime()
        val afterCommit = lastCommitRequestedElapsedMs()
            ?.let { ", afterCommit=${recognitionStartedAt - it}ms" }
            .orEmpty()
        addDebug("recognizing ${strokes.size} stroke(s), language=${prefs.recognitionLanguage}$afterCommit")
        val recognized = when (val outcome = recognitionService.recognize(message, prefs.recognitionLanguage)) {
            is com.inkwell.diary.recognize.RecognitionOutcome.Text -> {
                val recognitionMs = SystemClock.elapsedRealtime() - recognitionStartedAt
                addDebug("recognized in ${recognitionMs}ms: ${outcome.value.ifBlank { "(blank)" }.shortForDebug()}")
                outcome.value
            }
            is com.inkwell.diary.recognize.RecognitionOutcome.Failure -> {
                val recognitionMs = SystemClock.elapsedRealtime() - recognitionStartedAt
                addDebug("recognition failed in ${recognitionMs}ms: ${outcome.message.shortForDebug()}")
                ""
            }
        }

        if (recognized.isBlank()) {
            promptFade.join()
            strokeStore.clear()
            renderer.showHint()
            captureController()?.setInputEnabled(true)
            setBusy(false)
            setStatus("Recognition failed")
            return
        }

        val notebookBeforeRequest = activeNotebook() ?: loadActiveNotebook()
        engine.replaceHistory(notebookBeforeRequest.rebuildApiHistory())
        val committedAt = System.currentTimeMillis()
        val exchangeId = newExchangeId(committedAt)
        var persistedNotebook = notebookBeforeRequest.withExchange(
            Exchange(
                id = exchangeId,
                committedAt = committedAt,
                ink = NotebookInk(
                    strokes = strokes,
                    recognizedText = recognized.trim(),
                ),
                reply = null,
            ),
            updatedAt = committedAt,
        )
        setActiveNotebook(persistedNotebook)
        notebookStore.save(persistedNotebook)
        addDebug("exchange saved: notebook=${persistedNotebook.id}, exchanges=${persistedNotebook.exchanges.size}")

        val provider = prefs.provider
        val apiKey = apiKeyForRequest(provider)
        if (apiKey == null) {
            promptFade.join()
            strokeStore.clear()
            captureController()?.setInputEnabled(true)
            setBusy(false)
            setStatus("Secure storage error")
            return
        }
        if (apiKey.isBlank()) {
            promptFade.join()
            strokeStore.clear()
            captureController()?.setInputEnabled(true)
            setBusy(false)
            setStatus("AI setup needed")
            addDebug("ai setup missing: ${provider.label} API key")
            alertDialogs.showMissingApiKeyWarning(provider.label)
            return
        }

        setStatus("Sending")
        addDebug("sending to ${provider.label} ${prefs.model}, effort=${prefs.reasoningEffort.label}, chars=${recognized.length}")
        val aiStartedAt = SystemClock.elapsedRealtime()
        var firstReplyDeltaAt: Long? = null
        var replyStarted = false
        var streamedChars = 0
        var replyRenderMs = 0L
        val settings = ConversationSettings(
            apiKey = apiKey,
            model = prefs.model,
            systemPrompt = systemPromptForRequest(),
            provider = provider,
            reasoningEffort = prefs.reasoningEffort,
        )
        val useGeneratedStrokes = HandwritingReplyMode.fromPrefs(prefs).usesGeneratedStrokes
        val result = if (useGeneratedStrokes) {
            engine.sendMessage(settings, recognized)
        } else {
            engine.streamMessage(settings, recognized) { delta ->
                if (delta.isEmpty()) return@streamMessage
                promptFade.join()
                withContext(Dispatchers.Main) {
                    if (!replyStarted) {
                        val deltaAt = SystemClock.elapsedRealtime()
                        firstReplyDeltaAt = deltaAt
                        addDebug("first reply delta, ttft=${deltaAt - aiStartedAt}ms")
                        setStatus("Reply")
                        replyOverlay().beginReply()
                        replyStarted = true
                    }
                    val renderStartedAt = SystemClock.elapsedRealtime()
                    writeReplyDelta(delta)
                    replyRenderMs += SystemClock.elapsedRealtime() - renderStartedAt
                    streamedChars += delta.length
                }
            }
        }

        promptFade.join()
        when (result) {
            is AnthropicResult.Success -> {
                val totalMs = SystemClock.elapsedRealtime() - aiStartedAt
                val ttft = firstReplyDeltaAt?.let { "${it - aiStartedAt}ms" } ?: "n/a"
                addDebug(
                    "reply done: ${provider.label} ${prefs.model}, ttft=$ttft, stream+render=${totalMs}ms, render=${replyRenderMs}ms, chars=${result.text.length}, visible=$streamedChars",
                )
                val generatedStrokes = if (useGeneratedStrokes) {
                    renderGeneratedHandwritingReply(result.text, afterStrokes = strokes)
                } else {
                    null
                }
                if (generatedStrokes == null && !replyStarted) {
                    setStatus("Reply")
                    replyOverlay().revealReply(result.text)
                }
                val savedAt = System.currentTimeMillis()
                val exchange = persistedNotebook.exchanges.firstOrNull { it.id == exchangeId }
                if (exchange != null) {
                    persistedNotebook = persistedNotebook.withExchange(
                        exchange.copy(
                            reply = NotebookReply(
                                text = result.text,
                                sketch = generatedStrokes?.let { NotebookSketch(it) },
                                displayText = generatedStrokes == null,
                                personaId = prefs.persona.name,
                                createdAt = savedAt,
                            ),
                        ),
                        updatedAt = savedAt,
                    )
                    setActiveNotebook(persistedNotebook)
                    notebookStore.save(persistedNotebook)
                    engine.replaceHistory(persistedNotebook.rebuildApiHistory())
                    addDebug("reply saved: notebook=${persistedNotebook.id}, exchanges=${persistedNotebook.exchanges.size}")
                }
            }
            is AnthropicResult.Failure -> {
                val error = ErrorMapper.from(result.kind)
                val totalMs = SystemClock.elapsedRealtime() - aiStartedAt
                addDebug(
                    "ai failed: ${provider.label} ${prefs.model}, ${result.kind}, total=${totalMs}ms${result.detail?.let { " - ${it.shortForDebug()}" }.orEmpty()}",
                )
                setStatus("AI error: ${result.kind}")
                alertDialogs.showAiFailureWarning(error)
            }
        }
        strokeStore.clear()
        captureController()?.setInputEnabled(true)
        setBusy(false)
    }

    private fun systemPromptForRequest(): String {
        return if (voiceChangedForNextRequest()) {
            setVoiceChangedForNextRequest(false)
            "${prefs.systemPrompt()}\n\nThe voice of the diary has changed."
        } else {
            prefs.systemPrompt()
        }
    }

    private suspend fun renderGeneratedHandwritingReply(
        text: String,
        afterStrokes: List<InkStroke> = emptyList(),
    ): List<InkStroke>? {
        val serverUrl = prefs.handwritingSynthesisServerUrl
        if (serverUrl.isBlank()) {
            addDebug("handwriting synthesis skipped: server endpoint not set")
            return null
        }
        val area = renderer.replyWritingArea(afterStrokes)
        if (area == null) {
            addDebug("handwriting synthesis skipped: renderer area unavailable")
            return null
        }

        setStatus("Writing")
        val startedAt = SystemClock.elapsedRealtime()
        val sourceLabel = serverUrl.shortForDebug(80)
        addDebug("handwriting synthesis start: $sourceLabel, chars=${text.length}")
        val request = HandwritingSynthesisRequest(
            text = text,
            pageWidth = area.pageWidth,
            pageHeight = area.pageHeight,
            left = area.left,
            top = area.top,
            maxWidth = area.maxWidth,
            fontSizeSp = handwritingSynthesisFontSizePx(
                fontSizeSp = prefs.handwritingFontSizeSp,
                scaledDensity = context.resources.displayMetrics.density * context.resources.configuration.fontScale,
            ),
            strokeWidthMm = prefs.handwritingStrokeWidthMm,
        )
        return when (val result = handwritingSynthesisClient.synthesize(serverUrl, request)) {
            is HandwritingSynthesisResult.Success -> {
                addDebug(
                    "handwriting synthesis done: strokes=${result.strokes.size}, points=${result.strokes.sumOf { it.points.size }}, total=${SystemClock.elapsedRealtime() - startedAt}ms",
                )
                withContext(Dispatchers.Main) {
                    replyOverlay().clearReply()
                    renderer.beginSketchReply()
                    renderer.revealGeneratedHandwritingStrokes(result.strokes)
                }
                result.strokes
            }
            is HandwritingSynthesisResult.Failure -> {
                addDebug(
                    "handwriting synthesis failed: ${result.message.shortForDebug()}, total=${SystemClock.elapsedRealtime() - startedAt}ms",
                )
                null
            }
        }
    }

    private suspend fun writeReplyDelta(delta: String) {
        delta.forEach { char ->
            replyOverlay().appendReplyText(char.toString())
            delay(if (char.isWhitespace()) STREAM_WORD_GAP_MS else STREAM_CHARACTER_GAP_MS)
        }
    }

    private fun String.shortForDebug(limit: Int = 160): String {
        val normalized = replace('\n', ' ').trim()
        return if (normalized.length <= limit) normalized else normalized.take(limit) + "..."
    }

    private companion object {
        private const val STREAM_CHARACTER_GAP_MS = 24L
        private const val STREAM_WORD_GAP_MS = 55L
    }
}
