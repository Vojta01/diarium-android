package cz.digitalnivedomi.diarium.ui.theme

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

/**
 * The mood palette is shared by check-in, calendar and stats — a mood must look
 * the same everywhere, so the mapping is pinned by tests rather than by eye.
 */
class MoodColorTest {

    @Test
    fun `every step of the mood scale has its own colour`() {
        val colours = (1..5).map { moodColor(it) }
        assertEquals("5 distinct mood colours", 5, colours.toSet().size)
    }

    @Test
    fun `scale endpoints match the named palette`() {
        assertEquals(MoodAwful, moodColor(1))
        assertEquals(MoodBad, moodColor(2))
        assertEquals(MoodNeutral, moodColor(3))
        assertEquals(MoodGood, moodColor(4))
        assertEquals(MoodGreat, moodColor(5))
    }

    @Test
    fun `unknown or missing mood falls back to a neutral colour`() {
        assertEquals(TextTertiary, moodColor(null))
        assertEquals(TextTertiary, moodColor(0))
        assertEquals(TextTertiary, moodColor(6))
        assertNotEquals(MoodGreat, moodColor(null))
    }
}
