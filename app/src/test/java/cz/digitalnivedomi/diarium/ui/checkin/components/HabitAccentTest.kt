package cz.digitalnivedomi.diarium.ui.checkin.components

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Pure JVM coverage for the habit tick -> accent decision: ticking a positive
 * habit must read as positive (green), a negative "avoid" habit keeps its red
 * accent, and an unticked habit stays neutral. The colours themselves live in
 * the composable; this is the branch that picks them.
 */
class HabitAccentTest {

    @Test
    fun `a ticked positive habit reads positive`() {
        assertEquals(HabitAccent.POSITIVE, habitAccentState(isNegative = false, checked = true))
    }

    @Test
    fun `an unticked habit stays neutral whatever its kind`() {
        assertEquals(HabitAccent.NEUTRAL, habitAccentState(isNegative = false, checked = false))
        assertEquals(HabitAccent.NEUTRAL, habitAccentState(isNegative = true, checked = false))
    }

    @Test
    fun `a ticked negative habit keeps its red accent`() {
        assertEquals(HabitAccent.NEGATIVE, habitAccentState(isNegative = true, checked = true))
    }
}
