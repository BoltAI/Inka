package co.podzim.inka.brain

import org.junit.Assert.assertEquals
import org.junit.Test

class StreamingDrawToolParserTest {
    @Test
    fun `emits completed path values once`() {
        val parser = StreamingDrawToolParser()

        assertEquals(emptyList<String>(), parser.append("""{"paths":[{"d":"M 0"""))
        assertEquals(listOf("M 0 0 L 1 1"), parser.append(""" 0 L 1 1"}"""))
        assertEquals(listOf("M 2 2 L 3 3"), parser.append(""",{"d":"M 2 2 L 3 3"}"""))
        assertEquals(emptyList<String>(), parser.append("""],"page_text_transcript":"draw"}"""))
    }

    @Test
    fun `handles every split point in a valid payload`() {
        val payload = """{"paths":[{"d":"M 0 0 L 1 1"},{"d":"M 2 2 Q 3 3 4 4"}],"page_text_transcript":"draw"}"""
        val expected = listOf("M 0 0 L 1 1", "M 2 2 Q 3 3 4 4")

        for (split in 0..payload.length) {
            val parser = StreamingDrawToolParser()
            val emitted = parser.append(payload.substring(0, split)) + parser.append(payload.substring(split))

            assertEquals("split=$split", expected, emitted)
            assertEquals(emptyList<String>(), parser.append(""))
        }
    }

    @Test
    fun `ignores incomplete or unrelated strings`() {
        val parser = StreamingDrawToolParser()

        assertEquals(emptyList<String>(), parser.append("""{"note":"d should not emit","paths":[{"d":"M 1"""))
        assertEquals(emptyList<String>(), parser.append(" 1 L 2\\\""))
        assertEquals(emptyList<String>(), parser.append(""" still not done"""))
        assertEquals(listOf("""M 1 1 L 2" still not done"""), parser.append(""""}]}"""))
    }
}
