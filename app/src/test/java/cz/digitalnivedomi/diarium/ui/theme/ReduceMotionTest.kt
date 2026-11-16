package cz.digitalnivedomi.diarium.ui.theme

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The reduced-motion decision is a pure function of the two platform signals, so
 * it can be pinned without an emulator: the system's animator switch, and the
 * `animator_duration_scale` fallback that some OEM builds update instead.
 */
class ReduceMotionTest {

    @Test
    fun `animators disabled means reduced motion`() {
        assertTrue(shouldReduceMotion(animatorsEnabled = false, durationScale = 1f))
    }

    @Test
    fun `a zero animator duration scale means reduced motion`() {
        assertTrue(shouldReduceMotion(animatorsEnabled = true, durationScale = 0f))
    }

    @Test
    fun `normal animations are not reduced`() {
        assertFalse(shouldReduceMotion(animatorsEnabled = true, durationScale = 1f))
        assertFalse(shouldReduceMotion(animatorsEnabled = null, durationScale = 1f))
    }
}
