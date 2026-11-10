package cz.digitalnivedomi.diarium.notifications

import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class NotificationSchedulerOwnerTest {

    @Test
    fun `owner validation fails closed for missing or switched accounts`() {
        assertTrue(NotificationScheduler.capturedOwnerMatches("account-a", "account-a"))
        assertFalse(NotificationScheduler.capturedOwnerMatches(null, "account-a"))
        assertFalse(NotificationScheduler.capturedOwnerMatches("", "account-a"))
        assertFalse(NotificationScheduler.capturedOwnerMatches("account-a", "account-b"))
        assertFalse(NotificationScheduler.capturedOwnerMatches("account-a", null))
    }

    @Test
    fun `scheduled report worker input captures its original owner`() {
        val input = NotificationScheduler.workerInputData(
            NotificationScheduler.TYPE_WEEKLY,
            "account-a",
        )

        assertEquals(NotificationScheduler.TYPE_WEEKLY, input.getString(NotificationScheduler.EXTRA_TYPE))
        assertEquals("account-a", input.getString(NotificationScheduler.EXTRA_OWNER_ID))
    }

    @Test
    fun `ownerless legacy work input remains ownerless and can fail closed`() {
        val input = NotificationScheduler.workerInputData(NotificationScheduler.TYPE_MONTHLY, null)

        assertEquals(NotificationScheduler.TYPE_MONTHLY, input.getString(NotificationScheduler.EXTRA_TYPE))
        assertNull(input.getString(NotificationScheduler.EXTRA_OWNER_ID))
    }
}
