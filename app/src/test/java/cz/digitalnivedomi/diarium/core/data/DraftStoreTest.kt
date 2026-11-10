package cz.digitalnivedomi.diarium.core.data

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import java.io.File
import java.io.IOException
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Draft persistence, the native twin of the web's `diarium_draft_{date}`
 * localStorage keys: autosave per date, restore when the DB has nothing,
 * clear after a successful save.
 *
 * Gratitude is asserted with an empty slot in the middle on purpose — the web
 * keeps `data.gratitude[i]` by index, so a draft that compacts the array would
 * move the user's text into the wrong box when the app is reopened.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class DraftStoreTest {

    /**
     * A store plus the scope backing it. DataStore refuses to open one file
     * twice while the first instance is alive, so [close] has to be called
     * before a test reopens the same file — which is exactly what the
     * "survives a reopening" test means.
     */
    private class Handle(
        val store: DraftStore,
        val dataStore: androidx.datastore.core.DataStore<androidx.datastore.preferences.core.Preferences>,
        private val scope: CoroutineScope,
    ) {
        suspend fun close() {
            scope.coroutineContext[Job]?.cancelAndJoin()
        }
    }

    private fun newStore(file: File, ownerId: String = OWNER_A): Handle {
        val scope = CoroutineScope(Dispatchers.IO + Job())
        val dataStore = PreferenceDataStoreFactory.create(
            scope = scope,
            produceFile = { file },
        )
        return Handle(
            DraftStore(dataStore, ownerId),
            dataStore,
            scope,
        )
    }

    private fun tempFile(): File =
        File.createTempFile("diarium_draft_test", ".preferences_pb").also { it.delete() }

    @Test
    fun `save restore and clear round trip a draft`() = runBlocking {
        val handle = newStore(tempFile())
        val store = handle.store
        val date = "2026-09-10"
        val entry = DiaryEntry(
            mood = 4,
            moodEmoji = "🙂",
            sleepQuality = 3,
            stress = 2,
            activities = listOf("🏋️ Cvičení"),
            habits = mapOf("alkohol" to true),
            gratitude = listOf("Rodina", "", "Práce"),
            note = "rozepsaná poznámka",
            scaleValues = mapOf("energy" to 5),
        )

        // Nothing stored yet.
        assertNull(store.load(date))

        store.save(date, entry)
        val restored = store.load(date)

        assertNotNull(restored)
        assertEquals(entry, restored)
        assertEquals(4, restored?.mood)
        assertEquals("rozepsaná poznámka", restored?.note)
        // The empty slot stays in the middle: box 3 must not slide into box 2.
        assertEquals("Rodina", restored?.gratitude?.get(0))
        assertEquals("", restored?.gratitude?.get(1))
        assertEquals("Práce", restored?.gratitude?.get(2))

        // A successful save clears the draft.
        store.clear(date)
        assertNull(store.load(date))

        handle.close()
    }

    @Test
    fun `drafts are scoped per date`() = runBlocking {
        val handle = newStore(tempFile())
        val store = handle.store

        store.save("2026-09-10", DiaryEntry(mood = 5, moodEmoji = "😄"))
        store.save("2026-09-09", DiaryEntry(mood = 1, moodEmoji = "😡"))

        assertEquals(5, store.load("2026-09-10")?.mood)
        assertEquals(1, store.load("2026-09-09")?.mood)

        store.clear("2026-09-10")

        assertNull(store.load("2026-09-10"))
        assertNotNull(store.load("2026-09-09"))

        handle.close()
    }

    @Test
    fun `flush writes the pending draft without a suspending caller`() = runBlocking {
        val handle = newStore(tempFile())
        val store = handle.store
        val date = "2026-09-11"
        val entry = DiaryEntry(mood = 2, moodEmoji = "🙁", note = "spěchá")

        // ON_STOP / onDispose call it exactly like this: no scope, no suspend.
        store.flush(date, entry)

        // The write runs on the store's own scope, so poll for it.
        withTimeout(5_000) {
            while (store.load(date) == null) delay(25)
        }

        assertEquals(entry, store.load(date))
        assertEquals("spěchá", store.load(date)?.note)

        handle.close()
    }

    @Test
    fun `draft key matches the web localStorage naming`() {
        assertEquals("diarium_draft_v2_account-a_2026-09-10", DraftStore.key(OWNER_A, "2026-09-10"))
        assertEquals("diarium_draft_2026-09-10", DraftStore.legacyKey("2026-09-10"))
    }

    @Test
    fun `drafts survive a store reopening`() = runBlocking {
        val file = tempFile()

        val first = newStore(file)
        first.store.save("2026-09-10", DiaryEntry(mood = 3, moodEmoji = "😐", note = "trvá"))
        // Release the file so the read below starts from disk, like a cold start.
        first.close()

        val reopened = newStore(file)
        assertEquals(3, reopened.store.load("2026-09-10")?.mood)
        assertEquals("trvá", reopened.store.load("2026-09-10")?.note)
        reopened.close()
    }

    @Test
    fun `generated reflection stays account pinned across process restart and account switch`() = runBlocking {
        val file = tempFile()
        val date = "2026-11-10"
        val reflection = "Generated text survives the process exit"
        val entry = DiaryEntry(mood = 4, note = "unsent entry", aiReflection = reflection)
        val accountA = newStore(file, OWNER_A)
        accountA.store.stage(
            date = date,
            entry = entry,
            baselineExists = true,
            baselineId = ROW_ID,
            baselineRevision = 7,
            pendingAiReflection = reflection,
        ).await()
        accountA.close()

        val accountB = newStore(file, OWNER_B)
        assertNull("a switched account cannot read A's generated text", accountB.store.loadRecord(date))
        accountB.close()

        val reopenedA = newStore(file, OWNER_A)
        val recovered = reopenedA.store.loadRecord(date)
        assertEquals(OWNER_A, recovered?.ownerId)
        assertEquals(entry, recovered?.entry)
        assertEquals(reflection, recovered?.pendingAiReflection)
        assertEquals(7L, recovered?.baselineRevision)
        reopenedA.close()
    }

    @Test
    fun `conditional clear removes only the draft matching a completed save`() = runBlocking {
        val handle = newStore(tempFile())
        val store = handle.store
        val date = "2026-09-12"
        val completed = DiaryEntry(mood = 4, note = "queued snapshot")
        val newer = DiaryEntry(mood = 2, note = "new edit")

        store.save(date, completed)
        assertTrue(store.clearIfUnchanged(date, completed))
        assertNull(store.load(date))

        store.save(date, completed)
        store.save(date, newer)
        assertFalse(store.clearIfUnchanged(date, completed))
        assertEquals(newer, store.load(date))

        handle.close()
    }

    @Test
    fun `draft server baseline and unresolved request metadata survive persistence`() = runBlocking {
        val handle = newStore(tempFile())
        val date = "2026-09-13"
        val entry = DiaryEntry(mood = 4, note = "draft")
        val baseline = "2026-09-12T18:00:00Z"

        handle.store.save(
            date,
            entry,
            baselineUpdatedAt = baseline,
            baselineExists = true,
            baselineId = ROW_ID,
            baselineRevision = 7,
            pendingSaveRequestId = REQUEST_ID,
        )

        val restored = handle.store.loadRecord(date)
        assertEquals(baseline, restored?.baselineUpdatedAt)
        assertEquals(true, restored?.baselineExists)
        assertEquals(ROW_ID, restored?.baselineId)
        assertEquals(7L, restored?.baselineRevision)
        assertEquals(REQUEST_ID, restored?.pendingSaveRequestId)
        handle.close()
    }

    @Test
    fun `server-owned fields omitted from drafts do not block completed-request cleanup`() = runBlocking {
        val handle = newStore(tempFile())
        val completed = DiaryEntry(mood = 4, note = "stored", aiReflection = "server-generated")
        handle.store.save(
            "2026-09-17",
            completed,
            pendingSaveRequestId = REQUEST_ID,
            pendingAiReflection = "server-generated",
        )
        val restored = handle.store.loadRecord("2026-09-17")
        assertEquals("server-generated", restored?.pendingAiReflection)
        assertEquals("server-generated", restored?.entry?.aiReflection)

        assertTrue(handle.store.clearIfUnchanged("2026-09-17", completed, REQUEST_ID))
        assertNull(handle.store.loadRecord("2026-09-17"))
        handle.close()
    }

    @Test
    fun `a terminal conflict releases only the matching request id and retains the draft token`() = runBlocking {
        val handle = newStore(tempFile())
        val date = "2026-09-16"
        val entry = DiaryEntry(mood = 4, note = "unsent conflict copy", aiReflection = "reflection awaiting CAS")
        handle.store.save(
            date,
            entry,
            baselineExists = true,
            baselineId = ROW_ID,
            baselineRevision = 7,
            pendingSaveRequestId = REQUEST_ID,
            pendingAiReflection = "reflection awaiting CAS",
        )

        assertFalse(handle.store.clearRequestForConflict(date, entry, OTHER_REQUEST_ID))
        assertEquals(REQUEST_ID, handle.store.loadRecord(date)?.pendingSaveRequestId)
        assertTrue(handle.store.clearRequestForConflict(date, entry, REQUEST_ID))
        val retained = handle.store.loadRecord(date)
        assertEquals(entry, retained?.entry)
        assertEquals(true, retained?.baselineExists)
        assertEquals(ROW_ID, retained?.baselineId)
        assertEquals(7L, retained?.baselineRevision)
        assertEquals("reflection awaiting CAS", retained?.pendingAiReflection)
        assertNull(retained?.pendingSaveRequestId)
        handle.close()
    }

    @Test
    fun `request id is part of conditional draft cleanup identity`() = runBlocking {
        val handle = newStore(tempFile())
        val entry = DiaryEntry(mood = 4, note = "retry safely")
        handle.store.save("2026-09-15", entry, pendingSaveRequestId = REQUEST_ID)

        assertFalse(handle.store.clearIfUnchanged("2026-09-15", entry, OTHER_REQUEST_ID))
        assertEquals(REQUEST_ID, handle.store.loadRecord("2026-09-15")?.pendingSaveRequestId)
        assertTrue(handle.store.clearIfUnchanged("2026-09-15", entry, REQUEST_ID))
        handle.close()
    }

    @Test
    fun `draft revision mismatch requires explicit recovery against server entry`() {
        val base = DraftRecord(
            ownerId = OWNER_A,
            date = "2026-09-10",
            entry = DiaryEntry(mood = 4, note = "unfinished edit"),
            baselineUpdatedAt = "2026-09-10T10:00:00Z",
            baselineExists = true,
        )

        assertFalse(base.conflictsWith(remoteExists = true, remoteUpdatedAt = "2026-09-10T10:00:00Z"))
        assertTrue(base.conflictsWith(remoteExists = true, remoteUpdatedAt = "2026-09-10T11:00:00Z"))
        assertTrue(
            base.copy(baselineUpdatedAt = null)
                .conflictsWith(remoteExists = true, remoteUpdatedAt = "2026-09-10T11:00:00Z"),
        )
    }

    @Test
    fun `autosave never upgrades an unverifiable draft baseline from a later server read`() = runBlocking {
        val handle = newStore(tempFile())
        val date = "2026-09-14"
        val initial = DiaryEntry(mood = 3, note = "recovered local text")
        handle.store.save(date, initial)
        handle.store.save(
            date,
            initial.copy(note = "later autosave"),
            baselineUpdatedAt = "2026-09-14T12:00:00Z",
            baselineExists = true,
        )

        val recovered = handle.store.loadRecord(date)
        assertEquals("later autosave", recovered?.entry?.note)
        assertNull("the original unknown baseline stays unknown", recovered?.baselineUpdatedAt)
        assertNull(recovered?.baselineExists)
        handle.close()
    }

    @Test
    fun `draft CAS baseline compares both row id and server revision`() {
        val base = DraftRecord(
            ownerId = OWNER_A,
            date = "2026-09-10",
            entry = DiaryEntry(mood = 4, note = "unfinished edit"),
            baselineUpdatedAt = "2026-09-10T10:00:00Z",
            baselineExists = true,
            baselineId = ROW_ID,
            baselineRevision = 7,
        )

        assertFalse(
            base.conflictsWith(
                remoteExists = true,
                remoteUpdatedAt = null,
                remoteId = ROW_ID,
                remoteRevision = 7,
            ),
        )
        assertTrue(
            base.conflictsWith(
                remoteExists = true,
                remoteUpdatedAt = null,
                remoteId = ROW_ID,
                remoteRevision = 8,
            ),
        )
        assertTrue(
            base.conflictsWith(
                remoteExists = true,
                remoteUpdatedAt = null,
                remoteId = OTHER_ROW_ID,
                remoteRevision = 7,
            ),
        )
    }

    @Test
    fun `an unverified draft baseline requires recovery even when the remote day is empty`() {
        val unverified = DraftRecord(
            ownerId = OWNER_A,
            date = "2026-09-10",
            entry = DiaryEntry(mood = 4, note = "local draft"),
            baselineUpdatedAt = null,
            baselineExists = null,
        )

        assertTrue(unverified.conflictsWith(remoteExists = false, remoteUpdatedAt = null))
    }

    @Test
    fun `drafts are isolated when the signed in account changes`() = runBlocking {
        val file = tempFile()
        val accountA = newStore(file, OWNER_A)
        val privateDraft = DiaryEntry(mood = 5, note = "account A only")
        accountA.store.save("2026-09-10", privateDraft)
        accountA.close()

        val accountB = newStore(file, OWNER_B)
        assertNull(accountB.store.load("2026-09-10"))
        accountB.store.save("2026-09-10", DiaryEntry(mood = 1, note = "account B"))
        accountB.close()

        val reopenedA = newStore(file, OWNER_A)
        assertEquals(privateDraft, reopenedA.store.load("2026-09-10"))
        reopenedA.close()
    }

    @Test
    fun `legacy ownerless draft remains quarantined and is not restored`() = runBlocking {
        val handle = newStore(tempFile(), OWNER_A)
        val date = "2026-09-10"
        val key = stringPreferencesKey(DraftStore.legacyKey(date))
        val legacy = DiaryEntry(mood = 5, note = "unknown owner").toJson().toString()
        handle.dataStore.edit { it[key] = legacy }

        assertNull(handle.store.load(date))
        assertEquals(legacy, handle.dataStore.data.first()[key])
        handle.close()
    }

    @Test
    fun `a newer queued draft survives a successful save clear and later edits remain owner scoped`() = runBlocking {
        val file = tempFile()
        val scope = CoroutineScope(Dispatchers.IO + Job())
        val backing = PreferenceDataStoreFactory.create(scope = scope, produceFile = { file })
        val gated = GatedPreferencesDataStore(backing)
        val oldScreenStore = DraftStore(gated, OWNER_A)
        val currentStore = DraftStore(gated, OWNER_A)
        val otherOwnerStore = DraftStore(gated, OWNER_B)
        val date = "2026-09-15"
        val completed = DiaryEntry(mood = 4, note = "saved snapshot")
        val newerEdit = completed.copy(note = "edited before clear")
        val laterEdit = completed.copy(note = "edited after clear")
        val otherOwnerEdit = DiaryEntry(mood = 1, note = "other owner")

        try {
            gated.gateNextWrite()
            val oldWrite = oldScreenStore.stage(date, completed)
            withTimeout(5_000) { gated.writeStarted.await() }

            // This stage is submitted while the old write is in flight, but before
            // the successful server save's cleanup begins. It must not be invalidated
            // by that cleanup, even though the DataStore transaction is still queued.
            val newerWrite = currentStore.stage(date, newerEdit)
            val otherOwnerWrite = otherOwnerStore.stage(date, otherOwnerEdit)
            val clear = async { currentStore.clearIfUnchanged(date, completed) }
            yield()
            gated.releaseWrite.complete(Unit)

            oldWrite.await()
            newerWrite.await()
            otherOwnerWrite.await()
            assertFalse("cleanup must retain the newer queued edit", withTimeout(5_000) { clear.await() })
            assertEquals(newerEdit, currentStore.loadRecord(date)?.entry)
            assertEquals(otherOwnerEdit, otherOwnerStore.loadRecord(date)?.entry)

            // A subsequent edit still persists after the clear barrier and remains
            // isolated by both account and date.
            currentStore.save(date, laterEdit)
            assertEquals(laterEdit, oldScreenStore.loadRecord(date)?.entry)
            assertNull(currentStore.loadRecord("2026-09-16"))
            assertEquals(otherOwnerEdit, otherOwnerStore.loadRecord(date)?.entry)
        } finally {
            gated.releaseWrite.complete(Unit)
            scope.coroutineContext[Job]?.cancelAndJoin()
        }
    }

    @Test
    fun `a delayed old account flush cannot cross into the current account draft`() = runBlocking {
        val file = tempFile()
        val scope = CoroutineScope(Dispatchers.IO + Job())
        val backing = PreferenceDataStoreFactory.create(scope = scope, produceFile = { file })
        val gated = GatedPreferencesDataStore(backing)
        val accountA = DraftStore(gated, OWNER_A)
        val accountB = DraftStore(gated, OWNER_B)
        val date = "2026-09-16"
        val privateA = DiaryEntry(mood = 5, note = "account A")
        val savedB = DiaryEntry(mood = 2, note = "account B saved")

        try {
            gated.gateNextWrite()
            accountA.flush(date, privateA)
            withTimeout(5_000) { gated.writeStarted.await() }

            accountB.save(date, savedB)
            assertTrue(accountB.clearIfUnchanged(date, savedB))
            gated.releaseWrite.complete(Unit)

            withTimeout(5_000) {
                while (accountA.loadRecord(date)?.entry != privateA) delay(10)
            }
            assertEquals(privateA, accountA.loadRecord(date)?.entry)
            assertNull("account A's delayed flush must not create B's draft", accountB.loadRecord(date))
        } finally {
            gated.releaseWrite.complete(Unit)
            scope.coroutineContext[Job]?.cancelAndJoin()
        }
    }

    @Test
    fun `a cancelled screen waiter cannot cancel the durable draft stage`() = runBlocking {
        val file = tempFile()
        val scope = CoroutineScope(Dispatchers.IO + Job())
        val backing = PreferenceDataStoreFactory.create(scope = scope, produceFile = { file })
        val gated = GatedPreferencesDataStore(backing)
        val store = DraftStore(gated, OWNER_A)
        val date = "2026-09-17"
        val draft = DiaryEntry(mood = 4, note = "staged before cancellable save")

        try {
            gated.gateNextWrite()
            val screenSave = launch { store.save(date, draft) }
            withTimeout(5_000) { gated.writeStarted.await() }
            screenSave.cancelAndJoin()
            gated.releaseWrite.complete(Unit)

            withTimeout(5_000) {
                while (store.loadRecord(date)?.entry != draft) delay(10)
            }
            assertEquals(draft, store.loadRecord(date)?.entry)
        } finally {
            gated.releaseWrite.complete(Unit)
            scope.coroutineContext[Job]?.cancelAndJoin()
        }
    }

    @Test
    fun `an IOException while reading a draft is surfaced as recoverable corruption`() = runBlocking {
        val failure = runCatching {
            DraftStore(FailingReadDataStore(), OWNER_A).loadRecord("2026-09-18")
        }.exceptionOrNull()

        assertTrue(failure is DraftStoreReadException)
        assertTrue(failure?.cause is IOException)
    }

    @Test
    fun `a malformed draft is not silently treated as missing or overwritten`() = runBlocking {
        val handle = newStore(tempFile())
        val date = "2026-09-19"
        val key = stringPreferencesKey(DraftStore.key(OWNER_A, date))
        val corrupt = "{not valid json"
        handle.dataStore.edit { it[key] = corrupt }

        val failure = runCatching { handle.store.loadRecord(date) }.exceptionOrNull()
        assertTrue(failure is DraftStoreReadException)
        val writeFailure = runCatching {
            handle.store.save(date, DiaryEntry(mood = 4, note = "do not replace corrupt source"))
        }.exceptionOrNull()
        assertNotNull("a write must not replace an unreadable corrupt record", writeFailure)
        assertEquals(corrupt, handle.dataStore.data.first()[key])
        handle.close()
    }

    @Test
    fun `a lifecycle flush failure is observable and a later retry persists the draft`() = runBlocking {
        val file = tempFile()
        val scope = CoroutineScope(Dispatchers.IO + Job())
        val backing = PreferenceDataStoreFactory.create(scope = scope, produceFile = { file })
        val store = DraftStore(FailOnceWriteDataStore(backing), OWNER_A)
        val date = "2026-09-20"
        val entry = DiaryEntry(mood = 5, note = "retained in the form")

        try {
            val failed = runCatching { store.flush(date, entry).await() }.exceptionOrNull()
            assertTrue("flush must expose its DataStore failure", failed is IOException)
            assertNull(backing.data.first()[stringPreferencesKey(DraftStore.key(OWNER_A, date))])

            withTimeout(5_000) { store.flush(date, entry).await() }
            assertEquals(entry, store.loadRecord(date)?.entry)
        } finally {
            scope.coroutineContext[Job]?.cancelAndJoin()
        }
    }

    private companion object {
        const val OWNER_A = "account-a"
        const val OWNER_B = "account-b"
        const val ROW_ID = "ecaf917a-7c84-4ad5-b657-cc5d9af10a36"
        const val OTHER_ROW_ID = "c9754ac3-cf12-40db-8615-04642f4590a4"
        const val REQUEST_ID = "10000000-0000-4000-8000-000000000001"
        const val OTHER_REQUEST_ID = "10000000-0000-4000-8000-000000000002"
    }

    private class GatedPreferencesDataStore(
        private val delegate: DataStore<Preferences>,
    ) : DataStore<Preferences> {
        val writeStarted = CompletableDeferred<Unit>()
        val releaseWrite = CompletableDeferred<Unit>()
        @Volatile
        private var gateArmed = false

        fun gateNextWrite() {
            gateArmed = true
        }

        override val data: Flow<Preferences> get() = delegate.data

        override suspend fun updateData(
            transform: suspend (Preferences) -> Preferences,
        ): Preferences {
            if (gateArmed) {
                gateArmed = false
                writeStarted.complete(Unit)
                releaseWrite.await()
            }
            return delegate.updateData(transform)
        }
    }

    private class FailingReadDataStore : DataStore<Preferences> {
        override val data: Flow<Preferences> = flow<Preferences> { throw IOException("simulated DataStore read failure") }

        override suspend fun updateData(
            transform: suspend (Preferences) -> Preferences,
        ): Preferences = throw IOException("simulated DataStore write failure")
    }

    private class FailOnceWriteDataStore(
        private val delegate: DataStore<Preferences>,
    ) : DataStore<Preferences> {
        private val shouldFail = AtomicBoolean(true)

        override val data: Flow<Preferences> get() = delegate.data

        override suspend fun updateData(
            transform: suspend (Preferences) -> Preferences,
        ): Preferences {
            if (shouldFail.compareAndSet(true, false)) throw IOException("simulated transient write failure")
            return delegate.updateData(transform)
        }
    }
}
