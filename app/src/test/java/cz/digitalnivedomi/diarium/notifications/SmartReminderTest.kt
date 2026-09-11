package cz.digitalnivedomi.diarium.notifications

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Pure decision-table tests for [SmartReminder.shouldNotify]. */
class SmartReminderTest {

    private val friday = 5

    private val base = NotificationPrefs(
        reminderEnabled = true,
        reminderDays = setOf(1, 2, 3, 4, 5, 6, 7),
        smartReminder = true,
        lastReminderDate = null,
    )

    @Test
    fun notifies_whenEnabledDayMatchesNoEntryNotYetReminded() {
        assertTrue(SmartReminder.shouldNotify(base, "2026-09-11", friday, hasSavedEntry = false))
    }

    @Test
    fun neverNotifies_whenReminderDisabled() {
        val prefs = base.copy(reminderEnabled = false)
        assertFalse(SmartReminder.shouldNotify(prefs, "2026-09-11", friday, hasSavedEntry = false))
    }

    @Test
    fun skips_whenDayNotSelected() {
        val prefs = base.copy(reminderDays = setOf(1, 2)) // weekdays only, not Friday
        assertFalse(SmartReminder.shouldNotify(prefs, "2026-09-11", friday, hasSavedEntry = false))
    }

    @Test
    fun skips_whenAlreadyRemindedToday() {
        val prefs = base.copy(lastReminderDate = "2026-09-11")
        assertFalse(SmartReminder.shouldNotify(prefs, "2026-09-11", friday, hasSavedEntry = false))
    }

    @Test
    fun notifies_whenLastReminderWasAnotherDay() {
        val prefs = base.copy(lastReminderDate = "2026-09-10")
        assertTrue(SmartReminder.shouldNotify(prefs, "2026-09-11", friday, hasSavedEntry = false))
    }

    @Test
    fun skips_whenSmartAndEntryAlreadySaved() {
        assertFalse(SmartReminder.shouldNotify(base, "2026-09-11", friday, hasSavedEntry = true))
    }

    @Test
    fun notifies_whenEntrySavedButSmartReminderOff() {
        val prefs = base.copy(smartReminder = false)
        assertTrue(SmartReminder.shouldNotify(prefs, "2026-09-11", friday, hasSavedEntry = true))
    }

    @Test
    fun offlineUnknown_isPassedAsNotSaved_andNotifies() {
        // Risk #10: an unknown answer must not silently drop the reminder.
        assertTrue(SmartReminder.shouldNotify(base, "2026-09-11", friday, hasSavedEntry = false))
    }
}
