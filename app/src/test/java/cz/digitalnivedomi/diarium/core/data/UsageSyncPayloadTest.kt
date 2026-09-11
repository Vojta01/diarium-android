package cz.digitalnivedomi.diarium.core.data

import cz.digitalnivedomi.diarium.core.stats.DayAppUsage
import cz.digitalnivedomi.diarium.core.stats.DayUsage
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Payload contract for the phone-usage half of `save_daily_entry(jsonb)`.
 *
 * Two things are load-bearing here and neither is obvious from the Kotlin:
 *
 *  1. **The key set must stay at five.** The RPC upserts every column present in
 *     the payload and leaves absent ones alone (`col = case when (p_payload ?
 *     'col') then excluded.col else t.col end`). A build that accidentally
 *     carried `mood`/`note`/… would null out the user's check-in; the mirror
 *     guard lives in [EntriesRepositoryPayloadTest], which asserts the app's own
 *     form payload never carries `phone_*`.
 *  2. **`phone_screen_time`/`phone_unlocks` must be integer-looking.** The SQL
 *     matches them against `^-?[0-9]+$` and raises 22023 otherwise, so a Double
 *     (even `7200.0`) is rejected outright — which would have been a silent
 *     nightly failure. Hence the seconds-as-whole-number assertions.
 */
class UsageSyncPayloadTest {

    private companion object {
        const val USER_ID = "00000000-0000-0000-0000-000000000001"
        const val DATE = "2026-09-10"
    }

    private fun usage(
        totalSec: Long = 7200,
        unlocks: Long = 19,
        apps: List<DayAppUsage> = listOf(
            DayAppUsage("com.instagram.android", "Instagram", 2520), // 42 min
            DayAppUsage("org.telegram.messenger", "Telegram", 900), // 15 min
            DayAppUsage("com.example.blip", "Blip", 29), // under the threshold
        ),
    ) = DayUsage(totalSec = totalSec, unlocks = unlocks, apps = apps)

    private fun appsOf(payload: JSONObject) = payload.getJSONArray("phone_top_apps")

    // ------------------------------------------------------------- the contract

    @Test
    fun `the payload carries exactly the five phone keys`() {
        val payload = UsageSyncPayload.build(USER_ID, DATE, usage())

        assertEquals(
            setOf("user_id", "date", "phone_screen_time", "phone_unlocks", "phone_top_apps"),
            payload.keys().asSequence().toSet(),
        )
        assertEquals(USER_ID, payload.getString("user_id"))
        assertEquals(DATE, payload.getString("date"))
        assertEquals(7200, payload.getInt("phone_screen_time"))
        assertEquals(19, payload.getInt("phone_unlocks"))
    }

    @Test
    fun `no check-in column travels with a phone push, so the upsert cannot null one`() {
        val payload = UsageSyncPayload.build(USER_ID, DATE, usage())

        val checkInColumns = listOf(
            "mood", "mood_emoji", "sleep_quality", "stress", "activities",
            "habits", "gratitude", "note", "weather", "photo_path",
            "ai_reflection", "scale_values",
        )
        for (key in checkInColumns) {
            assertFalse("a phone push must not send '$key'", payload.has(key))
        }
    }

    // ------------------------------------------------------------------ numbers

    @Test
    fun `screen time and unlocks are whole numbers, not Doubles`() {
        val payload = UsageSyncPayload.build(USER_ID, DATE, usage(totalSec = 3600, unlocks = 7))

        val screenTime = payload.get("phone_screen_time")
        assertTrue("seconds, never a Double", screenTime is Long)
        assertEquals("3600", screenTime.toString())
        assertEquals("7", payload.get("phone_unlocks").toString())
    }

    // --------------------------------------------------------------- top apps

    @Test
    fun `apps under thirty seconds are left out of the chart`() {
        val payload = UsageSyncPayload.build(USER_ID, DATE, usage())
        val apps = appsOf(payload)

        assertEquals(2, apps.length())
        assertEquals("Instagram", apps.getJSONObject(0).getString("app"))
        assertEquals("Telegram", apps.getJSONObject(1).getString("app"))
    }

    @Test
    fun `an app of exactly thirty seconds is kept`() {
        val payload = UsageSyncPayload.build(
            USER_ID,
            DATE,
            usage(apps = listOf(DayAppUsage("com.example.edge", "Edge", 30))),
        )

        assertEquals(1, appsOf(payload).length())
    }

    @Test
    fun `at most fifteen apps travel, in the order the fold credited them`() {
        val many = (1..20).map { DayAppUsage("com.example.p$it", "P$it", 60L * it) }

        val apps = appsOf(UsageSyncPayload.build(USER_ID, DATE, usage(apps = many)))

        assertEquals(15, apps.length())
        assertEquals("P1", apps.getJSONObject(0).getString("app"))
        assertEquals("P15", apps.getJSONObject(14).getString("app"))
    }

    @Test
    fun `top apps carry the resolved label, not the package name`() {
        val payload = UsageSyncPayload.build(USER_ID, DATE, usage())

        assertEquals("Instagram", appsOf(payload).getJSONObject(0).getString("app"))
        assertFalse(payload.toString().contains("com.instagram.android"))
    }

    @Test
    fun `minutes stay fractional so the web can recover sub-minute apps`() {
        // src/lib/stats.ts normalises with Math.round(minutes * 60) — 1.5 → 90 s.
        // Rounding to whole minutes here would silently cost up to 59 s per app.
        val payload = UsageSyncPayload.build(
            USER_ID,
            DATE,
            usage(apps = listOf(DayAppUsage("com.example.app", "App", 90))),
        )

        assertEquals(1.5, appsOf(payload).getJSONObject(0).getDouble("minutes"), 1e-9)
        assertEquals(42.0, appsOf(UsageSyncPayload.build(USER_ID, DATE, usage()))
            .getJSONObject(0).getDouble("minutes"), 1e-9)
    }

    @Test
    fun `a day with no app over the threshold still sends an empty array, not a missing key`() {
        val payload = UsageSyncPayload.build(USER_ID, DATE, usage(totalSec = 120, unlocks = 2, apps = emptyList()))

        assertTrue(payload.has("phone_top_apps"))
        assertEquals(0, appsOf(payload).length())
        assertEquals(120, payload.getInt("phone_screen_time"))
        assertEquals(2, payload.getInt("phone_unlocks"))
    }

    @Test
    fun `the thresholds are parameters whose defaults match the legacy worker`() {
        assertEquals(30L, UsageSyncPayload.MIN_APP_SEC)
        assertEquals(15, UsageSyncPayload.MAX_APPS)

        val apps = listOf(DayAppUsage("com.example.small", "Small", 5))
        assertEquals(0, appsOf(UsageSyncPayload.build(USER_ID, DATE, usage(apps = apps))).length())
        assertEquals(1, appsOf(UsageSyncPayload.build(USER_ID, DATE, usage(apps = apps), minSec = 5)).length())
    }

    @Test
    fun `a zeroed day is still a well-formed payload`() {
        val payload = UsageSyncPayload.build(USER_ID, DATE, DayUsage(0, 0, emptyList()))

        assertEquals(0, payload.getInt("phone_screen_time"))
        assertEquals(0, payload.getInt("phone_unlocks"))
        assertEquals(0, appsOf(payload).length())
    }
}
