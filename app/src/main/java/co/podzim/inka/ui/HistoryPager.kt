package co.podzim.inka.ui

import co.podzim.inka.data.NotebookPage

internal class HistoryPager {
    var isOpen: Boolean = false
        private set
    var pages: List<NotebookPage> = emptyList()
        private set
    var pageIndex: Int = 0
        private set

    val pageCount: Int
        get() = pages.size

    val currentPage: NotebookPage?
        get() = pages.getOrNull(pageIndex)

    val hasNextPage: Boolean
        get() = pageIndex < pages.lastIndex

    fun open(pages: List<NotebookPage>) {
        this.pages = pages
        pageIndex = pages.lastIndex
        isOpen = true
    }

    fun close() {
        isOpen = false
        pages = emptyList()
        pageIndex = 0
    }

    fun turn(delta: Int): Boolean {
        if (!isOpen) return false
        val next = (pageIndex + delta).coerceIn(0, pages.lastIndex)
        if (next == pageIndex) return false
        pageIndex = next
        return true
    }
}
