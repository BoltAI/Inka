package com.inkwell.diary.ui

internal enum class CaptureInputMode {
    Disabled,
    ReadOnly,
    Writable,
}

internal fun captureInputMode(
    settingsPanelOpen: Boolean,
    busy: Boolean,
    historyOpen: Boolean,
    modalOverlayOpen: Boolean = false,
): CaptureInputMode {
    return when {
        modalOverlayOpen || settingsPanelOpen || busy -> CaptureInputMode.Disabled
        historyOpen -> CaptureInputMode.ReadOnly
        else -> CaptureInputMode.Writable
    }
}
