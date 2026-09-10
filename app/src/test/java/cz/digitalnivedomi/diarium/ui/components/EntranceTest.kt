package cz.digitalnivedomi.diarium.ui.components

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The staggered entrance is pure arithmetic, so it is pinned here: the first card
 * starts immediately, each following card waits a little longer, and a long list
 * is capped instead of turning into a slow-motion reveal.
 */
class EntranceTest {

    @Test
    fun `the first card starts immediately`() {
        assertEquals(0, Entrance.delayMillisFor(0))
    }

    @Test
    fun `a negative index is treated as the first card`() {
        assertEquals(0, Entrance.delayMillisFor(-3))
    }

    @Test
    fun `each following card waits one step longer`() {
        assertEquals(Entrance.STEP_MILLIS, Entrance.delayMillisFor(1))
        assertEquals(Entrance.STEP_MILLIS * 2, Entrance.delayMillisFor(2))
        assertTrue(Entrance.delayMillisFor(4) > Entrance.delayMillisFor(3))
    }

    @Test
    fun `a long list is capped at the maximum delay`() {
        assertEquals(Entrance.MAX_MILLIS, Entrance.delayMillisFor(10))
        assertEquals(Entrance.MAX_MILLIS, Entrance.delayMillisFor(100))
    }
}
