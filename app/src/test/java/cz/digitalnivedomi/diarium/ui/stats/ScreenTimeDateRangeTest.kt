package cz.digitalnivedomi.diarium.ui.stats

import cz.digitalnivedomi.diarium.core.stats.StatsDay
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ScreenTimeDateRangeTest {
    @Test fun `builds inclusive dates and preserves missing versus recorded zero`() {
        val result = ScreenTimeDateRange.days(
            listOf(StatsDay("2026-01-02", screenTimeSeconds = 0, unlocks = 0), StatsDay("2026-01-03", screenTimeSeconds = 90)),
            "2026-01-01", "2026-01-03",
        )
        assertEquals(listOf("2026-01-01", "2026-01-02", "2026-01-03"), result.map { it.date })
        assertNull(result[0].seconds)
        assertEquals(0, result[1].seconds)
        assertEquals(0, result[1].unlocks)
    }

    @Test fun `rejects reversed invalid and greater than 400 day ranges`() {
        assertTrue(ScreenTimeDateRange.days(emptyList(), "2026-02-02", "2026-02-01").isEmpty())
        assertTrue(ScreenTimeDateRange.days(emptyList(), "bad", "2026-02-01").isEmpty())
        assertTrue(ScreenTimeDateRange.days(emptyList(), "2025-01-01", "2026-02-05").isEmpty())
    }
}
