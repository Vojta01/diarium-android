package cz.digitalnivedomi.diarium.ui.stats

import cz.digitalnivedomi.diarium.core.stats.ScreenTimeDay
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

class ScreenTimeChartBucketsTest {
    @Test fun `inclusive global end is clipped to completed days only`() {
        assertEquals("2026-11-15", completedScreenTimeEnd("2026-11-09", "2026-11-16", "2026-11-16"))
        assertEquals("2026-11-12", completedScreenTimeEnd("2026-11-09", "2026-11-12", "2026-11-16"))
        assertNull(completedScreenTimeEnd("2026-11-17", "2026-11-16", "2026-11-16"))
        assertNull(completedScreenTimeEnd("bad", "2026-11-16", "2026-11-16"))
    }

    @Test fun `7 and 30 inclusive dates remain actual daily bars with distinct labels`() {
        listOf(7, 30).forEach { count ->
            val start = LocalDate.parse("2026-01-01")
            val days = (0 until count).map { offset ->
                val date = start.plusDays(offset.toLong()).toString()
                when (offset) {
                    0 -> ScreenTimeDay(date, seconds = 0, unlocks = 0, hasApps = false)
                    1 -> ScreenTimeDay(date, seconds = null, unlocks = null, hasApps = false)
                    else -> ScreenTimeDay(date, seconds = 60, unlocks = 1, hasApps = false)
                }
            }

            val buckets = screenTimeBuckets(days)
            assertEquals("$count dates should produce $count daily bars", count, buckets.size)
            assertEquals(days.map { it.date }, buckets.map { it.startDate })
            assertTrue(buckets.all { it.days.size == 1 && it.startDate == it.endDate && it.label.isNotBlank() })
            assertEquals(0, buckets.first().seconds)
            assertNull(buckets[1].seconds)
            assertEquals(days, buckets.flatMap { it.days })
        }
    }

    @Test fun `31 and 90 inclusive dates use calendar weeks with exact source days`() {
        listOf(31, 90).forEach { count ->
            val start = LocalDate.parse("2026-01-01")
            val days = (0 until count).map { offset ->
                val date = start.plusDays(offset.toLong()).toString()
                ScreenTimeDay(date, seconds = if (offset == 0) 0 else null, unlocks = if (offset == 0) 0 else null, hasApps = false)
            }
            val buckets = screenTimeBuckets(days)
            assertTrue("$count dates should be aggregated", buckets.size < count)
            assertEquals(days, buckets.flatMap { it.days })
            assertEquals(days.first().date, buckets.first().startDate)
            assertEquals(days.last().date, buckets.last().endDate)
            assertTrue(buckets.all { it.label.isNotBlank() })
            assertEquals(0, buckets.first().seconds)
            assertEquals(0, buckets.first().unlocks)
            assertNull(buckets.drop(1).firstOrNull()?.seconds)
        }
    }

    @Test fun `long ranges use calendar month buckets with exact source-day totals`() {
        val raw = (0..94).map { offset ->
            val date = LocalDate.parse("2026-01-20").plusDays(offset.toLong()).toString()
            ScreenTimeDay(date, seconds = if (offset == 0) 0 else null, unlocks = null, hasApps = false)
        }
        val buckets = screenTimeBuckets(raw)
        assertEquals(4, buckets.size)
        assertEquals(listOf("2026-01-20", "2026-02-01", "2026-03-01", "2026-04-01"), buckets.map { it.startDate })
        assertEquals(0, buckets.first().seconds)
        assertNull(buckets[1].seconds)
        assertEquals(raw, buckets.flatMap { it.days })
        assertTrue(buckets.all { it.label.isNotBlank() && it.endDate >= it.startDate })
    }
}
