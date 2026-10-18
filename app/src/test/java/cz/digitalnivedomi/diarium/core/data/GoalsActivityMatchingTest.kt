package cz.digitalnivedomi.diarium.core.data

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Pins the label↔key bridge and the habit-fulfilment path added to the goals
 * maths: `entries.activities` stores display LABELS (`Chůze`) while goals store
 * slug KEYS (`chuze`), and a goal bound to a habit is fulfilled by
 * `entries.habits` key→`true` rather than by an `activities` value. Pure JVM:
 * no Android framework, no Robolectric, no network, no clock — `today` is a
 * fixed date.
 */
class GoalsActivityMatchingTest {

    private val today = "2026-10-13"

    private fun day(date: String, keys: List<String>, habitKeys: List<String> = emptyList()) =
        DayActivities(date, keys, habitKeys)

    /** The goal's count over the whole lookback window, mirroring `loadProgress`. */
    private fun count(days: List<DayActivities>, key: String) =
        countActivityInPeriod(days, key, dateMinusDays(today, 365), today)

    // ── label ↔ slug key ────────────────────────────────────────────────────

    @Test
    fun `a stored display label counts for the slug goal key`() {
        val days = listOf(day(today, listOf("Chůze")))
        assertEquals(1, count(days, "chuze"))
        assertEquals(1, count(days, "Chůze")) // a label against itself still matches
    }

    @Test
    fun `diacritics are stripped before comparing`() {
        val days = listOf(day(today, listOf("Trénink")))
        assertEquals(1, count(days, "trenink"))
        assertEquals(1, count(days, "Trénink"))
    }

    @Test
    fun `case differences are ignored`() {
        val days = listOf(day(today, listOf("CTENI")))
        assertEquals(1, count(days, "cteni"))
        assertEquals(1, count(days, "Cteni"))
    }

    @Test
    fun `normalizeActivityValue mirrors the pickers slug and is idempotent`() {
        assertEquals("chuze", normalizeActivityValue("Chůze"))
        assertEquals("trenink", normalizeActivityValue("Trénink"))
        assertEquals("cteni", normalizeActivityValue("CTENI"))
        assertEquals("jit_behat", normalizeActivityValue("Jít běhat!"))
        listOf("chuze", "trenink", "cteni", "beh").forEach { slug ->
            assertEquals(slug, normalizeActivityValue(slug))
            assertEquals(slug, normalizeActivityValue(normalizeActivityValue(slug)))
        }
    }

    @Test
    fun `countActivity normalises labels the same way`() {
        val perDay = listOf(listOf("Chůze"), listOf("chuze"), listOf("spanek"))
        assertEquals(2, countActivity(perDay, "chuze"))
    }

    // ── habits ──────────────────────────────────────────────────────────────

    @Test
    fun `a fulfilled habit counts toward a goal bound to it`() {
        val days = listOf(day(today, emptyList(), listOf("kourit")))
        assertEquals(1, count(days, "kourit"))
    }

    @Test
    fun `an unfulfilled habit does not count`() {
        val days = listOf(day(today, emptyList(), emptyList()))
        assertEquals(0, count(days, "kourit"))
    }

    @Test
    fun `daysFrom keeps true habits and drops false ones`() {
        val row = JSONObject(
            """{"date":"$today","activities":["Chůze"],"habits":{"kourit":true,"pit_vodu":false,"meditace":true}}""",
        )
        val days = daysFrom(listOf(row))
        assertEquals(setOf("kourit", "meditace"), days.single().habitKeys.toSet())
        assertEquals(1, count(days, "kourit"))
        assertEquals(0, count(days, "pit_vodu"))
        assertEquals(1, count(days, "chuze")) // the activities side still parses
    }

    @Test
    fun `a day matching both an activity and a habit counts once`() {
        val days = listOf(day(today, listOf("Chůze", "chuze"), listOf("chuze")))
        assertEquals(1, count(days, "chuze"))
    }

    @Test
    fun `habit days drive the streak and the period history`() {
        val days = listOf(
            day("2026-10-11", emptyList(), listOf("kourit")),
            day("2026-10-12", emptyList(), listOf("kourit")),
            day(today, emptyList(), listOf("kourit")),
        )
        val goal = Goal("g1", "kourit", "Kouření", 1, "daily", true)
        assertEquals(3, count(days, "kourit"))
        assertEquals(3, streakOf(activityDates(days, "kourit", dateMinusDays(today, 365), today)))
        assertEquals(true, computeHistory(days, goal, today).last().met)
    }
}
