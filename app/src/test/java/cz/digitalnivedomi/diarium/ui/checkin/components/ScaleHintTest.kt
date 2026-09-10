package cz.digitalnivedomi.diarium.ui.checkin.components

import cz.digitalnivedomi.diarium.core.data.Scale
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Pure JVM coverage for the scales range hint and the shared-range helper. */
class ScaleHintTest {

    @Test
    fun `hint names the worst and best ends`() {
        assertEquals("1 = nejhorší · 5 = nejlepší", scaleRangeHint(1, 5))
        assertEquals("0 = nejhorší · 10 = nejlepší", scaleRangeHint(0, 10))
    }

    @Test
    fun `shared range is reported only when every scale agrees`() {
        val uniform = listOf(
            Scale(id = "energie", name = "Energie", emoji = "⚡"),
            Scale(id = "produktivita", name = "Produktivita", emoji = "💪"),
        )
        assertEquals(1 to 5, sharedScaleRange(uniform))

        val mixed = listOf(
            Scale(id = "a", name = "A"),
            Scale(id = "b", name = "B", minValue = 0, maxValue = 10),
        )
        assertNull(sharedScaleRange(mixed))
        assertNull(sharedScaleRange(emptyList()))
    }
}
