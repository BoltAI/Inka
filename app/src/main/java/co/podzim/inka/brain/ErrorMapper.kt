package co.podzim.inka.brain

enum class BrainErrorKind {
    Network,
    InvalidKey,
    Server,
    BadRequest,
    Unknown,
}

data class DiaryError(
    val diegeticLine: String,
    val banner: String? = null,
)

object ErrorMapper {
    fun from(kind: BrainErrorKind): DiaryError {
        return when (kind) {
            BrainErrorKind.Network -> DiaryError(
                diegeticLine = "No internet connection. Connect to Wi-Fi and try again.",
            )
            BrainErrorKind.InvalidKey -> DiaryError(
                diegeticLine = "The selected provider key did not work.",
                banner = "Check API key in Settings",
            )
            BrainErrorKind.Server -> DiaryError(
                diegeticLine = "The AI service is not responding. Try again in a moment.",
            )
            BrainErrorKind.BadRequest -> DiaryError(
                diegeticLine = "The AI request was rejected.",
                banner = "Check provider, model, and prompt in Settings",
            )
            BrainErrorKind.Unknown -> DiaryError(
                diegeticLine = "The AI request failed. Check settings and try again.",
            )
        }
    }
}
