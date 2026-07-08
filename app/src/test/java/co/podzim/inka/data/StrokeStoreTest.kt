package co.podzim.inka.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class StrokeStoreTest {
    @Test
    fun `round trips captured strokes into snapshot`() {
        val store = StrokeStore()
        store.beginStroke(InkPoint(1f, 2f, 0.5f, 10L))
        store.addPoint(InkPoint(3f, 4f, 0.6f, 20L))
        store.finishCurrent()
        store.beginStroke(InkPoint(5f, 6f, 0.7f, 30L))
        store.finishCurrent()

        val snapshot = store.snapshot(width = 100, height = 200)

        assertEquals(100, snapshot.width)
        assertEquals(200, snapshot.height)
        assertEquals(2, snapshot.strokes.size)
        assertEquals(listOf(1f, 3f), snapshot.strokes[0].points.map { it.x })
        assertEquals(5f, snapshot.strokes[1].points.single().x)
        assertFalse(store.isEmpty())
    }

    @Test
    fun `clear removes current and finished strokes`() {
        val store = StrokeStore()
        store.beginStroke(InkPoint(1f, 2f, 1f, 10L))
        store.addPoint(InkPoint(2f, 3f, 1f, 11L))

        store.clear()

        assertTrue(store.isEmpty())
        assertEquals(0, store.snapshot(10, 10).strokes.size)
    }
}

