package cz.digitalnivedomi.diarium.ui.checkin

import androidx.test.ext.junit.runners.AndroidJUnit4
import cz.digitalnivedomi.diarium.core.data.DiaryEntry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * The reflection part of [CheckInUiState]: what switching day clears, what a
 * loaded row seeds, and the flags the AI section renders from.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class ReflectionStateTest {

    private fun holderWithReflection(text: String): CheckInStateHolder =
        CheckInStateHolder().apply {
            markReflectionLoading()
            setReflection(text)
        }

    @Test
    fun `setReflection stores the text and re-arms the save`() {
        val holder = CheckInStateHolder()
        holder.markSaved()
        assertTrue(holder.state.saved)

        holder.markReflectionLoading()
        holder.setReflection("Dnes sis to užil.")

        assertEquals("Dnes sis to užil.", holder.state.reflection)
        assertNull(holder.state.reflectionError)
        assertFalse(holder.state.reflectionLoading)
        // The text is not in the database until the screen saves the entry again.
        assertFalse(holder.state.saved)
    }

    @Test
    fun `switching day clears the reflection and its status`() {
        val holder = holderWithReflection("Dnes sis to užil.")
        holder.markReflectionError("AI teď neodpovídá, zkuste to znovu.")

        holder.setDate("2026-09-09")

        assertNull(holder.state.reflection)
        assertNull(holder.state.reflectionError)
        assertFalse(holder.state.reflectionLoading)
    }

    @Test
    fun `loading an entry seeds the stored reflection`() {
        val holder = CheckInStateHolder()
        holder.setDate("2026-09-10")

        holder.load(DiaryEntry(mood = 4, aiReflection = "Včera dobrý, dnes lepší."))

        assertEquals("Včera dobrý, dnes lepší.", holder.state.reflection)
        assertNull(holder.state.reflectionError)
        assertFalse(holder.state.reflectionLoading)
    }

    @Test
    fun `an entry without a reflection leaves the section empty`() {
        val holder = CheckInStateHolder()
        holder.load(DiaryEntry(mood = 4))

        assertNull(holder.state.reflection)
        assertNull(holder.state.reflectionError)
        assertFalse(holder.state.reflectionLoading)
    }

    @Test
    fun `a failure keeps the text already on screen and stops the spinner`() {
        val holder = holderWithReflection("Dnes sis to užil.")
        holder.markReflectionLoading()

        holder.markReflectionError("Nepodařilo se připojit k serveru.")

        assertEquals("Dnes sis to užil.", holder.state.reflection)
        assertEquals("Nepodařilo se připojit k serveru.", holder.state.reflectionError)
        assertFalse(holder.state.reflectionLoading)
    }

    @Test
    fun `a new attempt clears the previous error`() {
        val holder = CheckInStateHolder()
        holder.markReflectionError("Nepodařilo se připojit k serveru.")

        holder.markReflectionLoading()

        assertNull(holder.state.reflectionError)
        assertTrue(holder.state.reflectionLoading)
    }

    @Test
    fun `a reflection alone does not make the day worth drafting`() {
        val holder = CheckInStateHolder()
        holder.load(DiaryEntry(aiReflection = "Dnes sis to užil."))

        // The reflection is the server's text, not user input — it must never make
        // an otherwise empty day count as content to save as a draft.
        assertFalse(holder.state.hasContent)
    }
}
