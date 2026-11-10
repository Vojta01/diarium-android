package cz.digitalnivedomi.diarium.core.data

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.time.LocalDate

/**
 * Read-only local snapshots needed to inspect ownerless beta5 data. Implementations
 * must not write, delete, migrate, replay, or fetch data from a network.
 */
interface LegacyRecoveryStorage {
    suspend fun readDraftPreferences(): Map<String, Any?>
    suspend fun readPendingSavePreferences(): Map<String, Any?>

    /** Returns names only; profile values are never passed to the scanner. */
    suspend fun readProfilePreferenceNames(): Set<String>

    /** Returns presence only; implementations must not return or log the secret. */
    suspend fun legacyAiSecretStatus(): LegacySecretPresence
}

/** Read-only adapter over the same store abstractions used by the app. */
class PreferenceLegacyRecoveryStorage(
    private val drafts: DraftStore,
    private val pendingSaves: PendingSaveStore,
    private val profile: ProfileStore,
    private val secrets: SecretSlot,
) : LegacyRecoveryStorage {
    override suspend fun readDraftPreferences(): Map<String, Any?> = drafts.legacyRecoverySnapshot()

    override suspend fun readPendingSavePreferences(): Map<String, Any?> =
        pendingSaves.legacyRecoverySnapshot()

    override suspend fun readProfilePreferenceNames(): Set<String> =
        profile.legacyRecoveryPreferenceNames()

    override suspend fun legacyAiSecretStatus(): LegacySecretPresence = try {
        secrets.legacyValueStatus()
    } catch (_: Exception) {
        LegacySecretPresence.UNKNOWN
    }

    companion object {
        /**
         * Uses each store's existing Context delegate, so a file already opened by
         * DraftStore/ProfileStore is reused rather than opened through a second
         * DataStore factory.
         */
        fun from(
            context: Context,
            secrets: SecretSlot = EncryptedSecretSlot(context),
        ): PreferenceLegacyRecoveryStorage {
            return PreferenceLegacyRecoveryStorage(
                drafts = DraftStore.from(context, ownerId = null),
                pendingSaves = PendingSaveStore.from(context, ownerId = null),
                profile = ProfileStore.from(context, ownerId = null),
                secrets = secrets,
            )
        }
    }
}

enum class LegacyRecoveryType {
    DRAFT,
    PENDING_SAVE,
}

enum class LegacyRecoveryRecordStatus {
    PREVIEWABLE,
    MALFORMED,
}

enum class LegacyProfileField {
    NAME,
    BIRTH_YEAR,
    AI_ENABLED,
    ONBOARDING_DONE,
}

/** A safe identifier built from the record type and a validated calendar date. */
data class LegacyRecoveryRecordId internal constructor(
    val type: LegacyRecoveryType,
    val date: String,
)

data class LegacyRecoveryRecord(
    val id: LegacyRecoveryRecordId,
    val status: LegacyRecoveryRecordStatus,
) {
    val type: LegacyRecoveryType get() = id.type
    val date: String get() = id.date
}

/** Constraints carried with inventory results for any future manual-recovery UI. */
class LegacyRecoveryRequirements private constructor(
    val authenticatedDestinationRequired: Boolean,
    val explicitConfirmationRequired: Boolean,
    val automaticRecoveryAllowed: Boolean,
    val cacheIsAuthoritative: Boolean,
) {
    companion object {
        val MANUAL_ONLY = LegacyRecoveryRequirements(
            authenticatedDestinationRequired = true,
            explicitConfirmationRequired = true,
            automaticRecoveryAllowed = false,
            cacheIsAuthoritative = false,
        )
    }
}

