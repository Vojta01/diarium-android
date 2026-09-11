package cz.digitalnivedomi.diarium.core.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.LocalDate
import java.util.Locale

/**
 * Read/write access to the user's `scales` rows and their `scale_entries`.
 *
 * This is the *management* side of scales (create / edit / delete / seed), the
 * counterpart of the read-only picker in [PickersRepository.scales]. Both read
 * the same table under the signed-in user's own JWT — neither ever needs a
 * server-side key.
 *
 * Conventions copied from [PickersRepository] on purpose:
 *  - every call runs on [Dispatchers.IO];
 *  - a failed query returns an empty list rather than throwing, so a flaky
 *    network can never leave the screen unusable;
 *  - ordering and date filtering happen **client-side**, so a column rename or
 *    an unsupported operator can never turn into a 400 from PostgREST;
 *  - writes are scoped with `user_id=eq.<jwt sub>` so one user can never touch
 *    another user's rows.
 *
 * Deleting a scale removes only the `scales` row here; its `scale_entries` are
 * cleaned up by the database's cascading foreign key, exactly as the web relies
 * on.
 */
class ScalesRepository(
    private val client: SupabaseClient,
    private val session: SessionContext = SessionContext(),
) {

    /** The user's scales, ordered by `sort_order` (ties keep the fetch order). */
    suspend fun listScales(): List<Scale> = withContext(Dispatchers.IO) {
        val userId = session.userId() ?: return@withContext emptyList()
        read("scales", mapOf("user_id" to "eq.$userId"))
            .mapNotNull { row -> row.toScale() }
            .sortedBy { it.sortOrder }
    }

    /** Inserts a new scale. Returns false when signed out or the write failed. */
    suspend fun createScale(draft: ScaleDraft): Boolean = withContext(Dispatchers.IO) {
        val userId = session.userId() ?: return@withContext false
        val body = draft.toJson().apply { put("user_id", userId) }
        client.post("scales", body).isSuccessful
    }

    /** Partial update of one scale, scoped to the signed-in user. */
    suspend fun updateScale(id: String, draft: ScaleDraft): Boolean = withContext(Dispatchers.IO) {
        val userId = session.userId() ?: return@withContext false
        client.patch(
            "scales",
            draft.toJson(),
            mapOf("user_id" to "eq.$userId", "id" to "eq.$id"),
        ).isSuccessful
    }

    /** Removes a scale and (via the FK cascade) its entries. */
    suspend fun deleteScale(id: String): Boolean = withContext(Dispatchers.IO) {
        val userId = session.userId() ?: return@withContext false
        client.delete(
            "scales",
            mapOf("user_id" to "eq.$userId", "id" to "eq.$id"),
        ).isSuccessful
    }

    /**
     * Entries for one scale in `[from, to]` (inclusive), ascending by date. The
     * date window is applied client-side on the ISO `date` text, so the query
     * itself never depends on a PostgREST operator.
     */
    suspend fun entries(scaleId: String, from: LocalDate, to: LocalDate): List<ScaleEntry> =
        withContext(Dispatchers.IO) {
            val userId = session.userId() ?: return@withContext emptyList()
            if (scaleId.isBlank()) return@withContext emptyList()
            read("scale_entries", mapOf("user_id" to "eq.$userId", "scale_id" to "eq.$scaleId"))
                .mapNotNull { row ->
                    val date = row.plainString("date").takeIf { it.isNotBlank() } ?: return@mapNotNull null
                    val value = row.optInt("value", 0)
                    if (date < from.toString() || date > to.toString()) return@mapNotNull null
                    ScaleEntry(
                        id = row.plainString("id"),
                        scaleId = row.plainString("scale_id").ifBlank { scaleId },
                        date = date,
                        value = value,
                    )
                }
                .sortedBy { it.date }
        }

    /**
     * 30-day distribution + average for every scale in [scales], in one query.
     * Values of 0 (the check-in's "not filled" marker) are not entries and are
     * therefore not counted.
     */
    suspend fun statsFor(
        scales: List<Scale>,
        days: Int = DEFAULT_WINDOW_DAYS,
        today: LocalDate = LocalDate.now(),
    ): Map<String, ScaleStats> = withContext(Dispatchers.IO) {
        if (scales.isEmpty()) return@withContext emptyMap()
        val userId = session.userId() ?: return@withContext emptyMap()
        val from = windowStart(today, days).toString()
        val to = today.toString()

        val byScale: Map<String, List<Int>> = read("scale_entries", mapOf("user_id" to "eq.$userId"))
            .mapNotNull { row ->
                val scaleId = row.plainString("scale_id").takeIf { it.isNotBlank() } ?: return@mapNotNull null
                val date = row.plainString("date").takeIf { it.isNotBlank() } ?: return@mapNotNull null
                if (date < from || date > to) return@mapNotNull null
                val value = row.optInt("value", 0)
                if (value <= 0) return@mapNotNull null
                scaleId to value
            }
            .groupBy({ it.first }, { it.second })

        scales.associate { scale ->
            val values = byScale[scale.id].orEmpty()
            scale.id to ScaleStats(distribution(values), average(values), values.size)
        }
    }

    /** Convenience: stats for a single scale. */
    suspend fun stats(
        scale: Scale,
        days: Int = DEFAULT_WINDOW_DAYS,
        today: LocalDate = LocalDate.now(),
    ): ScaleStats {
        val values = entries(scale.id, windowStart(today, days), today).map { it.value }.filter { it > 0 }
        return ScaleStats(distribution(values), average(values), values.size)
    }

    /**
     * Upserts today's (or any date's) value for a scale — the web's
     * `scale_entries` write, keyed on `(user_id, scale_id, date)`.
     */
    suspend fun saveEntry(scaleId: String, date: LocalDate, value: Int): Boolean =
        withContext(Dispatchers.IO) {
            val userId = session.userId() ?: return@withContext false
            if (scaleId.isBlank()) return@withContext false
            val body = JSONObject().apply {
                put("user_id", userId)
                put("scale_id", scaleId)
                put("date", date.toString())
                put("value", value)
            }
            client.post(
                "scale_entries",
                body,
                extraHeaders = mapOf("Prefer" to "resolution=merge-duplicates"),
            ).isSuccessful
        }

    /**
     * Web-parity seeding of the two default scales.
     *
     * The decision of *what* to write is the pure [planSeed] function over the
     * current rows; this only executes it: delete every row the plan rejects
     * (renamed/misspelled, duplicated, or carrying a range that was edited away
     * from the default), then insert every default the plan says is missing.
     * Returns false when signed out or when any single write failed.
     */
    suspend fun seedDefaults(): Boolean = withContext(Dispatchers.IO) {
        val userId = session.userId() ?: return@withContext false
        val plan = planSeed(listScales())
        var ok = true
        plan.toDeleteIds.forEach { id ->
            if (!client.delete("scales", mapOf("user_id" to "eq.$userId", "id" to "eq.$id")).isSuccessful) {
                ok = false
            }
        }
        plan.toCreate.forEach { draft ->
            val body = draft.toJson().apply { put("user_id", userId) }
            if (!client.post("scales", body).isSuccessful) ok = false
        }
        ok
    }

    // ── internals ───────────────────────────────────────────────────────────

    private fun read(table: String, query: Map<String, String>): List<JSONObject> {
        val resp = client.get(table, query + mapOf("select" to "*"))
        if (!resp.isSuccessful) return emptyList()
        val array = resp.asJsonArray() ?: return emptyList()
        return (0 until array.length()).mapNotNull { array.optJSONObject(it) }
    }

    private fun JSONObject.toScale(): Scale? {
        val id = plainString("id").takeIf { it.isNotBlank() } ?: return null
        val min = optInt("min_value", 1)
        val max = optInt("max_value", 5)
        if (max <= min) return null // drop malformed rows, as the picker does
        return Scale(
            id = id,
            name = plainString("name").ifBlank { "Škála" },
            emoji = plainString("emoji").ifBlank { "📊" },
            minValue = min,
            maxValue = max,
            color = plainString("color").ifBlank { DEFAULT_COLOR },
            sortOrder = optInt("sort_order", 0),
        )
    }

    companion object {
        const val DEFAULT_WINDOW_DAYS = 30
        const val DEFAULT_COLOR = "#6366F1"

        /** First day of the window that ends on [today] and spans [days] days. */
        fun windowStart(today: LocalDate, days: Int): LocalDate =
            today.minusDays((days.coerceAtLeast(1) - 1).toLong())
    }
}

