package cz.digitalnivedomi.diarium.core.data

import cz.digitalnivedomi.diarium.core.achievements.ACHIEVEMENTS
import cz.digitalnivedomi.diarium.core.achievements.AchievementEntry
import cz.digitalnivedomi.diarium.core.achievements.AchievementGoal
import cz.digitalnivedomi.diarium.core.achievements.AchievementStatus
import cz.digitalnivedomi.diarium.core.achievements.AchievementsData
import cz.digitalnivedomi.diarium.core.achievements.achievementsData
import cz.digitalnivedomi.diarium.core.achievements.computeProgress
import cz.digitalnivedomi.diarium.core.achievements.mergeProgress
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.time.LocalDate
import java.time.OffsetDateTime

/**
 * The panel could not be loaded. Thrown by [AchievementsRepository.load] so the
 * screen shows its retry state instead of an all-zero grid that looks exactly
 * like "you have earned nothing" — the same "no silent empty screen" rule the
 * other repositories follow.
 */
class AchievementsLoadException(message: String) : Exception(message)

/**
 * Panel order: earned badges first, then the rest, and each group keeps the
 * catalogue order it already has (`sortedBy` is stable). The pure catalogue in
 * `Achievements.kt` stays the source of truth; only the surface order changes so
 * the badges the user has already won lead the grid.
 */
fun sortForPanel(statuses: List<AchievementStatus>): List<AchievementStatus> =
    statuses.sortedBy { if (it.unlocked) 0 else 1 }

/**
 * Read-only access to the achievements panel for the signed-in user.
 *
 * Mirrors the web's `getAchievements` + `syncAchievements` (`src/lib/achievements.ts`):
 * it reads the stored `achievements` rows, recomputes every badge from the real
 * `entries` and `goals` data with the pure helpers in `core/achievements`, merges
 * the two monotonically (`greatest(stored, computed)`, so an earned badge never
 * regresses) and — because the web does — writes back a newly unlocked row best
 * effort. The panel is built from the 17 definitions in `ACHIEVEMENTS`; a stored
 * row whose key has no definition (the web's stale `use_template` row) is ignored.
 *
 * Every request carries only the anon key from [SupabaseClient]'s BuildConfig plus
 * the signed-in user's own JWT ([SessionContext] → [SessionStore.validAccessToken]);
 * no server-side key is ever needed or sent.
 *
 * ── Entries window (do NOT copy the web's `.limit(10000)`) ───────────────────
 * The web asks for up to 10000 `entries` rows, but the project's PostgREST
 * `db-max-rows` caps every response at 1000 rows, so the web silently under-counts
 * streaks and totals for anyone past a thousand entries. This repository instead
 * bounds the query by date, not by a row limit: only entries from the last
 * [WINDOW_DAYS] days are fetched. The largest catalogue target is 365 days
 * (`streak_365`/`entries_365`), so 400 days is enough for every badge while a
 * one-row-per-day table keeps the response at ≤ 400 rows — comfortably under the
 * 1000-row cap, so nothing is ever truncated and no paging is needed.
 */
