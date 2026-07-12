package co.podzim.inka.ui

import android.app.Activity
import android.app.AlertDialog
import co.podzim.inka.brain.DiaryError

internal class MainAlertDialogs(
    private val activity: Activity,
) {
    fun confirmBurnNotebookFromHistory(onBurn: () -> Unit) {
        AlertDialog.Builder(activity)
            .setTitle("Burn this notebook?")
            .setMessage("This permanently deletes the saved notebook on this device.")
            .setPositiveButton("Burn") { _, _ -> onBurn() }
            .setNegativeButton("Cancel", null)
            .show()
    }

    fun showMissingApiKeyWarning(providerLabel: String) {
        showWarning(
            title = "$providerLabel API key required",
            message = "Add a $providerLabel API key in Settings > AI Settings before asking for a reply.",
        )
    }

    fun showSecureStorageWarning() {
        showWarning(
            title = "Secure storage unavailable",
            message = "Inka could not open Android encrypted storage, so your API key was not read or saved. Restart the app and try again.",
        )
    }

    fun showAiFailureWarning(error: DiaryError) {
        showWarning(
            title = error.banner ?: "AI request failed",
            message = error.diegeticLine,
        )
    }

    fun showReplyPaginationHelp() {
        showWarning(
            title = "Reading a longer reply",
            message = "Swipe left or right with a finger to move between pages. " +
                "Swipe left past the final reply page to open a blank page and keep writing.",
        )
    }

    fun showUnsupportedDeviceWarning(
        onContinue: () -> Unit,
        onQuit: () -> Unit,
    ): Boolean {
        if (activity.isFinishing || activity.isDestroyed) return false
        AlertDialog.Builder(activity)
            .setTitle("BOOX tablet required")
            .setMessage(
                "Inka only works properly on BOOX e-ink tablets. This device was not recognized as BOOX. " +
                    "If you continue, pen input and e-ink refresh may not work, and the app may crash.",
            )
            .setPositiveButton("Continue anyway") { _, _ -> onContinue() }
            .setNegativeButton("Quit") { _, _ -> onQuit() }
            .setCancelable(false)
            .show()
        return true
    }

    fun showWarning(title: String, message: String) {
        if (activity.isFinishing || activity.isDestroyed) return
        AlertDialog.Builder(activity)
            .setTitle(title)
            .setMessage(message)
            .setPositiveButton("OK", null)
            .show()
    }
}
