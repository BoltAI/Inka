package co.podzim.inka.ui

internal fun shouldRenderAfterNotebookLoad(
    renderOnComplete: Boolean,
    rendererInitialized: Boolean,
    pageSurfaceInitialized: Boolean,
    settingsPanelOpen: Boolean,
    historyOpen: Boolean,
    strokeStoreEmpty: Boolean,
    busy: Boolean,
): Boolean {
    return renderOnComplete &&
        rendererInitialized &&
        pageSurfaceInitialized &&
        !settingsPanelOpen &&
        !historyOpen &&
        strokeStoreEmpty &&
        !busy
}