/** A scale as it is written back to the `scales` table. */
data class ScaleDraft(
    val name: String,
    val emoji: String = "📊",
    val minValue: Int = 1,
    val maxValue: Int = 5,
    val color: String = "#6366F1",
    val sortOrder: Int = 0,
)

/** One `scale_entries` row. */
data class ScaleEntry(
    val id: String,
    val scaleId: String,
    val date: String,
    val value: Int,
)

/**
 * A scale's read-out over a window: how many entries fell on each value, the
 * (unrounded) mean, and how many entries there were. `count == 0` is the
 * "no data" case — the average is then 0.0 but nothing was measured.
 */
data class ScaleStats(
    val distribution: Map<Int, Int>,
    val average: Double,
    val count: Int,
)

/**
 * The two scales the web seeds, byte-for-byte: name, emoji, range, colour and
 * sort order all have to match, because [planSeed] deletes any existing row
 * whose name is not one of these or whose range drifted from it.
 */
val DEFAULT_SCALES: List<ScaleDraft> = listOf(
    ScaleDraft(name = "Energie", emoji = "⚡", minValue = 1, maxValue = 5, color = "#eab308", sortOrder = 0),
    ScaleDraft(name = "Produktivita", emoji = "💪", minValue = 1, maxValue = 5, color = "#22c55e", sortOrder = 1),
)

