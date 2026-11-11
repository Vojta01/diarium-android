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
    UNSUPPORTED,
}

enum class LegacyProfileField {
    NAME,
    BIRTH_YEAR,
    AI_ENABLED,
    ONBOARDING_DONE,
}

/** A safe identifier built from the record type and a validated calendar date. */
class LegacyRecoveryRecordId internal constructor(
    val type: LegacyRecoveryType,
    val date: String,
) {
    override fun equals(other: Any?): Boolean =
        other is LegacyRecoveryRecordId && type == other.type && date == other.date

    override fun hashCode(): Int = 31 * type.hashCode() + date.hashCode()

    override fun toString(): String = "LegacyRecoveryRecordId(type=$type, date=$date)"
}

data class LegacyRecoveryRecord(
    val id: LegacyRecoveryRecordId,
    val status: LegacyRecoveryRecordStatus,
) {
    val type: LegacyRecoveryType get() = id.type
    val date: String get() = id.date
}

/** Constraints carried with inventory results for the manual-recovery UI. */
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
    val unsupportedCount: Int get() = records.count { it.status == LegacyRecoveryRecordStatus.UNSUPPORTED }
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

/** Sensitive diary payload. Keep it out of logs and expose it only after attestation. */
class LegacyRecoveryPayload internal constructor(
    internal val sourceRaw: String,
    val entry: DiaryEntry,
    internal val safePreview: LegacyEntryPreview,
)

/** An authorized, on-device view of one selected legacy entry. */
class LegacyRecoveryContents internal constructor(
    val id: LegacyRecoveryRecordId,
    val entry: DiaryEntry,
)

/**
 * Inventories ownerless, date-keyed legacy records without changing them. A scan
 * returns only type/date/status and counts. The coordinator exposes a selected
 * record's summary or contents only after explicit attestation.
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
    internal suspend fun preview(id: LegacyRecoveryRecordId): LegacyEntryPreview? =
        readPayload(id)?.safePreview

    /**
     * Returns diary fields only for the manual, attested recovery path. The raw
     * source string is retained privately so a later copy can detect source changes.
     */
    internal suspend fun readPayload(id: LegacyRecoveryRecordId): LegacyRecoveryPayload? {
        val date = validLegacyDate(id.date) ?: return null
        val key = when (id.type) {
            LegacyRecoveryType.DRAFT -> LEGACY_DRAFT_PREFIX + date
            LegacyRecoveryType.PENDING_SAVE -> date
        }
        val preferences = when (id.type) {
            LegacyRecoveryType.DRAFT -> storage.readDraftPreferences()
            LegacyRecoveryType.PENDING_SAVE -> storage.readPendingSavePreferences()
        }
        val raw = preferences[key] as? String ?: return null
        val parsed = parseEntry(raw, id.type) as? EntryParse.Supported ?: return null
        return LegacyRecoveryPayload(raw, parsed.entry, parsed.json.toSafePreview())
    }

    private fun record(type: LegacyRecoveryType, date: String, raw: Any?): LegacyRecoveryRecord {
        val status = when (parseEntry(raw, type)) {
            is EntryParse.Supported -> LegacyRecoveryRecordStatus.PREVIEWABLE
            EntryParse.Malformed -> LegacyRecoveryRecordStatus.MALFORMED
            EntryParse.Unsupported -> LegacyRecoveryRecordStatus.UNSUPPORTED
        }
        return LegacyRecoveryRecord(LegacyRecoveryRecordId(type, date), status)
    }

    private fun parseEntry(raw: Any?, type: LegacyRecoveryType): EntryParse {
        val text = raw as? String ?: return EntryParse.Malformed
        val root = try {
            JSONObject(text)
        } catch (_: Exception) {
            return EntryParse.Malformed
        }
        val candidate: JSONObject
        val wrapped: Boolean
        when (type) {
            LegacyRecoveryType.DRAFT -> when {
                root.has("entry") -> {
                    candidate = root.optJSONObject("entry") ?: return EntryParse.Malformed
                    wrapped = true
                }
                root.has("data") -> {
                    candidate = root.optJSONObject("data") ?: return EntryParse.Malformed
                    wrapped = true
                }
                else -> {
                    candidate = root
                    wrapped = false
                }
            }
            LegacyRecoveryType.PENDING_SAVE -> {
                candidate = root.optJSONObject("entry") ?: return EntryParse.Malformed
                wrapped = true
            }
        }
        if (wrapped) {
            val allowedEnvelopeFields = when (type) {
                LegacyRecoveryType.DRAFT -> draftEnvelopeFields
                LegacyRecoveryType.PENDING_SAVE -> pendingSaveEnvelopeFields
            }
            if (root.keys().asSequence().any { it !in allowedEnvelopeFields }) return EntryParse.Unsupported
        }
        val keys = candidate.keys().asSequence().toSet()
        if (keys.isEmpty()) return EntryParse.Malformed
        if (keys.any { it !in entryFields } || !hasSupportedFieldTypes(candidate)) {
            return EntryParse.Unsupported
        }
        return EntryParse.Supported(candidate, DiaryEntry.fromJson(candidate))
    }

    private fun hasSupportedFieldTypes(json: JSONObject): Boolean {
        for (field in listOf("mood", "sleepQuality", "stress")) {
            if (json.has(field) && !json.opt(field).isIntegerInRange(0..5)) return false
        }
        for (field in listOf("moodEmoji", "note")) {
            if (json.has(field) && json.opt(field) !is String) return false
        }
        for (field in listOf("photoPath", "photoPathToDelete")) {
            if (json.has(field) && json.opt(field) !is String && json.opt(field) != JSONObject.NULL) return false
        }
        if (json.has("clearPhoto") && json.opt("clearPhoto") !is Boolean) return false
        for (field in listOf("activities", "weather", "clearedScaleIds", "gratitude")) {
            if (!json.has(field)) continue
            val values = json.optJSONArray(field) ?: return false
            if ((0 until values.length()).any { values.opt(it) !is String }) return false
            if (field == "gratitude" && values.length() > DiaryEntry.GRATITUDE_SLOTS) return false
        }
        if (json.has("habits")) {
            val habits = json.optJSONObject("habits") ?: return false
            if (habits.keys().asSequence().any { habits.opt(it) !is Boolean }) return false
        }
        if (json.has("scaleValues")) {
            val scales = json.optJSONObject("scaleValues") ?: return false
            if (scales.keys().asSequence().any { !scales.opt(it).isIntegerInRange(Int.MIN_VALUE..Int.MAX_VALUE) }) {
                return false
            }
        }
        return true
    }

    private fun Any?.isIntegerInRange(range: IntRange): Boolean {
        val number = this as? Number ?: return false
        val value = number.toDouble()
        return value.isFinite() && value == value.toLong().toDouble() && value.toLong() in range.first.toLong()..range.last.toLong()
    }

    private sealed interface EntryParse {
        data class Supported(val json: JSONObject, val entry: DiaryEntry) : EntryParse
        data object Malformed : EntryParse
        data object Unsupported : EntryParse
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

        private val draftEnvelopeFields = setOf(
            "entry",
            "data",
            "updatedAt",
            "baselineUpdatedAt",
            "baselineExists",
            "baselineId",
            "baselineRevision",
        )
        private val pendingSaveEnvelopeFields = setOf(
            "entry",
            "queuedAt",
            "transactionId",
            "baselineUpdatedAt",
            "baselineExists",
            "baselineId",
            "baselineRevision",
        )
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
