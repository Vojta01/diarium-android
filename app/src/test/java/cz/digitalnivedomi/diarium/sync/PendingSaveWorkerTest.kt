package cz.digitalnivedomi.diarium.sync

import androidx.work.Data
import androidx.work.ExistingWorkPolicy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PendingSaveWorkerTest {

    @Test
    fun `legacy ownerless work does not inherit whichever account is currently signed in`() {
        assertNull(PendingSaveWorker.ownerFromInput(Data.Builder().build()))
        assertEquals(
            "account-a",
            PendingSaveWorker.ownerFromInput(Data.Builder().putString("owner_id", "account-a").build()),
        )
    }

    @Test
    fun `enqueuing during an exiting work chain appends rather than drops the request`() {
        assertEquals(ExistingWorkPolicy.APPEND_OR_REPLACE, PendingSaveWorker.uniqueWorkPolicy)
    }

    @Test
    fun `each account has a separate unique work name`() {
        val accountA = PendingSaveWorker.workName("account-a")
        val accountB = PendingSaveWorker.workName("account-b")

        assertNotEquals(accountA, accountB)
        assertTrue(accountA.startsWith(PendingSaveWorker.WORK_NAME + "."))
        assertTrue(accountB.startsWith(PendingSaveWorker.WORK_NAME + "."))
    }
}
