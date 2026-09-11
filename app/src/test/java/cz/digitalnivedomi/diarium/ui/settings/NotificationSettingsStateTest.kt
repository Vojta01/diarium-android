package cz.digitalnivedomi.diarium.ui.settings

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The pure JVM logic behind Nastavení notifikací — no Compose, no Android, no
 * network. The labels asserted here are the exact Czech strings the old
 * `NotificationSettingsActivity` rendered, because prefs written by the alpha
 * builds have to keep working unchanged.
 */
class NotificationSettingsStateTest {

    // ── toggleDay ─────────────────────────────────────────────────────────────

    @Test
    fun `toggleDay adds a day that is missing`() {
        val result = NotificationSettingsState.toggleDay(setOf(1, 3), 5)
        assertEquals(setOf(1, 3, 5), result)
    }

    @Test
    fun `toggleDay removes a day that is present`() {
        val result = NotificationSettingsState.toggleDay(setOf(1, 3, 5), 3)
        assertEquals(setOf(1, 5), result)
    }

    @Test
    fun `toggleDay can empty the set and add into an empty set`() {
        val empty = NotificationSettingsState.toggleDay(setOf(2), 2)
        assertTrue(empty.isEmpty())
        assertEquals(setOf(7), NotificationSettingsState.toggleDay(emptyOf(), 7))
    }

    private fun emptyOf(): Set<Int> = emptySet()

    @Test
    fun `toggleDay keeps the set sorted-independent and does not mutate the input`() {
        val original = setOf(1, 2, 3)
        val result = NotificationSettingsState.toggleDay(original, 4)
        assertEquals(setOf(1, 2, 3), original)
        assertEquals(setOf(1, 2, 3, 4), result)
    }

    // ── timeLabel ─────────────────────────────────────────────────────────────

    @Test
    fun `timeLabel pads single digit hours and minutes`() {
        assertEquals("00:00", NotificationSettingsState.timeLabel(0))
        assertEquals("07:05", NotificationSettingsState.timeLabel(7 * 60 + 5))
        assertEquals("09:09", NotificationSettingsState.timeLabel(9 * 60 + 9))
    }

    @Test
    fun `timeLabel renders the defaults of the app`() {
        assertEquals("19:00", NotificationSettingsState.timeLabel(19 * 60))
        assertEquals("20:00", NotificationSettingsState.timeLabel(20 * 60))
        assertEquals("21:00", NotificationSettingsState.timeLabel(21 * 60))
    }

    @Test
    fun `timeLabel handles midnight and the last minute of the day`() {
        assertEquals("00:00", NotificationSettingsState.timeLabel(24 * 60))
        assertEquals("23:59", NotificationSettingsState.timeLabel(23 * 60 + 59))
    }

    @Test
    fun `timeLabel normalises out-of-range values instead of crashing`() {
        assertEquals("23:00", NotificationSettingsState.timeLabel(-60))
        assertEquals("01:00", NotificationSettingsState.timeLabel(25 * 60))
    }

    @Test
    fun `hour and minute parts feed the platform picker`() {
        assertEquals(19, NotificationSettingsState.hourOf(19 * 60 + 30))
        assertEquals(30, NotificationSettingsState.minuteOf(19 * 60 + 30))
        assertEquals(23, NotificationSettingsState.hourOf(-60))
    }

    // ── weekly day labels ─────────────────────────────────────────────────────

    @Test
    fun `weeklyDayLabel maps 1 to Monday and 7 to Sunday`() {
        assertEquals("Po", NotificationSettingsState.weeklyDayLabel(1))
        assertEquals("Ne", NotificationSettingsState.weeklyDayLabel(7))
    }

    @Test
    fun `weeklyDayLabel uses an explicit name list when given one`() {
        val full = listOf("A", "B", "C", "D", "E", "F", "G")
        assertEquals("C", NotificationSettingsState.weeklyDayLabel(3, full))
    }

    @Test
    fun `weeklyDayName returns the full Czech names of the old dialog`() {
        assertEquals("Pondělí", NotificationSettingsState.weeklyDayName(1))
        assertEquals("Neděle", NotificationSettingsState.weeklyDayName(7))
    }

    @Test
    fun `weeklyDayLabel never throws on an out-of-range day`() {
        assertEquals("?", NotificationSettingsState.weeklyDayLabel(0))
        assertEquals("?", NotificationSettingsState.weeklyDayLabel(8))
    }

    // ── daysSummary ───────────────────────────────────────────────────────────

    @Test
    fun `daysSummary says every day when all seven are selected`() {
        assertEquals("Každý den", NotificationSettingsState.daysSummary((1..7).toSet()))
    }

    @Test
    fun `daysSummary says no day when the set is empty`() {
        assertEquals("Žádný den", NotificationSettingsState.daysSummary(emptySet()))
    }

    @Test
    fun `daysSummary lists the selected days sorted by the week`() {
        assertEquals("Po St Pá", NotificationSettingsState.daysSummary(setOf(5, 1, 3)))
    }

    // ── channel + permission rows ─────────────────────────────────────────────

    @Test
    fun `channelLabel matches the channel names the worker creates`() {
        assertEquals("Diarium připomenutí", NotificationSettingsState.channelLabel(true))
        assertEquals("Diarium připomenutí (tichá)", NotificationSettingsState.channelLabel(false))
    }

    @Test
    fun `permission chips read as the Czech status words`() {
        assertEquals("povoleno", NotificationSettingsState.permissionChip(PermissionState.GRANTED))
        assertEquals("zamítnuto", NotificationSettingsState.permissionChip(PermissionState.DENIED))
        assertEquals("nevyřešeno", NotificationSettingsState.permissionChip(PermissionState.UNDECIDED))
    }

    @Test
    fun `usage access chips cover the errored state`() {
        assertEquals("povoleno", NotificationSettingsState.usageAccessChip(UsageAccessState.ALLOWED))
        assertEquals("zamítnuto", NotificationSettingsState.usageAccessChip(UsageAccessState.DENIED))
        assertEquals("chyba", NotificationSettingsState.usageAccessChip(UsageAccessState.ERRORED))
        assertEquals("nevyřešeno", NotificationSettingsState.usageAccessChip(UsageAccessState.UNDECIDED))
    }

    @Test
    fun `usage access hint tells the user about the Android 13 gotcha`() {
        val hint = NotificationSettingsState.usageAccessHint(UsageAccessState.UNDECIDED)
        assertTrue(hint.contains("Otevřít nastavení"))
        assertTrue(hint.contains("Povolit omezená nastavení"))
    }

    @Test
    fun `exact alarm chip is allowed or not`() {
        assertEquals("povoleno", NotificationSettingsState.exactAlarmChip(true))
        assertEquals("nepovoleno", NotificationSettingsState.exactAlarmChip(false))
        assertFalse(NotificationSettingsState.exactAlarmHint(true).isEmpty())
    }
}
