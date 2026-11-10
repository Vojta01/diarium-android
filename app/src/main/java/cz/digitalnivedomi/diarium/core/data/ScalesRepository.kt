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
 *  - a failed read of values returns an empty list rather than throwing, so a
 *    flaky network can never leave the stats section unusable — but
 *    [listScales] throws, because "no scales" and "could not read" must not be
 *    confused by the seed (see [seedDefaults]);
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

    /**
     * The user's scales, ordered by `sort_order` (ties keep the fetch order).
     *
     * Unlike the other reads in this class, a failed list is thrown rather than
     * returned as an empty list: the Scales screen turns the exception into its
     * error + retry state, and [seedDefaults] must never interpret "the read
     * failed" as "the user has no scales" and seed over them.
     */
    suspend fun listScales(): List<Scale> = withContext(Dispatchers.IO) {
        val userId = session.userId() ?: throw IllegalStateException(SIGNED_OUT)
        readStrict("scales", mapOf("user_id" to "eq.$userId"))
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
     * Web-parity seeding of the two default scales — **additive only**.
     *
     * [planSeed] decides which defaults are missing; this only inserts those. It
     * never deletes a scale: a custom scale is the user's data (and its
     * `scale_entries` would cascade away with it), and a drifted default is left
     * exactly as the user configured it instead of being deleted and recreated.
     *
     * A failed [listScales] aborts before any write and returns false, so the
     * screen shows its error/retry instead of seeding a user whose scales merely
     * could not be read.
     */
    suspend fun seedDefaults(): Boolean = withContext(Dispatchers.IO) {
        val userId = session.userId() ?: return@withContext false
        val existing = try {
            listScales()
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (_: Exception) {
            return@withContext false
        }
        val plan = planSeed(existing)
        var ok = true
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

    /** Like [read], but a failed query throws instead of reading as "no rows". */
    private fun readStrict(table: String, query: Map<String, String>): List<JSONObject> {
        val resp = client.get(table, query + mapOf("select" to "*"))
        if (!resp.isSuccessful) {
            throw IllegalStateException(
                resp.errorMessage ?: "Načtení škál se nezdařilo (${resp.code}).",
            )
        }
        val array = resp.asJsonArray() ?: throw IllegalStateException("Server vrátil neplatnou odpověď.")
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

        /** Same wording the check-in save uses, so a signed-out read reads like a session problem. */
        private const val SIGNED_OUT = "Přihlášení vypršelo, přihlas se znovu."

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
 * sort order all have to match, because [planSeed] only recognises a default by
 * its name.
 */
val DEFAULT_SCALES: List<ScaleDraft> = listOf(
    ScaleDraft(name = "Energie", emoji = "⚡", minValue = 1, maxValue = 5, color = "#eab308", sortOrder = 0),
    ScaleDraft(name = "Produktivita", emoji = "💪", minValue = 1, maxValue = 5, color = "#22c55e", sortOrder = 1),
)

/** What [ScalesRepository.seedDefaults] will do to the rows that exist now. */
internal data class SeedPlan(
    val toCreate: List<ScaleDraft>,
)

/** Name comparison used by the seed plan: trimmed and case-insensitive. */
internal fun normalizeScaleName(name: String): String = name.trim().lowercase(Locale.ROOT)

/**
 * Decides which scales a seed run creates, over the rows that exist right now —
 * pure, so it is unit-tested directly.
 *
 * Additive only, by design (2026-11 audit):
 *  1. a default that has no row is created;
 *  2. a default that already has a row is left untouched, even when its range or
 *     colour drifted from the default — the user may have configured it that way,
 *     and deleting/recreating it would cascade its `scale_entries` away;
 *  3. a custom (non-default) scale is never touched, let alone deleted;
 *  4. duplicate default-named rows are left alone: resolving them would require a
 *     delete, and losing a scale is worse than an extra one.
 *
 * There is deliberately no delete list. A real reset is a separate, explicit
 * action, never a side effect of "seed the defaults".
 */
internal fun planSeed(
    existing: List<Scale>,
    defaults: List<ScaleDraft> = DEFAULT_SCALES,
): SeedPlan {
    val existingNames = existing.map { normalizeScaleName(it.name) }.toSet()
    val creates = defaults.filter { def -> normalizeScaleName(def.name) !in existingNames }
    return SeedPlan(toCreate = creates)
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
