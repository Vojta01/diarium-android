package cz.digitalnivedomi.diarium.ui.checkin

import cz.digitalnivedomi.diarium.core.data.DiaryEntry
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The state-machine half of the 2026-11 check-in data-loss fixes. All of it is
 * plain JVM logic on [CheckInStateHolder], because that is where the screen's
 * decisions live:
 *
 *  - a failed load is a distinct, unsavable state (never an empty day),
 *  - a save is pinned to the date it started on and cannot be started twice,
 *  - photo removal and scale clearing are explicit, persisted intents.
 */
class CheckInDataLossTest {

    private fun holder(date: String = DATE) = CheckInStateHolder(date)

    // ── A: failed load must not become a savable empty day ─────────────────

    @Test
    fun `a failed load is an error state, not an empty editable day`() {
        val holder = holder()

        holder.markLoadFailed("Nepodařilo se připojit k serveru.")

        assertEquals("Nepodařilo se připojit k serveru.", holder.state.loadError)
        assertFalse("nothing may be written over an unread day", holder.state.canSave)
        assertFalse(holder.state.saved)
        assertFalse("the passive/stored flag must not be guessed", holder.state.storedEntry)
    }

    @Test
    fun `a failed load keeps the draft but still blocks saving`() {
        val holder = holder()
        val draft = DiaryEntry(mood = 3, note = "rozepsané")

        holder.markLoadFailed("offline", draft)

        assertEquals(draft, holder.state.entry)
        assertFalse("editing the draft is fine, saving it over the row is not", holder.state.canSave)
    }

    @Test
    fun `a successful retry clears the failure`() {
        val holder = holder()
        holder.markLoadFailed("offline")

        holder.load(DiaryEntry(mood = 4), stored = true)

        assertEquals(null, holder.state.loadError)
        assertTrue(holder.state.canSave)
        assertTrue(holder.state.showsPassiveDay)
    }

    @Test
    fun `loading again preserves the failure until the retry succeeds`() {
        val holder = holder()
        holder.markLoadFailed("offline")

        holder.setLoading(true)

        assertEquals("offline", holder.state.loadError)
        assertFalse("a retry in flight is not savable either", holder.state.canSave)
    }

    @Test
    fun `a draft store failure retains the dirty entry and blocks saving`() {
        val holder = holder()
        holder.load(DiaryEntry.EMPTY, stored = false, baselineExists = false)
        holder.setNote("keep this local note")

        holder.markDraftStoreFailed("local draft unreadable")

        assertEquals("keep this local note", holder.state.entry.note)
        assertTrue(holder.state.draftDirty)
        assertEquals("local draft unreadable", holder.state.draftStoreError)
        assertFalse(holder.state.loading)
        assertFalse(holder.state.canSave)
    }

    @Test
    fun `a day switch starts a locked load and drops the previous load failure`() {
        val holder = holder()
        holder.markLoadFailed("offline")

        holder.setDate("2026-09-09")

        assertEquals(null, holder.state.loadError)
        assertTrue("the new day cannot be edited before it loads", holder.state.loading)
        holder.setNote("must not become the new day's value")
        assertEquals(DiaryEntry.EMPTY, holder.state.entry)
    }

    @Test
    fun `form edits saves and navigation are blocked while the selected day loads`() {
        val holder = holder()
        holder.setLoading(true)

        holder.selectMood(4, "🙂")
        holder.setNote("must not be overwritten by the load")
        holder.startEditing()

        assertEquals(DiaryEntry.EMPTY, holder.state.entry)
        assertFalse(holder.state.editing)
        assertFalse(holder.beginSave())
        assertFalse(holder.beginDateNavigation())
    }

    @Test
    fun `save and date navigation are blocked while reflection generation is in flight`() {
        val holder = holder()
        holder.load(
            DiaryEntry(mood = 4, note = "already stored"),
            stored = true,
            baselineExists = true,
            baselineId = ROW_ID,
            baselineRevision = 7,
        )
        holder.markReflectionLoading()

        assertFalse("save stays disabled until generated text is safely persisted", holder.state.canSave)
        assertFalse("date navigation cannot race the reflection save", holder.beginDateNavigation())
        assertEquals(null, holder.beginSaveRequest(OWNER_ID))
    }

    @Test
    fun `a legitimate empty day is still editable and savable`() {
        val holder = holder()

        holder.load(DiaryEntry.EMPTY, stored = false)

        assertEquals(null, holder.state.loadError)
        assertTrue(holder.state.canSave)
        assertFalse(holder.state.showsPassiveDay)
    }

    // ── B: save/date race ──────────────────────────────────────────────────