class AchievementsRepository(
    private val client: SupabaseClient,
    private val session: SessionContext = SessionContext(),
    /** Injectable "today" so the window is deterministic in tests. */
    private val today: () -> LocalDate = { LocalDate.now() },
) {

    /**
     * Loads the whole panel. Throws [AchievementsLoadException] when a query fails
     * (so the UI can offer a retry); returns an empty catalogue when signed out.
     */
    suspend fun load(): AchievementsData = withContext(Dispatchers.IO) {
        val userId = session.userId() ?: return@withContext achievementsData(emptyMap())

        val stored = readStored(userId)
        val entries = readEntries(userId)
        val goals = readGoals(userId)

        val computed = computeProgress(entries, goals)
        val merged = mergeProgress(computed, stored.associate { it.key to it.progress })
        val unlockDates = unlockDates(stored, merged)

        // Web parity: persist the true progress / unlock stamp, best effort.
        runCatching { syncUnlocks(userId, stored, merged, unlockDates) }

        val panel = achievementsData(merged, ACHIEVEMENTS, unlockDates)
        AchievementsData(sortForPanel(panel.statuses))
    }

    // ── reads ────────────────────────────────────────────────────────────────

    /** Stored `achievements` rows: id, key, progress and the unlock timestamp. */
    private fun readStored(userId: String): List<StoredAchievement> {
        val resp = client.get(
            "achievements",
            mapOf(
                "user_id" to "eq.$userId",
                "select" to "id,achievement_key,progress,unlocked_at",
            ),
        )
        val array = resp.jsonArray("odznaky") ?: return emptyList()
        return (0 until array.length()).mapNotNull { i ->
            val row = array.optJSONObject(i) ?: return@mapNotNull null
            val key = row.str("achievement_key")
            if (key.isBlank()) return@mapNotNull null
            StoredAchievement(
                id = row.str("id"),
                key = key,
                progress = row.optInt("progress", 0),
                unlockedAt = row.strOrNull("unlocked_at"),
            )
        }
    }

    /**
     * The window's `entries` rows, reduced to what the pure helpers read. Only the
     * six columns the domain needs are selected — `photo_path` and `scale_values`
     * feed `add_photo`/`use_scale`, `mood`/`activities`/`created_at` the rest —
     * and no `order=`: `computeProgress` sorts by date itself, so a column rename
     * can never turn into a 400 here.
     */
    private fun readEntries(userId: String): List<AchievementEntry> {
        val since = today().minusDays(WINDOW_DAYS.toLong()).toString()
        val resp = client.get(
            "entries",
            mapOf(
                "user_id" to "eq.$userId",
                "select" to "date,mood,created_at,photo_path,scale_values,activities",
                "date" to "gte.$since",
            ),
        )
        val array = resp.jsonArray("záznamy") ?: return emptyList()
        return (0 until array.length()).mapNotNull { i ->
            array.optJSONObject(i)?.let(::achievementEntryFromRow)
        }
    }

    /** The `goals` rows `complete_goal`/`create_goal` need. */
    private fun readGoals(userId: String): List<AchievementGoal> {
        val resp = client.get(
            "goals",
            mapOf(
                "user_id" to "eq.$userId",
                "select" to "id,completed_at,activity_key,target_count,frequency",
            ),
        )
        val array = resp.jsonArray("cíle") ?: return emptyList()
        return (0 until array.length()).mapNotNull { i ->
            array.optJSONObject(i)?.let(::achievementGoalFromRow)
        }
    }

    // ── unlock bookkeeping ───────────────────────────────────────────────────

    /**
     * `achievement_key` → the date to show. A stored stamp wins; a badge that is
     * unlocked now but has no stored row gets the stamp we are about to write, so
     * the grid can show "Odemčeno <date>" on this very load.
     */
    private fun unlockDates(
        stored: List<StoredAchievement>,
        merged: Map<String, Int>,
    ): Map<String, String?> {
        val byKey = stored.associateBy { it.key }
        val now = OffsetDateTime.now().toString()
        val out = LinkedHashMap<String, String?>()
        for (def in ACHIEVEMENTS) {
            val progressNow = merged[def.key] ?: 0
            out[def.key] = when {
                byKey[def.key]?.unlockedAt != null -> byKey[def.key]!!.unlockedAt
                progressNow >= def.target -> now
                else -> null
            }
        }
        return out
    }

    /**
     * Writes the merged state back, the way the web's `syncAchievements` does:
     * insert a row for a badge unlocked with none stored, stamp `unlocked_at` on a
     * stored row that unlocks now, and keep the progress column monotonic. Entirely
     * best effort — the caller wraps it in `runCatching`, a failed write only means
     * the date is recomputed on the next load, never a broken panel.
     */
    private fun syncUnlocks(
        userId: String,
        stored: List<StoredAchievement>,
        merged: Map<String, Int>,
        unlockDates: Map<String, String?>,
    ) {
        val byKey = stored.associateBy { it.key }
        for (def in ACHIEVEMENTS) {
            val progressNow = merged[def.key] ?: 0
            if (progressNow < def.target) continue
            val existing = byKey[def.key]
            when {
                existing == null -> client.post(
                    "achievements",
                    JSONObject().apply {
                        put("user_id", userId)
                        put("achievement_key", def.key)
                        put("progress", progressNow)
                        put("target", def.target)
                        put("unlocked_at", unlockDates[def.key])
                    },
                    extraHeaders = mapOf("Prefer" to "resolution=merge-duplicates"),
                )

                existing.unlockedAt == null -> client.patch(
                    "achievements",
                    JSONObject().apply {
                        put("progress", progressNow)
                        put("unlocked_at", unlockDates[def.key])
                    },
                    mapOf("id" to "eq.${existing.id}"),
                )

                progressNow > existing.progress -> client.patch(
                    "achievements",
                    JSONObject().put("progress", progressNow),
                    mapOf("id" to "eq.${existing.id}"),
                )
            }
        }
    }

    private data class StoredAchievement(
        val id: String,
        val key: String,
        val progress: Int,
        val unlockedAt: String?,
    )

    private companion object {
        /**
         * 400 days: covers the largest target (365) with slack, and a
         * one-row-per-day table keeps the response at ≤ 400 rows, well under the
         * 1000-row `db-max-rows` cap that silently truncates the web's query.
         */
        const val WINDOW_DAYS = 400
    }
}

