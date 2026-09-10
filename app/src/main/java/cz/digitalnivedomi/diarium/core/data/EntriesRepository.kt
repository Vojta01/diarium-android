package cz.digitalnivedomi.diarium.core.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

/**
 * Reads and writes the `entries` table through the server-side RPCs — the same
 * contract the web app's `/api/save-entry` uses.
 *
 * Why the RPC rather than a plain PostgREST upsert: `save_daily_entry(jsonb)`
 * owns the conflict target (`user_id, date`), the `scale_entries` mirroring and
 * the "only overwrite a column when the payload actually carries it" rule.
 * Re-implementing that in Kotlin would drift from the web the first time the SQL
 * changes.
 */
class EntriesRepository(
    private val client: SupabaseClient,
    private val session: SessionContext = SessionContext(),
) {

    suspend fun currentUserId(): String? = session.userId()

    /** Loads the stored entry for [date], or null when that day has none yet. */
    suspend fun loadEntry(date: String): DiaryEntry? = withContext(Dispatchers.IO) {
        val userId = session.userId() ?: return@withContext null
        val resp = client.get(
            "entries",
            mapOf(
                "user_id" to "eq.$userId",
                "date" to "eq.$date",
                "select" to "*",
                "limit" to "1",
            ),
        )
        if (!resp.isSuccessful) return@withContext null
        val rows = resp.asJsonArray() ?: return@withContext null
        if (rows.length() == 0) return@withContext null
        fromRow(rows.getJSONObject(0))
    }

    /**
     * Upserts the day's check-in via `save_daily_entry`, then fires
     * `check_achievements`. Re-saving the same date updates in place, because the
     * RPC's conflict target is `(user_id, date)`.
     */
    suspend fun save(entry: DiaryEntry, date: String): Result<Unit> =
        withContext(Dispatchers.IO) {
            val userId = session.userId()
                ?: return@withContext Result.failure(IllegalStateException("Přihlášení vypršelo, přihlas se znovu."))
            val payload = buildPayload(entry, userId, date)
            val resp = client.post("rpc/save_daily_entry", JSONObject().put("p_payload", payload))
            if (!resp.isSuccessful) {
                return@withContext Result.failure(
                    IllegalStateException(resp.errorMessage ?: "Uložení se nezdařilo (${resp.code})"),
                )
            }
            // Achievements are a side effect — never lose the entry over them.
            runCatching { client.post("rpc/check_achievements", buildAchievementsPayload(entry, userId)) }
            Result.success(Unit)
        }

    /**
     * Uploads the day's photo to the public `diary-photos` bucket at
     * `{uid}/{date}.jpg` (upsert) and returns its public URL, or null on failure.
     */
    suspend fun uploadPhoto(date: String, bytes: ByteArray): String? = withContext(Dispatchers.IO) {
        val userId = session.userId() ?: return@withContext null
        val path = "$userId/$date.jpg"
        val resp = client.uploadObject("diary-photos/$path", bytes, "image/jpeg", upsert = true)
        if (!resp.isSuccessful) return@withContext null
        client.publicStorageUrl("diary-photos/$path")
    }

    companion object {

        /**
         * Maps a [DiaryEntry] onto the exact `save_daily_entry(jsonb)` payload.
         *
         * Fields the form does not own (`phone_screen_time`, `phone_unlocks`,
         * `phone_top_apps`) are deliberately omitted: the RPC assigns `excluded.*`
         * on conflict for any key present in the payload, so sending them as
         * `null` would wipe the screen-time worker's data.
         *
         * `ai_reflection` is the same rule pointing the other way — it travels
         * only when the day already has one, so an ordinary form save (which
         * always starts from an entry loaded without a reflection, or from an
         * empty one) can never blank out what the AI endpoint stored. That is the
         * behaviour of `src/app/api/save-entry/route.ts` on the web side.
         */
        fun buildPayload(entry: DiaryEntry, userId: String, date: String): JSONObject =
            JSONObject().apply {
                put("user_id", userId)
                put("date", date)
                put("mood", entry.mood)
                put("mood_emoji", entry.moodEmoji)
                put("sleep_quality", entry.sleepQuality)
                put("stress", entry.stress)
                put("activities", JSONArray(entry.activities))
                put("habits", JSONObject(entry.habits))
                put("gratitude", JSONArray(entry.filteredGratitude()))
                put("note", entry.note)
                put("weather", JSONArray(entry.weather))
                put("scale_values", JSONObject(entry.positiveScaleValues()))
                entry.aiReflection?.takeIf { it.isNotBlank() }?.let { put("ai_reflection", it) }
                entry.photoPath?.takeIf { it.isNotBlank() }?.let { put("photo_path", it) }
            }

        /** `check_achievements(user_id, has_photo, has_scale)` — the post-save hook. */
        fun buildAchievementsPayload(entry: DiaryEntry, userId: String): JSONObject =
            JSONObject().apply {
                put("p_user_id", userId)
                put("p_has_photo", !entry.photoPath.isNullOrBlank())
                put("p_has_scale", entry.positiveScaleValues().isNotEmpty())
            }

        /** Maps an `entries` row back onto the form model. */
        fun fromRow(row: JSONObject): DiaryEntry = DiaryEntry(
            mood = row.optInt("mood", 0),
            moodEmoji = row.optString("mood_emoji"),
            sleepQuality = row.optInt("sleep_quality", 0),
            stress = row.optInt("stress", 0),
            activities = row.optJSONArray("activities").stringList(),
            habits = row.optJSONObject("habits").booleanMap(),
            gratitude = row.optJSONArray("gratitude").slotList(DiaryEntry.GRATITUDE_SLOTS),
            note = row.optString("note"),
            photoPath = row.optString("photo_path").takeIf { it.isNotBlank() },
            scaleValues = row.optJSONObject("scale_values").intMap(),
            weather = row.optJSONArray("weather").stringList(),
            phoneScreenTime = row.optInt("phone_screen_time", -1).takeIf { it >= 0 },
            phoneUnlocks = row.optInt("phone_unlocks", -1).takeIf { it >= 0 },
            phoneTopApps = row.optJSONArray("phone_top_apps").topApps(),
            aiReflection = row.optString("ai_reflection").takeIf { it.isNotBlank() },
        )
    }
}
