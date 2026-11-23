package cz.digitalnivedomi.diarium.ui.stats

import androidx.activity.ComponentActivity

import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import cz.digitalnivedomi.diarium.core.stats.BucketCadence
import cz.digitalnivedomi.diarium.core.stats.DateRange
import cz.digitalnivedomi.diarium.core.stats.StatsDay
import cz.digitalnivedomi.diarium.ui.theme.DiariumTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import java.time.LocalDate

@RunWith(AndroidJUnit4::class)
@Config(sdk = [34], qualifiers = "w411dp-h891dp-420dpi")
class StatsScreenMoodActivityTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    @Test fun `global period ranges are exact inclusive complete days and custom preserves exact bounds`() {
        val today = LocalDate.parse("2026-11-16")
        val anchor = LocalDate.parse("2026-11-15")
        val week = selectedStatsDateRange(StatsRange.WEEK, 30, anchor, today, null, 2026)
        assertEquals(DateRange(LocalDate.parse("2026-11-09"), anchor), week)
        assertEquals(7, week!!.dayCount)
        val month = selectedStatsDateRange(StatsRange.MONTH, 30, anchor, today, null, 2026)
        assertEquals(30, month!!.dayCount)
        assertEquals(anchor, month!!.endInclusive)
        val quarter = selectedStatsDateRange(StatsRange.QUARTER, 30, anchor, today, null, 2026)
        assertEquals(90, quarter!!.dayCount)
        assertEquals(anchor, quarter.endInclusive)
        val custom = ValidatedStatsDateRange(LocalDate.parse("2026-10-02"), LocalDate.parse("2026-10-04"))
        assertEquals(DateRange(custom.startInclusive, custom.endInclusive),
            selectedStatsDateRange(StatsRange.CUSTOM, 30, anchor, today, custom, 2026)!!)
        val todayCustom = ValidatedStatsDateRange(LocalDate.parse("2026-11-15"), today)
        assertEquals(1,
            selectedStatsDateRange(StatsRange.CUSTOM, 30, anchor, today, todayCustom, 2026)!!.dayCount)
        assertEquals(2, selectedStatsDateRange(
            StatsRange.CUSTOM, 30, anchor, today, todayCustom, 2026, capCustomToMoodAnchor = false,
        )!!.dayCount)
        assertNull(selectedStatsDateRange(StatsRange.WEEK, 30, null, today, null, 2026))
        assertNull(selectedStatsDateRange(StatsRange.CUSTOM, 30, null, today, todayCustom, 2026))
        assertEquals(DateRange(LocalDate.parse("2026-11-15"), LocalDate.parse("2026-11-15")),
            selectedStatsDateRange(StatsRange.CUSTOM, 30, anchor, today, todayCustom, 2026)!!)
        assertTrue(week!!.endInclusive.isBefore(today))
    }

    @Test fun `custom digital range with no complete day is empty and never invents an interval`() {
        val today = LocalDate.parse("2026-11-16")
        val todayOnly = ValidatedStatsDateRange(today, today)
        assertNull(completedDigitalStatsDateRange(StatsRange.CUSTOM, 30, today, todayOnly, 2026))
        assertNull(completedDigitalStatsDateRange(
            StatsRange.CUSTOM, 30, today, ValidatedStatsDateRange(today.plusDays(1), today.plusDays(2)), 2026,
        ))
    }

    @Test fun `custom digital bounds remain inclusive when completed dates exist`() {
        val today = LocalDate.parse("2026-11-16")
        val custom = ValidatedStatsDateRange(today.minusDays(2), today)
        assertEquals(DateRange(today.minusDays(2), today.minusDays(1)),
            completedDigitalStatsDateRange(StatsRange.CUSTOM, 30, today, custom, 2026))
    }

    @Test fun `digital empty range presents a truthful empty state`() {
        compose.setContent { DiariumTheme { DigitalCategory(emptyList(), null, today = "2026-11-16", selectedDate = null, onSelectDate = {}) } }
        compose.onNodeWithText("Digitální data zatím nedostupná").assertExists()
        compose.onNodeWithText("V tomto vlastním období není žádný dokončený den pro digitální statistiky.").assertExists()
    }

    @Test fun `mood without an answered-day anchor presents an explicit empty state`() {
        compose.setContent { DiariumTheme { MoodRangeUnavailable() } }
        compose.onNodeWithText("Rozsah nálady nedostupný").assertExists()
        compose.onNodeWithText("Zatím není žádný zaznamenaný check-in s odpovědí na náladu. Zobrazí se zde, až bude takový záznam.").assertExists()
    }

    @Test fun `mood custom comparison follows the anchor-capped inclusive dates`() {
        val anchor = LocalDate.parse("2026-11-10")
        val today = LocalDate.parse("2026-11-16")
        val applied = ValidatedStatsDateRange(LocalDate.parse("2026-11-08"), today)
        val displayed = selectedStatsDateRange(StatsRange.CUSTOM, 30, anchor, today, applied, 2026)!!
        val current = cz.digitalnivedomi.diarium.core.stats.StatsDateRange.between(displayed.start, displayed.endInclusive)
        val previous = current.previous()

        assertEquals(DateRange(LocalDate.parse("2026-11-08"), anchor), displayed)
        assertEquals(3, current.dayCount)
        assertEquals(LocalDate.parse("2026-11-05"), previous.startInclusive)
        assertEquals(LocalDate.parse("2026-11-07"), previous.endInclusive)
        assertEquals(current.dayCount, previous.dayCount)
    }

    @Test fun `exact 7-day range is inclusive daily and selected detail maps to actual check-in`() {
        val start = LocalDate.parse("2026-02-01")
        val days = listOf(
            StatsDay("2026-01-31", mood = 5),
            StatsDay("2026-02-01", mood = 2),
            StatsDay("2026-02-04", mood = 4),
            StatsDay("2026-02-07", mood = 1),
            StatsDay("2026-02-08", mood = 5),
        )
        val result = buildMoodTrendPresentation(days, DateRange(start, start.plusDays(6)))
        assertEquals(BucketCadence.DAILY, result.cadence)
        assertEquals(7, result.slots.size)
        assertEquals("2026-02-04", result.slots[3].key)
        assertTrue(result.slots[3].moodText.isNotBlank())
        assertTrue(result.slots[3].detailRows.any { it.first == "Datum" })
        assertFalse(result.slots[3].detailRows.any { it.first.contains("průměr", ignoreCase = true) })

        var opened = ""
        compose.setContent {
            DiariumTheme {
                MoodTrendCard(days, axisStart = start, axisEnd = start.plusDays(6), onOpenDay = { opened = it })
            }
        }
        compose.onAllNodesWithTag("stats_trend_bar")[3].performClick()
        compose.onNodeWithText("Otevřít celý den ›").assertExists().performClick()
        compose.runOnIdle { assertEquals("2026-02-04", opened) }
        compose.onNodeWithText("7denní průměr").assertDoesNotExist()
    }

    @Test fun `long windows aggregate calendar weeks and months with actual range and sample counts`() {
        val start = LocalDate.parse("2026-01-10")
        val weekly = buildMoodTrendPresentation(
            listOf(StatsDay("2026-01-11", mood = 2), StatsDay("2026-01-12", mood = 4), StatsDay("2026-02-01", mood = 5)),
            DateRange(start, start.plusDays(89)),
        )
        assertEquals(BucketCadence.WEEKLY, weekly.cadence)
        assertTrue(weekly.slots.first().detailRows.any { it.second == "2026-01-10 – 2026-01-11" })
        assertTrue(weekly.slots.first().detailRows.any { it.first == "Platné odpovědi" && it.second == "1 / 2" })
        assertEquals("2026-01-11", weekly.slots.first().key)

        val monthly = buildMoodTrendPresentation(
            listOf(StatsDay("2026-01-31", mood = 3), StatsDay("2026-02-01", mood = 5)),
            DateRange(start, start.plusDays(119)),
        )
        assertEquals(BucketCadence.MONTHLY, monthly.cadence)
        assertTrue(monthly.slots.first().detailRows.any { it.second.startsWith("2026-01-10 – 2026-01-31") })
        assertTrue(monthly.slots.first().detailRows.any { it.second == "1 / 22" })
    }

    @Test fun `rare activity shows insufficient n rather than a good mood score`() {
        val days = listOf(
            StatsDay("2026-02-01", mood = 5, activities = listOf("running")),
            StatsDay("2026-02-02", mood = 2),
        )
        compose.setContent { DiariumTheme { ActivityCard(days) } }
        compose.onNodeWithText("running").assertExists()
        compose.onNodeWithText("Nedostatek dat · s aktivitou n=1, bez aktivity n=1; potřeba alespoň 5 v každé skupině.").assertExists()
        compose.onNodeWithText("5.0 / 5").assertDoesNotExist()
        compose.onNodeWithText("nejde o příčinu ani účinek", substring = true).assertExists()
    }
}