data class LegacyRecoveryInventory(
    val records: List<LegacyRecoveryRecord>,
    val profileFieldsPresent: Set<LegacyProfileField>,
    /** Presence only. UNKNOWN is distinct from a successful read that found no key. */
    val legacyAiSecretStatus: LegacySecretPresence,
    val requirements: LegacyRecoveryRequirements = LegacyRecoveryRequirements.MANUAL_ONLY,
) {
    val recordCount: Int get() = records.size
    val draftCount: Int get() = records.count { it.type == LegacyRecoveryType.DRAFT }
    val pendingSaveCount: Int get() = records.count { it.type == LegacyRecoveryType.PENDING_SAVE }
    val malformedCount: Int get() = records.count { it.status == LegacyRecoveryRecordStatus.MALFORMED }
    val legacyAiSecretPresent: Boolean?
        get() = when (legacyAiSecretStatus) {
            LegacySecretPresence.PRESENT -> true
            LegacySecretPresence.ABSENT -> false
            LegacySecretPresence.UNKNOWN -> null
        }
}

/**
 * A text-free overview of an entry. It deliberately excludes notes, activities,
 * paths, token-like strings, and all unknown JSON fields.
 */
data class LegacyEntryPreview(
    val mood: Int?,
    val sleepQuality: Int?,
    val stress: Int?,
    val activityCount: Int,
    val enabledHabitCount: Int,
    val nonEmptyGratitudeSlotCount: Int,
    val noteCharacterCount: Int,
    /** Presence only; the stored path is never returned or opened. */
    val hasPhotoReference: Boolean,
    val scaleValueCount: Int,
)

/**
 * Inventories ownerless, date-keyed legacy records without changing them. A scan
 * returns only type/date/status and counts. Call [preview] separately after an
 * explicit caller action to get a text-free summary for one valid entry.
 *
 * This scanner does not inspect the shared entry cache: cache data is not an
 * authoritative recovery source. It has no restore/replay/delete operation.
 */
class LegacyRecoveryScanner(private val storage: LegacyRecoveryStorage) {

    suspend fun scan(): LegacyRecoveryInventory {
        val drafts = storage.readDraftPreferences()
        val pendingSaves = storage.readPendingSavePreferences()
        val profileNames = storage.readProfilePreferenceNames()
        val records = buildList {
            drafts.forEach { (key, value) ->
                val date = legacyDraftDateFromKey(key) ?: return@forEach
                add(record(LegacyRecoveryType.DRAFT, date, value))
            }
            pendingSaves.forEach { (key, value) ->
                val date = validLegacyDate(key) ?: return@forEach
                add(record(LegacyRecoveryType.PENDING_SAVE, date, value))
            }
        }.sortedWith(compareBy<LegacyRecoveryRecord>({ it.date }, { it.type.ordinal }))

        val profileFields = profileNames.mapNotNullTo(mutableSetOf()) { PROFILE_FIELD_BY_KEY[it] }
        return LegacyRecoveryInventory(
            records = records,
            profileFieldsPresent = profileFields,
            legacyAiSecretStatus = storage.legacyAiSecretStatus(),
        )
    }

    /**
     * Explicit read-only preview request for one scanner-issued id. Only validated
     * local preferences are read; no stored path is ever interpreted as a file.
     */
    suspend fun preview(id: LegacyRecoveryRecordId): LegacyEntryPreview? {
        val date = validLegacyDate(id.date) ?: return null
        val key = when (id.type) {
            LegacyRecoveryType.DRAFT -> LEGACY_DRAFT_PREFIX + date
            LegacyRecoveryType.PENDING_SAVE -> date
        }
        val preferences = when (id.type) {
            LegacyRecoveryType.DRAFT -> storage.readDraftPreferences()
            LegacyRecoveryType.PENDING_SAVE -> storage.readPendingSavePreferences()
        }
        val payload = entryPayload(preferences[key], id.type) ?: return null
        return payload.toSafePreview()
    }

    private fun record(type: LegacyRecoveryType, date: String, raw: Any?): LegacyRecoveryRecord {
        val status = if (entryPayload(raw, type) == null) {
            LegacyRecoveryRecordStatus.MALFORMED
        } else {
            LegacyRecoveryRecordStatus.PREVIEWABLE
        }
        return LegacyRecoveryRecord(LegacyRecoveryRecordId(type, date), status)
    }

