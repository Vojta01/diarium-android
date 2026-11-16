package cz.digitalnivedomi.diarium.ui.stats

import androidx.test.ext.junit.runners.AndroidJUnit4
import cz.digitalnivedomi.diarium.core.data.Goal
import cz.digitalnivedomi.diarium.core.data.GoalProgress
import cz.digitalnivedomi.diarium.core.data.PhoneTopApp
import cz.digitalnivedomi.diarium.core.data.StatsData
import cz.digitalnivedomi.diarium.core.stats.HabitOutcome
import cz.digitalnivedomi.diarium.core.stats.StatsDay
import org.junit.Assert.assertEquals
import java.time.LocalDate
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * [StatsStateHolder] — the load / loaded / failed flow the Statistiky screen renders
 * from, and the 7-days / 30-days / this-year switching.
 *
 * The distinction these cases protect is the one the screen cannot afford to lose:
 * "still loading", "loaded, and this window is empty" and "the read failed" are three
 * different things. A failed read must never be left holding a window the screen would
 * happily draw, and it must never be mistaken for "the user has no data yet".
 *
 * The range is local state: the read already holds [cz.digitalnivedomi.diarium.core.data.StatsRepository.LOAD_DAYS]
 * days, so switching the window is a slice of what is on screen and never a second
 * request.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class StatsStateTest {

    private fun data(
        today: String = "2026-09-10",
        days: List<StatsDay> = emptyList(),
    ) = StatsData(today = today, days = days)

    private fun day(date: String, mood: Int = 0) = StatsDay(date = date, mood = mood)

    @Test
    fun `global windows anchor at latest answered mood and ignore screen time only and future rows`() {
        val loaded = data(today = "2026-09-10", days = listOf(
            day("2026-09-01", 4),
            StatsDay(date = "2026-09-08", mood = 0, screenTimeSeconds = 120),
            day("2026-09-09", 3),
            day("2026-09-10", mood = 0).copy(screenTimeSeconds = 200),
            day("2026-09-11", 5),
        ))
        assertEquals(LocalDate.parse("2026-09-09"), loaded.moodAnchorDate())
        val week = loaded.forRange(StatsRange.WEEK)
        assertEquals(listOf("2026-09-08", "2026-09-09"), week.map { it.date })
    }

    @Test
    fun `global mood range has no anchor when there are no answered moods`() {
        val loaded = data(days = listOf(StatsDay(date = "2026-09-10", mood = 0, screenTimeSeconds = 90)))
        assertNull(loaded.moodAnchorDate())
        assertTrue(loaded.forRange(StatsRange.WEEK).isEmpty())
    }

    @Test
    fun `global range includes exactly seven days ending at latest mood`() {
        val loaded = data(today = "2026-09-10", days = (1..10).map { day("2026-09-%02d".format(it), 5) })
        val week = loaded.forRange(StatsRange.WEEK)
        assertEquals(7, week.size)
        assertEquals("2026-09-04", week.first().date)
        assertEquals("2026-09-10", week.last().date)
    }

    @Test
    fun `a fresh holder is loading with nothing on screen`() {
        val holder = StatsStateHolder()

        assertTrue(holder.state.loading)
        assertNull(holder.state.data)
        assertNull(holder.state.errorMessage)
        // The mood statistics default to the web's 30-day window.
        assertEquals(StatsRange.MONTH, holder.state.range)
    }

    @Test
    fun `a loaded window stops the spinner and keeps the days`() {
        val holder = StatsStateHolder()
        val loaded = data(days = listOf(day("2026-09-10", mood = 5)))

        holder.show(loaded)

        assertFalse(holder.state.loading)
        assertEquals(loaded, holder.state.data)
        assertNull(holder.state.errorMessage)
        // The day survives the round-trip through the holder.
        assertEquals(5, holder.state.data?.days?.single()?.mood)
    }

    @Test
    fun `an empty window is loaded, not loading and not an error`() {
        val holder = StatsStateHolder()

        holder.show(data(days = emptyList()))

        assertFalse(holder.state.loading)
        assertNull(holder.state.errorMessage)
        assertNotNull(holder.state.data)
        assertTrue(holder.state.data?.days?.isEmpty() == true)
    }

    @Test
    fun `a failure without prior data shows message and keeps data absent`() {
        val holder = StatsStateHolder()

        holder.showError("Nepodařilo se připojit k serveru.")

        assertFalse(holder.state.loading)
        assertEquals("Nepodařilo se připojit k serveru.", holder.state.errorMessage)
        assertNull(holder.state.data)
    }

    @Test
    fun `a live refresh failure keeps cached data visible and reports stale error`() {
        val holder = StatsStateHolder()
        val cached = data(days = listOf(day("2026-09-10", mood = 5)))
        holder.show(cached)

        holder.markLoading()
        // The screen replays its owner-bound cache before issuing the refresh.
        holder.show(cached)
        holder.showError("Nepodařilo se připojit k serveru.")

        assertFalse(holder.state.loading)
        assertEquals(cached, holder.state.data)
        assertEquals("Nepodařilo se připojit k serveru.", holder.state.errorMessage)
    }

    @Test
    fun `an error is never rendered as an empty window`() {
        // The two states are deliberately different: null data + a message is a
        // failure, empty days + no message is "this window has nothing in it".
        val failed = StatsStateHolder()
        failed.markLoading()
        failed.showError("Načtení statistik se nezdařilo.")

        assertNull(failed.state.data)
        assertNotNull(failed.state.errorMessage)

        val empty = StatsStateHolder()
        empty.show(data(days = emptyList()))

        assertNotNull(empty.state.data)
        assertNull(empty.state.errorMessage)
    }

    @Test
    fun `a new attempt clears the previous error and shows the spinner`() {
        val holder = StatsStateHolder()
        holder.showError("Načtení statistik se nezdařilo.")

        holder.markLoading()

        assertTrue(holder.state.loading)
        assertNull(holder.state.errorMessage)
    }

    @Test
    fun `a success after a failure replaces the message with the window`() {
        val holder = StatsStateHolder()
        holder.showError("Nepodařilo se připojit k serveru.")

        holder.show(data(today = "2026-09-10", days = listOf(day("2026-09-10", mood = 3))))

        assertFalse(holder.state.loading)
        assertNull(holder.state.errorMessage)
        assertEquals("2026-09-10", holder.state.data?.today)
        assertEquals(1, holder.state.data?.days?.size)
    }

    @Test
    fun `switching the range is local and keeps the loaded window`() {
        val holder = StatsStateHolder()
        val loaded = data(days = listOf(day("2026-09-10", mood = 5)))
        holder.show(loaded)

        holder.selectRange(StatsRange.WEEK)
        assertEquals(StatsRange.WEEK, holder.state.range)
        assertEquals(loaded, holder.state.data)   // no second read, no spinner

        holder.selectRange(StatsRange.YEAR)
        assertEquals(StatsRange.YEAR, holder.state.range)
        assertEquals(loaded, holder.state.data)

        holder.selectRange(StatsRange.MONTH)
        assertEquals(StatsRange.MONTH, holder.state.range)
        assertEquals(loaded, holder.state.data)

        assertFalse(holder.state.loading)
        assertNull(holder.state.errorMessage)
    }

    @Test
    fun `the seven day range is the last seven calendar days`() {
        val days = (1..10).map { day("2026-09-%02d".format(it), mood = 5) }
        val loaded = data(today = "2026-09-10", days = days)

        val week = loaded.forRange(StatsRange.WEEK)

        assertEquals(7, week.size)
        // 2026-09-10 − 6 days = 2026-09-04, and 09-01..09-03 fall outside.
        assertEquals("2026-09-04", week.first().date)
        assertEquals("2026-09-10", week.last().date)
    }

    @Test
    fun `the thirty day range is the last thirty calendar days`() {
        val days = (1..30).map { day("2026-09-%02d".format(it), mood = 3) }
        val loaded = data(today = "2026-09-30", days = days)

        val month = loaded.forRange(StatsRange.MONTH)

        assertEquals(30, month.size)
        // 2026-09-30 − 29 days = 2026-09-01.
        assertEquals("2026-09-01", month.first().date)
        assertEquals("2026-09-30", month.last().date)

        // A day just outside the window is not drawn, even though it was read.
        val withOutside = data(today = "2026-09-30", days = days + day("2026-08-31", mood = 4))
        val sliced = withOutside.forRange(StatsRange.MONTH)
        assertEquals(30, sliced.size)
        assertFalse(sliced.any { it.date == "2026-08-31" })
    }

    @Test
    fun `the year range is the calendar year of today, not a day count`() {
        val days = listOf(
            day("2025-12-31", mood = 4),
            day("2026-01-01", mood = 2),
            day("2026-09-10", mood = 5),
        )
        val loaded = data(today = "2026-09-10", days = days)

        assertEquals(2026, loaded.year)

        val year = loaded.forRange(StatsRange.YEAR)

        assertEquals(listOf("2026-01-01", "2026-09-10"), year.map { it.date })
    }

    @Test
    fun `the range controls carry the web's Czech labels`() {
        assertEquals("7 dní", StatsRange.WEEK.label)
        assertEquals("30 dní", StatsRange.MONTH.label)
        assertEquals("Tento rok", StatsRange.YEAR.label)

        assertEquals(7, StatsRange.WEEK.daysCount)
        assertEquals(30, StatsRange.MONTH.daysCount)

        assertFalse(StatsRange.WEEK.year)
        assertFalse(StatsRange.MONTH.year)
        assertTrue(StatsRange.YEAR.year)
    }

    @Test
    fun `custom comparison uses actual inclusive dates and preceding equal length window`() {
        val custom = validateCustomDateRange("2025-10-04", "2025-10-09", "2025-11-15")!!
        val current = comparisonWindow(StatsRange.CUSTOM, custom, LocalDate.parse("2025-11-15"), 6)
        val previous = current.previous()

        assertEquals(LocalDate.parse("2025-10-04"), current.startInclusive)
        assertEquals(LocalDate.parse("2025-10-09"), current.endInclusive)
        assertEquals(6, current.dayCount)
        assertEquals(LocalDate.parse("2025-09-28"), previous.startInclusive)
        assertEquals(LocalDate.parse("2025-10-03"), previous.endInclusive)
        assertEquals(current.dayCount, previous.dayCount)
    }

    @Test
    fun `custom inclusive dates validate leap days and loaded boundaries`() {
        val leap = validateCustomDateRange("2024-02-29", "2024-03-01", "2024-03-01")
        assertEquals(2, leap?.dayCount)
        assertNull(validateCustomDateRange("2023-02-29", "2023-03-01", "2023-03-01"))
        assertNull(validateCustomDateRange("2025-01-01", "2024-12-31", "2025-01-01"))
        assertNull(validateCustomDateRange("2024-01-01", "2025-01-01", "2024-12-31"))
        assertNull(validateCustomDateRange("2023-11-26", "2023-11-27", "2024-12-30"))
        assertEquals(400, validateCustomDateRange("2023-11-27", "2024-12-30", "2024-12-30")?.dayCount)
    }

    @Test
    fun `custom range rejects overlong future and reversed selections`() {
        assertNull(validateCustomDateRange("2024-01-01", "2025-02-05", "2025-02-05"))
        assertNull(validateCustomDateRange("2025-02-06", "2025-02-05", "2025-02-05"))
        assertNull(validateCustomDateRange("2025-02-05", "2025-02-06", "2025-02-05"))
        assertEquals(1, validateCustomDateRange("2025-02-05", "2025-02-05", "2025-02-05")?.dayCount)
    }

    @Test
    fun `draft edits do not change applied range until valid apply`() {
        val holder = StatsStateHolder()
        val loaded = data(days = listOf(day("2026-09-04", 4), day("2026-09-05", 5), day("2026-09-10", 2)))
        holder.show(loaded)
        holder.restore(StatsRange.CUSTOM.name, StatsCategory.MOOD.name, 7, true, "2026-09-04", "2026-09-05")
        holder.editCustomRange("2026-09-10", "2026-09-10")

        assertEquals("2026-09-04", holder.state.appliedCustomStartDate)
        assertEquals("2026-09-05", holder.state.appliedCustomEndDate)
        assertEquals("2026-09-10", holder.state.customStartDate)
        assertEquals(listOf("2026-09-04", "2026-09-05"), loaded.days.filter {
            it.date >= holder.state.appliedCustomStartDate && it.date <= holder.state.appliedCustomEndDate
        }.map { it.date })

        assertTrue(holder.applyCustomRange())
        assertEquals("2026-09-10", holder.state.appliedCustomStartDate)
        assertEquals("2026-09-10", holder.state.appliedCustomEndDate)
    }

    @Test
    fun `invalid draft reports error while preserving applied dates and chart data`() {
        val holder = StatsStateHolder()
        val loaded = data(days = listOf(day("2026-09-04", 4), day("2026-09-05", 5)))
        holder.show(loaded)
        holder.restore(StatsRange.CUSTOM.name, StatsCategory.MOOD.name, 2, true, "2026-09-04", "2026-09-05")
        holder.editCustomRange("not-a-date", "2026-09-10")

        assertFalse(holder.applyCustomRange())
        assertEquals("2026-09-04", holder.state.appliedCustomStartDate)
        assertEquals("2026-09-05", holder.state.appliedCustomEndDate)
        assertEquals("Zadej platné datum v rozsahu načtené historie (max. 400 dní).", holder.state.customRangeError)
        assertEquals(loaded, holder.state.data)
        assertEquals(2, holder.state.data!!.days.count {
            it.date >= holder.state.appliedCustomStartDate && it.date <= holder.state.appliedCustomEndDate
        })
    }

    @Test
    fun `recorded explicit false is unmet but unproven historical default off stays unknown`() {
        val outcomes = habitPeriodOutcomes(
            listOf(
                StatsDay(date = "2026-09-08", mood = 4, habits = mapOf("h1" to false)),
                StatsDay(date = "2026-09-09", mood = 4),
                StatsDay(date = "2026-09-10", mood = 4),
            ),
            habitKey = "h1",
            activeToday = true,
            today = "2026-09-10",
        )

        assertEquals(listOf(HabitOutcome.UNMET, HabitOutcome.UNKNOWN, HabitOutcome.UNMET), outcomes)
    }

    @Test
    fun `explicit habit answer proves recorded check-in even when mood is unanswered`() {
        val outcomes = habitPeriodOutcomes(
            listOf(
                StatsDay(date = "2026-09-08", mood = 0, habits = mapOf("h1" to false)),
                StatsDay(date = "2026-09-09", mood = 0, habits = mapOf("h1" to true)),
                StatsDay(date = "2026-09-10", mood = 0, screenTimeSeconds = 120),
                StatsDay(date = "2026-09-07", mood = 0),
            ),
            habitKey = "h1",
            activeToday = true,
            today = "2026-09-10",
        )

        assertEquals(
            listOf(HabitOutcome.UNMET, HabitOutcome.MET, HabitOutcome.UNKNOWN, HabitOutcome.UNKNOWN),
            outcomes,
        )
    }

    @Test
    fun `digital ranges end on the last complete date and clamp applied bounds`() {
        val today = LocalDate.parse("2026-09-10")
        val week = digitalStatsDateRange(StatsRange.WEEK, 30, today, null, 2026)
        assertEquals(LocalDate.parse("2026-09-03"), week!!.start)
        assertEquals(LocalDate.parse("2026-09-09"), week.endInclusive)

        val custom = digitalStatsDateRange(
            StatsRange.CUSTOM,
            30,
            today,
            ValidatedStatsDateRange(LocalDate.parse("2026-09-08"), LocalDate.parse("2026-09-10")),
            2026,
        )
        assertEquals(LocalDate.parse("2026-09-08"), custom!!.start)
        assertEquals(LocalDate.parse("2026-09-09"), custom.endInclusive)
    }

    @Test
    fun `custom digital period containing only today has no current or comparison dates`() {
        val today = LocalDate.parse("2026-09-10")
        val appliedTodayOnly = ValidatedStatsDateRange(today, today)
        val displayed = digitalStatsDateRange(StatsRange.CUSTOM, 30, today, appliedTodayOnly, 2026)

        assertNull(displayed)
        // The explicit custom selection is not replaced with the customDays fallback.
        assertEquals(today, appliedTodayOnly.startInclusive)
        assertEquals(today, appliedTodayOnly.endInclusive)
    }

    @Test
    fun `digital period caption exactly reflects complete chart bounds and empty range`() {
        val today = LocalDate.parse("2026-09-10")
        val defaultWeek = digitalStatsDateRange(StatsRange.WEEK, 30, today, null, 2026)
        assertEquals(
            "Digitální období: 2026-09-03 – 2026-09-09 · 7 dní",
            digitalPeriodCaption(defaultWeek),
        )

        val todayOnly = digitalStatsDateRange(
            StatsRange.CUSTOM, 30, today, ValidatedStatsDateRange(today, today), 2026,
        )
        assertNull(todayOnly)
        assertEquals(
            "Digitální období nedostupné: zvolený rozsah neobsahuje žádný dokončený den.",
            digitalPeriodCaption(todayOnly),
        )
    }

    @Test
    fun `digital comparison follows displayed complete range despite stale mood anchor and today sync`() {
        val today = LocalDate.parse("2026-09-10")
        val days = listOf(
            day("2026-09-03", mood = 0).copy(screenTimeSeconds = 300),
            day("2026-09-04", mood = 4).copy(screenTimeSeconds = 400),
            day("2026-09-09", mood = 0).copy(screenTimeSeconds = 900),
            day("2026-09-10", mood = 0).copy(screenTimeSeconds = 1_000),
        )
        val staleMoodAnchor = LocalDate.parse("2026-09-04")
        assertEquals(staleMoodAnchor, data(today = today.toString(), days = days).moodAnchorDate())

        val displayed = digitalStatsDateRange(StatsRange.WEEK, 30, today, null, 2026)
        val current = digitalComparisonWindow(displayed!!)
        val prior = current.previous()
        val currentRows = days.filter { it.date >= current.startInclusive.toString() && it.date <= current.endInclusive.toString() }
        val priorRows = days.filter { it.date >= prior.startInclusive.toString() && it.date <= prior.endInclusive.toString() }

        assertEquals(LocalDate.parse("2026-09-03"), current.startInclusive)
        assertEquals(LocalDate.parse("2026-09-09"), current.endInclusive)
        assertEquals(7, current.dayCount)
        assertEquals(LocalDate.parse("2026-08-27"), prior.startInclusive)
        assertEquals(LocalDate.parse("2026-09-02"), prior.endInclusive)
        assertEquals(current.dayCount, prior.dayCount)
        assertEquals(listOf("2026-09-03", "2026-09-04", "2026-09-09"), currentRows.map { it.date })
        assertFalse(currentRows.any { it.date == today.toString() })
        assertTrue(priorRows.isEmpty())
    }

    @Test
    fun `custom digital comparison uses exact displayed dates not mood anchor`() {
        val today = LocalDate.parse("2026-09-15")
        val custom = ValidatedStatsDateRange(LocalDate.parse("2026-09-08"), LocalDate.parse("2026-09-15"))
        val days = (1..15).map { dayOfMonth ->
            day("2026-09-${dayOfMonth.toString().padStart(2, '0')}", mood = if (dayOfMonth == 2) 5 else 0)
                .copy(screenTimeSeconds = dayOfMonth * 60)
        }
        assertEquals(LocalDate.parse("2026-09-02"), data(today = today.toString(), days = days).moodAnchorDate())
        val displayed = digitalStatsDateRange(StatsRange.CUSTOM, 30, today, custom, 2026)
        val current = digitalComparisonWindow(displayed!!)
        val prior = current.previous()
        val currentRows = days.filter { it.date >= current.startInclusive.toString() && it.date <= current.endInclusive.toString() }
        val priorRows = days.filter { it.date >= prior.startInclusive.toString() && it.date <= prior.endInclusive.toString() }

        assertEquals(LocalDate.parse("2026-09-08"), current.startInclusive)
        assertEquals(LocalDate.parse("2026-09-14"), current.endInclusive)
        assertEquals(7, current.dayCount)
        assertEquals(LocalDate.parse("2026-09-01"), prior.startInclusive)
        assertEquals(LocalDate.parse("2026-09-07"), prior.endInclusive)
        assertEquals(7, prior.dayCount)
        assertEquals((8..14).map { "2026-09-${it.toString().padStart(2, '0')}" }, currentRows.map { it.date })
        assertFalse(currentRows.any { it.date == today.toString() })
        assertEquals((1..7).map { "2026-09-${it.toString().padStart(2, '0')}" }, priorRows.map { it.date })
    }

    @Test
    fun `top-app-only records make digital data available but an unsynced day is not zero`() {
        val appOnly = StatsDay(
            date = "2026-09-08",
            screenTimeSeconds = null,
            unlocks = null,
            topApps = listOf(PhoneTopApp("Reader", 12.5)),
        )
        val unsynced = StatsDay(date = "2026-09-09")
        val reportedZero = StatsDay(date = "2026-09-10", screenTimeSeconds = 0)

        assertTrue(hasDigitalData(listOf(appOnly)))
        assertFalse(hasDigitalData(listOf(unsynced)))
        assertTrue(hasDigitalData(listOf(reportedZero)))
        assertNull(appOnly.screenTimeSeconds)
    }

    @Test
    fun `digital selected date is cleared when applied bounds move`() {
        val start = LocalDate.parse("2026-09-04")
        val end = LocalDate.parse("2026-09-10")

        assertEquals("2026-09-08", retainSelectedDateWithinBounds("2026-09-08", start, end))
        assertNull(retainSelectedDateWithinBounds("2026-09-03", start, end))
        assertNull(retainSelectedDateWithinBounds("2026-09-11", start, end))
        assertNull(retainSelectedDateWithinBounds("not-a-date", start, end))
        assertNull(retainSelectedDateWithinBounds(null, start, end))
    }

    @Test
    fun `goal loading distinguishes initial, empty, and failed states`() {
        val initial = GoalStatsLoadState()
        assertTrue(initial.loading)
        assertNull(initial.goals)
        assertNull(initial.errorMessage)

        val empty = initial.success(emptyList())
        assertFalse(empty.loading)
        assertEquals(emptyList<cz.digitalnivedomi.diarium.core.data.GoalProgress>(), empty.goals)
        assertNull(empty.errorMessage)

        val failed = initial.failure("Offline")
        assertFalse(failed.loading)
        assertNull(failed.goals)
        assertEquals("Offline", failed.errorMessage)
    }

    @Test
    fun `goal refresh failure preserves previously loaded goals and retry clears error`() {
        val goal = GoalProgress(
            goal = Goal("goal-1", "exercise", "Exercise", 3, "weekly", true),
            count = 2,
            streak = 1,
        )
        val previous = GoalStatsLoadState().success(listOf(goal))
        val refreshing = previous.begin()
        assertTrue(refreshing.loading)
        assertEquals(listOf(goal), refreshing.goals)

        val failed = refreshing.failure("Offline")
        assertFalse(failed.loading)
        assertEquals(listOf(goal), failed.goals)
        assertEquals("Offline", failed.errorMessage)

        val retried = failed.begin()
        assertTrue(retried.loading)
        assertEquals(listOf(goal), retried.goals)
        assertNull(retried.errorMessage)
    }

    @Test
    fun `restoring applied state does not overwrite an in-progress draft on shell recomposition`() {
        val holder = StatsStateHolder()
        holder.restore(StatsRange.CUSTOM.name, StatsCategory.MOOD.name, 7, false, "2026-09-04", "2026-09-05")
        holder.editCustomRange("2026-09-08", "2026-09-10")
        holder.restore(StatsRange.CUSTOM.name, StatsCategory.MOOD.name, 7, false, "2026-09-04", "2026-09-05")

        assertEquals("2026-09-08", holder.state.customStartDate)
        assertEquals("2026-09-10", holder.state.customEndDate)
        assertEquals("2026-09-04", holder.state.appliedCustomStartDate)
        assertEquals("2026-09-05", holder.state.appliedCustomEndDate)
    }
}
