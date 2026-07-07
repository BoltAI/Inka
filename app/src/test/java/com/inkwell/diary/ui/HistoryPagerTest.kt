package com.inkwell.diary.ui

import com.inkwell.diary.data.NotebookPage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class HistoryPagerTest {
    @Test
    fun `open starts on newest page`() {
        val pager = HistoryPager()
        val pages = listOf(NotebookPage(0), NotebookPage(1), NotebookPage(2))

        pager.open(pages)

        assertTrue(pager.isOpen)
        assertEquals(3, pager.pageCount)
        assertEquals(2, pager.pageIndex)
        assertEquals(NotebookPage(2), pager.currentPage)
        assertFalse(pager.hasNextPage)
    }

    @Test
    fun `turn clamps to available pages and reports changes`() {
        val pager = HistoryPager()
        pager.open(listOf(NotebookPage(0), NotebookPage(1), NotebookPage(2)))

        assertTrue(pager.turn(-1))
        assertEquals(1, pager.pageIndex)
        assertTrue(pager.hasNextPage)

        assertTrue(pager.turn(-100))
        assertEquals(0, pager.pageIndex)

        assertFalse(pager.turn(-1))
        assertEquals(0, pager.pageIndex)

        assertTrue(pager.turn(100))
        assertEquals(2, pager.pageIndex)

        assertFalse(pager.turn(1))
        assertEquals(2, pager.pageIndex)
    }

    @Test
    fun `close resets state`() {
        val pager = HistoryPager()
        pager.open(listOf(NotebookPage(0), NotebookPage(1)))

        pager.close()

        assertFalse(pager.isOpen)
        assertEquals(0, pager.pageCount)
        assertEquals(0, pager.pageIndex)
        assertNull(pager.currentPage)
        assertFalse(pager.hasNextPage)
    }

    @Test
    fun `turn does nothing while closed`() {
        val pager = HistoryPager()

        assertFalse(pager.turn(1))
    }
}
