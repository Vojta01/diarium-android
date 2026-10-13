package cz.digitalnivedomi.diarium.ui.checkin.components

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Pure JVM coverage for the habit tick -> accent decision: a tick always means
 * fulfilled (owner decision, 2026-10-13), so ticking *any* habit — positive or
 * avoid ("negative") — reads as POSITIVE/green, and an unticked habit stays
 * NEUTRAL whatever its kind. There is no red accent any more. The colours
 * themselves live in the composable; this is the branch that picks them.
 */
class HabitAccentTest {

    @Test
    fun `a ticked positive habit reads positive`() {
        assertEquals(HabitAccent.POSITIVE, habitAccentState(isNegative = false, checked = true))
    }

    @Test
    fun `a ticked negative habit reads positive too`() {
        assertEquals(HabitAccent.POSITIVE, habitAccentState(isNegative = true, checked = true))
    }

    @Test
    fun `an unticked habit stays neutral whatever its kind`() {
        assertEquals(HabitAccent.NEUTRAL, habitAccentState(isNegative = false, checked = false))
        assertEquals(HabitAccent.NEUTRAL, habitAccentState(isNegative = true, checked = false))
    }
}
