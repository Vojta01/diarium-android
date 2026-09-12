package cz.digitalnivedomi.diarium.core.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The two anchoring rules of the "Přehled" screen, filed as a defect on 2026-09-12:
 * *"denní reflexe se zde opět nezobrazuje, chci tam zatím tu včerejší z 11. 9. —
 * stejně tak seznam používaných aplikací."*
 *
 * The screen keyed both cards off the newest *logged* day, which on that morning was
 * a 12. 9. row holding nothing but the phone's first sync: eleven minutes of screen
 * time, two apps, no reflection. So the card with yesterday's complete list and
 * yesterday's text was replaced by a near-empty one — "málo dat" where the owner
 * expected his apps.
 *
 * Two rules fix it, both derived here:
 *
 * 1. The reflection card speaks about the newest **recorded** day (mood filled, see
 *    [isRecordedDay]) — a day the phone merely synced is not a record. When that day
 *    has no text yet, the newest reflection there is stays on screen with its own
 *    date instead of blanking the card.
 * 2. The apps card shows the newest day whose list is long enough to rank
 *    ([DashboardRepository.RANKED_TOP_APPS_MIN]) and always prints that day, so an
 *    older list can never pass for today's.
 *
 * [DashboardRepository.derive] is a pure function of the loaded rows, so these tests
 * need no transport, session or clock.
 */
class DashboardAnchoringTest {

    private val today = "2026-09-12"

    private fun derive(vararg rows: Pair<String, DiaryEntry>) = DashboardRepository.derive(
        today,
        rows.map { DatedEntry(it.first, it.second) },
    )

    private fun apps(vararg pairs: Pair<String, Int>) = pairs.map {
        PhoneTopApp(app = it.first, minutes = it.second)
    }

    // ── The reflection card: the newest *recorded* day ─────────────────────────

    @Test
    fun `a synced day without a mood is not the day the reflection card speaks about`() {
        // 12. 9. carries the morning sync only; 11. 9. is the last day the owner
        // actually recorded.
        val data = derive(
            "2026-09-12" to DiaryEntry(phoneScreenTime = 660, phoneUnlocks = 9),
            "2026-09-11" to DiaryEntry(mood = 3, aiReflection = "Včerejší text."),
        )

        assertEquals("2026-09-11", data.newestRecorded?.date)
        assertEquals("Včerejší text.", data.newestRecorded?.reflection)
    }

    @Test
    fun `no recorded day leaves the anchor empty instead of pointing at a sync`() {
        val data = derive("2026-09-12" to DiaryEntry(phoneScreenTime = 660))

        assertNull(data.newestRecorded)
    }

    @Test
    fun `a recorded today is still the newest recorded day`() {
        val data = derive(
            "2026-09-12" to DiaryEntry(mood = 2, aiReflection = "Dnešní."),
            "2026-09-11" to DiaryEntry(mood = 3, aiReflection = "Včerejší."),
        )

        assertEquals("2026-09-12", data.newestRecorded?.date)
        assertEquals("2026-09-12", data.newestEntry?.date)
    }

    @Test
    fun `an older reflection stays on screen while the recorded day has none`() {
        // The card prints this text with its own date, so it is never blank while a
        // reflection exists anywhere.
        val data = derive(
            "2026-09-12" to DiaryEntry(mood = 3),
            "2026-09-11" to DiaryEntry(mood = 3, aiReflection = "Včerejší text."),
        )

        assertEquals("2026-09-12", data.newestRecorded?.date)
        assertNull(data.newestRecorded?.reflection)
        assertEquals("2026-09-11", data.reflection?.date)
        assertEquals("Včerejší text.", data.reflection?.text)
    }

    // ── The apps card: the newest *rankable* day ───────────────────────────────

    @Test
    fun `a morning sync never replaces a complete list from yesterday`() {
        val data = derive(
            "2026-09-12" to DiaryEntry(
                phoneScreenTime = 660,
                phoneTopApps = apps("Telegram" to 3, "Chrome" to 2),
            ),
            "2026-09-11" to DiaryEntry(
                mood = 3,
                phoneTopApps = apps(
                    "Instagram" to 42,
                    "Chrome" to 30,
                    "Telegram" to 21,
                    "Gmail" to 9,
                ),
            ),
        )

        // topApps keeps the raw rule — the newest day with any list at all.
        assertEquals("2026-09-12", data.topApps?.date)
        // rankedTopApps steps back to the newest day that can be ranked, and the card
        // prints that day, so the older list never passes for today's.
        assertEquals("2026-09-11", data.rankedTopApps?.date)
        assertEquals(4, data.rankedTopApps?.apps?.size)
    }

    @Test
    fun `with no rankable day the newest list is still kept as the fallback`() {
        val data = derive(
            "2026-09-12" to DiaryEntry(phoneTopApps = apps("Telegram" to 3, "Chrome" to 2)),
            "2026-09-11" to DiaryEntry(phoneTopApps = apps("Gmail" to 4)),
        )

        assertNull(data.rankedTopApps)
        assertEquals("2026-09-12", data.topApps?.date)
    }

    @Test
    fun `a day with exactly the minimum number of apps is rankable`() {
        val data = derive(
            "2026-09-11" to DiaryEntry(
                phoneTopApps = apps("Instagram" to 42, "Chrome" to 30, "Telegram" to 21),
            ),
        )

        assertEquals("2026-09-11", data.rankedTopApps?.date)
        assertEquals(
            DashboardRepository.RANKED_TOP_APPS_MIN,
            data.rankedTopApps?.apps?.size,
        )
    }

    @Test
    fun `an empty list is neither rankable nor the card's day`() {
        val data = derive("2026-09-11" to DiaryEntry(phoneTopApps = emptyList()))

        assertNull(data.rankedTopApps)
        assertNull(data.topApps)
    }
}
