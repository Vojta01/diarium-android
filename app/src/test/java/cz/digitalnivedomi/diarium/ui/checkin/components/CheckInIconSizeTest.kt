package cz.digitalnivedomi.diarium.ui.checkin.components

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure-JVM guard for the owner's "the icons are too small / not clear at all"
 * complaint: the shared size tokens may grow, but the icon that anchors an
 * activity/habit chip must never drop back under 22sp, the mood emoji must stay
 * the largest element in the form, and the touch targets must stay at least
 * 44dp. No Compose, no Robolectric — plain unit assertions on the tokens.
 */
class CheckInIconSizeTest {

    @Test
    fun `an icon that anchors a labelled item is never below 22sp`() {
        assertTrue("chip icon must be in sp", CheckInIconSize.chip.isSp)
        assertEquals(22f, CheckInIconSize.chip.value, 0f)
        assertEquals(22f, CheckInIconSize.rowIcon.value, 0f)
    }

    @Test
    fun `a selected chip icon is bigger than a resting one`() {
        assertTrue(CheckInIconSize.chipSelected.value > CheckInIconSize.chip.value)
    }

    @Test
    fun `the mood emoji stays the largest element`() {
        assertTrue(CheckInIconSize.scale.value > CheckInIconSize.chipSelected.value)
        assertTrue(CheckInIconSize.scaleSelected.value > CheckInIconSize.scale.value)
    }

    @Test
    fun `chips and icon-only actions keep a comfortable touch target`() {
        assertTrue(CheckInIconSize.chipMinHeight.value >= 44f)
        assertTrue(CheckInIconSize.touchTarget.value >= 44f)
    }

    @Test
    fun `the clear glyph is bigger than the old 12sp and stays readable`() {
        assertEquals(14f, CheckInIconSize.clearGlyph.value, 0f)
        assertTrue(CheckInIconSize.actionGlyph.value >= 14f)
    }
}