    @Test
    fun `foreground retry reuses its immutable request id until the user edits`() {
        val holder = holder()
        holder.load(
            DiaryEntry(mood = 4, note = "retry this exact save"),
            stored = true,
            baselineExists = true,
            baselineId = ROW_ID,
            baselineRevision = 7,
        )

        val first = holder.beginSaveRequest(OWNER_ID)!!
        holder.markError("reply lost", DATE)
        val retry = holder.beginSaveRequest(OWNER_ID)!!

        assertEquals(first, retry)
        holder.markError("reply lost again", DATE)
        holder.setNote("changed after unknown outcome")
        val edited = holder.beginSaveRequest(OWNER_ID)!!

        assertTrue(first.requestId != edited.requestId)
        assertEquals("changed after unknown outcome", edited.entry.note)
        assertEquals(7L, edited.baseline.revision)
    }

    @Test
    fun `restored pending reflection remains part of the same conditional retry`() {
        val holder = holder()
        val reflection = "Generated reflection awaiting confirmation"
        holder.load(
            DiaryEntry(mood = 4, note = "retry the reflected entry"),
            stored = true,
            baselineExists = true,
            baselineId = ROW_ID,
            baselineRevision = 7,
            pendingSaveRequestId = REQUEST_ID,
            pendingAiReflection = reflection,
        )

        val first = holder.beginSaveRequest(OWNER_ID)!!
        assertTrue(first.writeAiReflection)
        assertEquals(reflection, first.entry.aiReflection)
        holder.markError("reply lost", DATE)

        val retry = holder.beginSaveRequest(OWNER_ID)!!
        assertEquals(first, retry)
        assertEquals(REQUEST_ID, retry.requestId)
    }

    @Test
    fun `markSaved is a no-op when the day changed during the save`() {
        val holder = holder()
        holder.selectMood(4, "🙂")
        assertTrue(holder.beginSave())

        holder.setDate("2026-09-09")
        holder.markSaved(DATE)

        assertFalse("the new day was never saved", holder.state.saved)
        assertFalse("and its reflection window must not open", holder.state.showReflectionDialog)
        assertEquals("2026-09-09", holder.state.date)
    }

    @Test
    fun `markSaved applies when the save finishes on the day it started for`() {
        val holder = holder()
        holder.selectMood(4, "🙂")
        holder.beginSave()

        holder.markSaved(DATE)

        assertTrue(holder.state.saved)
        assertTrue(holder.state.showReflectionDialog)
    }

    @Test
    fun `a second save cannot start while one is in flight`() {
        val holder = holder()

        assertTrue(holder.beginSave())
        assertFalse("the double tap must be swallowed", holder.beginSave())
    }

    @Test
    fun `edits are ignored while a save is in flight`() {
        val holder = holder()
        holder.selectMood(4, "🙂")
        holder.beginSave()

        holder.setNote("změna během ukládání")
        holder.selectMood(1, "😡")

        assertEquals(4, holder.state.entry.mood)
        assertEquals("", holder.state.entry.note)
    }

    @Test
    fun `an old save's error never lands on the newly opened day`() {
        val holder = holder()
        holder.beginSave()
        holder.setDate("2026-09-09")

        holder.markError("stará chyba", DATE)

        assertEquals(null, holder.state.errorMessage)
    }

    // ── C: explicit photo removal ──────────────────────────────────────────

    @Test
    fun `removing a photo records an explicit clear intent`() {
        val holder = holder()
        holder.load(DiaryEntry(photoPath = "https://x/photo.jpg"), stored = true)

        holder.setPhotoPath(null)

        assertEquals(null, holder.state.entry.photoPath)
        assertTrue("null alone cannot distinguish 'never had one'", holder.state.entry.clearPhoto)
    }

    @Test
    fun `removing a photo that does not exist records nothing`() {
        val holder = holder()

        holder.setPhotoPath(null)

        assertFalse("an ordinary edit must never send a clearing PATCH", holder.state.entry.clearPhoto)
    }

    @Test
    fun `picking a new photo drops the clear intent`() {
        val holder = holder()
        holder.load(DiaryEntry(photoPath = "https://x/old.jpg"), stored = true)
        holder.setPhotoPath(null)

        holder.setPhotoPath("https://x/new.jpg")

        assertEquals("https://x/new.jpg", holder.state.entry.photoPath)
        assertFalse(holder.state.entry.clearPhoto)
    }

    @Test
    fun `replacing a photo retains the old path until the save commits`() {
        val oldPath = "https://x/old.jpg"
        val holder = holder()
        holder.load(DiaryEntry(photoPath = oldPath), stored = true)

        holder.setPhotoPath("https://x/new-unique.jpg")

        assertEquals("https://x/new-unique.jpg", holder.state.entry.photoPath)
        assertEquals(oldPath, holder.state.entry.photoPathToDelete)
        assertFalse(holder.state.entry.clearPhoto)
    }

