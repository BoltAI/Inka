package co.podzim.inka.recognize

import com.google.mlkit.common.model.DownloadConditions
import com.google.mlkit.common.model.RemoteModelManager
import com.google.mlkit.vision.digitalink.recognition.DigitalInkRecognition
import com.google.mlkit.vision.digitalink.recognition.DigitalInkRecognitionModel
import com.google.mlkit.vision.digitalink.recognition.DigitalInkRecognitionModelIdentifier
import com.google.mlkit.vision.digitalink.recognition.DigitalInkRecognizerOptions
import com.google.mlkit.vision.digitalink.recognition.RecognitionContext
import com.google.mlkit.vision.digitalink.recognition.WritingArea
import co.podzim.inka.data.InkMessage
import kotlinx.coroutines.tasks.await

sealed class RecognitionOutcome {
    data class Text(val value: String) : RecognitionOutcome()
    data class Failure(val message: String) : RecognitionOutcome()
}

sealed class ModelDownloadOutcome {
    data object Ready : ModelDownloadOutcome()
    data class Failure(val message: String) : ModelDownloadOutcome()
}

sealed class RecognitionModelsOutcome {
    data class Ready(val languageTags: Set<String>) : RecognitionModelsOutcome()
    data class Failure(val message: String) : RecognitionModelsOutcome()
}

sealed class ModelDeleteOutcome {
    data object Deleted : ModelDeleteOutcome()
    data class Failure(val message: String) : ModelDeleteOutcome()
}

interface RecognitionService {
    suspend fun ensureModel(languageTag: String): ModelDownloadOutcome
    suspend fun downloadedModels(languageTags: List<String>): RecognitionModelsOutcome
    suspend fun deleteModel(languageTag: String): ModelDeleteOutcome
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

    override suspend fun downloadedModels(languageTags: List<String>): RecognitionModelsOutcome {
        val manager = RemoteModelManager.getInstance()
        return try {
            val downloadedModels = manager.getDownloadedModels(DigitalInkRecognitionModel::class.java).await()
            val downloadedTags = languageTags.mapNotNull { tag ->
                val model = modelFor(tag) ?: return@mapNotNull null
                if (downloadedModels.contains(model)) tag else null
            }.toSet()
            RecognitionModelsOutcome.Ready(downloadedTags)
        } catch (e: Exception) {
            RecognitionModelsOutcome.Failure(e.message ?: "Model status unavailable")
        }
    }

    override suspend fun deleteModel(languageTag: String): ModelDeleteOutcome {
        val model = modelFor(languageTag)
            ?: return ModelDeleteOutcome.Failure("No ML Kit model for $languageTag")
        val manager = RemoteModelManager.getInstance()
        return try {
            manager.deleteDownloadedModel(model).await()
            ModelDeleteOutcome.Deleted
        } catch (e: Exception) {
            ModelDeleteOutcome.Failure(e.message ?: "Model delete failed")
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
