package cz.digitalnivedomi.diarium.ui.checkin.components

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Pure JVM coverage for the discrete slider maths behind the scales section:
 * the step/value mapping between a slider position and a whole 1..N value
 * (including clamping and the unset state) and the range-hint wording for
 * different min/max configurations.
 *
 * No Robolectric, no Compose — these are the pure helpers the composables call.
 */
class ScaleSliderLogicTest {

    // ── sliderSteps: only whole numbers are reachable ────────────────────────

    @Test
    fun `steps leave one slot per whole number between the ends`() {
        assertEquals(3, sliderSteps(1, 5)) // 1,2,3,4,5
        assertEquals(0, sliderSteps(1, 2)) // 1,2
        assertEquals(8, sliderSteps(0, 9)) // 0..9
    }

    @Test
    fun `steps never go negative for a degenerate range`() {
        assertEquals(0, sliderSteps(5, 5))
        assertEquals(0, sliderSteps(5, 4))
    }

    // ── snapScaleValue: slider position -> discrete value ────────────────────

    @Test
    fun `slider position snaps to the nearest whole value`() {
        assertEquals(3, snapScaleValue(3.0f, 1, 5))
        assertEquals(3, snapScaleValue(3.4f, 1, 5))
        assertEquals(4, snapScaleValue(3.5f, 1, 5))
        assertEquals(2, snapScaleValue(2.4f, 1, 5))
    }

    @Test
    fun `slider position clamps to the scale bounds`() {
        assertEquals(1, snapScaleValue(-4f, 1, 5))
        assertEquals(1, snapScaleValue(0.4f, 1, 5))
        assertEquals(5, snapScaleValue(99f, 1, 5))
        assertEquals(10, snapScaleValue(42f, 0, 10))
        assertEquals(0, snapScaleValue(-1f, 0, 10))
    }

    @Test
    fun `a two-slot range still resolves to its ends`() {
        assertEquals(1, snapScaleValue(1.2f, 1, 2))
        assertEquals(2, snapScaleValue(1.8f, 1, 2))
    }

    @Test
    fun `a degenerate range falls back to its minimum`() {
        assertEquals(5, snapScaleValue(7.5f, 5, 5))
        assertEquals(5, snapScaleValue(7.5f, 5, 4))
    }

    // ── sliderPosition / scaleSliderValue: the unset state ───────────────────

    @Test
    fun `an unset scale parks the thumb at its minimum`() {
        assertEquals(1f, sliderPosition(null, 1), 0.0001f)
        assertEquals(0f, sliderPosition(null, 0), 0.0001f)
        assertEquals(3f, sliderPosition(3, 1), 0.0001f)
    }

    @Test
    fun `an unset value reads as a dash and a set one as value over max`() {
        assertEquals("—", scaleSliderValue(null, 5))
        assertEquals("3 / 5", scaleSliderValue(3, 5))
        assertEquals("7 / 10", scaleSliderValue(7, 10))
    }

    // ── scaleRangeHint: wording for different min/max configurations ─────────

    @Test
    fun `range hint states which end is best for the configured bounds`() {
        assertEquals("1 = nejhorší · 5 = nejlepší", scaleRangeHint(1, 5))
        assertEquals("0 = nejhorší · 10 = nejlepší", scaleRangeHint(0, 10))
        assertEquals("1 = nejhorší · 2 = nejlepší", scaleRangeHint(1, 2))
    }

    @Test
    fun `range hint can be inverted when the maximum is the worst end`() {
        assertEquals("1 = nejlepší · 5 = nejhorší", scaleRangeHint(1, 5, higherIsBetter = false))
        assertEquals("0 = nejlepší · 10 = nejhorší", scaleRangeHint(0, 10, higherIsBetter = false))
    }
}