/** What [ScalesRepository.seedDefaults] will do to the rows that exist now. */
internal data class SeedPlan(
    val toCreate: List<ScaleDraft>,
    val toDeleteIds: List<String>,
)

/** Name comparison used by the seed plan: trimmed and case-insensitive. */
internal fun normalizeScaleName(name: String): String = name.trim().lowercase(Locale.ROOT)

/**
 * Decides which scales a seed run creates and which it deletes, over the rows
 * that exist right now — pure, so it is unit-tested directly.
 *
 * The rules (the web's contract):
 *  1. rows whose name is not a default are deleted (renamed / misspelled rows);
 *  2. when several rows share a default's name, only the first survives;
 *  3. a default that has no row is created;
 *  4. a default whose existing row has a different range is deleted **and**
 *     recreated, so the scale comes back with the correct range instead of
 *     silently staying wrong (and the user does not lose the scale's slot);
 *  5. a default whose row already matches is left untouched.
 */
internal fun planSeed(
    existing: List<Scale>,
    defaults: List<ScaleDraft> = DEFAULT_SCALES,
): SeedPlan {
    val creates = mutableListOf<ScaleDraft>()
    val deletes = mutableListOf<String>()
    val defaultNames = defaults.map { normalizeScaleName(it.name) }.toSet()

    // 1. not a default by name
    existing
        .filter { normalizeScaleName(it.name) !in defaultNames }
        .forEach { deletes += it.id }

    // 2. duplicates of a default name
    existing
        .filter { normalizeScaleName(it.name) in defaultNames }
        .groupBy { normalizeScaleName(it.name) }
        .values
        .forEach { sameName -> sameName.drop(1).forEach { deletes += it.id } }

    // 3./4./5.
    defaults.forEach { def ->
        val match = existing.firstOrNull { normalizeScaleName(it.name) == normalizeScaleName(def.name) }
        when {
            match == null -> creates += def
            match.minValue != def.minValue || match.maxValue != def.maxValue -> {
                deletes += match.id
                creates += def
            }
        }
    }
    return SeedPlan(toCreate = creates, toDeleteIds = deletes)
}

/**
 * How often each value occurs in [values]. Values that never occur are simply
 * absent from the map (callers read them as 0 with `map[v] ?: 0`) — the function
 * never invents a key for a value it did not see.
 */
internal fun distribution(values: List<Int>): Map<Int, Int> {
    val counts = LinkedHashMap<Int, Int>()
    values.forEach { value -> counts[value] = (counts[value] ?: 0) + 1 }
    return counts
}

/**
 * Arithmetic mean of [values] as a **Double, with no rounding** — the rounding
 * happens once, at display time, in [formatAverage]. An empty list is defined as
 * 0.0 (and callers show a dash instead of that 0.0 when the count is 0).
 */
internal fun average(values: List<Int>): Double =
    if (values.isEmpty()) 0.0 else values.sum().toDouble() / values.size

/**
 * Display form of [average]: one decimal place, rounded **half-up**, with the
 * Czech decimal comma. Half-up is chosen so a scale's mean reads the way a
 * person rounds it by hand (2.25 → "2,3"); `BigDecimal.valueOf` avoids the
 * binary-double surprises of the plain constructor.
 */
internal fun formatAverage(value: Double): String =
    BigDecimal.valueOf(value)
        .setScale(1, RoundingMode.HALF_UP)
        .toPlainString()
        .replace('.', ',')

/** JSON body for a draft; [ScaleDraft] carries no id (ids come from the server). */
private fun ScaleDraft.toJson(): JSONObject =
    JSONObject().apply {
        put("name", name.trim())
        put("emoji", emoji.trim().ifBlank { "📊" })
        put("min_value", minValue)
        put("max_value", maxValue)
        put("color", color.trim().ifBlank { ScalesRepository.DEFAULT_COLOR })
        put("sort_order", sortOrder)
        put("is_active", true)
    }