    private fun entryPayload(raw: Any?, type: LegacyRecoveryType): JSONObject? {
        val text = raw as? String ?: return null
        val root = try {
            JSONObject(text)
        } catch (_: Exception) {
            return null
        }
        val candidate = when (type) {
            LegacyRecoveryType.DRAFT -> root.optJSONObject("entry") ?: root.optJSONObject("data") ?: root
            LegacyRecoveryType.PENDING_SAVE -> root.optJSONObject("entry") ?: return null
        }
        return candidate.takeIf { entryFields.any(it::has) }
    }

    private fun JSONObject.toSafePreview(): LegacyEntryPreview {
        val habits = optJSONObject("habits")
        val enabledHabitCount = habits?.keys()?.asSequence()
            ?.count { habits.opt(it) == true }
            ?: 0
        val gratitude = optJSONArray("gratitude")
        val nonEmptyGratitudeCount = gratitude?.indices()?.count { index ->
            (gratitude.opt(index) as? String)?.isNotBlank() == true
        } ?: 0
        val photoPath = opt("photoPath") as? String
        val pathToDelete = opt("photoPathToDelete") as? String

        return LegacyEntryPreview(
            mood = safeRating("mood"),
            sleepQuality = safeRating("sleepQuality"),
            stress = safeRating("stress"),
            activityCount = optJSONArray("activities")?.length() ?: 0,
            enabledHabitCount = enabledHabitCount,
            nonEmptyGratitudeSlotCount = nonEmptyGratitudeCount,
            noteCharacterCount = (opt("note") as? String)?.length ?: 0,
            hasPhotoReference = !photoPath.isNullOrBlank() || !pathToDelete.isNullOrBlank() || optBoolean("clearPhoto"),
            scaleValueCount = optJSONObject("scaleValues")?.length() ?: 0,
        )
    }

    private fun JSONObject.safeRating(key: String): Int? =
        (opt(key) as? Number)?.toInt()?.takeIf { it in 0..5 }

    companion object {
        /** Production adapter; reads the app's stores without creating parallel DataStores. */
        fun from(
            context: Context,
            secrets: SecretSlot = EncryptedSecretSlot(context),
        ): LegacyRecoveryScanner = LegacyRecoveryScanner(
            PreferenceLegacyRecoveryStorage.from(context, secrets),
        )

        const val DRAFTS_DATASTORE_NAME = "diarium_drafts"
        const val PENDING_SAVES_PREFS_NAME = PendingSaveStore.PREFS_NAME
        const val PROFILE_DATASTORE_NAME = "diarium_profile"
        const val AI_SECRETS_PREFS_NAME = AiKeyStore.FILE_NAME
        const val LEGACY_AI_SECRET_KEY = AiKeyStore.KEY

        private val entryFields = setOf(
            "mood",
            "moodEmoji",
            "sleepQuality",
            "stress",
            "activities",
            "habits",
            "gratitude",
            "note",
            "photoPath",
            "clearPhoto",
            "photoPathToDelete",
            "scaleValues",
            "weather",
            "clearedScaleIds",
        )
        private val PROFILE_FIELD_BY_KEY = mapOf(
            "user_name" to LegacyProfileField.NAME,
            "birth_year" to LegacyProfileField.BIRTH_YEAR,
            "ai_enabled" to LegacyProfileField.AI_ENABLED,
            "onboarding_done" to LegacyProfileField.ONBOARDING_DONE,
        )
    }
}

private const val LEGACY_DRAFT_PREFIX = "diarium_draft_"
private val ISO_LEGACY_DATE = Regex("\\d{4}-\\d{2}-\\d{2}")

private fun legacyDraftDateFromKey(key: String): String? {
    if (!key.startsWith(LEGACY_DRAFT_PREFIX)) return null
    return validLegacyDate(key.removePrefix(LEGACY_DRAFT_PREFIX))
}

private fun validLegacyDate(value: String): String? {
    if (!ISO_LEGACY_DATE.matches(value)) return null
    return runCatching { LocalDate.parse(value) }.getOrNull()?.toString()
}

private fun JSONArray.indices(): IntRange = 0 until length()
