package cz.digitalnivedomi.diarium.core.data

import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LegacyRecoveryScannerTest {

    @Test
    fun `scan inventories multiple legacy dates and profile flags without values`() = runBlocking {
        val source = MemoryRecoveryStorage().apply {
            drafts["diarium_draft_2026-10-02"] = "{\"mood\":4,\"note\":\"private draft\"}"
            drafts["diarium_draft_2026-10-01"] = "{\"mood\":2}"
            drafts["diarium_draft_v2_account-a_2026-10-03"] = "{\"mood\":5}"
            pendingSaves["2026-10-03"] = "{\"queuedAt\":1,\"entry\":{\"mood\":3}}"
            pendingSaves["2026-10-01"] = "{\"entry\":{\"mood\":1}}"
            profileFields += setOf("user_name", "birth_year", "ai_enabled", "onboarding_done", "profile_v2_account-a_name")
            aiSecretPresent = true
        }

        val inventory = LegacyRecoveryScanner(source).scan()

        assertEquals(4, inventory.recordCount)
        assertEquals(2, inventory.draftCount)
        assertEquals(2, inventory.pendingSaveCount)
        assertEquals(0, inventory.malformedCount)
        assertEquals(
            listOf(
                LegacyRecoveryRecordId(LegacyRecoveryType.DRAFT, "2026-10-01"),
                LegacyRecoveryRecordId(LegacyRecoveryType.PENDING_SAVE, "2026-10-01"),
                LegacyRecoveryRecordId(LegacyRecoveryType.DRAFT, "2026-10-02"),
                LegacyRecoveryRecordId(LegacyRecoveryType.PENDING_SAVE, "2026-10-03"),
            ),
            inventory.records.map { it.id },
        )
        assertEquals(
            setOf(
                LegacyProfileField.NAME,
                LegacyProfileField.BIRTH_YEAR,
                LegacyProfileField.AI_ENABLED,
                LegacyProfileField.ONBOARDING_DONE,
            ),
            inventory.profileFieldsPresent,
        )
        assertEquals(LegacySecretPresence.PRESENT, inventory.legacyAiSecretStatus)
        assertTrue(inventory.requirements.authenticatedDestinationRequired)
        assertTrue(inventory.requirements.explicitConfirmationRequired)
        assertFalse(inventory.requirements.automaticRecoveryAllowed)
        assertFalse(inventory.requirements.cacheIsAuthoritative)
        assertFalse(inventory.toString().contains("private draft"))
    }

    @Test
    fun `malformed date keyed records stay inventoried and cannot be previewed`() = runBlocking {
        val source = MemoryRecoveryStorage().apply {
            drafts["diarium_draft_2026-10-04"] = "{not json"
            pendingSaves["2026-10-05"] = "{\"queuedAt\":4}"
            pendingSaves["2026-10-06"] = 23
        }
        val scanner = LegacyRecoveryScanner(source)

        val inventory = scanner.scan()

        assertEquals(3, inventory.recordCount)
        assertEquals(3, inventory.malformedCount)
        assertTrue(inventory.records.all { it.status == LegacyRecoveryRecordStatus.MALFORMED })
        inventory.records.forEach { assertNull(scanner.preview(it.id)) }
    }

    @Test
    fun `pending save requires a typed nested entry and does not treat envelope fields as diary data`() = runBlocking {
        val source = MemoryRecoveryStorage().apply {
            pendingSaves["2026-10-11"] = JSONObject()
                .put("queuedAt", 7)
                .put("mood", 5)
                .toString()
            pendingSaves["2026-10-12"] = JSONObject()
                .put("queuedAt", 8)
                .put("entry", "not an object")
                .put("mood", 4)
                .toString()
            pendingSaves["2026-10-13"] = JSONObject()
                .put("queuedAt", 9)
                .put("entry", JSONObject().put("mood", 3))
                .toString()
        }
        val scanner = LegacyRecoveryScanner(source)

        val inventory = scanner.scan()

        assertEquals(3, inventory.pendingSaveCount)
        assertEquals(2, inventory.malformedCount)
        assertEquals(
            listOf(
                LegacyRecoveryRecordStatus.MALFORMED,
                LegacyRecoveryRecordStatus.MALFORMED,
                LegacyRecoveryRecordStatus.PREVIEWABLE,
            ),
            inventory.records.map { it.status },
        )
        assertNull(scanner.preview(inventory.records[0].id))
        assertNull(scanner.preview(inventory.records[1].id))
        assertEquals(3, scanner.preview(inventory.records[2].id)?.mood)
    }

    @Test
    fun `preview is an explicit safe summary that omits free text paths and token shaped fields`() = runBlocking {
        val secretLikeText = "private note containing a fake bearer marker"
        val apiKey = "private-api-key-marker"
        val unsafePath = "/data/user/0/app/files/private.jpg"
        val source = MemoryRecoveryStorage().apply {
            drafts["diarium_draft_2026-10-07"] = JSONObject()
                .put("mood", 4)
                .put("sleepQuality", 3)
                .put("stress", 2)
                .put("activities", JSONArray().put("private activity"))
                .put("habits", JSONObject().put("private habit", true))
                .put("gratitude", JSONArray().put("one").put("").put("three"))
                .put("note", secretLikeText)
                .put("photoPath", unsafePath)
                .put("apiKey", apiKey)
                .toString()
            pendingSaves["2026-10-08"] = JSONObject()
                .put("entry", JSONObject().put("mood", 1).put("note", "another private note"))
                .put("access_token", "do-not-return")
                .toString()
            aiSecretPresent = true
        }
        val scanner = LegacyRecoveryScanner(source)
        val inventory = scanner.scan()

        val draftId = inventory.records.single { it.type == LegacyRecoveryType.DRAFT }.id
        val pendingId = inventory.records.single { it.type == LegacyRecoveryType.PENDING_SAVE }.id
        val preview = scanner.preview(draftId)
        val pendingPreview = scanner.preview(pendingId)

        assertNotNull(preview)
        assertEquals(4, preview?.mood)
        assertEquals(3, preview?.sleepQuality)
        assertEquals(2, preview?.stress)
        assertEquals(1, preview?.activityCount)
        assertEquals(1, preview?.enabledHabitCount)
        assertEquals(2, preview?.nonEmptyGratitudeSlotCount)
        assertEquals(secretLikeText.length, preview?.noteCharacterCount)
        assertTrue(preview?.hasPhotoReference == true)
        assertNotNull(pendingPreview)
        assertEquals(1, pendingPreview?.mood)
        val output = "$inventory $preview $pendingPreview"
        assertFalse(output.contains(secretLikeText))
        assertFalse(output.contains("another private note"))
        assertFalse(output.contains(apiKey))
        assertFalse(output.contains("access_token"))
        assertFalse(output.contains("do-not-return"))
        assertFalse(output.contains(unsafePath))
        assertFalse(output.contains("private activity"))
    }

    @Test
    fun `scanning and previewing preserve source records exactly`() = runBlocking {
        val source = MemoryRecoveryStorage().apply {
            drafts["diarium_draft_2026-10-09"] = " { \"mood\" : 5, \"note\" : \"keep bytes\" } "
            drafts["diarium_draft_2026-10-10"] = "{broken"
            pendingSaves["2026-10-09"] = "{\"entry\":{\"mood\":2}}"
            pendingSaves["2026-10-10"] = "not-json"
            profileFields += setOf("user_name", "birth_year")
            aiSecretPresent = true
        }
        val before = source.snapshot()
        val scanner = LegacyRecoveryScanner(source)
        val inventory = scanner.scan()
        inventory.records
            .filter { it.status == LegacyRecoveryRecordStatus.PREVIEWABLE }
            .forEach { scanner.preview(it.id) }

        assertEquals(before, source.snapshot())
    }

    private class MemoryRecoveryStorage : LegacyRecoveryStorage {
        val drafts = linkedMapOf<String, Any?>()
        val pendingSaves = linkedMapOf<String, Any?>()
        val profileFields = linkedSetOf<String>()
        var aiSecretPresent = false

        override suspend fun readDraftPreferences(): Map<String, Any?> = drafts.toMap()

        override suspend fun readPendingSavePreferences(): Map<String, Any?> = pendingSaves.toMap()

        override suspend fun readProfilePreferenceNames(): Set<String> = profileFields.toSet()

        override suspend fun legacyAiSecretStatus(): LegacySecretPresence =
            if (aiSecretPresent) LegacySecretPresence.PRESENT else LegacySecretPresence.ABSENT

        fun snapshot(): List<Any> = listOf(
            drafts.toMap(),
            pendingSaves.toMap(),
            profileFields.toSet(),
            aiSecretPresent,
        )
    }
}
