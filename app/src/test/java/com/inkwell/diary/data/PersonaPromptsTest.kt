package com.inkwell.diary.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PersonaPromptsTest {
    @Test
    fun `persona picker order follows persona pack`() {
        assertEquals(
            listOf(
                Persona.Whisper,
                Persona.Confidant,
                Persona.Storyteller,
                Persona.Scholar,
                Persona.Muse,
                Persona.Socrates,
                Persona.Wit,
                Persona.Custom,
            ),
            Persona.entries,
        )
    }

    @Test
    fun `default persona is whisper`() {
        assertEquals(Persona.Whisper, Persona.default)
        assertEquals(Persona.Whisper, Persona.fromStoredName(null))
        assertEquals(Persona.Whisper, Persona.fromStoredName(""))
    }

    @Test
    fun `legacy stored persona names map to current personas`() {
        assertEquals(Persona.Confidant, Persona.fromStoredName("Diary"))
        assertEquals(Persona.Confidant, Persona.fromStoredName("Companion"))
        assertEquals(Persona.Socrates, Persona.fromStoredName("Socratic"))
        assertEquals(Persona.Whisper, Persona.fromStoredName("Missing"))
    }

    @Test
    fun `built in prompts prepend base rules`() {
        val prompt = PersonaPrompts.forPersona(Persona.Whisper, "")

        assertTrue(prompt.startsWith(PersonaPrompts.BASE_RULES))
        assertTrue(prompt.contains(PersonaPrompts.WHISPER))
    }

    @Test
    fun `custom prompt still keeps base rules first`() {
        val prompt = PersonaPrompts.forPersona(Persona.Custom, "Speak like a quiet lighthouse.")

        assertTrue(prompt.startsWith(PersonaPrompts.BASE_RULES))
        assertTrue(prompt.endsWith("Speak like a quiet lighthouse."))
    }
}
