package cz.digitalnivedomi.diarium.core.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.text.Normalizer
import java.util.Locale

/**
 * Read-only-by-default access to the pickers the form needs: the activity
 * catalogue (+ the user's own activities), habits and scales, plus note
 * templates.
 *
 * Every reader degrades gracefully — a failed query returns the static fallback
 * from [PickerDefaults] rather than throwing, so a flaky network never leaves the
 * form unusable. Ordering is applied client-side (no `order=` query parameter) so
 * a column rename in the database can't turn into a 400.
 */
class PickersRepository(
    private val client: SupabaseClient,
    private val session: SessionContext = SessionContext(),
) {

    /** Catalogue + user activities, with user rows overriding catalogue rows by key. */
    suspend fun activities(): List<ActivityDef> = withContext(Dispatchers.IO) {
        val catalog = read("activity_catalog", emptyMap()).map { row ->
            ActivityDef(
                key = row.optString("key"),
                label = row.optString("label"),
                icon = row.optString("icon"),
                category = row.optString("category").ifBlank { "obecné" },
                color = row.optString("color").ifBlank { DEFAULT_COLOR },
                source = "catalog",
            )
        }.filter { it.key.isNotBlank() && it.label.isNotBlank() }

        val userRows = readUserActivities()

        if (catalog.isEmpty() && userRows.isEmpty()) return@withContext PickerDefaults.ACTIVITY_FALLBACK

        val byKey = LinkedHashMap<String, ActivityDef>()
        catalog.forEach { byKey[it.key] = it }
        userRows.forEach { row -> byKey[row.key] = row }

        val visible = byKey.values.filter { it.isActive }
        if (visible.isEmpty()) PickerDefaults.ACTIVITY_FALLBACK else visible.sortedWith(activityOrder)
    }

    /** User rows hidden by the user (shown in the "skryté" restore list). */
    suspend fun hiddenActivities(): List<ActivityDef> = withContext(Dispatchers.IO) {
        val hidden = readUserActivities().filter { !it.isActive }
        if (hidden.isEmpty()) emptyList()
        else hidden.sortedWith(activityOrder)
    }

    /** Habits the user can toggle; `habit_catalog` merged with the `habits` table. */
    suspend fun habits(): List<HabitDef> = withContext(Dispatchers.IO) {
        val catalog = read("habit_catalog", emptyMap()).map { row ->
            HabitDef(
                key = row.optString("key"),
                label = row.optString("label"),
                icon = row.optString("icon"),
                category = row.optString("category").ifBlank { "obecné" },
                color = row.optString("color").ifBlank { DEFAULT_COLOR },
                isNegative = row.optBoolean("is_negative", false),
                source = "catalog",
            )
        }.filter { it.key.isNotBlank() && it.label.isNotBlank() }

        val userId = session.userId()
        val userHabits = if (userId == null) emptyList() else read(
            "habits",
            mapOf("user_id" to "eq.$userId"),
        ).mapNotNull { row ->
            val key = row.optString("key").ifBlank { row.optString("label") }
            if (key.isBlank()) return@mapNotNull null
            HabitDef(
                key = key,
                label = row.optString("label").ifBlank { key },
                icon = row.optString("icon"),
                category = row.optString("category").ifBlank { "obecné" },
                color = row.optString("color").ifBlank { DEFAULT_COLOR },
                isNegative = row.optBoolean("is_negative", false),
                source = if (row.optBoolean("is_default", false)) "default" else "user",
                isActive = row.optBoolean("is_active", true),
            )
        }

        val byKey = LinkedHashMap<String, HabitDef>()
        catalog.forEach { byKey[it.key] = it }
        userHabits.forEach { row -> byKey[row.key] = row }

        val visible = byKey.values.filter { it.isActive }
        if (visible.isEmpty()) PickerDefaults.HABIT_FALLBACK else visible
    }

    /** Active scales for the user, in `sort_order`. */
    suspend fun scales(): List<Scale> = withContext(Dispatchers.IO) {
        val userId = session.userId() ?: return@withContext emptyList()
        read("scales", mapOf("user_id" to "eq.$userId"))
            .mapNotNull { row ->
                val id = row.optString("id").takeIf { it.isNotBlank() } ?: return@mapNotNull null
                Scale(
                    id = id,
                    name = row.optString("name").ifBlank { "Škála" },
                    emoji = row.optString("emoji").ifBlank { "📊" },
                    minValue = row.optInt("min_value", 1),
                    maxValue = row.optInt("max_value", 5).coerceAtLeast(row.optInt("min_value", 1) + 1),
                    color = row.optString("color").ifBlank { DEFAULT_COLOR },
                    sortOrder = row.optInt("sort_order", 0),
                )
            }
            .filter { it.maxValue > it.minValue } // drop malformed rows
            .sortedBy { it.sortOrder }
    }

    /** The user's note templates, or the three built-ins when they have none. */
    suspend fun templates(): List<NoteTemplate> = withContext(Dispatchers.IO) {
        val userId = session.userId() ?: return@withContext PickerDefaults.DEFAULT_TEMPLATES
        val rows = read("templates", mapOf("user_id" to "eq.$userId"))
            .mapNotNull { row ->
                val content = row.optString("content").takeIf { it.isNotBlank() } ?: return@mapNotNull null
                NoteTemplate(
                    id = row.optString("id").ifBlank { content },
                    name = row.optString("name").ifBlank { "Šablona" },
                    content = content,
                    sortOrder = row.optInt("sort_order", 0),
                )
            }
            .sortedBy { it.sortOrder }
        rows.ifEmpty { PickerDefaults.DEFAULT_TEMPLATES }
    }

    // ── Custom activity management (add / hide / restore) ───────────────────

    /** Adds a custom activity; re-adding the same label upserts in place. */
    suspend fun addActivity(label: String): Boolean = withContext(Dispatchers.IO) {
        val userId = session.userId() ?: return@withContext false
        val clean = label.trim()
        if (clean.isBlank()) return@withContext false
        val body = JSONObject().apply {
            put("user_id", userId)
            put("key", slug(clean))
            put("label", clean)
            put("icon", "⭐")
            put("category", "vlastní")
            put("color", DEFAULT_COLOR)
            put("is_active", true)
        }
        client.post(
            "user_activities",
            body,
            extraHeaders = mapOf("Prefer" to "resolution=merge-duplicates"),
        ).isSuccessful
    }

    /** Hides an activity (writes an `is_active:false` marker for catalogue rows). */
    suspend fun hideActivity(def: ActivityDef): Boolean = withContext(Dispatchers.IO) {
        val userId = session.userId() ?: return@withContext false
        val body = JSONObject().apply {
            put("user_id", userId)
            put("key", def.key)
            put("label", def.label)
            put("icon", def.icon)
            put("category", def.category)
            put("color", def.color)
            put("is_active", false)
        }
        client.post(
            "user_activities",
            body,
            extraHeaders = mapOf("Prefer" to "resolution=merge-duplicates"),
        ).isSuccessful
    }

    /** Restores a previously hidden activity. */
    suspend fun restoreActivity(key: String): Boolean = withContext(Dispatchers.IO) {
        val userId = session.userId() ?: return@withContext false
        client.patch(
            "user_activities",
            JSONObject().put("is_active", true),
            mapOf("user_id" to "eq.$userId", "key" to "eq.$key"),
        ).isSuccessful
    }

    // ── internals ───────────────────────────────────────────────────────────

    private fun readUserActivities(): List<ActivityDef> {
        val userId = session.userId() ?: return emptyList()
        return read("user_activities", mapOf("user_id" to "eq.$userId")).mapNotNull { row ->
            val key = row.optString("key").ifBlank { row.optString("label") }
            if (key.isBlank()) return@mapNotNull null
            ActivityDef(
                key = key,
                label = row.optString("label").ifBlank { key },
                icon = row.optString("icon"),
                category = row.optString("category").ifBlank { "vlastní" },
                color = row.optString("color").ifBlank { DEFAULT_COLOR },
                source = if (row.optBoolean("is_default", false)) "default" else "user",
                isActive = row.optBoolean("is_active", true),
            )
        }
    }

    private fun read(table: String, query: Map<String, String>): List<JSONObject> {
        val resp = client.get(table, query + mapOf("select" to "*"))
        if (!resp.isSuccessful) return emptyList()
        val array: JSONArray = resp.asJsonArray() ?: return emptyList()
        return (0 until array.length()).mapNotNull { array.optJSONObject(it) }
    }

    private companion object {
        const val DEFAULT_COLOR = "#6366F1"

        /** Catalogue groups first in CATEGORY_ORDER, then alphabetically. */
        val activityOrder = Comparator<ActivityDef> { a, b ->
            val ai = PickerDefaults.CATEGORY_ORDER.indexOf(a.category)
            val bi = PickerDefaults.CATEGORY_ORDER.indexOf(b.category)
            val byCategory = (if (ai == -1) Int.MAX_VALUE else ai).compareTo(if (bi == -1) Int.MAX_VALUE else bi)
            if (byCategory != 0) byCategory else a.label.compareTo(b.label)
        }

        /** `Jít běhat!` → `jit_behat` — a stable slug for custom activity keys. */
        fun slug(label: String): String {
            val ascii = Normalizer.normalize(label, Normalizer.Form.NFD)
                .replace(Regex("\\p{Mn}+"), "")
            return ascii.lowercase(Locale.ROOT)
                .replace(Regex("[^a-z0-9]+"), "_")
                .trim('_')
                .ifBlank { "vlastni_" + label.hashCode().toString().replace("-", "n") }
        }
    }
}
