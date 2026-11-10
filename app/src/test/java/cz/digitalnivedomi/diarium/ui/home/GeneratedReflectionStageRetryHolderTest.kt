package cz.digitalnivedomi.diarium.ui.home

import cz.digitalnivedomi.diarium.core.data.DiaryEntry
import cz.digitalnivedomi.diarium.core.data.StoredEntrySnapshot
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GeneratedReflectionStageRetryHolderTest {
    @Test
    fun `pending text is scoped to exact owner and date and only matching retry can clear it`() {
        val holder = GeneratedReflectionStageRetryHolder()
        val retry = PendingGeneratedReflectionStage(
            ownerId = OWNER_A,
            date = DATE_A,
            snapshot = StoredEntrySnapshot(
                entry = DiaryEntry(mood = 4, note = "synthetic entry"),
                updatedAt = "2026-11-10T12:00:00Z",
                id = "entry-a",
                revision = 7,
            ),
            reflection = "Synthetic unsaved reflection",
        )

        holder.put(retry)

        assertEquals(retry, holder.get(OWNER_A, DATE_A))
        assertNull("another account must not read this text", holder.get(OWNER_B, DATE_A))
        assertNull("another day must not read this text", holder.get(OWNER_A, DATE_B))
        assertFalse("a stale/different retry cannot clear the current one", holder.clear(retry.copy(reflection = "other")))
        assertEquals(retry, holder.get(OWNER_A, DATE_A))
        assertTrue(holder.clear(retry))
        assertNull(holder.get(OWNER_A, DATE_A))
    }

    @Test
    fun `a fresh process holder does not restore text that failed durable staging`() {
        val firstProcess = GeneratedReflectionStageRetryHolder()
        val retry = PendingGeneratedReflectionStage(
            ownerId = OWNER_A,
            date = DATE_A,
            snapshot = StoredEntrySnapshot(DiaryEntry(note = "synthetic entry"), updatedAt = null),
            reflection = "Synthetic unsaved reflection",
        )
        firstProcess.put(retry)

        val afterProcessRestart = GeneratedReflectionStageRetryHolder()

        assertNull(afterProcessRestart.get(OWNER_A, DATE_A))
        assertEquals(retry, firstProcess.get(OWNER_A, DATE_A))
    }

    private companion object {
        const val OWNER_A = "owner-a"
        const val OWNER_B = "owner-b"
        const val DATE_A = "2026-11-10"
        const val DATE_B = "2026-11-11"
    }
}
