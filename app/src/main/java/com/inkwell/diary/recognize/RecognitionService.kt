package com.inkwell.diary.recognize

import com.google.mlkit.common.model.DownloadConditions
import com.google.mlkit.common.model.RemoteModelManager
import com.google.mlkit.vision.digitalink.recognition.DigitalInkRecognition
import com.google.mlkit.vision.digitalink.recognition.DigitalInkRecognitionModel
import com.google.mlkit.vision.digitalink.recognition.DigitalInkRecognitionModelIdentifier
import com.google.mlkit.vision.digitalink.recognition.DigitalInkRecognizerOptions
import com.google.mlkit.vision.digitalink.recognition.RecognitionContext
import com.google.mlkit.vision.digitalink.recognition.WritingArea
import com.inkwell.diary.data.InkMessage
import kotlinx.coroutines.tasks.await

sealed class RecognitionOutcome {
    data class Text(val value: String) : RecognitionOutcome()
    data class Failure(val message: String) : RecognitionOutcome()
}

sealed class ModelDownloadOutcome {
    data object Ready : ModelDownloadOutcome()
    data class Failure(val message: String) : ModelDownloadOutcome()
}

interface RecognitionService {
    suspend fun ensureModel(languageTag: String): ModelDownloadOutcome
    suspend fun recognize(message: InkMessage, languageTag: String): RecognitionOutcome
}

class MlKitRecognitionService : RecognitionService {
    override suspend fun ensureModel(languageTag: String): ModelDownloadOutcome {
        val model = modelFor(languageTag)
            ?: return ModelDownloadOutcome.Failure("No ML Kit model for $languageTag")
        val manager = RemoteModelManager.getInstance()
        return try {
            if (manager.isModelDownloaded(model).await()) {
                ModelDownloadOutcome.Ready
            } else {
                manager.download(model, DownloadConditions.Builder().build()).await()
                ModelDownloadOutcome.Ready
            }
        } catch (e: Exception) {
            ModelDownloadOutcome.Failure(e.message ?: "Model download failed")
        }
    }

    override suspend fun recognize(
        message: InkMessage,
        languageTag: String,
    ): RecognitionOutcome {
        val model = modelFor(languageTag)
            ?: return RecognitionOutcome.Failure("No ML Kit model for $languageTag")
        return try {
            val recognizer = DigitalInkRecognition.getClient(
                DigitalInkRecognizerOptions.builder(model).build(),
            )
            ensureModel(languageTag)
            val context = RecognitionContext.builder()
                .setPreContext("")
                .setWritingArea(WritingArea(message.width.toFloat(), message.height.toFloat()))
                .build()
            val result = recognizer.recognize(MlKitInkConverter.toInk(message), context).await()
            RecognitionOutcome.Text(result.candidates.firstOrNull()?.text.orEmpty().trim())
        } catch (e: Exception) {
            RecognitionOutcome.Failure(e.message ?: "Recognition failed")
        }
    }

    private fun modelFor(languageTag: String): DigitalInkRecognitionModel? {
        val identifier = DigitalInkRecognitionModelIdentifier.fromLanguageTag(languageTag)
            ?: return null
        return DigitalInkRecognitionModel.builder(identifier).build()
    }
}