    @Test
    fun `removing a staged replacement still remembers the stored old path`() {
        val oldPath = "https://x/old.jpg"
        val holder = holder()
        holder.load(DiaryEntry(photoPath = oldPath), stored = true)
        holder.setPhotoPath("https://x/new-unique.jpg")

        holder.setPhotoPath(null)

        assertTrue(holder.state.entry.clearPhoto)
        assertEquals(oldPath, holder.state.entry.photoPathToDelete)
    }

    @Test
    fun `a newer photo upload supersedes an older callback for the same date`() {
        val holder = holder()
        val first = holder.beginPhotoUpload()
        val second = holder.beginPhotoUpload()

        assertFalse(holder.isPhotoUploadCurrent(first, DATE))
        assertTrue(holder.isPhotoUploadCurrent(second, DATE))
    }

    @Test
    fun `a photo upload callback stays stale after switching away and back`() {
        val holder = holder()
        val oldPath = "https://x/stored.jpg"
        holder.load(DiaryEntry(photoPath = oldPath), stored = true)
        val token = holder.beginPhotoUpload()

        holder.setDate("2026-09-09")
        holder.load(DiaryEntry.EMPTY)
        holder.setDate(DATE)
        holder.load(DiaryEntry(photoPath = oldPath), stored = true)

        assertFalse(holder.isPhotoUploadCurrent(token, DATE))
        assertEquals(oldPath, holder.state.entry.photoPath)
    }

    @Test
    fun `the clear intents survive the draft json round-trip`() {
        val entry = DiaryEntry(
            mood = 4,
            clearPhoto = true,
            photoPathToDelete = "https://x/old.jpg",
            clearedScaleIds = setOf("focus", "energy"),
        )

        val restored = DiaryEntry.fromJson(JSONObject(entry.toJson().toString()))

        assertTrue(restored.clearPhoto)
        assertEquals("https://x/old.jpg", restored.photoPathToDelete)
        assertEquals(setOf("energy", "focus"), restored.clearedScaleIds)
    }

    @Test
    fun `an ordinary entry writes neither clear key into the draft`() {
        val json = DiaryEntry(mood = 4, photoPath = "https://x/p.jpg").toJson()

        assertFalse(json.has("clearPhoto"))
        assertFalse(json.has("photoPathToDelete"))
        assertFalse(json.has("clearedScaleIds"))
    }

    // ── D: explicit scale clearing ─────────────────────────────────────────

    @Test
    fun `clearing a scale records its id`() {
        val holder = holder()
        holder.load(DiaryEntry(scaleValues = mapOf("focus" to 4)), stored = true)

        holder.setScale("focus", 0)

        assertFalse(holder.state.entry.scaleValues.containsKey("focus"))
        assertEquals(setOf("focus"), holder.state.entry.clearedScaleIds)
    }

    @Test
    fun `touching an untouched scale at zero records nothing`() {
        val holder = holder()

        holder.setScale("focus", 0)

        assertTrue(holder.state.entry.clearedScaleIds.isEmpty())
    }

    @Test
    fun `setting a cleared scale again drops the intent`() {
        val holder = holder()
        holder.load(DiaryEntry(scaleValues = mapOf("focus" to 4)), stored = true)
        holder.setScale("focus", 0)

        holder.setScale("focus", 3)

        assertEquals(mapOf("focus" to 3), holder.state.entry.scaleValues)
        assertTrue(holder.state.entry.clearedScaleIds.isEmpty())
    }

    @Test
    fun `the payload still skips zero values while the clear intent stays in the model`() {
        val holder = holder()
        holder.load(
            DiaryEntry(scaleValues = mapOf("energy" to 4, "focus" to 2)),
            stored = true,
        )
        holder.setScale("focus", 0)

        val payload = cz.digitalnivedomi.diarium.core.data.EntriesRepository.buildPayload(
            holder.state.entry,
            "user-1",
            DATE,
        )

        val scales = payload.getJSONObject("scale_values")
        assertEquals(1, scales.length())
        assertEquals(4, scales.getInt("energy"))
        assertFalse(scales.has("focus"))
        assertEquals(setOf("focus"), holder.state.entry.clearedScaleIds)
    }

    private companion object {
        const val DATE = "2026-09-10"
        const val OWNER_ID = "5f2c1b7e-9d3a-4c6f-8a10-2b4d6e8f0a12"
        const val ROW_ID = "ecaf917a-7c84-4ad5-b657-cc5d9af10a36"
        const val REQUEST_ID = "10000000-0000-4000-8000-000000000006"
    }
}