// ── Row mapping (pure, so a plain JVM test can exercise it) ──────────────────

/** `entries` row → the reduced [AchievementEntry] the pure helpers consume. */
internal fun achievementEntryFromRow(row: JSONObject): AchievementEntry = AchievementEntry(
    date = row.str("date"),
    mood = row.optInt("mood", 0),
    createdAt = row.strOrNull("created_at"),
    hasPhoto = row.strOrNull("photo_path") != null,
    scaleValues = parseScaleValues(row),
    activities = parseActivities(row.optJSONArray("activities")),
)

/** `goals` row → the reduced [AchievementGoal] `complete_goal` needs. */
internal fun achievementGoalFromRow(row: JSONObject): AchievementGoal = AchievementGoal(
    activityKey = row.str("activity_key"),
    targetCount = row.optInt("target_count", 1).coerceAtLeast(1),
    frequency = row.str("frequency").ifBlank { "weekly" },
)

/**
 * `scale_values` is a JSON object (`{ "<scale id>": 3 }`). Never trust it to be an
 * object — a legacy row may hold the JSON as a string — and drop zero/non-numeric
 * values, since only a value above zero satisfies `use_scale` ([hasPositiveScale]).
 */
private fun parseScaleValues(row: JSONObject): Map<String, Int> {
    val obj = row.optJSONObject("scale_values")
        ?: row.strOrNull("scale_values")?.let { raw ->
            runCatching { JSONObject(raw) }.getOrNull()
        }
        ?: return emptyMap()
    val out = LinkedHashMap<String, Int>()
    val keys = obj.keys()
    while (keys.hasNext()) {
        val key = keys.next()
        val value = when (val v = obj.opt(key)) {
            is Number -> v.toInt()
            is String -> v.toIntOrNull() ?: 0
            else -> 0
        }
        if (value != 0) out[key] = value
    }
    return out
}

/**
 * `activities` is an array of activity keys, but tolerate rows that store objects
 * (`{ "key": ... }`) so a shape change cannot silently break `complete_goal`.
 */
private fun parseActivities(array: JSONArray?): List<String> {
    if (array == null) return emptyList()
    val out = ArrayList<String>(array.length())
    for (i in 0 until array.length()) {
        when (val value = array.opt(i)) {
            is String -> if (value.isNotBlank()) out.add(value)
            is JSONObject -> {
                val key = value.str("key")
                if (key.isNotBlank()) out.add(key)
            }
        }
    }
    return out
}

/** PostgREST JSON never returns a real Kotlin null; `isNull` covers absent + JSON null. */
private fun JSONObject.str(key: String): String =
    if (isNull(key)) "" else optString(key, "")

private fun JSONObject.strOrNull(key: String): String? =
    if (isNull(key)) null else optString(key, "").takeIf { it.isNotBlank() }

/**
 * Turns a failed response into [AchievementsLoadException]; a successful but
 * empty/non-array body yields null (an empty table is not an error).
 */
private fun HttpResponse.jsonArray(what: String): JSONArray? {
    if (!isSuccessful) {
        throw AchievementsLoadException(
            errorMessage ?: "Načtení ($what) selhalo (HTTP $code).",
        )
    }
    return asJsonArray()
}
